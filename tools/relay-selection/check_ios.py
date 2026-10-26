#!/usr/bin/env python3
"""Check shared selection vectors using methods extracted from a supplied iOS source file."""
import argparse
import hashlib
import math
from pathlib import Path
import re
import subprocess
import tempfile


def declaration(source, marker):
    # Match the declaration's own closing indentation, not nested closure braces.
    lines = source.splitlines(keepends=True)
    matches = [i for i, line in enumerate(lines) if line.strip().startswith(marker)]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one declaration: {marker}")
    start = matches[0]
    indent = re.match(r" *", lines[start]).group()
    for end in range(start + 1, len(lines)):
        if lines[end].rstrip() == indent + "}":
            return "".join(lines[start:end + 1])
    raise ValueError(f"Missing closing brace: {marker}")


def validate_cases(text):
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines or lines[0] != "id\tcsv\tlatitude\tlongitude\tcount\texpected":
        raise ValueError("Invalid vector header")
    if len(lines) == 1:
        raise ValueError("No selection vectors")
    ids = set()
    for line in lines[1:]:
        f = line.split("\t")
        if len(f) != 6:
            raise ValueError("Invalid vector fields")
        if not f[0].strip() or f[0] in ids:
            raise ValueError("Invalid or duplicate vector ID")
        ids.add(f[0])
        if not re.fullmatch(r"[a-z0-9-]+\.csv", f[1]):
            raise ValueError("Invalid vector filename")
        lat, lon, count = float(f[2]), float(f[3]), int(f[4])
        if not (math.isfinite(lat) and -90 <= lat <= 90 and math.isfinite(lon) and -180 <= lon <= 180):
            raise ValueError("Invalid vector coordinates")
        if not -(2**31) <= count < 2**31:
            raise ValueError("Invalid vector count")
        if f[5] != "-" and not all(x.startswith("wss://") and len(x) > 6 for x in f[5].split("|")):
            raise ValueError("Invalid vector expectations")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ios_source", type=Path, help="Read-only GeoRelayDirectory.swift")
    parser.add_argument("--fixtures", type=Path, default=Path(__file__).resolve().parents[2] /
                        "app/src/test/resources/contracts/relay-selection-v1")
    args = parser.parse_args()
    validate_cases((args.fixtures / "cases.tsv").read_text())
    source_bytes = args.ios_source.read_bytes()
    source = source_bytes.decode("utf-8")
    parts = [declaration(source, marker) for marker in (
        "struct GeoRelayDirectoryValidationPolicy:", "struct Entry:",
        "nonisolated static func validatedEntries(",
        "nonisolated private static func validatedDirectoryAddress(",
        "func closestRelays(toLat", "private func haversineKm(")]
    # Keep the selected declarations verbatim. Supply only the containing type and entries.
    swift = "import Foundation\n" + parts[0] + "\nstruct VectorDirectory {\n" + parts[1]
    swift += "var entries: [Entry]\n" + "\n".join(parts[2:5]) + "\n}\n" + parts[5]
    swift += r'''
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let rows = try String(contentsOf: root.appendingPathComponent("cases.tsv"), encoding: .utf8)
var failures = 0
var count = 0
for row in rows.split(separator: "\n").dropFirst() {
    let f = row.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
    guard f.count == 6, let lat = Double(f[2]), let lon = Double(f[3]), let n = Int(f[4]) else {
        fatalError("Malformed vector")
    }
    let data = try Data(contentsOf: root.appendingPathComponent(f[1]))
    guard let entries = VectorDirectory.validatedEntries(from: data, policy: .live, minimumEntries: 1) else {
        fatalError("Vector directory rejected: \(f[0])")
    }
    let expected = f[5] == "-" ? [] : f[5].split(separator: "|").map(String.init)
    let actual = VectorDirectory(entries: entries).closestRelays(toLat: lat, lon: lon, count: n)
    count += 1
    if actual != expected {
        failures += 1
        print("FAIL \(f[0]): expected \(expected), got \(actual)")
    }
}
print("\(count) selection vectors, \(failures) failures")
exit(failures == 0 ? 0 : 1)
'''
    print("iOS source SHA256:", hashlib.sha256(source_bytes).hexdigest(), flush=True)
    print("Extracted declarations SHA256:", hashlib.sha256("".join(parts).encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="relay-vectors-") as tmp:
        path = Path(tmp)
        (path / "main.swift").write_text(swift)
        subprocess.run(["swiftc", "-module-cache-path", str(path / "modules"), str(path / "main.swift"), "-o", str(path / "check")], check=True)
        result = subprocess.run([str(path / "check"), str(args.fixtures.resolve())])
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
