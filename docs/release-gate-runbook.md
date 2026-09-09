# Physical-device and cross-client release gate

This runbook turns Milestone 10 into a repeatable release procedure. The gate
uses a host-side CLI and USB/ADB as its control channel, so control traffic
never shares BLE, Wi-Fi Aware, Nostr, or Tor with the system under test.

The gate cannot pass without the required physical devices and counterpart
clients. A pending or blocked result is useful diagnostic evidence, but it is
not release approval.

## Safety and privacy rules

- Use only disposable lab app data, identities, nicknames, messages, and files.
- Never use a personal Nostr account or a production relay.
- Do not put device serials, UDIDs, Bluetooth/MAC/IP addresses, peer IDs,
  usernames, email addresses, local home paths, or message contents in a
  result, trace, filename, issue, commit, or release artifact.
- Device selectors may be supplied to ADB commands as ephemeral inputs. The
  tooling emits only logical aliases such as `android-current`.
- Models, manufacturer classes, Android API levels, negotiated MTU classes,
  client versions, commit hashes, aggregate counts, durations, and stable
  failure reason codes are allowed.
- Do not archive raw logcat. Convert observations to the structured,
  privacy-checked trace format, and keep any raw diagnostic capture local until
  it has been reviewed and sanitized.

The validator rejects known identifying fields and values before a passing
bundle can be created.

## Required lab

Prepare:

- At least three physical Android devices for three-hop relay testing.
- At least two Android API levels and two manufacturer classes.
- BLE central and peripheral support on every Android device.
- At least one Android 13+ device with Wi-Fi Aware.
- One physical device running the current iOS client.
- The last supported Android client.
- The release-candidate APK built from one exact full Git commit.
- A local, disposable Nostr relay/Tor fixture with production network access
  blocked.

One physical handset may be reused for the legacy-client phase after the
current-client evidence for that slot is complete, but the matrix must keep the
logical aliases and installed client versions unambiguous.

## 1. Verify the deterministic gate

From the repository root:

```sh
./gradlew clientRewriteContractTest checkChangedLineCoverage lintDebug
python3 tools/release_gate/release_gate.py validate-manifest
```

Do not begin device work from a dirty tree or a build whose deterministic gate
does not pass.

## 2. Create the device matrix

Copy `tools/release_gate/device-matrix.example.json` to an ignored working
directory under `release-gate-results/`. Replace every template value and set
both current-Android commit fields to the exact full commit under test.

Probe Android capabilities without storing the ADB selector:

```sh
python3 tools/release_gate/android_lab.py probe \
  --serial "$BITCHAT_ADB_SELECTOR" \
  --alias android-current
```

Copy only the returned logical metadata into the matrix. Validate it:

```sh
python3 tools/release_gate/release_gate.py validate-matrix \
  --matrix release-gate-results/device-matrix.json \
  --commit "$BITCHAT_RELEASE_COMMIT"
```

The matrix validator enforces physical devices, three Android participants, two
API levels, two manufacturer classes, Wi-Fi Aware, BLE roles, iOS, and explicit
current/legacy client versions.

## 3. Initialize disposable fixtures

```sh
python3 tools/release_gate/release_gate.py init \
  --matrix release-gate-results/device-matrix.json \
  --commit "$BITCHAT_RELEASE_COMMIT" \
  --run-id rc-lab-01 \
  --output release-gate-results/rc-lab-01
```

Initialization pins the scenario and fixture manifests, creates every scenario
as `pending`, and generates deterministic:

- zero-byte and small files;
- a Unicode-named medium file;
- sparse exact-maximum and oversized boundary files.

The fixture manifest records size and SHA-256. The final archive contains the
manifest, not the large fixture bodies.

Clear only the disposable app data on each selected lab device:

```sh
python3 tools/release_gate/android_lab.py prepare \
  --serial "$BITCHAT_ADB_SELECTOR" \
  --confirm-disposable-app-data
```

This stops the app and runs package-data cleanup. The explicit confirmation is
required because the operation is destructive to that app's local data.

## 4. Execute scenarios

The canonical scenario list is
`tools/release_gate/scenarios.json`. It contains 27 mandatory scenarios:

- the complete physical transport matrix;
- Android API/manufacturer/permission/background coverage;
- 11 Android-to-Android workflows;
- 8 cross-client/backward-compatibility workflows;
- 6 background and endurance workflows.

For each scenario:

1. Confirm the listed participants and capabilities.
2. Perform the corresponding steps in
   [device-transport-test-matrix.md](device-transport-test-matrix.md) and the
   Milestone 10 checklist.
