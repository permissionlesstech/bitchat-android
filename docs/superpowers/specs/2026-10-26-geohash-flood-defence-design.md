# Geohash flood defence: design

Date: 2026-10-26 · Base: `upstream/main` @ `b8016545` · Branch: `feat/geohash-identity-cost` (local only, nothing pushed until confirmed)

## Why

PR #999 added a spam filter for public chats. It limits each sender and catches
repeated text. A test branch (`test/pr999-random-identity-flood`, commit `b8354d58`)
shows that a flood where every message comes from a fresh Nostr key with random
text gets through completely: 10,000 of 10,000 messages shown. Every check in
#999 needs either the sender or the text to repeat, and new Nostr keys are free.

The test branch shows the gap but contains no fix. PR #1001 (single-message
formatting checks) was closed without merging and does not address floods.

The goal is a fix that **actually solves the flood case for geohash channels,
proven locally with numbers, before anything is pushed**. If an experiment below
shows the fix does not work, that gets reported plainly instead of tuned around.

## Goals

1. Normal conversation in a geohash channel stays readable during a
   random-identity flood.
2. **A flood shows at most 6 bot messages per minute in a channel, whatever the
   flood rate** (decided by Moe), plus at most one initial burst equal to the
   pool's capacity.
3. Nothing changes for a channel that isn't being flooded, up to 6 first-time
   posters per minute: existing #999 tests and behaviour stay identical.
4. Every claim in the write-up comes from a test or benchmark we ran.

## Non-goals

- Mesh (BLE / Wi-Fi Aware) flooding. Mesh senders are keyed differently and
  need their own analysis. The pool applies to geohash (`nostr:`) senders only.
  The write-up must say plainly that a mesh random-identity flood still gets
  through.
- On-device AI or content classifiers.
- A "N hidden, tap to show" UI. Hidden messages stay dropped as in #999.
- Re-landing #1001.
- Changing iOS. iOS is out of reach of this repo; part 2 produces a proposal
  for it.

## Anti-goals (a failure even if the code works)

- Real people in an active channel lose messages when nothing is flooding.
- Joining a channel stops showing its recent history (the backfill trap #999
  solved with `TokenBucketSchedule`).
- A flood in one channel affecting any other channel.
- Any change that can make an Android user stop seeing iOS users.
- A stored record that reveals which geohashes the user visited and survives a
  panic wipe.
- Numbers in the write-up that weren't measured.
- Editing someone else's evidence: the flood test branch stays as its author
  wrote it.

## Current state (verified in code at `b8016545`)

- `GeohashMessageHandler.onEvent` checks, in order: geohash tag, signature,
  dedupe, optional per-message PoW, blocklist, then `SpamFilter.shouldHide`.
  The signature proves the key sent the message; nothing makes the key cost
  anything.
- `SpamFilter.shared` is one process-wide instance fed by both geohash handlers
  and mesh. `check()` has no channel parameter today.
- `SpamFilter` keeps per-sender state in an LRU of `maxTrackedSenders = 1_000`.
  A flood of fresh keys evicts real senders and resets their limits
  (reproduced by test). Every checked text also enters `recentTexts` (200
  entries) for the cross-user copy check, so a flood also pushes real texts out
  of it.
- Message timestamps (`created_at`) are set by the sender and clamped to
  `[now - 1h, now]`.
- Per-message NIP-13 PoW exists on both clients and does not match: Android has
  it off by default and, when on (12 bits), rejects anything with a lower
  committed difficulty. iOS always mines 8 bits and never rejects. An Android
  user who turns PoW on drops every iOS geohash message (reproduced by test).
  Out of scope here; raised separately.
- Geohash keys are per channel: `HMAC-SHA256(deviceSeed, geohash ‖ counter)`,
  counter 0–9, in `NostrIdentity.deriveIdentity`. Panic
  (`NostrIdentityBridge.clearAllAssociations`) deletes the device seed, so
  every channel key changes.
