# Pretrained multilingual character filter

The experimental public-chat filter now bundles 32 fixed character-bigram
models covering the 34 declared locale variants and default English. Phone
and watch run the same app-owned implementation. Devices only score text:
there is no on-device training, message collection, model download, or new
runtime dependency. Public-message admission changes locally; private
messages, non-text routing, and packet formats retain their existing behavior.

## Model pack and training

The retrained pack contains 1,587,413 compressed bytes (about 1.51 MiB). Its decoded weight
arrays occupy 3,015,144 bytes, excluding object and string overhead. Models load
lazily and share immutable weights across scoring calls. Script selection
limits work to at most 16 candidate models rather than all 32.

Language families are `en`, `ar`, `bn`, `de`, `es`, `eu`, `fa`, `fil`, `fr`,
`he`, `hi`, `id`, `it`, `ja`, `ka`, `ko`, `mg`, `ms`, `ne`, `nl`, `pa`, `pl`,
`pt`, `ru`, `sv`, `ta`, `th`, `tr`, `uk`, `ur`, `vi`, and `zh`. Portuguese
variants share a model; simplified and traditional Chinese share a model.
Punjabi covers the declared `pa-PK` Arabic-script locale. These weights do
not establish support for other scripts or romanizations of those languages.

Training runs in the separate Python tools under `tools/spam_filter`; none of
that machinery ships in the app. It uses the pinned public corpus candidates
described below, filtered static translations in `app/src/main/res`, the original
synthetic English corpus, and invented examples in `language-examples.json`.
The generator performs no network requests and reads no app messages or device
data. Python's standard library is sufficient after corpus preparation.

Public corpus input is partitioned 80/10/10 by conversation/source group across
languages. If identical normalized text occurs in different groups or languages,
it is retained only in the most conservative partition: test before calibration,
calibration before training. Repository fixtures are excluded from all public
corpus partitions. The resulting public input has 810,272 training, 100,630
calibration and 100,618 held-out texts. Including static/synthetic input gives
816,092 training, 101,205 calibration and 101,090 test texts. Processing uses one
language at a time and a local SQLite index to bound memory.

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

These commands require the verified prepared corpus cache. `--repository-only`
selects the historical UI/synthetic input instead. The generator produces
`LanguageModelPack.kt`, shared static/synthetic held-out JVM fixtures, and
`tools/spam_filter/language-models.json`. Downloaded text never enters those JVM
fixtures. The manifest
records locale aliases, corpus hashes, per-language thresholds, sample counts,
weight sizes, and SHA-256 checksums. Adding a declared locale without a model
causes generation to fail.

Each model estimates `P(next | previous)` with unigram backoff for unseen pairs.
The backoff strength is `5.0`, and unigram pseudocounts are `0.25`. Negative log
costs use 16-bit quantization at a scale of 4,096. Only observed bigrams are stored;
unseen pairs use the stored backoff and unigram costs. Untrained characters
receive the model's strongest bounded fallback cost. The decoder checks the
checksum, format, array ordering, bounds, finite threshold, and decompression
limit before exposing weights. The alphabet bound is 16,384 code points, retaining
all 5,002 characters observed in Chinese training; each decoded model remains
bounded to 1 MiB. Characters seen only in held-out input receive the ordinary
unknown-character score.

Thresholds use the 95th percentile of sentence-length positive calibration
scores plus `0.55`. The margin allows ordinary formatted chat, including synthetic
reference codes, without exempting any characters from scoring. Separate seeded
noise calibration generates 256 random 96-character samples from each native-script
training alphabet. The final threshold is capped
at `0.98` times that noise set's first percentile. Noise calibration uses seed
`424242`; evaluation uses different seeds and samples. The manifest records
both thresholds and whether the cap conflicts with positive calibration. None
of the final models has that conflict, but held-out false positives still occur.

## Downloaded conversational training candidates

