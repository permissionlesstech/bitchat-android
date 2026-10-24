# Relay selection vectors

`app/src/test/resources/contracts/relay-selection-v1/` contains synthetic CSV
files and literal expected URL lists. `cases.tsv` records each case's directory,
query latitude/longitude, requested count and ordered result (`|` separated;
`-` means an empty result).

For accepted directory rows, selection sorts by Haversine distance from the query
coordinate, ascending, then by canonical endpoint key for equal computed distances.
It returns up to `max(count, 0)` URLs. These coordinate-based vectors do not test
geohash decoding or establish identical floating-point ordering for arbitrary
coordinates.

Run the Android production parser and selection helper through the JVM suite:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.bitchat.android.nostr.RelaySelectionVectorTest'
```

With Python 3 and Swift available, check the same cases against a local iOS
source file:

```sh
python3 tools/relay-selection/check_ios.py <ios-checkout>/bitchat/Nostr/GeoRelayDirectory.swift
```

The script reads that file without modifying it or fetching anything. It extracts
its validation policy, entry type, parser, address validator, coordinate-based
selector and distance function into a temporary Swift wrapper. The selected
declarations are unchanged; unrelated application dependencies are excluded.
Source and extracted-declaration hashes identify the implementation checked.
Missing declarations, malformed or empty vector manifests, and compilation failures
stop the check. Source changes
require checking that extraction still covers the relevant dependencies.

Check the local runner’s input and extraction guards with:

```sh
python3 -m unittest discover -s tools/relay-selection -p 'test_*.py'
```

The fixtures cover identical-coordinate ties, reversed row order, lowercase
endpoint keys, default-port duplicates, a nondefault port, requested-count limits,
longitude-dependent order, latitude weighting and selection across the dateline. Both implementations
are compared with literal expectations, rather than generating one client's
expected output from the other. The Android test runs with the unit suite; the
Swift check is an explicit local command and requires the separate source checkout.

The CSVs use reserved example hosts and invented coordinates. They are small,
accepted directories; the Swift parser uses a one-entry minimum without a prior
snapshot. These are selection tests, not remote-download admission tests. They do
not require identical parser acceptance, exercise geohash decoding, or establish
network delivery or physical-device compatibility. Update expectations only after
explaining the intended selection-policy change and checking affected clients.
