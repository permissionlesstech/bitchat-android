---
name: mesh-lab
description: Run, diagnose, and extend bitchat Android Mesh Lab physical-device tests. Use this skill whenever work capable of changing physical peer behavior touches mesh discovery or routing, BLE or Wi-Fi transport, Noise/crypto/identity, foreground-service or power behavior, public or private messaging, file/media transfer, protocol packets, or fragmentation; whenever a user asks for physical-device validation, ADB test hooks, hardware regression reproduction, or a new Mesh Lab scenario; and before claiming that such changes work on real devices, even if the user does not name Mesh Lab explicitly. Do not use it for docs-only, unit-test-only, or pure UI changes that cannot affect mesh or service behavior.
---

# Mesh Lab

Use the repository's debug-only ADB harness to validate mesh behavior on physical
devices. Treat it as a development integration test, not as the privacy-checked
release gate and not as proof about a release APK.

## Establish current ground truth

Run from the repository root.

Before choosing commands or changing a scenario:

1. Read the Mesh Lab section of `AGENTS.md`.
2. Read the "mesh lab" appendix in `docs/release-gate-runbook.md`.
3. Use `python3 tools/release_gate/mesh_lab.py --help` and the relevant
   subcommand help.
4. Inspect the selected scenario function in `tools/release_gate/mesh_lab.py`;
   its current CLI and assertions are authoritative if documentation has drifted.
5. Inspect
   `app/src/debug/java/com/bitchat/android/testhook/TestHookDriver.kt` before
   using an ad-hoc command, diagnosing hook behavior, or extending coverage.

Do not infer a physical pass from unit tests, compilation, old evidence, or a
successful local send call.

## Decide the physical coverage

Inspect the change or requested behavior first. Select the smallest scenario set
that exercises the affected physical contract, expanding to `all` for broad,
cross-cutting, or release-sensitive changes.

| Affected behavior | Start with |
|---|---|
| Discovery, connection management, routing, foreground-service lifecycle | `broadcast`, `dm`, `session_recovery` |
| Background, doze, or power-duty-cycle behavior | Existing setup keeps devices awake and foregrounded; add a focused workflow or use the full release gate |
| Wi-Fi Aware or transport-selection behavior | Existing setup enables BLE and does not pin Wi-Fi; add a transport-specific control/assertion or use the full release gate |
| Noise, crypto, authenticated peer state, identity persistence | `dm`, `file_private`, `session_recovery`, `identity_reset` |
| Public messaging or message delivery | `broadcast`, then `dm` if shared routing changed |
| File/media encoding, transfer, fragmentation, admission limits | `file`, `file_private`, `file_oversize` |
| Packet parsing, bridge/routing, TTL, or protocol changes | `raw`, plus a receiving scenario such as `broadcast` or `dm` |
| Broad mesh or transport refactor | `all` |
| UI-only work with no service, state, or delivery effect | Usually no Mesh Lab run; explain why |

When the selected scenarios do not exercise the new contract, add a focused
scenario instead of treating unrelated green tests as coverage.

## Protect devices and evidence

Mesh Lab setup is destructive to the app's local data. It force-stops the app,
clears package data, regenerates identity, cycles Bluetooth, grants permissions,
and changes wake/lock-screen timeout settings without restoring them.

- Use only designated disposable lab app data and deterministic test content.
- Use two authorized physical Android BLE devices on API 26 or newer. Emulators
  do not exercise the required BLE mesh behavior.
- Before `setup`, `identity_reset`, or `all`, confirm that the selected devices
  may have bitchat app data cleared. `identity_reset` clears device B even when
  setup was skipped. If the user has not already established authorization, ask.
- Never attempt to defeat a secure lock screen. Ask the operator to unlock it.
- Keep every device unlocked, awake, foregrounded, and preferably charging.
- Treat ADB selectors as ephemeral secrets. Do not put serials, device names,
  peer IDs, addresses, fingerprints, local home paths, or raw logcat in commits,
  pull requests, issues, or published artifacts.
- Write raw evidence under `/tmp`, keep it local, and never commit it. Failure
  evidence can include unsanitized logcat and lab identifiers.
- Use debug APKs only. The exported test-hook receiver intentionally has no
  production security boundary and must never be moved into `src/main`.
- Use an authorized, controlled lab area. After setup, inspect peer state
  locally and stop if an unexpected peer is present before sending broadcasts,
  files, or raw packets.
- On a non-dedicated device, record the prior Bluetooth, stay-awake, screen
  timeout, and lock-screen-disabled settings locally. Restore only those
  recorded values after the run, or tell the operator exactly what remains
  changed.

If the hardware, operator confirmation, or prerequisites are unavailable,
report the physical result as `blocked (not run)` and provide the exact handoff
command. Never soften this to "pass" or "probably works."

## Run a two-phone batch

Preflight the environment without copying device selectors into durable output:

```sh
python3 --version
adb devices
./gradlew assembleDebug
```

Set up the disposable pair:

