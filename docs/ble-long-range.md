# BLE long range (LE Coded PHY)

The Android BLE transport can use Bluetooth 5 LE Coded PHY to extend
phone-to-phone range without root or extra hardware. It adds to the existing
transport and doesn't replace any of it. The bitchat wire protocol, GATT
service, Noise sessions, and packet formats are unchanged.

## Modes

The mode is set under Debug settings → "Long range (LE Coded PHY)" and is
stored in the `ble_long_range_mode` preference.

| Mode | Discovery | Established links |
| --- | --- | --- |
| `OFF` | Legacy advertising and legacy scan (stock behavior) | No PHY requests |
| `ADAPTIVE` | Legacy + Coded advertising, extended scan on all PHYs | Client switches to Coded S=8 when smoothed RSSI is below about -80 dBm and back to 1M above about -66 dBm (10 s minimum dwell) |
| `MAX_RANGE` (default) | Legacy + Coded advertising, extended scan on all PHYs | Both ends request Coded S=8 after setup, with one bounded retry |

### Coded coding (S=8 / S=2)

The debug menu also chooses the coding requested for links: S=8 (default,
125 kbit/s, about 4× the 1M range), S=2 (500 kbit/s, about 2×), or no
preference. This is passed as the `setPreferredPhy` PHY option and stored in
the `ble_coded_coding` preference. Changing it re-requests Coded on every
current link.

It's only a preference. The remote controller may use a different coding for
its own transmissions, and Android reports a Coded link only as
`PHY_LE_CODED`, never which coding is in use. The debug menu therefore shows
the coding this side *requested*. Advertising coding is chosen by the
controller; Android exposes no setting for it.

## Runtime capability detection and fallback

Capabilities are read from the public `BluetoothAdapter` API every time
advertising or scanning starts. The code never assumes Coded PHY support:

- `isLeCodedPhySupported` and `isLeExtendedAdvertisingSupported` must both be
  true for long-range discovery. Otherwise the device advertises and scans
  legacy-only, as in `OFF`.
- `leMaximumAdvertisingDataLength` must fit the 47-byte Coded advertisement.
- If the stack rejects an extended scan with `SCAN_FAILED_FEATURE_UNSUPPORTED`,
  the client latches legacy scanning until the mode is changed.
- If the Coded advertising set fails with `FEATURE_UNSUPPORTED` or
  `DATA_TOO_LARGE`, it isn't retried. Transient failures retry with backoff,
  up to 5 attempts. Legacy advertising is independent and is never affected.
- A `setPreferredPhy` request the remote refuses (because it has no Coded
  support, for example) leaves the link on its current PHY.

## Advertising

Every mode keeps the legacy connectable 1M advertisement: the service UUID,
plus the 8-byte peer ID in the scan response. iOS, older Android clients, and
any client without Coded support keep discovering through it.

Long-range modes add a second, independent advertising set:

- `legacyMode=false`, connectable, not scannable
- primary and secondary PHY: LE Coded
- payload: flags, the 128-bit mesh service UUID, and service data with the
  8-byte peer ID. Extended connectable advertising can't carry a scan
  response, so the peer ID goes in the advertisement itself.
- interval and requested TX power follow the power mode. The controller clamps
  TX power to the device's certified maximum, so the app can't exceed
  regulatory limits. Android doesn't let apps choose S=2 or S=8 coding for
  advertising; the controller decides.

In long-range modes, balanced power mode requests `ADVERTISE_TX_POWER_HIGH`
for the legacy set (stock is `MEDIUM`).

Each advertising set has its own random address. Scanners that hear the same
peer ID on two addresses keep only one in-flight client attempt per peer
(`BluetoothConnectionTracker.reservePeerConnectionAttempt`).

## Scanning and connection

- Extended scanning uses `setLegacy(false)` and `PHY_LE_ALL_SUPPORTED`, so it
  receives both legacy 1M and Coded advertisements.
- RSSI gating is PHY-aware. Coded results are accepted down to about receiver
  sensitivity (-110 dBm in foreground, -100/-90 in power-saving modes). Legacy
  results get a 5 dB relaxation from the stock thresholds. `OFF` keeps the
  stock thresholds.
- `connectGatt` initiates on the PHY the advertisement was heard on (Coded
  mask for Coded advertisements, 1M for legacy, both when unknown).
- After setup the client samples link RSSI every 5 s with `readRemoteRssi`.
  This is a local controller read and adds no airtime. The samples feed the
  diagnostics and the `ADAPTIVE` controller.

## Diagnostics

`BleLinkDiagnostics` records, and the debug sheet shows:

- controller capabilities
- legacy and Coded advertiser state, plus the TX power the controller selected
- scan mode, and counts of legacy and Coded advertisements heard
- per link: role, TX/RX PHY, RSSI, PHY discovered on, PHY requests and
  failures, link-level delivery (successful / total writes and notifications),
  and packets received
- a ring buffer of radio events: PHY changes, failures, fallbacks, and
  disconnect status codes

Events are also logged under the logcat tag `BleLongRange`. Keep logcat local;
it contains Bluetooth addresses.

## User-facing indicator

The Mesh row in the channel sheet (tap "mesh" in the chat header) reflects the
live state through `BleRangeIndicator`:

- Long range active: a "long range" badge next to the title, the subtitle
  "Bluetooth long range • est. ~40–200m", and a second line saying whether
  connections are on Coded PHY (all, some, or none yet).
- Off, or not supported by the phone: the standard subtitle with
  "~10–50m"; unsupported phones also get a "not supported" line.

The ranges are rough phone-to-phone estimates. Coded S=8 adds roughly 12 dB of
link budget, about 4× the distance in free space; walls, bodies and antenna
orientation dominate in practice. The second line describes connections rather
than counting devices, because one phone is reachable through several
addresses and links.

## Throughput trade-off

Coded S=8 carries 125 kbit/s, an eighth of 1M. Text messages, announcements,
and Noise handshakes aren't noticeably affected. Media and voice transfers
over a Coded link are proportionally slower. `ADAPTIVE` keeps short-range links
on 1M if that matters more than range.
