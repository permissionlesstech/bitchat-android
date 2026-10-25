# Single-message formatting abuse

The public spam filter checks each text message for extreme formatting before
its existing repetition analysis and sender token buckets. One message can be
rejected without any previous traffic. The verdict is `FORMATTING_ABUSE`, which
also appears in the existing debug bypass. Disabling the spam filter disables
this check. Private-message admission and non-text content use their existing
paths.

The detector uses fixed, conservative thresholds:

| Signal | Rejection threshold |
| --- | --- |
| Consecutive combining marks | 32 marks |
| Distributed mark stacks | At least four runs of eight marks, at least 64 marks total, and at least 40% marks |
| Consecutive identical code points | 128 code points |
| Consecutive whitespace | 512 code points, including mixed whitespace |
| Invisible formatting or non-whitespace controls | At least 128 code points and at least 60% of the message |
| Repeated symbol or emoji wall | At least 512 normalized UTF-16 units after removing whitespace, marks and invisible controls, with at least 90% duplicate five-unit windows |
| Repeated text | At least 256 normalized alphanumeric UTF-16 units, with at least 85% duplicate five-unit windows, or at least 48 words with at least 70% duplicate four-word windows |

NFKD normalization and case folding are used for analysis only. Displayed and
stored text is never rewritten. Ordinary combining scripts, decomposed accents,
bidirectional text and joined emoji are covered by negative synthetic fixtures.
These heuristics can still reject intentionally repetitive art or long copied
material; they do not classify topics, opinions or commercial content.

To bound work, messages exceeding 8,192 input UTF-16 units are rejected before
normalization. Normalized text exceeding 32,768 units is also rejected. All
remaining scans and fixed-size window hashes take linear work in bounded text;
there is no model, network request or extra cross-message state. Formatting
rejections enter the existing bounded verdict cache, consume no sender tokens,
and are not retained in repetition history.

The phone and watch compile the same detector through the watch's existing
shared-source include. The sender buckets and cross-message similarity
algorithm from the parent spam-filter change are unchanged.

Run the focused synthetic checks with:

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'com.bitchat.android.spam.*' \
  --tests 'com.bitchat.android.services.IncomingMessageAdmissionTest'
```