```sh
python3 tools/release_gate/mesh_lab.py setup \
  --serial-a "$MESH_SERIAL_A" \
  --serial-b "$MESH_SERIAL_B" \
  --apk app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Build and pass the current-tree debug APK during normal use. If setup
intentionally omits `--apk`, first verify on both devices that the installed
package is debuggable via `run-as` and that its package dump contains
`TestHookReceiver`; do this before any command that clears data.

Inspect `peers` on both devices after setup. Continue only when every discovered
participant belongs to the controlled lab.

Run either the selected scenario or the full suite. Run this entire block in one
shell invocation so the temporary-directory variable cannot disappear between
agent shell calls. Abort the block if the directory is empty or missing before
passing it to `--out`; otherwise an empty path can put private evidence in the
repository. Replace `dm` with
`all` only when full-suite data clearing has been authorized.

```sh
MESH_EVIDENCE_DIR="$(mktemp -d /tmp/meshlab-evidence.XXXXXX)"
if [ -z "$MESH_EVIDENCE_DIR" ] || [ ! -d "$MESH_EVIDENCE_DIR" ]; then
  echo "mktemp failed; aborting so evidence cannot land in the repository" >&2
  exit 1
fi
chmod 700 "$MESH_EVIDENCE_DIR"

python3 tools/release_gate/mesh_lab.py scenario dm \
  --serial-a "$MESH_SERIAL_A" \
  --serial-b "$MESH_SERIAL_B" \
  --out "$MESH_EVIDENCE_DIR"
```

Rerun `setup` before a fresh scenario batch when prior churn, stale identities,
or zombie GATT links could contaminate the result.

Keep the private evidence only as long as the active investigation needs it.
Do not delete failure evidence that the user still needs; when it is no longer
needed, remove it or move it to an explicitly approved protected location.

## Interpret results precisely

- `dm`, `broadcast`, `file`, and `file_private` include receiver-side assertions.
- `file` and `file_private` currently validate a 1 KB deterministic fixture and
  SHA-256 integrity, not sustained or boundary-sized transfer performance.
- `file_oversize` validates sender rejection and receiver absence for a 512 KB
  broadcast, not the exact 256/257-fragment boundary.
- `raw` proves that the local transport bridge accepted the injected packet. It
  does not prove that another device received or accepted it.
- `session_recovery` proves recoverability after process death, but may fall
  back to an explicit handshake; it does not prove a fully automatic recovery.
- `identity_reset` proves new-identity recovery after `pm clear`; stale state for
  the old identity is diagnostic evidence rather than a purge assertion.
- The current CLI drives exactly two phones. It does not validate a three-hop
  topology, transport-specific Wi-Fi Aware behavior, permission denial, doze,
  endurance, resource bounds, transfer cancellation, release builds, or
  cross-client compatibility.
- For ad-hoc commands, `status: ok` can mean the command completed without
  satisfying the requested state. Inspect fields such as `reached_min_peers`,
  `direct`, `established`, or `cancelled`.
- `all` runs scenarios sequentially on evolving device state. In the current
  runner it writes combined evidence only, and a failed sub-scenario can abort
  aggregation without clean structured evidence. Run selected scenarios
  individually first when durable per-scenario evidence matters, then use
  `all` as broader regression coverage.

A scenario exits zero on pass and non-zero on failure. On failure, preserve the
local evidence, inspect its error first, then use state dumps and filtered logcat:

```sh
python3 tools/release_gate/mesh_lab.py cmd \
  --serial "$MESH_SERIAL_A" state

adb -s "$MESH_SERIAL_A" logcat -d -t 200 -s \
  TestHook MessageHandler FragmentManager BitchatFilePacket
```

Common first checks are screen/foreground state, mutual discovery, direct-peer
state, Noise session state, and stale Bluetooth connections. Rerun `setup` only
after preserving useful diagnostics.

The generic `cmd --extra` wrapper does not encode every Android extra type
correctly: Boolean `enabled` and integer `min_peers`/`ttl` are notable cases.
Use a direct `adb shell am broadcast` with `--ez` or `--ei`, after reading the
driver, when exact types matter. Use the top-level `--timeout-ms 30000` option
for command timeouts; never pass `--extra timeout_ms=...`, because it duplicates
the runner's `timeout_ms` keyword and fails before dispatch.

## Add a physical-device scenario

Prefer extending `tools/release_gate/mesh_lab.py` with existing hook commands.
Add or change an Android hook only when the public mesh API cannot express the
required action or observation.

Design the scenario around an observable remote contract:

1. Generate a unique token or deterministic fixture so stale state cannot pass.
2. Start the receiver wait before sending.
3. Assert remote sender identity, content, session state, digest, or expected
   absence—not merely that the sender accepted a call.
4. Use bounded timeouts and return structured JSON evidence.
5. For negative tests, assert both the expected sender error and that the
   receiver did not observe the artifact.
6. Keep hook code and manifest registration under `src/debug`.
7. Update the runbook scenario table and troubleshooting guidance.
8. Run the new scenario individually, then run relevant neighboring scenarios
   or `all` to detect state contamination.

Do not add test-only branches to production mesh code merely to make a scenario
easy to drive.

## Report the outcome

End with a compact physical-test report:

- Change or contract tested
- Device topology: logical roles only, such as phone A to phone B
- Build and scenario names
- Result: `pass`, `fail`, or `blocked (not run)`
- Local evidence directory, clearly marked private and uncommitted
- On failure: the exact violated invariant and the next diagnostic
- Coverage limits and any scenario fallback that weakens the claim

Keep device selectors and raw evidence out of the report, commit, and pull
request.