3. Record connection, lifecycle, transport, receipt, resource, and terminal
   state as aggregate evidence.
4. Append at least one structured trace event.
5. Mark the scenario `pass`, `fail`, `blocked`, or `unsupported`.

Record evidence with the exact keys declared by the scenario:

```sh
python3 tools/release_gate/release_gate.py record \
  --run release-gate-results/rc-lab-01 \
  --scenario A2A-001 \
  --status pass \
  --evidence connection-transitions=4 \
  --evidence packet-correlation-count=6 \
  --evidence failure-reasons=none
```

Append a privacy-safe trace event:

```sh
python3 tools/release_gate/release_gate.py trace \
  --run release-gate-results/rc-lab-01 \
  --scenario A2A-001 \
  --source android-current \
  --event reconnect-terminal \
  --outcome pass \
  --metric reconnect-count=1 \
  --metric duplicate-delivery-count=0
```

Capture resource snapshots during endurance work:

```sh
python3 tools/release_gate/android_lab.py snapshot \
  --serial "$BITCHAT_ADB_SELECTOR" \
  --alias android-current \
  --run release-gate-results/rc-lab-01 \
  --scenario END-003
```

Use run-local sequential correlation labels while observing packets; archive
only aggregate correlation counts. Record failures with a stable reason code,
file a regression issue, and preserve the incomplete artifact.

## 5. Endurance requirements

- `END-001` requires at least 240 minutes.
- `END-002` requires at least 50 large-transfer/cancellation cycles.
- Sample memory, threads, file descriptors, wake locks, connection counts, and
  late callbacks at consistent intervals.
- A passing result requires bounded resource behavior and a clean terminal
  state; merely completing the time window is insufficient.

The validator rejects shorter durations and cycle counts.

## 6. Inspect progress and validate

During a run:

```sh
python3 tools/release_gate/release_gate.py validate \
  --run release-gate-results/rc-lab-01 \
  --allow-incomplete

python3 tools/release_gate/release_gate.py summary \
  --run release-gate-results/rc-lab-01
```

The release validator, without `--allow-incomplete`, requires:

- every scenario to be `pass`;
- every declared evidence field;
- at least one structured trace per scenario;
- the pinned scenario and fixture manifests;
- the exact client commit and complete device matrix;
- endurance minimums;
- a completion timestamp;
- no detected identifying fields or values.

`unsupported`, `blocked`, and `pending` never satisfy release approval.

## 7. Archive release approval

After the complete validator passes:

```sh
python3 tools/release_gate/release_gate.py bundle \
  --run release-gate-results/rc-lab-01 \
  --output release-gate-results/rc-lab-01.zip
```

The deterministic archive contains the scenario manifest, device/client matrix,
results, structured trace, fixture manifest, Markdown summary, and
`SHA256SUMS`. Attach it to the release approval record without renaming fields
or adding raw diagnostics.

Finally, clean the disposable app data with the same confirmed `cleanup`
command and stop the local relay/Tor fixture.

## Failure handling

- `fail`: behavior violated a contract. Record a stable reason code, file a bug,
  add a deterministic regression where possible, fix it, and rerun the affected
  scenario plus dependent scenarios.
- `blocked`: required lab infrastructure or counterpart client was unavailable.
  Preserve the artifact and do not approve release.
- `unsupported`: the selected device lacks a capability. Because the defined
  matrix requires Wi-Fi Aware, replace the device or matrix; unsupported does
  not waive a mandatory scenario.
- A flaky result is a failure until its cause is understood. Never average
  retries into a pass.

## Appendix: mesh lab (ADB test hooks, debug builds)

For day-to-day development there is a lighter-weight harness that drives a
debug-only broadcast receiver (`app/src/debug/`, never shipped in release)
exposing mesh operations over ADB: scan, connect, Noise handshake, DMs,
public broadcast, live push-to-talk, announce, file send/receive, BLE toggle, state dumps, and
raw packet injection. Results are JSON files in the app sandbox polled by the
host (`cache/testhook/results/<id>.json`, also logged under tag `TestHook`).

### Prerequisites

- A JDK (e.g. the one bundled with Android Studio; set `JAVA_HOME`) and the
  Android SDK platform-tools. `adb` must be on `PATH` or `ANDROID_HOME` set.
- Python 3.10+ on the host. No third-party packages are required.
- **Two physical Android devices** (API 26+, BLE) with USB debugging enabled,
  both plugged into the host. Emulators are not supported (BLE mesh).
