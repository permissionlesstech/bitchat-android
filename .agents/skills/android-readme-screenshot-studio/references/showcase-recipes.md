# README showcase recipes

Use these recipes as composition guidance, then adapt them to the user's exact
request and the current UI. The requested chronology and framing always win over
the examples.

## Mesh-chat showcase

### Visual goal

Make the screen read as a real conversation at a glance:

1. one strong media anchor near the top;
2. a short text exchange with alternating senders;
3. a compact voice-note exchange with visibly different speech envelopes;
4. a small final reaction or acknowledgement;
5. the app header and composer framing the timeline.

Avoid stuffing every supported feature into one frame. The screenshot should
show capability through hierarchy, not through maximum item count.

### Known-good fixture shape

For a request like “photo, messages, three voices, thumbs-up,” use exactly:

| Order | Type | Sender |
|---|---|---|
| 1 | Image | self or remote, according to the story |
| 2 | Short text | other sender |
| 3 | Short text | alternating sender |
| 4 | Short text | alternating sender |
| 5 | Short text | alternating sender |
| 6 | Voice note | sender A |
| 7 | Voice note | sender B |
| 8 | Voice note | sender A |
| 9 | `👍` | sender B |

Keep copy conversational and concise. Use synthetic names and avoid real
locations, contacts, identities, or sensitive content.

For public mesh rendering, pass the current mesh peer ID as `senderPeerID` on
self-authored messages. This exercises the same ownership and color path as
production. Alternate sender IDs so every back-and-forth row renders its sender
header instead of being grouped away.

### Photo framing

A portrait or tall crop can let the latest rows stay visible while the older
photo slides partially behind Bitchat's translucent header. This is visually
useful only when the photo subject remains legible.

- Preserve the app's own rounded image treatment.
- Do not bake UI chrome into the photo.
- Avoid important content under the header overlap.
- Do not select a photo subject from nicknames alone.
- If generating a synthetic photo, obtain or infer subject approval first,
  disclose generation, and keep the source outside the final commit unless
  requested.

### Natural voice rows

Prepare three short speech clips with different durations, pauses, and cadence.
Locally available offline TTS plus an audio transcoder is sufficient. Use the
same audio format the app normally records or plays, such as M4A.

After copying the files into an app-readable location, create
`BitchatMessageType.Audio` messages that point to those actual files. Let
`AudioWaveformExtractor` and `VoiceWaveformCache` produce the bars.

Reject the capture when:

- all three envelopes look identical;
- the bars are uniform or sinusoidal rather than speech-like;
- duration labels are missing or implausible;
- a waveform is clipped by the screen edge;
- a temporary progress or cancel state is visible.

### Layout tuning

Inject the complete fixture in one operation so the list adopts it as history
instead of animating rows during capture. Let the reverse-layout list settle at
the newest message.

If the oldest photo is not partially visible, prefer changing its aspect ratio
or the number of short text rows over manually scrolling to an unstable offset.
If the newest reaction falls behind the composer, shorten earlier content or
reduce media height. Do not crop app content to solve a fixture problem.

## Geohash globe showcase

### Visual goal

Show:

- the entire Earth;
- a readable geohash grid;
- the selected coarse cell;
- the requested geographic focus beneath the selection crosshair;
- the picker hint and precision controls.

Use the current picker Activity and renderer. Do not composite a globe or grid
outside the app.

### Focus without zoom

When a user says “focus on the Middle East, no zoom”:

1. open or seed the picker at a geohash centered on the requested area;
2. allow the globe to center on that location;
3. reduce precision to the same coarsest whole-Earth level used for the
   showcase;
4. preserve camera distance while confirming the center moved;
5. capture with the whole globe still visible.

The exact seed may change with picker implementation. Validate against visible
geography and the selected geohash label rather than assuming the seed worked.

Reject the capture when:

- the requested region is off-center;
- Earth is clipped;
- reducing precision also changed the camera distance against the brief;
- the selected cell or crosshair is illegible;
- controls overlap the globe;
- stale system bars remain in the final README asset.

## Crop and output

Capture the full physical screen first. Determine the status-bar and
navigation-bar insets from the current profile, then crop those insets only.

For a Pixel 9 Pro XL profile at 1344×2992, a 1344×2780 final image was a
known-good result in one verified run. Treat those numbers as evidence, not as a
universal crop rule.

Every paired README image should:

- be a valid PNG;
- share width and height;
- retain the app header and composer or controls;
- exclude Android status and gesture/navigation chrome;
- remain sharp at GitHub's rendered width.

## Acceptance checklist

### Chat

- [ ] Media subject matches the user's brief.
- [ ] Message chronology exactly matches the requested sequence.
- [ ] Sender ownership and alternation are correct.
- [ ] Voice-note count is exact.
- [ ] Waveforms were extracted from real speech audio and look distinct.
- [ ] Final reaction is visible above the composer.
- [ ] Header peer count and nickname are synthetic and intentional.

### Globe

- [ ] Requested region is centered.
- [ ] Zoom level matches the brief.
- [ ] Whole Earth and grid are visible.
- [ ] Selected cell and crosshair are legible.
- [ ] Hint and precision controls are unobstructed.

### Repository

- [ ] Only requested README and PNG assets remain in the diff.
- [ ] Temporary debug fixture and media are removed.
- [ ] Final clean debug build passes.
- [ ] Screenshot validator passes.
- [ ] PR text contains no machine or personal identifiers.
- [ ] Static-capture limitations are disclosed.