- `NostrBackgroundRuntime` publishes presence automatically to every available
  channel at city level or wider when location is on, so most users have
  already published under today's keys.
- `SpamFilter.shared.reset()` is called from the panic path
  (`ChatViewModel`) and when the filter is toggled (`SpamFilterPreferences`).

## The fix, part 1: shared pool for unestablished senders (Android only, PR candidate)

### Behaviour

Each geohash channel gets **one shared token bucket (the pool)** for all
senders that aren't yet established in that channel.

A sender is **established** in a channel once we admitted one of its messages
there **and at least 2 minutes of device time have passed since that admission
arrived**. Device time, not message time, because the sender controls
`created_at`: with message time a bot could backdate one message and look
established at once.

Order of checks for a geohash message:

1. #999's checks that need no sender state: repeated characters and the
   cross-user copy check. A message hidden here never touches the pool.
2. If the sender is not established (and, from part 2, not proven): the
   channel's pool. Empty pool → hidden with a new verdict `NEW_SENDER_POOL`.
   Pool-hidden messages create no sender state and are not added to
   `recentTexts` or any history, so a flood can neither evict real senders nor
   push real texts out of the copy check.
3. The rest of #999 unchanged: sender state, own-history repeat check, rate
   buckets.

### Decided

- Lives inside `SpamFilter.check`, so the existing toggle, debug bypass, verdict
  cache (dedupe across handlers) and red debug marking apply automatically.
- **One pool per geohash channel.** `check()` gains the channel for geohash
  messages (the handler knows `subscribedGeohash`). An attacker spreading over
  N channels gets N trickles, but each channel still sees at most the cap.
- The pool is a `TokenBucketSchedule`, so it runs on message timestamps and
  stays correct for out-of-order relay backfill when joining a channel.
- Established senders are kept in their own bounded map per channel, written
  only on admission. Because the pool admits only a trickle, a flood can't fill
  it. `reset()` clears it, so panic and toggling clear it.
- Starting parameters: capacity 6, refill 0.1/s (6 per minute, from Goal 2).
  The 2-minute establish delay is a starting point. Final values come from E1.
  They live in `SpamFilterConfig` beside the other limits; no new debug sliders
  in this change (keeps the PR small).

### Expected weak spots (to be measured, not hidden)

- **Newcomer during a flood.** A real person whose first message lands
  mid-flood competes with the bots. Prototype: 3 of 14 messages shown.
- **Joining a channel during a flood.** Everyone is new to you, so the people
  already chatting compete with the bots while the backlog loads. Predicted to
  go badly; this is what part 2 is for.
- **Backdated bot messages.** The pool's schedule reaches back an hour, so a bot
  can backdate messages into empty past slots. Up to about an hour of pool
  budget (≈ 360 messages at 6/min) could appear once, as old messages. E1
  measures this. If it's real, the builder may shorten the pool's backdate
  window, provided join-backfill with no flood still passes.

## The fix, part 2: channel-bound identity proof-of-work (local, behind a debug flag; proposal for maintainer + iOS)

### Rule

A geohash key is **proven** in channel `g` when

```
leadingZeroBits( SHA-256( "bitchat-geohash-id-v1" ‖ pubkeyX(32 bytes) ‖ utf8(lowercase(g)) ) ) ≥ N
```

- The geohash is inside the hash (binding), so work done for one channel is
  worthless in any other. Without binding a bot grinds keys once and reuses
  them in every channel, while honest users must grind per channel.
- The domain-separation prefix is my call; it stops the hash colliding with any
  other use of SHA-256 over a pubkey.
- Receivers already have the pubkey and the `g` tag, so verification is one
  hash and reveals nothing new.
