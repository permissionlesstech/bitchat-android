---
name: android-readme-screenshot-studio
description: Create or refresh polished, high-resolution screenshots of the Bitchat Android app for README and repository showcase use. Use this skill whenever a user asks for README screenshots, app-store-like repository images, a populated mesh-chat showcase, voice-note or media conversation captures, a geohash globe image, higher-resolution Android emulator captures, or a PR that adds or replaces documentation screenshots. It owns the complete workflow from latest-main isolation and deterministic synthetic fixtures through real app rendering, visual inspection, system-chrome cropping, README asset updates, clean builds, and an optional PR. Do not use it for before/after UI regression evidence, which belongs to android-ui-visual-review, or for physical mesh behavior, which belongs to mesh-lab.
compatibility: Requires git, gh, the Android SDK and emulator, adb, Java/Gradle, Python 3, and image inspection support. FFmpeg is useful for capture-only media preparation.
---

# Android README Screenshot Studio

Create repository screenshots from the real Bitchat Android UI, not from a
drawn mockup. The result should look intentional enough for the top of the
README while remaining reproducible, synthetic, and honest about what a static
emulator capture proves.

## Resolve the brief

Extract as much as possible from the conversation before asking questions.
Confirm or infer:

1. Which surfaces are needed, such as mesh chat and geohash globe.
2. The exact visible state and ordering. Treat phrases like “photo, messages,
   three voices, thumbs-up” as a chronological contract rather than a loose
   suggestion.
3. Whether existing README images should be preserved, replaced, or added.
4. The target base. Default repository work to the latest `origin/main`.
5. Whether GitHub publication and merge are authorized. A request to “open a
   PR and merge it” authorizes both; otherwise do not merge.

Do not invent the subject of a requested photo. Nicknames, channel names, or
previous fixture copy are not sufficient justification for choosing an outdoor,
urban, political, or personal scene. Reuse an existing rights-safe asset when
the subject should remain stable, or ask for the intended subject. If the user
explicitly approves synthetic imagery, disclose it and keep its source
capture-only unless they request a committed asset.

Read [references/showcase-recipes.md](references/showcase-recipes.md) for every
run. It contains the concrete mesh-chat and globe recipes, framing guidance,
the verified current-pair fast path, and the final acceptance checklist. When
the request matches the existing README pair and the production UI has not
materially changed, try that fast path first and then validate every visible
result. Fall back to tracing the current implementation when an entry point,
state model, or composition has changed.

When a populated screen requires a debug fixture, also read
[../android-ui-visual-review/references/fixture-recipes.md](../android-ui-visual-review/references/fixture-recipes.md).

## Work in a fresh tree

Protect the user's active checkout:

1. Inspect `git status` without modifying it.
2. Fetch `origin/main`.
3. Create a new `codex/` branch in a temporary worktree rooted at the current
   `origin/main`.
4. Keep screenshots and raw captures in a separate temporary artifact
   directory so cleanup or checkout operations cannot remove them.

Do not switch the user's original checkout, reuse a dirty branch, or mix an
unrelated PR into the screenshot change. If the request continues an existing
screenshot PR, reuse its already-isolated worktree only after verifying its
head and base.

## Establish the capture contract

Before building, write a compact local matrix containing:

- surface and navigation path;
- chronological fixture contents;
- expected visible top and bottom rows;
- emulator profile class, portrait orientation, theme, and locale;
- crop policy and final asset dimensions;
- existing asset path and README reference;
- behaviors the static screenshot does not prove.

Use the current production UI and latest `main` interaction model. Trace the
screen entry point and state source before adding a fixture. A beautiful capture
of a stale or fake UI is not acceptable.

## Use a high-resolution Android canvas

Prefer the newest stable Android runtime installed locally and a large,
high-density portrait emulator profile. Reuse a previously validated capture
profile when it remains available, but verify the guest properties and keep
the resulting profile facts in the local capture contract rather than the
repository or GitHub text.

After boot, record the guest values with an explicit emulator selector:

```sh
adb -s "$ANDROID_README_SERIAL" shell getprop ro.build.version.release
adb -s "$ANDROID_README_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_README_SERIAL" shell getprop ro.build.version.security_patch
adb -s "$ANDROID_README_SERIAL" shell wm size
adb -s "$ANDROID_README_SERIAL" shell wm density
```

Never publish emulator selectors, AVD names, local paths, usernames, IP
addresses, or other machine identifiers.

## Build a deterministic showcase fixture

Launch the Activity before injecting process-local state. Prefer existing debug
hooks. If they cannot express the composition, add the smallest temporary
command under:

```text
app/src/debug/java/com/bitchat/android/testhook/
```

The fixture should:

- use synthetic names, peer IDs, message IDs, and copy;
- use the real local mesh peer ID for self-authored messages;
- use a fixed epoch so timestamps and ordering are stable;
- insert records in the exact requested chronology;
- report structured counts through the test-hook result file;
- copy capture-only media into the app's cache or files directory;
- populate only the peers and state needed for the header;
- avoid persistence unless persistence itself is the subject.

