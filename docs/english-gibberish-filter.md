# Pretrained multilingual character filter

The experimental public-chat filter now bundles 32 fixed character-bigram
models covering the 34 declared locale variants and default English. Phone
and watch run the same app-owned implementation. Devices only score text:
there is no on-device training, message collection, model download, or new
runtime dependency. Public-message admission changes locally; private
messages, non-text routing, and packet formats retain their existing behavior.

## Model pack and training

The pack contains 109,359 compressed bytes (about 107 KiB). Its decoded weight
arrays occupy 222,452 bytes, excluding object and string overhead. Models load
lazily and share immutable weights across scoring calls. Script selection
limits work to at most 16 candidate models rather than all 32.

Language families are `en`, `ar`, `bn`, `de`, `es`, `eu`, `fa`, `fil`, `fr`,
`he`, `hi`, `id`, `it`, `ja`, `ka`, `ko`, `mg`, `ms`, `ne`, `nl`, `pa`, `pl`,
`pt`, `ru`, `sv`, `ta`, `th`, `tr`, `uk`, `ur`, `vi`, and `zh`. Portuguese
variants share a model; simplified and traditional Chinese share a model.
Punjabi covers the declared `pa-PK` Arabic-script locale. These weights do
not establish support for other scripts or romanizations of those languages.

Training uses filtered static translations already in `app/src/main/res`,
the original synthetic English corpus, and original invented examples in
`tools/spam_filter/language-examples.json`. No live conversations, account
information, device data, or external corpus service is used. The native-script
corpus includes additional Japanese, Korean, and Chinese paragraphs to improve
character coverage. The examples have not received independent linguistic review.

Resource keys are deterministically split into training, calibration, and test
partitions using SHA-256. Synthetic examples have explicit separate partitions.
Normalized calibration text is excluded from every model's training corpus;
test text is excluded from every training and calibration corpus. This also
prevents regional variants or overlapping translations from leaking identical
sentences between partitions.

```sh
python3 tools/spam_filter/train_languages.py
python3 tools/spam_filter/train_languages.py --check
```

The standard-library-only generator produces `LanguageModelPack.kt`, shared
held-out JVM fixtures, and `tools/spam_filter/language-models.json`. The manifest
records locale aliases, corpus hashes, per-language thresholds, sample counts,
weight sizes, and SHA-256 checksums. Adding a declared locale without a model
causes generation to fail.

Each model estimates `P(next | previous)` with unigram backoff for unseen pairs.
The backoff strength is `5.0`, and unigram pseudocounts are `0.25`. Negative log
costs use 16-bit quantization at a scale of 4,096. Only observed bigrams are stored;
unseen pairs use the stored backoff and unigram costs. Untrained characters
receive the model's strongest bounded fallback cost. The decoder checks the
checksum, format, array ordering, bounds, finite threshold, and decompression
limit before exposing weights.

Thresholds use the 95th percentile of sentence-length positive calibration
scores plus `0.35`. Separate seeded noise calibration generates 256 random
96-character samples from each training alphabet. The final threshold is capped
at `0.98` times that noise set's first percentile. Noise calibration uses seed
`424242`; evaluation uses different seeds and samples. The manifest records
both thresholds and whether the cap conflicts with positive calibration. None
of the final models has that conflict, but held-out false positives still occur.

## Runtime behavior

Text receives NFKC normalization, locale-independent lowercase conversion, and
collapsed whitespace. Korean syllables remain intact: decomposing them into
Jamo allowed random valid syllables to resemble ordinary Korean in an earlier
development model.

The dominant letter script selects candidates independently of the device's
UI language. Latin considers 16 languages; Arabic considers four; Cyrillic and
Devanagari consider two each. Han considers Chinese and Japanese; kana selects
Japanese. Other supported scripts select their own model. Common-only text,
including standalone numbers, uses English. Unsupported scripts also receive
scores through the English fallback. Every normalized code point contributes;
there are no technical-token, URL, number, punctuation, emoji, script, or short
message exemptions.

The best candidate's average cost is divided by its own threshold, then scaled
to the existing `4.2` decision boundary. Scores are relative language-model
costs, not probabilities of abuse. Only empty input has no score. Work is bounded
to the first 4,096 UTF-16 units and 1,000 normalized code points. Mixed-language
text is scored as a whole rather than splitting away unfamiliar characters;
legitimate code switching can therefore be rejected.

The historical `englishGibberishEnabled` configuration field and
`ENGLISH_GIBBERISH` verdict remain compatible. Master filtering, debug bypass,
verdict caching, sender budgets, and repetition filtering retain their behavior.
The debug explanation now says "unlikely character sequence".