- Proven senders skip the pool (they still go through #999).

### Sending side

- Start from today's key for the channel, `k0`. Try `k0 + j` (mod curve order)
  for j = 0, 1, 2…, stepping the public key by adding G each try (measured
  ~56× faster than full derivation). The first passing `j` gives the channel's
  key.
- Store `j` per channel in the secure identity store, keyed by
  `HMAC(seed, "idx" ‖ geohash)` so no geohash is stored in plain form, and wipe
  it together with the device seed on panic.
- Behind a debug flag, default **off**. With the flag off, keys are derived
  exactly as today.
- **Flag transitions (decided):** turning the flag on switches every channel to
  its ground key once; turning it off switches back. That's acceptable for a
  local experiment, and we keep no record of where the user has published. A
  real rollout has the same one-time key change for everyone (presence already
  publishes under today's keys); that cost goes into the proposal.
- Grind scheduling (pre-grind available channels in the background vs. on
  first open) is left to the builder. While a channel's grind isn't finished,
  that channel uses today's key and goes through the pool.
- The receive-side "proven skips the pool" check can be on regardless, because
  no one has proven keys yet, so it changes nothing until clients start
  grinding.

### Deliverable

1. Android implementation of both sides, behind the flag, tested locally.
2. A short written proposal for the maintainer and iOS with the rule, the
   chosen N, the one-time key change, and the measured numbers.

## Experiments, with stop conditions

All local, synthetic data only, no public relays, no real geohashes or
locations (obviously synthetic geohash strings). The builder writes the
scenarios below as tests; their parameters are part of this design so the
builder does not decide whether the fix passes.

**E1 scenarios** (each channel 15 minutes; real `SpamFilter`):

- *Already in channel:* 12 people chatting (one message every 20–90 s each)
  from minute 0; flood from minute 3 to 13.
- *Join during flood:* same channel, but this device subscribes at minute 8 and
  receives the last hour as backfill in random order, then live traffic.
- *Newcomer:* a 13th person posting every 40 s from minute 6.
- *No flood, busy:* 30 senders, of which 6 are first-time posters per minute;
  no bots.
- *No flood, backfill:* join a channel with an hour of history from 40 senders;
  no bots.
- *Bot shapes:* 1 post per bot key; 3 posts per key, 3 minutes apart; bots that
  backdate their messages up to an hour.
- *Flood rates:* 1, 17 and 100 messages per second.
- *Other channel:* a second, quiet channel while channel 1 is flooded.

| # | What | Pass condition | If it fails |
|---|------|----------------|-------------|
| E1 | The scenarios above, plus a small grid over pool capacity, refill and establish delay. | *Already in channel:* ≥ 99 % of normal messages shown. *Every flood scenario:* bot messages shown ≤ 6 per minute after the first minute, at every flood rate and bot shape, including backdating. *No flood busy / backfill / other channel:* identical to #999 (0 extra hidden). *Join during flood* and *newcomer*: **reported**, no pass bar. | Stop and report that the pool doesn't work, with the numbers. |
| E2 | All existing tests (`SpamFilterTest`, `GeohashMessageHandlerSpamTest`, `IncomingMessageAdmissionTest`, Nostr tests) plus **our own** geohash-only version of the flood test in `b8354d58`. The test branch itself is not edited. | All pre-existing tests pass unchanged; our flood test passes. | Report which tests broke and why. |
| E3 | Part 2 with the pool: *join during flood* and *newcomer*, where real users have proven keys. | ≥ 99 % of normal messages shown in both. | Report it; part 2 would not fix the pool's weak spots. |
| E4 | Grind timing including the binding hash: JVM, emulator, and the Pixel 9a **only with Moe's OK at that point**. Repeated runs, report mean and spread. | Some N where the Pixel's mean grind is ≤ 30 s **and** 10,000 bot identities cost ≥ 10 CPU-hours on desktop. | Report that identity PoW isn't practical at a useful difficulty. |
| E5 | Attacker cost: measured multi-core CPU rate. GPU: cite a published benchmark if a solid one exists, otherwise state it's unmeasured. | n/a | n/a |
| E6 | Mesh Lab spam scenario on two physical devices (AGENTS.md requires it for messaging changes). | Existing scenario still passes. | Report. Needs Moe's devices and time. |

## Left to the builder

- Where exactly the established map and per-channel pools live in `SpamFilter`,
  their size caps and eviction of idle channels.
- How `check()` receives the channel (new parameter vs. channel-qualified key),
  keeping mesh callers unchanged.
- Test structure, as long as tests use synthetic data and no sleeps (inject the
  clock for device time).
- Grind scheduling (see part 2).
- How the debug flag for part 2 is exposed (debug settings, debug source set).

## Rollout (after local confirmation; not part of this work)

1. Part 1 as a small PR referencing the flood test branch, with E1/E2 numbers
   and the plain statement that mesh floods remain open.
2. Separately: the per-message PoW mismatch, raised with the maintainer.
3. Part 2 as a proposal plus the Android branch, once the maintainer and iOS
   agree on the rule, N and the one-time key change.

## Results (2026-10-26, local)

Verdict: **the pool alone does not meet the bars; channel-bound identity PoW with proof-only
admission does, but only once real users' clients grind proofs (needs iOS).**

Deviations from the design, both forced by measurements:

- "Established" uses message time, not device time: with device time a joiner's whole backfill
  arrives within seconds, so nobody could be established and channel history would be hidden.
- Default `establishAfterMs` is 0 (established from the first admitted message). With 2 minutes,
  a busy channel with no flood lost 228 of 712 messages.

E1, default config (pool + #999), already in channel, 10-minute flood:

| Bot shape | Normal shown | Bots/min after first minute |
|---|---|---|
| one post per key, 1/17/100 msg/s | 205/209 | 6.0 |
| 3 posts per key | 205/209 | 14 |
| backdated up to 1 h, 1 msg/s | 205/209 | 34 |
| keep posting every 10 s | 205/209 | 42–231 |

- No flood, busy (6 new posters/min): 712/712, identical to #999. Backfill of 40 senders: 399/400.
- A second channel during a flood: unaffected (86/86).
- Join during flood: 156/197 normal shown (one-post bots); 175/197 with persistent bots, which
  then show ≈ 191/min.

Proof-only (`establishByTime = false`) with real users proven (E3): normal 209/209 and 197/197,
newcomer 14/14, bots ≈ 6/min, plus a one-time burst of ≤ 41 when joining mid-flood. Without
proofs, unproven real users are crushed (35/209), so proof-only cannot ship before clients grind.

E2: app 782 tests, wear 238 tests, 0 failures. Lint: no new issues on changed lines.

E4 (single core, binding hash included): desktop JVM 500k tries/s; Android emulator 197k tries/s.
At 22 bits: emulator mean 21 s per channel (p90 ≈ 3.8× median); 10,000 bot identities cost
23 desktop core-hours.

E4 on the Pixel 9a (3 rounds, thermal status normal): 80k tries/s single core, about 2.5× slower
than the emulator. Mean per channel: 20 bits 13 s, 21 bits 26 s, 22 bits 52 s (one real 22-bit
grind took 88 s). Only **21 bits** meets both bars: Pixel 26 s ≤ 30 s, and 10,000 bot
identities ≈ 11.7 desktop core-hours ≥ 10. Default `requiredBits` = 21. The margin is thin, and
the bot figure assumes the attacker runs the same JVM code; optimized native or GPU grinders are
faster (unmeasured), so the real bot cost is lower than this.

Security review fixes: `isProven` accepts only canonical 64-hex pubkeys (Kotlin's hex parsing
accepts forms like "+f", which could let one proven key appear as several senders). Known and
not changed: the pool charges message time, so backdated bots can use past pool slots once
(measured above); charging arrival time instead breaks joining a channel.

Open: Mesh Lab (E6), GPU cost (E5 unmeasured), backdated bots at low
flood rates, mesh flooding (identity PoW for mesh requested as a follow-up).
