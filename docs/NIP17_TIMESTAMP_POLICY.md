# Direct-message timestamp admission

[NIP-17](https://github.com/nostr-protocol/nips/blob/master/17.md#encrypting)
randomizes seal and gift-wrap timestamps up to two days into the past. The
inner rumor retains the send time. Android applies an additional local receive
policy: a 24-hour delivery lookback with 15 minutes of clock tolerance. Those
local windows are not limits imposed by NIP-17 itself.

Both comparisons are inclusive:

- Inner rumor: `now - 87300 <= created_at <= now + 900`.
- Outer gift wrap: `now - 260100 <= created_at <= now + 900`.

The outer maximum age includes 172800 seconds of randomization, 86400 seconds
of delivery delay, and 900 seconds of clock tolerance. A rumor sent 24 hours
ago with the full two-day randomization must survive the outer check. Checking
only 48 hours would discard it before decrypting its valid inner timestamp.

Account and background DM subscriptions use that same outer cutoff, converting
the millisecond API value to seconds when encoding the Nostr filter. Both
receive entry points check the wrapper before decrypting and check the rumor
before delivering it. A recent wrapper cannot make an old rumor acceptable.
These checks do not replace signature verification, decryption, or deduplication.

`app/src/test/resources/contracts/nip17-timestamp-v1.csv` contains fixed,
synthetic timestamp-policy vectors. Its literal timestamps are independent of
production constants. They cover exact boundaries, one-second rejections, a
24-hour delayed message randomized by 48 hours, and disagreements between
inner and outer age. These are spec-derived acceptance vectors, not encrypted
NIP-44 test vectors or evidence of a physical cross-client exchange.

The existing relay filter limit remains 100. This change does not implement
pagination or guarantee complete delivery for histories exceeding that limit.
Physical-device interoperability and the complete Android build remain separate
validation requirements.