A separate acquisition workflow has fetched and prepared **1,011,664 distinct
text records across all 32 language families**. The bundled app weights now use
the training partition of these candidates. The source acquisition report is a
snapshot taken before this retraining; the separate retraining report records
the new model's measured behavior.

The [source catalog](../tools/spam_filter/corpus-sources.json) records public URLs,
pinned revisions, artifact checksums, declared licenses, and source limitations.
The [aggregate coverage report](test-results/conversational-corpus-coverage.json)
and [language table](test-results/conversational-corpus-coverage.csv) contain only
counts and provenance. Corpus text remains in the ignored local directory
`build/corpora/conversational`:

- `raw/`: source snapshots and extracted dialogue subsets;
- `prepared/<language>.jsonl.gz`: text records with source and grouping references;
- `manifest.json`: per-language sizes, checksums, source counts and filtering outcomes;
- `licenses/`: source notices retained locally.

Selected sources include [SOC-2508-MULTI](https://huggingface.co/datasets/marcodsn/SOC-2508-MULTI)
synthetic online chat; the [Aya Collection's SODA component](https://huggingface.co/datasets/CohereLabs/aya_collection)
of machine-translated synthetic dialogue; original human-authored
[Aya Q&A](https://huggingface.co/datasets/CohereLabs/aya_dataset);
[Tatoeba](https://en.wiki.tatoeba.org/articles/show/using-the-tatoeba-corpus)
authored sentences; and [MASSIVE](https://huggingface.co/datasets/AmazonScience/massive)
assistant utterances. Additional synthetic Malagasy exchanges, translated Georgian
roleplay, professional Shahmukhi sentence translations and domain-specific Punjabi
QA supplement the low-resource languages. These source types are labelled separately.

The SODA acquisition samples training row groups through bounded HTTP range
requests. Only text and source references enter the extracted files. The full
multi-gigabyte original shards are not downloaded, so their complete-file hashes
are recorded as provider declarations rather than claimed as verified. HTTP
range offsets, total sizes and extracted-subset checksums are verified.

Preparation samples up to 10,000 unique texts per source and language using
content hashes, preserves accents/punctuation/numbers/emoji, and deduplicates
across sources. It projects away contributor, persona and worker metadata,
filters obvious contacts/long identifiers and invalid controls, checks scripts,
and excludes upstream MASSIVE validation/test rows. It never selects examples
by whether the current spam model accepts them. The resulting text is not
guaranteed anonymized; keep it local and separate from repository test fixtures.
Tatoeba sentence IDs are retained for the required per-author attribution work
before any text redistribution.

Coverage limitations remain explicit:

- French has 41,895 selected texts, including 20,000 dialogue records; Italian
  has 40,311, also including 20,000 dialogue records.
- Filipino has 21,533 selected authored sentences, QA texts and assistant
  utterances. A general-dialogue SODA subset was not found.
- `pa-PK` has 1,750 selected records. The strongest supplements are 863 distinct
  professionally translated sentences and 748 agricultural QA texts. A broad
  casual-chat corpus remains unverified. Aya's Punjabi-letter examples use
  Gurmukhi and are rejected for this locale. SACOR's advertised 5,127 rows contain
  only 53 distinct target strings, which are counted once.
- The full Chinese candidate set exceeded the earlier 4,096-character model
  alphabet bound. The updated decoder permits 16,384 code points while retaining
  the 1 MiB per-model decompression bound; no training characters are discarded.

Offline extraction requires the optional tools dependency in
`tools/spam_filter/requirements-corpora.txt`; it is not an Android dependency.
With PyArrow available in the Python environment:

```sh
python3 tools/spam_filter/conversational_corpora.py fetch
python3 tools/spam_filter/conversational_corpora.py prepare --per-source-limit 10000
python3 tools/spam_filter/conversational_corpora.py verify
python3 -m unittest tools.spam_filter.test_corpus_http tools.spam_filter.test_conversational_corpora
```

The current local cache also contains an isolated reader under `deps/`. Set
`PYTHONPATH=build/corpora/conversational/deps` for these commands when using that
reader. Weekly Tatoeba URLs can change: a fresh download with a different checksum
is rejected until the source snapshot is deliberately updated. The cached snapshot
remains usable offline. OpenSubtitles is not selected because the published OPUS
page does not provide a clear open-data license grant and the upstream terms
restrict commercial use.

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

## Conversational retraining evaluation

The [aggregate retraining report](test-results/conversational-gibberish-retraining.json)
and [per-language comparison](test-results/conversational-gibberish-languages.csv)
compare the previous multilingual UI/synthetic weights with the conversational
weights on the same 100,618 public held-out texts. The standalone evaluator uses
the actual compiled JVM normalizer, candidate selection, model decoder and
quantized scorer for both packs. It writes counts, never source text, to the
repository report. Public corpus texts are training candidates, not independently
verified abuse labels; these rejection rates are specific to this corpus.

| Held-out corpus | Texts | Previous blocked | Retrained blocked |
|---|---:|---:|---:|
| All 32 language families | 100,618 | 16,945 (16.84%) | 1,506 (1.50%) |
| French | 4,300 | 597 (13.88%) | 14 (0.33%) |
| Italian | 4,084 | 268 (6.56%) | 21 (0.51%) |
| Korean | 2,998 | 2,694 (89.86%) | 237 (7.91%) |
| Chinese | 3,733 | 3,489 (93.46%) | 334 (8.95%) |

All 60,000 seeded fresh-identity flood messages remain hidden, covering spaced
letters, unbroken letters and mixed printable ASCII through mesh and Nostr sender
keys. All 3,200 independent native-script random strings are flagged. Independent
letter seeds and keyboard-like noise regressions also pass. These JVM tests prove
local admission behavior, not physical radio delivery.

The separate invented chat cohort improves from 3/128 blocked to 0/128. The broader
static/synthetic language cohort regresses from 20/472 to 26/472, primarily on
out-of-domain UI labels. The strict number/symbol cohort also regresses slightly,
from 68/120 to 70/120 blocked. Its category details remain in the aggregate report;
URLs, standalone numbers, hashes and code still have substantial false positives.
No content exemption has been restored. Chinese and Korean rejection rates remain
too high to describe language coverage as uniformly reliable.

To repeat a training and measurement run using the prepared cache:

```sh
python3 tools/spam_filter/train_languages.py \
  --evaluation-dir build/reports/conversational-retraining
./gradlew :app:compileDebugKotlin
python3 tools/spam_filter/evaluate_corpora.py
```

On the first run in an evaluation directory, the generator preserves the previous
pack as `baseline-pack.kt` and never overwrites that comparison snapshot. Use a
new ignored output directory for a new comparison. Held-out public text and raw
tool output remain private under that directory. The evaluator uses JDK 21+
and the existing cached Kotlin/Gson build dependencies; none ships as an added
Android dependency.

## Historical UI/synthetic evaluation

The historical baseline is an actual build of `055360a4`, the previous English-only filter
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
property. To reproduce the historical English-only comparison, copy only the generated held-out
fixture and generic evaluation test into an isolated baseline checkout; keep its
original detector intact. The original 220-case evaluation is already present
there. The language evaluation writes its measurements before enforcing current
multilingual accuracy guards. The English-only baseline is expected to fail
those guards; its saved measurements are the baseline comparison.

Both retrained debug APKs build and contain all 32 new model digests. Full JVM
results are 774 app passes with three pre-existing ignored tests, and 305 watch
passes. All 21 host-side corpus, partition and scenario checks pass. The generator
reproduces all artifacts with `--check`, and phone/watch synthetic case reports
are identical. Lint completes under the repository's nonblocking configuration:
the app has 311 errors, 356 warnings, and 19 hints; watch has 37 errors,
51 warnings, and 10 hints. None is in the changed model or evaluation files.

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