- Verify both are visible: `adb devices` → note the serials.

### Device preparation (important)

Keep both phones **unlocked with the screen on** for the whole run. A locked
or dozing device forces the app into POWER_SAVER (1 s BLE scan per 60 s),
which makes discovery and handshakes take minutes and will flake every
scenario. The harness runs `wake()` (dismiss keyguard, stretch screen
timeout) during `setup`, but it cannot defeat a secure lock screen — unlock
the devices manually first. Note that `svc power stayon` only helps while a
device is actually charging.

### Build and set up

```sh
./gradlew assembleDebug
python3 tools/release_gate/mesh_lab.py setup \
  --serial-a <serial-1> --serial-b <serial-2> \
  --apk app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

`setup` cycles Bluetooth, installs the APK, clears app data, grants all
runtime permissions, wakes and launches the app, sets deterministic nicknames
(`alice`/`bob`), and waits for mutual peer discovery. It is safe (and
recommended) to rerun `setup` before each scenario batch; `--apk` may be
omitted if the current build is already installed.

### Run scenarios

```sh
python3 tools/release_gate/mesh_lab.py scenario all \
  --serial-a <serial-1> --serial-b <serial-2> --out /tmp/meshlab-evidence
```

| Scenario | What it asserts |
|---|---|
| `dm` | Noise handshake both ways, encrypted DM round trips with content match |
| `favorite_verification` | favorite signal, orange-outline/filled mutual state, and peer fingerprint verification |
| `broadcast` | public mesh message A→B |
| `ptt_dm` | Noise-encrypted 440 Hz PTT in both directions; asserts real-time capture, zero sequence gaps, decoded PCM duration/energy/continuity, and finalized-note absorption |
| `ptt_broadcast` | signed public 440 Hz PTT with the same bidirectional packet and decoded-audio quality assertions |
| `file` | 1 KB broadcast file, receiver SHA-256 matches fixture |
| `file_oversize` | >256-fragment broadcast file is rejected sender-side, receiver sees nothing |
| `file_private` | Noise-encrypted private file, digest match |
| `media_private` | private-chat contact ID resolves to the live mesh peer; voice, image, and generic-file digests match |
| `raw` | raw packet injection is accepted by the mesh |
| `session_recovery` | force-stop B mid-session: identity persists, re-handshake, DMs flow again |
| `identity_reset` | pm clear B mid-session: new identity, rediscovery, handshake, DMs |
| `all` | every scenario above in sequence |

Each run writes `<scenario>-evidence.json` to `--out` (digests, timings,
session states, logcat excerpts on failure) and exits non-zero on failure.
Evidence is a local diagnostic artifact; it may contain lab peer IDs and is
not privacy-checked like release-gate bundles — do not publish it.

### Ad-hoc commands

Any hook command can be sent to one device directly:

```sh
python3 tools/release_gate/mesh_lab.py cmd --serial <serial> scan --extra timeout_ms=30000
python3 tools/release_gate/mesh_lab.py cmd --serial <serial> handshake --extra peer=<peer-id>
python3 tools/release_gate/mesh_lab.py cmd --serial <serial> state   # full mesh dump
```

See `TestHookDriver.kt` for the full command set (`ping`, `start`, `stop`,
`whoami`, `set_nickname`, `scan`, `peers`, `connect`, `handshake`, `session`,
`announce`, `broadcast_msg`, `dm_send`, `dm_recv`, `msg_recv`, `favorite_set`,
`favorite_status`, `verification_set`, `verification_status`, `file_send`,
`file_recv`, `file_cancel`, `ptt_send`, `ptt_recv`, `raw_send`, `ble`, `state`,
`clear_results`).

### Troubleshooting

- **Discovery/handshake timeouts**: almost always a locked or dozing phone —
  unlock both devices and rerun `setup`. `cmd ... state` shows
  `App In Background: true` and the BLE duty cycle when this is the cause.
- **Stale app state after many churn runs**: `svc bluetooth disable/enable`
  on both devices (done automatically by `setup`) clears zombie GATT links.
- **Watch the wire**: `adb -s <serial> logcat -s TestHook MessageHandler
  FragmentManager BitchatFilePacket` shows commands, results, decrypt
  failures, fragment rejects, and saved incoming files in real time.
- Results also persist on-device at
  `run-as com.bitchat.droid cat cache/testhook/results/<id>.json`.

Unlike the release gate, this harness is a development aid: it prints raw
diagnostics and does not produce a privacy-checked approval bundle.
