---
name: android-ui-visual-review
description: Analyze an Android pull request, branch, commit, or patch for user-visible changes and produce reproducible before/after screenshots from isolated builds. Use this skill whenever a user asks for PR screenshots, branch UI comparisons, visual regression evidence, Compose before/after captures, populated message-state screenshots, responsive/theme/locale comparisons, or GitHub comments containing Android UI evidence—even if they only say “show me what changed.” Also use it to determine and document that a suspected UI PR has no visual delta. Do not use it for implementing a new UI, ordinary code review without visual evidence, or physical mesh validation.
compatibility: Requires git, the Android SDK and emulator, adb, Java/Gradle, Python 3, and gh for pull-request resolution or publishing.
---

# Android UI Visual Review

Turn a PR or branch into trustworthy visual evidence. The comparison is useful
only when the before and after builds use the correct commits, Android runtime,
viewport, app state, and navigation path.

## Collect the two user choices

Before starting, resolve:

1. **Target** — a PR URL/number or a branch/commit. If it is missing, ask for it.
   For a branch, also ask for the intended base when it cannot be inferred
   safely; otherwise default to the repository's `main`.
2. **Publishing** — for a PR target, ask whether the final screenshots and
   findings should stay local or be posted as a PR comment. Do not perform any
   GitHub write unless the user explicitly chooses publishing. A prior explicit
   request such as “post these to the PR” already answers this question.

Do not block on publishing preference while doing read-only analysis if the user
has not answered yet. Keep the local workflow useful on its own.

## Read the routed guidance

- Read [references/analysis-playbook.md](references/analysis-playbook.md) for
  every run. It explains how to map a diff to screens, states, and a capture
  matrix.
- Read [references/fixture-recipes.md](references/fixture-recipes.md) whenever
  the affected screen needs messages, peers, channels, nicknames, settings,
  permissions, locale, theme, onboarding state, or another non-empty fixture.
- Read [references/github-publishing.md](references/github-publishing.md) only
  when the user has opted into a PR comment.

## Create an isolated review session

Never switch the user's active checkout between before and after revisions.
Run from the repository root:

```sh
.agents/skills/android-ui-visual-review/scripts/create_review_worktree.sh \
  --target "<PR URL, PR number, branch, or commit>"
```

For a branch with a non-default base:

```sh
.agents/skills/android-ui-visual-review/scripts/create_review_worktree.sh \
  --target "<branch>" \
  --base "<base ref>"
```

The script fetches a PR head when needed, computes the **actual merge-base**,
creates a detached temporary worktree at the before SHA, and prints:

- session directory
- worktree path
- artifact directory
- before and after SHAs
- PR number/base metadata when applicable

Keep artifacts outside the worktree so checkouts cannot remove them. The script
may symlink the ignored `local.properties` into the temporary worktree; never
publish it or quote its contents.

If a review session already exists and its SHAs are verified, reuse it. Do not
create a second worktree for the same run.

## Establish the visual contract

Use the actual diff, not the PR title, to determine what should be visible.

1. Record `git diff --stat`, `--name-status`, and the focused diff between the
   before and after SHAs.
2. Trace changed UI symbols to their composable/activity, state source, entry
   point, and prerequisites.
3. Separate direct visual changes from indirect ones such as dynamic color,
   locale recreation, launcher resources, default data, or backend state shown
   by an otherwise unchanged screen.
4. Produce a local capture matrix before building. Each row should define:
   screen, navigation path, fixture, logical width, theme, locale, permissions,
   and what difference is expected.
5. Include a control state where the UI should remain unchanged when that helps
   distinguish intentional degradation from a regression.

When the diff contains no UI/resource/state-to-UI change, say so. If the user
asked for a screenshot for every target, capture the nearest affected surface
before and after and label the expected result **no visual delta**. Do not invent
a UI claim for a behavioral fix.

## Use the newest stable Android runtime

Prefer an emulator for repeatable UI evidence. At run time:

1. Inspect installed SDK/system images and current official Android release
   information. Choose the newest **stable** API; do not silently use a preview.
2. Create a dedicated AVD and isolated data directory when possible. If current
   command-line tools cannot create a newly versioned image (for example an
   extension image), a verified `-sysdir` override with an isolated data
   directory is acceptable.
3. After boot, record guest properties rather than trusting the AVD name:

```sh
adb -s "$ANDROID_REVIEW_SERIAL" shell getprop ro.build.version.release
adb -s "$ANDROID_REVIEW_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_REVIEW_SERIAL" shell getprop ro.build.version.security_patch
adb -s "$ANDROID_REVIEW_SERIAL" shell wm size
adb -s "$ANDROID_REVIEW_SERIAL" shell wm density
```