## Paired evaluation

The baseline is an actual build of `055360a4`, the previous English-only filter
with all character exemptions removed. Identical test fixtures run against the
baseline and multilingual builds. False positives are recorded rather than
removed from the report. These are small constructed samples, not estimates of
real-world accuracy.

| Legitimate category | Samples | English-only blocked | Multilingual blocked |
|---|---:|---:|---:|
| English chat with numbers | 20 | 2 | 2 |
| English chat with punctuation | 20 | 1 | 2 |
| Numeric text and mathematics | 20 | 20 | 19 |
| Code and structured text | 20 | 18 | 17 |
| URLs | 20 | 11 | 10 |
| Hashes and identifiers | 20 | 13 | 18 |
| **Number/symbol subtotal** | **120** | **65** | **68** |
| Short replies | 20 | 15 | 11 |
| Multilingual text and emoji | 20 | 8 | 3 |
| Ordinary English controls | 60 | 0 | 0 |
| **Original legitimate cohort** | **220** | **88** | **82** |
| Separate held-out translations and chat | 472 | 246 | 20 |
| Synthetic chat subset of that cohort | 128 | 75 | 3 |

All 60,000 earlier random-identity messages are hidden: 10,000 spaced letters,
10,000 unbroken letters, and 10,000 mixed printable-ASCII messages for each of
the mesh and Nostr sender-key formats. Independent letter-flood seeds and the
keyboard-like stream also pass their detection regressions. All 3,200 native
random-character samples are flagged, 100 per language. The latter test scores
content directly; mesh/Nostr admission, actual signed Nostr events, and fresh
identities are covered separately by the existing JVM regressions.

The [aggregate record](test-results/multilingual-gibberish-filter.json),
[language case comparison](test-results/multilingual-gibberish-languages.csv), and
[original cohort comparison](test-results/multilingual-gibberish-characters.csv)
retain the measured outcomes, including newly blocked legitimate inputs.

The remaining synthetic chat rejections are one each in Tamil, Thai, and
Chinese. More broadly, 68/120 legitimate number/symbol samples are still blocked,
including many standalone formulas, hashes, and code fragments. Adding languages
does not make this strict policy suitable for every conversation. Fluent nonsense,
random real-word sequences, dialects, spelling variations, and unfamiliar vocabulary
remain limitations of a small character model.

## Validation and local testing

```sh
python3 tools/spam_filter/train_languages.py --check
python3 -m unittest tools.release_gate.test_mesh_lab_gibberish
./gradlew :app:assembleDebug :wear:assembleDebug testDebugUnitTest lintDebug
```

Both clients must produce identical case reports and score vectors. Tests cover
all locale aliases, normalization, bounded work, malformed packs, concurrent
scoring, multilingual admission, and random-identity floods. Evaluation tests
can write synthetic-only JSON under the optional `spam.evaluation.dir` JVM test
property. To reproduce the baseline comparison, copy only the generated held-out
fixture and generic evaluation test into an isolated baseline checkout; keep its
original detector intact. The original 220-case evaluation is already present
there. The language evaluation writes its measurements before enforcing current
multilingual accuracy guards. The English-only baseline is expected to fail
those guards; its saved measurements are the baseline comparison.

Both debug builds pass. Full JVM results are 772 app passes with three
pre-existing ignored tests, and 303 watch passes. All six host-side scenario
tests pass. Lint completes under the repository's nonblocking configuration:
the app has 311 errors, 335 warnings, and 19 hints; watch has 37 errors,
51 warnings, and 10 hints. None is in the new model or evaluation files.

Physical Mesh Lab validation is **blocked (not run)** because no physical
endpoints are available. The focused scenario observes two hidden noise samples
and five delivered controls: English, Japanese, Arabic, Korean, and Chinese.
Its per-run markers and ordinary controls are also checked through JVM public
admission. Host fakes verify assertion behavior; they do not prove radio delivery.
After authorized controlled-lab setup, run:

```sh
python3 tools/release_gate/mesh_lab.py scenario broadcast_gibberish \
  --serial-a "$MESH_SERIAL_A" --serial-b "$MESH_SERIAL_B" \
  --out "${MESH_EVIDENCE_DIR:?Set a private evidence directory}"
```

Raw logs, APKs, and lab evidence remain local and uncommitted.
[Initial English results](test-results/english-gibberish-filter.json),
[unbroken-text results](test-results/english-gibberish-unbroken.json), and
[all-character English results](test-results/english-gibberish-all-characters.json)
record historical models and policies.
