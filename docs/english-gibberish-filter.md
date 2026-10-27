# Experimental English character filter

The public-chat spam filter scores incoming text using a fixed English
character-bigram table. The current experiment removes content-based
exemptions for technical tokens, digits, punctuation, unsupported scripts,
and short messages. It changes local admission only, with no wire-format
change. Private-message and non-text routing keep their existing behavior.

## Fixed model and current scoring policy

`tools/spam_filter/english-training.txt` contains original synthetic English
examples. The corpus, additive smoothing of `0.1`, all 729 learned float32
weights, and threshold of `4.2` are unchanged. The weights occupy 2,916 bytes,
excluding class and runtime overhead. Both clients use the app-owned source.

```sh
python3 tools/spam_filter/train_english.py
python3 tools/spam_filter/train_english.py --check
```

The trained alphabet remains `a-z` plus space. Known character pairs retain
their existing negative-log-probability costs. Every transition involving an
untrained character receives the maximum cost already in the table:
`9.459308`. This is an explicit fallback penalty, not a learned probability
for digits, symbols, or other languages. No broader training corpus is added.
The model never learns from incoming traffic.

Scoring now reads raw Unicode code points, lowercases ASCII letters, and
collapses whitespace runs. Digits, punctuation, code delimiters, combining
marks, emoji, and other scripts contribute to the score. They do not cause
abstention, and their tokens are not discarded. There is no minimum length.
For a single character, known characters use the preceding-space cost and
unknown characters use the fallback penalty. Only empty input has no score.

The detector averages costs over transitions and flags a score of at least
`4.2`. Work remains bounded to a prefix starting within the first 4,096 UTF-16
units and at most 1,000 scored code points. Oversized content still faces the
existing character budget; it does not bypass the model just because it is
long. The master toggle, debug bypass, verdict caching, and code-level
`englishGibberishEnabled=false` control remain available.

## Paired false-positive evaluation

The evaluation uses the same 220 synthetic legitimate examples on the
previous implementation (`8319212c`) and the current implementation. Each
sample is checked with fresh filter state to separate content decisions
from sender budgets and cross-user repetition. Phone and watch produce
identical case-by-case results.

| Legitimate category | Samples | Blocked before | Blocked now |
|---|---:|---:|---:|
| English chat containing numbers | 20 | 0 | 2 |
| English chat containing punctuation | 20 | 0 | 1 |
| Numeric text and mathematics | 20 | 0 | 20 |
| Code and structured text | 20 | 0 | 18 |
| URLs, standalone and embedded | 20 | 0 | 11 |
| Hashes and identifiers | 20 | 1 | 13 |
| **Number/symbol subtotal** | **120** | **1** | **65 (54.17%)** |
| Short replies | 20 | 0 | 15 |
| Multilingual text and emoji | 20 | 0 | 8 |
| Additional ordinary English controls | 60 | 0 | 0 |
| **All legitimate controls** | **220** | **1** | **88 (40%)** |

Among the 93 number/symbol examples with at least 24 code points, 40 are
blocked (43.01%). Among the 27 shorter examples, 25 are blocked (92.59%).
Removing the minimum length therefore contributes to the increase, but it
does not account for all of it.

Examples of newly blocked synthetic chat include a sentence describing a
16:9 image with a 1920x1080 preview, a fictional decimal-price calculation,
and a sentence listing choices in square brackets. These are labelled
legitimate by construction; their rejection is recorded as a false positive.

The [aggregate result record](test-results/english-gibberish-all-characters.json)
includes model checksums, sample counts, length groups, flood results, and
validation. The [paired case list](test-results/english-gibberish-all-characters-cases.csv)
contains every synthetic input, its score before and after, and its verdict.
The cases are hand-picked format controls, not a representative sample of
real conversations. Their percentages must not be treated as real-world
false-positive estimates. No real messages, contacts, channel data,
locations, device identifiers, or private logs are used as fixtures.

## Flood regression

With `TEST_SEED=999`, each 10,000-message stream uses a fresh synthetic
identity for every message. The mixed stream samples printable non-space
ASCII, including letters, digits, and punctuation. All 10,000 mixed messages
are hidden for both mesh and Nostr sender keys. With the English rule off,
9,994 are admitted and six encounter existing non-model rules.

The spaced-letter streams hide 9,932/10,000 mesh messages and 9,945/10,000
Nostr messages. Unbroken-letter streams hide 9,998/10,000 and 9,999/10,000.
A separate keyboard-like stream hides all 1,000 examples. Plausible word
sequences and ordinary sentences remain a limitation.

The original bucket and repetition tests explicitly disable the English
rule when they need to isolate those contracts. The new evaluation reports
false positives directly; tests do not assume that all legitimate examples
must pass under the requested stricter policy.

## Reproduction and validation

```sh
TEST_SEED=999 ./gradlew :app:testDebugUnitTest \
  --tests 'com.bitchat.android.spam.*' \
  --tests '*GeohashMessageHandlerSpamTest' \
  --tests '*IncomingMessageAdmissionTest' \
  :wear:testDebugUnitTest --tests 'com.bitchat.android.spam.*'
python3 -m unittest tools.release_gate.test_mesh_lab_gibberish
```

`EnglishGibberishFalsePositiveEvaluationTest` prints per-category counts.
When the optional test system property `spam.evaluation.dir` is supplied,
it also writes synthetic case reports there. The recorded comparison uses
an actual baseline build followed by the changed build with identical cases.
To reproduce that baseline, add only `LegitimateCharacterSamples.kt` and
`EnglishGibberishFalsePositiveEvaluationTest.kt` to an isolated checkout of
`8319212c`, then run the evaluation test there. The current detector stays out
of that baseline checkout.

Both debug builds pass. Full unit results are 771 app passes with three
pre-existing ignored tests, and 302 watch passes. Host-side scenario checks
pass. Lint completes under the repository's nonblocking configuration with
repository-wide findings; the model and new evaluation files have no findings.

## Physical validation and historical measurements

Physical Mesh Lab validation remains unrun. The `broadcast_gibberish`
scenario now tests mixed-symbol and unbroken noise plus an ordinary-English
control. It does not measure fresh-identity flood rates. Other physical
scenarios using short technical-looking positive controls can be affected
by this aggressive policy; no physical pass is claimed.

After authorized controlled-lab setup, with a private evidence directory:

```sh
python3 tools/release_gate/mesh_lab.py scenario broadcast_gibberish \
  --serial-a "$MESH_SERIAL_A" --serial-b "$MESH_SERIAL_B" \
  --out "${MESH_EVIDENCE_DIR:?Set a private evidence directory}"
```

Raw logs, APKs, and physical evidence stay private and uncommitted.
[Initial results](test-results/english-gibberish-filter.json) and
[unbroken-text results](test-results/english-gibberish-unbroken.json)
record earlier policies and must not be read as results for the current one.