For voice notes, route real audio files through the app's waveform extractor.
Use short, distinct, locally synthesized speech clips or other rights-safe
speech audio. Never draw a decorative waveform and call it speech. Wait for
asynchronous decoding before capture, then inspect that pauses and syllable
envelopes look plausibly different between notes.

For image attachments, use a rights-safe existing asset or an explicitly
approved synthetic source. Keep fixture media outside production source sets
and remove every capture-only hook before committing. Compose image rows may
remember a decoded bitmap by file path. After replacing the bytes at an
unchanged path, relaunch the app or use a new destination path before judging
the revised crop.

## Capture from the real app

Build and install the ABI-matching debug APK, satisfy onboarding and
permissions, inject the fixture, and navigate to the intended surface.

Capture directly:

```sh
adb -s "$ANDROID_README_SERIAL" exec-out screencap -p > "$ARTIFACT_PATH"
```

Inspect the full screenshot immediately. Check message count and order,
nickname ownership, peer count, waveform variety, image visibility, globe
center, grid precision, clipping, and composer placement.

Inject a complete timeline in one operation and allow at least two seconds of
quiet UI time after the fixture reports success. This avoids capturing entry
animations, incomplete placement, or media that has not finished decoding.

Crop only Android system chrome. Preserve Bitchat's app header, translucent
overlap, content, and composer. Derive the crop from the observed status and
navigation insets; do not blindly reuse pixel offsets from a different profile.
Because the app renders edge-to-edge, app controls may extend into the reported
navigation inset. Place the bottom crop after the final control outline and
shadow but before the system gesture affordance; removing the entire inset can
clip the app itself.
Keep every final README screenshot in a matched portrait size.

Use image inspection after the crop. File dimensions and a successful ADB
command do not prove that the desired composition is visible.

## Update repository assets

Discover the current README references before writing. Prefer stable paths under
`docs/screenshots/` and replace only the assets the user requested.

When adding a showcase section:

- keep the layout readable on GitHub;
- give every image meaningful alt text;
- use relative repository paths;
- avoid machine-generated cache files or capture sources;
- keep paired screenshots at identical dimensions.

Run the bundled validator for every final asset:

```sh
python3 \
  .agents/skills/android-readme-screenshot-studio/scripts/validate_readme_screenshots.py \
  --repo-root . \
  --readme README.md \
  --require-same-size \
  --asset docs/screenshots/readme-mesh-chat.png \
  --asset docs/screenshots/readme-geohash-globe.png
```

When replacing only one image in an existing pair, pass both the changed and
unchanged assets with `--require-same-size`, and verify the unchanged asset's
checksum. Pass only one asset and omit `--require-same-size` only when the
README has no paired screenshot to preserve.

## Remove the fixture and verify cleanly

Before committing:

1. Remove temporary imports, commands, helpers, resources, and fixture media
   with a focused patch.
2. Verify `git diff -- app/src/debug` is empty.
3. Verify `git status --short` lists only the intended README and screenshot
   files.
4. Run `git diff --check`.
5. Run `./gradlew assembleDebug` after fixture removal.
6. Re-run the screenshot validator.
7. Confirm the user's original checkout is still untouched.

The final commit must not contain synthetic peer data, generated photo sources,
audio clips, ADB outputs, emulator configuration, or local capture reports
unless the user separately requested those artifacts in the repository.

## Record honest evidence

Use the capture manifest and report format from
`../android-ui-visual-review/` when before/after evidence is useful. For a
README-only change with no production UI delta, identical before/after images
are acceptable when explicitly labeled “no production UI delta.”

State limitations plainly:

- a populated mesh timeline proves rendering, not physical message delivery;
- a voice row proves waveform rendering, not audio playback;
- an attachment proves image rendering, not media transfer;
- a globe proves picker state, not live location or relay behavior.

## Commit, publish, and optionally merge

GitHub writes require user authorization. When authorized:

1. Stage only intended files.
2. Commit without overriding author or committer identity.
3. Push the `codex/` branch.
4. Use `gh pr create` or update the existing PR.
5. Describe the exact capture sequence, synthetic fixture disclosure,
   repository-safe validation commands, and limitations. Keep emulator,
   runtime, hardware, and local-environment facts out of GitHub text whenever
   repository privacy rules classify them as machine identifiers.
6. Verify the PR head and checks with `gh pr view` and `gh pr checks`.
7. Merge only when the user explicitly requested it and required checks allow
   it. Prefer the repository's normal merge strategy and use `gh`.
8. Verify the merged state and resulting `main` commit.

When GitHub publication is not authorized, leave the finished commit or local
change in the isolated worktree and hand back its branch and artifact paths.
Do not silently push it.

Do not place local paths, device selectors, generated-image paths, or personal
machine details in commits, PR text, comments, or merge messages.

## Final handoff

Lead with the outcome and include:

- PR and merge URL or status;
- final screenshot paths and dimensions;
- one-line composition summary per surface;
- build and validator results;
- synthetic media disclosure;
- current CI state or merged commit;
- confirmation that the original checkout was not modified.