Use an explicit emulator serial for every ADB command when any physical device
is also connected. Never put serials, device names, local paths, IP addresses,
or other machine identifiers into reports or GitHub comments.

Use a physical device only when the affected UI depends on hardware that the
emulator cannot reproduce. Ask before changing or clearing a physical device.
This skill does not replace Mesh Lab: if the diff changes mesh, transport,
crypto, service, or physical peer behavior, use the `mesh-lab` skill separately
before claiming the behavior works.

## Build and capture the before state

The worktree starts at the before SHA.

1. Build the debug APK with `./gradlew assembleDebug`.
2. Install the ABI-matching APK with `adb install -r`.
3. Complete stable prerequisites such as onboarding and permissions.
4. Apply the fixture from the capture matrix.
5. Navigate using semantic/UI-automator evidence where possible. Use coordinate
   taps only after inspecting the current screen, and keep coordinates local.
6. Capture every matrix row with a descriptive name:

```text
before-<surface>-<state>-<width>-<theme>.png
```

Use `adb exec-out screencap -p` so the PNG is written directly to the artifact
directory. Inspect every screenshot immediately; a successful command is not
proof that the intended screen was visible.

## Build and capture the after state

Before switching commits:

- Preserve the artifact directory outside the worktree.
- Preserve only intentional app state.
- Record any temporary debug fixture patch.

Checkout the recorded after SHA in the detached worktree, reapply the same
debug-only fixture if needed, build, and install with `-r` when state
preservation is part of the comparison.

Replay the same navigation and capture matrix. Name files with the matching
`after-` prefix. If reinstalling cannot preserve the state, replay the fixture
from its recorded inputs rather than comparing different states.

For responsive captures, record logical width in dp. On a fixed-pixel emulator,
changing density is acceptable when the calculation is documented:

```text
density = physical_width_px × 160 / desired_width_dp
```

Restore the original density/theme/locale after the matrix is complete.

## Use deterministic artificial data

Prefer existing debug hooks. When they cannot express the visual state, add the
smallest temporary command to
`app/src/debug/java/com/bitchat/android/testhook/TestHookDriver.kt`.

The fixture must:

- stay under `src/debug`
- use synthetic names/content and deterministic IDs
- use the current mesh peer ID for self-authored messages so alignment logic is
  exercised correctly
- use a fixed fixture epoch passed to both builds
- return structured success data through the existing test-hook result file
- be logically identical in before and after builds

After the final capture, remove the temporary fixture with a focused patch and
verify that the review worktree has no tracked modifications. Never commit or
publish the fixture unless the user separately asks to productize it.

## Validate and report

Create `capture-manifest.json` in the artifact directory using
[assets/capture-manifest.example.json](assets/capture-manifest.example.json) as
the shape. Use paths relative to the manifest and omit device selectors and
local absolute paths.

Validate it:

```sh
python3 \
  .agents/skills/android-ui-visual-review/scripts/validate_capture_manifest.py \
  "<artifact-directory>/capture-manifest.json"
```

Write a Markdown report next to the manifest with:

- target and exact before/after SHAs
- verified Android release/API/security patch and viewport
- concise code analysis
- visual findings, including intentional non-changes
- side-by-side before/after tables
- fixture disclosure
- limitations and any hardware behavior not exercised

End the local run only after:

- every manifest image exists and is a valid PNG
- each before/after pair has matching pixel dimensions
- every screenshot has been visually inspected
- the worktree has no tracked fixture changes
- the user's original checkout remains untouched

Leave the review session and artifacts available for later inspection unless
the user asks for cleanup.

## Optionally publish to the PR

Only after explicit user approval, follow
[references/github-publishing.md](references/github-publishing.md).

The PR comment should contain:

- Android capture environment
- detected visual changes
- clear before/after labels
- all requested screenshots
- limitations such as “no visual delta” or “hardware race not reproduced”

Use `gh` for all GitHub reads and writes. Verify the posted comment by reading it
back and counting the expected image embeds. Do not expose local paths or
machine identifiers, do not override Git author/committer identity, and do not
push screenshot files to a source branch unless the user separately authorizes
that repository change.

## Final handoff

Give the user:

- report and artifact links
- screenshot count
- one-line result per surface
- PR comment URL when published
- explicit statement that the original checkout was not modified
- any remaining coverage limitation

