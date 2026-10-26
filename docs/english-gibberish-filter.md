# Experimental English gibberish filter

The public-chat spam filter includes a fixed English character-bigram model.
It detects unlikely letter sequences even when every message has a different
identity and different content. It does not classify intent, establish that
two identities belong to the same person, or solve floods of plausible text.
It changes local public-message admission only; no wire format changes.
Private-message paths retain their existing bypass of public spam filtering.

## Model and training

`tools/spam_filter/english-training.txt` contains original synthetic examples.
No private messages, accounts, device information, locations, or remote
services are used in training. This intentionally small corpus is suitable
for a prototype and regression tests, not a claim of general language coverage.

Training counts adjacent characters independently within each example, using
the alphabet `a-z` plus a space. Each pair starts with a pseudocount of `0.1`:

```text
P(next | previous) = (pair count + 0.1) / (outgoing count + 27 * 0.1)
```

The generated model stores rounded negative natural log probabilities in a
row-major float table. The 729 float32 weights occupy 2,916 bytes, excluding
class, runtime, and APK overhead. Its source includes the training corpus
SHA-256, and both clients compile the same app-owned source.

Regenerate or verify the model without third-party Python packages:

```sh
python3 tools/spam_filter/train_english.py
python3 tools/spam_filter/train_english.py --check
```

The model is immutable at runtime. Incoming traffic never updates it.

## Admission policy

`EnglishGibberishDetector` averages the stored costs over eligible character
transitions. A score of at least `4.2` produces `ENGLISH_GIBBERISH`. This is a
language-pattern score, not a calibrated spam probability. The threshold is
a conservative experimental cutoff, not a guarantee about unfamiliar text.

The detector requires at least 24 letters across three whitespace-separated
words. It ignores technical tokens containing digits or internal symbols,
and words longer than 24 characters. Case, ordinary edge punctuation, and
whitespace differences do not change the model's interpretation. Straight
apostrophes inside words are ignored when scoring.

Non-ASCII letters or combining marks cause abstention, rather than treating an
unsupported language as English gibberish. ASCII transliterations and languages
using the same alphabet are not reliably distinguishable from English; their
false-positive risk still needs evaluation. Emoji and technical tokens are not
themselves evidence of gibberish.

Work is bounded to 4,096 input UTF-16 units and 1,000 scored characters. Larger
inputs are left to the existing character budget. Eligible input is scored
with table lookups in linear time. High-scoring messages are rejected before
allocating per-sender state or adding repetition history. Verdict caching and
the existing debug bypass still apply. Disabling the master spam toggle
disables this rule too. Code-level `englishGibberishEnabled=false` disables only
the new rule, allowing an exact comparison with the original filter.

## Synthetic evaluation

With `TEST_SEED=999`, the same random identity/text streams are checked with
the English rule disabled and enabled. The message generator and identity-key
draw order are unchanged from the original PR #999 reproduction. Messages
have 32-160 characters, with a space after every seven random lowercase
letters, and all timestamps are identical. Each message has a unique identity,
unique content, and unique message ID.

| Scenario | Baseline admitted | English model admitted | Hidden by model |
|---|---:|---:|---:|
| 10,000 fresh mesh identities | 10,000 | 67 | 9,933 (99.33%) |
| 10,000 fresh Nostr sender keys | 10,000 | 52 | 9,948 (99.48%) |
| 500 validly signed geohash events through the handler | 500 | 1 | 499 (99.8%) |
| 200 synthetic messages through mesh app-state admission | — | 1 | 199 (99.5%) |

Three additional seeds (`1000`, `1001`, `1002`) hide 1,986, 1,991, and 1,988
out of 2,000 messages respectively. The separate synthetic controls contain
60 English examples, 16 multilingual examples, and 15 technical/short examples.
The model flags none of those 91 controls. In the complete filter, one
deliberately repetitive technical control is hidden by the pre-existing
repeated-character rule with or without the English model; there are no new
admission changes for the control set.

The English and control examples are not part of the training corpus, but
these small synthetic sets were used during development. These results are
regression measurements, not unbiased estimates of real-world accuracy.
Normal English controls still arrive after the mesh and geohash floods.
The previous token-bucket and repetition tests explicitly disable the English
rule because their fixtures deliberately use random letters to isolate those
older rules. Separate tests exercise default-enabled admission, non-text
exemption, configuration changes, caching, budget preservation, and debug bypass.
Literal score vectors and shared tests check phone/watch consistency.

The [machine-readable results](test-results/english-gibberish-filter.json)
record the implementation revision, benchmark counts, seeds, model checksum,
and validation outcomes. The full unit suites passed 763 app tests and 296
watch tests, with three pre-existing app tests ignored. Both debug builds
passed. Lint completed under the repository's nonblocking configuration with
repository-wide findings; the new filter, tests, and hooks had no findings.
Physical validation is recorded as unrun.

Run the focused regression and comparison suite with JDK 21 and the project SDK:

```sh
TEST_SEED=999 ./gradlew :app:testDebugUnitTest \
  --tests 'com.bitchat.android.spam.*' \
  --tests '*GeohashMessageHandlerSpamTest' \
  --tests '*IncomingMessageAdmissionTest' \
  :wear:testDebugUnitTest --tests 'com.bitchat.android.spam.*'
```

## Physical validation

The `broadcast_gibberish` Mesh Lab scenario observes two high-confidence
synthetic gibberish messages remaining absent on the receiver, followed by
successful ordinary-English delivery. It supports phone-to-phone and
phone-to-watch runs. It uses unique per-run content and the debug-only
`msg_recv` lookback option to avoid a startup sleep. Negative checks must
complete their observation window; an unrelated hook error cannot pass.
This scenario checks the physical admission path with one sender. It does
not measure fresh-identity flood rates or prove transport-level abuse prevention.

Physical validation remains unrun. Follow the authorization and controlled-lab
requirements in the release-gate runbook before device setup. After setup:

```sh
python3 tools/release_gate/mesh_lab.py scenario broadcast_gibberish \
  --serial-a "$MESH_SERIAL_A" --serial-b "$MESH_SERIAL_B" \
  --out "$MESH_EVIDENCE_DIR"
```

Run `broadcast` and `broadcast_spam` as neighboring regressions. Evidence must
stay in a private, uncommitted directory. Host-side fake-endpoint tests verify
the scenario's failure handling; they are not physical-device evidence.

## Limits

Short nonsense, unbroken identifier-like strings, unsupported scripts, and
letter sequences chosen to resemble natural language can pass. An attacker
can also pad text with excluded tokens or language characters to seek
abstention, or simply send real sentences. The detector complements the
existing rules; it does not guarantee useful conversation under an adaptive
attack. Wider language coverage, independent corpora, false-positive review,
and physical validation are needed before treating the model as mature.
