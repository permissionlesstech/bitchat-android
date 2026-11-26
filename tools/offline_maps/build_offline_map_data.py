#!/usr/bin/env python3
"""Build the offline shelter, facility, and bus-stop JSON assets."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
import struct
import sys
import zipfile
from pathlib import Path
from typing import Any

from pyproj import CRS, Transformer


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_DIR = ROOT / "flutter_ui" / "assets" / "data"
COORDINATE_PATTERN = re.compile(r"\s*\(255\.0\)$")


def _decode_csv(data: bytes, source: str) -> list[dict[str, str]]:
    for encoding in ("utf-8-sig", "cp950", "big5"):
        try:
            text = data.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise UnicodeError(f"Unable to decode CSV source: {source}")
    return list(csv.DictReader(io.StringIO(text)))


def _read_csv(path: Path) -> list[dict[str, str]]:
    return _decode_csv(path.read_bytes(), str(path))


def _number(value: str | None, field: str, source: str) -> float:
    if value is None or not value.strip():
        raise ValueError(f"Missing {field} in {source}")
    try:
        return float(value.strip().replace(",", ""))
    except ValueError as error:
        raise ValueError(f"Invalid {field} in {source}: {value!r}") from error


def _validate_wgs84(longitude: float, latitude: float, source: str) -> None:
    if not (-180 <= longitude <= 180 and -90 <= latitude <= 90):
        raise ValueError(
            f"Coordinates out of range in {source}: {longitude}, {latitude}"
        )
    if longitude == 0 and latitude == 0:
        raise ValueError(f"Empty coordinates in {source}")


def _stable_id(kind: str, *parts: str) -> str:
    value = "\x1f".join((kind, *parts))
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]
    return f"{kind}:{digest}"


def _clean_shelter_name(name: str) -> str:
    return COORDINATE_PATTERN.sub("", name.strip())


def _first_property(properties: dict[str, Any], *names: str) -> str:
    for name in names:
        value = str(properties.get(name, "")).strip()
        if value:
            return value
    return ""


def build_shelters(source_dir: Path) -> list[list[Any]]:
    if not source_dir.is_dir():
        raise FileNotFoundError(f"Shelter GeoJSON directory not found: {source_dir}")

    shelters: list[list[Any]] = []
    shelter_records: set[tuple[float, float, str]] = set()
    skipped_empty_coordinates = 0
    skipped_out_of_range_coordinates = 0
    skipped_duplicates = 0
    files = sorted(source_dir.glob("*.json"))
    if not files:
        raise FileNotFoundError(f"No county GeoJSON files found in {source_dir}")

    for path in files:
        document = json.loads(path.read_text(encoding="utf-8"))
        features = document.get("features")
        if document.get("type") != "FeatureCollection" or not isinstance(
            features, list
        ):
            raise ValueError(f"Expected a GeoJSON FeatureCollection: {path}")

        for index, feature in enumerate(features):
            source = f"{path} feature {index}"
            properties = feature.get("properties") or {}
            geometry = feature.get("geometry") or {}
            coordinates = geometry.get("coordinates")
            if geometry.get("type") != "Point" or not isinstance(
                coordinates, list
            ) or len(coordinates) < 2:
                raise ValueError(f"Expected a point geometry in {source}")

            longitude = _number(str(coordinates[0]), "longitude", source)
            latitude = _number(str(coordinates[1]), "latitude", source)
            if longitude == 0 and latitude == 0:
                skipped_empty_coordinates += 1
                continue
            if not (-180 <= longitude <= 180 and -90 <= latitude <= 90):
                skipped_out_of_range_coordinates += 1
                continue

            address = _first_property(
                properties,
                "地址",
                "placemark_address",
                "座落",
                "建築物名稱",
            )
            if not address:
                raise ValueError(f"Missing shelter address in {source}")
            _validate_wgs84(longitude, latitude, source)
            code = _first_property(properties, "電腦編號", "編號")
            name = f"{address} ({code})" if code else address
            record = (
                round(latitude, 6),
                round(longitude, 6),
                _clean_shelter_name(name),
            )
            if record in shelter_records:
                skipped_duplicates += 1
                continue
            shelter_records.add(record)
            shelters.append(list(record))

    if skipped_empty_coordinates:
        print(
            "Skipped "
            f"{skipped_empty_coordinates} shelter records with (0, 0) coordinates",
            file=sys.stderr,
        )
    if skipped_out_of_range_coordinates:
        print(
            "Skipped "
            f"{skipped_out_of_range_coordinates} shelter records with out-of-range "
            "coordinates",
            file=sys.stderr,
        )
    if skipped_duplicates:
        print(
            f"Skipped {skipped_duplicates} duplicate shelter map records",
            file=sys.stderr,
        )

    return shelters


def _facility(
    kind: str,
    name: str,
    address: str,
    latitude: float,
    longitude: float,
) -> list[Any]:
    name = name.strip()
    if not name:
        raise ValueError(f"Missing {kind} name")
    _validate_wgs84(longitude, latitude, name)
    id_prefix = {"fireStation": "fire", "policeStation": "police"}.get(
        kind, kind
    )
    return [
        _stable_id(id_prefix, name, address.strip()),
        kind,
        round(latitude, 6),
        round(longitude, 6),
        name,
        address.strip(),
    ]


def _load_police_stations(source_dir: Path) -> list[list[Any]]:
    files = sorted(source_dir.rglob("PoliceAddress1_*.csv"))
    if not files:
        raise FileNotFoundError(
            f"No PoliceAddress1_*.csv source files found in {source_dir}"
        )

    transformer = Transformer.from_crs(
        CRS.from_epsg(3826), CRS.from_epsg(4326), always_xy=True
    )
    facilities: list[list[Any]] = []
    for path in files:
        for index, row in enumerate(_read_csv(path), start=2):
            source = f"{path}:{index}"
            name = row.get("中文單位名稱", "").strip()
            address = row.get("地址", "").strip()
            x = _number(row.get("POINT_X"), "POINT_X", source)
            y = _number(row.get("POINT_Y"), "POINT_Y", source)
            longitude, latitude = transformer.transform(x, y)
            facilities.append(
                _facility("policeStation", name, address, latitude, longitude)
            )
    return facilities


def _load_fire_stations(source_dir: Path) -> list[list[Any]]:
    files = sorted(source_dir.rglob("救援單位點位.csv"))
    if not files:
        raise FileNotFoundError(
            f"No 救援單位點位.csv source files found in {source_dir}"
        )

    facilities: list[list[Any]] = []
    skipped_empty_coordinates = 0
    skipped_out_of_range_coordinates = 0
    for path in files:
        for index, row in enumerate(_read_csv(path), start=2):
            source = f"{path}:{index}"
            longitude = _number(
                row.get("X座標_TWD97TM121"), "X座標_TWD97TM121", source
            )
            latitude = _number(
                row.get("Y座標_TWD97TM121"), "Y座標_TWD97TM121", source
            )
            if longitude == 0 and latitude == 0:
                skipped_empty_coordinates += 1
                continue
            if not (-180 <= longitude <= 180 and -90 <= latitude <= 90):
                skipped_out_of_range_coordinates += 1
                continue
            facilities.append(
                _facility(
                    "fireStation",
                    row.get("消防隊名稱", ""),
                    row.get("地址", ""),
                    latitude,
                    longitude,
                )
            )
    if skipped_empty_coordinates:
        print(
            "Skipped "
            f"{skipped_empty_coordinates} fire-station records with (0, 0) coordinates",
            file=sys.stderr,
        )
    if skipped_out_of_range_coordinates:
        print(
            "Skipped "
            f"{skipped_out_of_range_coordinates} fire-station records with "
            "out-of-range coordinates",
            file=sys.stderr,
        )
    return facilities


def _read_dbf(data: bytes, encoding: str, source: str) -> list[dict[str, str]]:
    if len(data) < 33:
        raise ValueError(f"Invalid DBF header: {source}")

    record_count = int.from_bytes(data[4:8], "little")
    header_length = int.from_bytes(data[8:10], "little")
    record_length = int.from_bytes(data[10:12], "little")
    fields: list[tuple[str, int]] = []
    offset = 32
    while offset < header_length and data[offset] != 0x0D:
        descriptor = data[offset : offset + 32]
        if len(descriptor) != 32:
            raise ValueError(f"Invalid DBF field descriptor: {source}")
        field_name = descriptor[:11].split(b"\0", 1)[0].decode("ascii")
        fields.append((field_name, descriptor[16]))
        offset += 32

    expected_length = header_length + record_count * record_length
    if len(data) < expected_length:
        raise ValueError(f"Truncated DBF records: {source}")

    rows: list[dict[str, str]] = []
    for record_index in range(record_count):
        start = header_length + record_index * record_length
        record = data[start : start + record_length]
        if record[:1] == b"*":
            continue
        cursor = 1
        row: dict[str, str] = {}
        for field_name, width in fields:
            raw = record[cursor : cursor + width].strip(b" \0")
            cursor += width
            row[field_name] = raw.decode(encoding, errors="strict").strip()
        rows.append(row)
    return rows


def _load_rail_stations(source_dir: Path) -> list[list[Any]]:
    files = sorted(source_dir.glob("MARK_臺鐵車站_*.zip"))
    if not files:
        raise FileNotFoundError(
            f"No MARK_臺鐵車站_*.zip source file found in {source_dir}"
        )

    facilities: list[list[Any]] = []
    for path in files:
        with zipfile.ZipFile(path) as archive:
            members = {Path(name).suffix.lower(): name for name in archive.namelist()}
            if not {".shp", ".dbf", ".prj"}.issubset(members):
                raise ValueError(f"Station archive is missing SHP/DBF/PRJ: {path}")

            dbf_name = members[".dbf"]
            cpg_name = members.get(".cpg")
            encoding = (
                archive.read(cpg_name).decode("ascii").strip()
                if cpg_name
                else "cp950"
            )
            attributes = _read_dbf(archive.read(dbf_name), encoding, dbf_name)
            prj = archive.read(members[".prj"]).decode("utf-8")
            transformer = Transformer.from_crs(
                CRS.from_wkt(prj), CRS.from_epsg(4326), always_xy=True
            )
            shape_data = archive.read(members[".shp"])
            if len(shape_data) < 100:
                raise ValueError(f"Invalid station SHP header: {path}")

            shape_type = int.from_bytes(shape_data[32:36], "little")
            if shape_type != 1:
                raise ValueError(f"Expected point geometries in {path}")

            geometries: list[tuple[float, float] | None] = []
            offset = 100
            while offset < len(shape_data):
                if offset + 8 > len(shape_data):
                    raise ValueError(f"Truncated station SHP record: {path}")
                content_length = (
                    int.from_bytes(shape_data[offset + 4 : offset + 8], "big") * 2
                )
                start = offset + 8
                end = start + content_length
                if end > len(shape_data) or content_length < 4:
                    raise ValueError(f"Invalid station SHP record length: {path}")
                record_type = int.from_bytes(shape_data[start : start + 4], "little")
                if record_type == 0:
                    geometries.append(None)
                elif record_type == 1 and content_length >= 20:
                    geometries.append(struct.unpack_from("<dd", shape_data, start + 4))
                else:
                    raise ValueError(f"Unexpected station geometry in {path}")
                offset = end

            if len(geometries) != len(attributes):
                raise ValueError(
                    f"Station SHP/DBF row count mismatch in {path}: "
                    f"{len(geometries)} != {len(attributes)}"
                )

            for index, (geometry, row) in enumerate(
                zip(geometries, attributes, strict=True), start=1
            ):
                if geometry is None:
                    continue
                longitude, latitude = transformer.transform(*geometry)
                name = row.get("MARKNAME1") or row.get("MARKNAME2", "")
                address = row.get("ADDRESS", "")
                mark_id = row.get("MARKID", "").strip()
                facility = _facility(
                    "station", name, address, latitude, longitude
                )
                if mark_id:
                    facility[0] = f"station:{mark_id}"
                facilities.append(facility)
    return facilities


def build_public_facilities(source_dir: Path, bus_source_dir: Path) -> list[list[Any]]:
    facilities = [
        *_load_fire_stations(source_dir),
        *_load_police_stations(source_dir),
        *_load_rail_stations(bus_source_dir),
    ]
    return facilities


def build_bus_stops(source_dir: Path) -> list[list[Any]]:
    if not source_dir.is_dir():
        raise FileNotFoundError(f"Bus source directory not found: {source_dir}")

    files = sorted(source_dir.glob("B*.zip"))
    if not files:
        raise FileNotFoundError(f"No TDX bus-stop ZIP files found in {source_dir}")

    stops_by_id: dict[str, list[Any]] = {}
    for path in files:
        with zipfile.ZipFile(path) as archive:
            csv_names = sorted(
                name for name in archive.namelist() if name.lower().endswith(".csv")
            )
            if not csv_names:
                raise ValueError(f"Bus-stop archive contains no CSV: {path}")
            for csv_name in csv_names:
                for index, row in enumerate(
                    _decode_csv(archive.read(csv_name), f"{path}!{csv_name}"),
                    start=2,
                ):
                    source = f"{path}!{csv_name}:{index}"
                    stop_id = row.get("StopUID", "").strip()
                    name = row.get("StopName", "").strip()
                    address = row.get("StopAddres", "").strip()
                    if not stop_id:
                        raise ValueError(f"Missing StopUID in {source}")
                    longitude = _number(row.get("Lon"), "Lon", source)
                    latitude = _number(row.get("Lat"), "Lat", source)
                    _validate_wgs84(longitude, latitude, source)
                    record = [
                        f"bus:{stop_id}",
                        "busStop",
                        round(latitude, 6),
                        round(longitude, 6),
                        name,
                        address,
                    ]
                    existing = stops_by_id.get(stop_id)
                    if existing is not None and existing != record:
                        raise ValueError(f"Conflicting duplicate StopUID in {source}")
                    stops_by_id[stop_id] = record

    return list(stops_by_id.values())


def _write_assets(output_dir: Path, assets: dict[str, list[list[Any]]]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    temporary_paths: list[Path] = []
    try:
        for filename, records in assets.items():
            path = output_dir / filename
            temporary = path.with_name(f".{path.name}.tmp")
            if temporary.exists():
                raise FileExistsError(f"Temporary output already exists: {temporary}")
            temporary.write_text(
                json.dumps(records, ensure_ascii=False, separators=(",", ":")) + "\n",
                encoding="utf-8",
                newline="\n",
            )
            temporary_paths.append(temporary)
        for temporary, filename in zip(temporary_paths, assets, strict=True):
            temporary.replace(output_dir / filename)
    finally:
        for temporary in temporary_paths:
            if temporary.exists():
                temporary.unlink()


def build(
    shelter_json_dir: Path,
    emergency_dir: Path,
    bus_dir: Path,
    output_dir: Path,
    *,
    check_only: bool,
    overwrite: bool,
) -> None:
    assets = {
        "shelters_tw.json": build_shelters(shelter_json_dir),
        "public_facilities_tw.json": build_public_facilities(emergency_dir, bus_dir),
        "bus_stops_tw.json": build_bus_stops(bus_dir),
    }

    targets = [output_dir / name for name in assets]
    existing = [path for path in targets if path.exists()]
    if existing and not overwrite and not check_only:
        raise FileExistsError(
            "Refusing to overwrite existing files; pass --overwrite: "
            + ", ".join(str(path) for path in existing)
        )

    for filename, records in assets.items():
        print(f"{filename}: {len(records):,} records")
    print("hospital markers: 0 (source ODS has no coordinates)")
    if not check_only:
        _write_assets(output_dir, assets)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--shelter-json-dir",
        type=Path,
        required=True,
        help="Directory containing county GeoJSON files from kiang/npa.gov.tw",
    )
    parser.add_argument(
        "--emergency-dir",
        type=Path,
        required=True,
        help="Directory containing the police and fire-service CSV files",
    )
    parser.add_argument(
        "--bus-dir",
        type=Path,
        required=True,
        help="Directory containing TDX bus-stop ZIPs and the railway-station ZIP",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"Output directory (default: {DEFAULT_OUTPUT_DIR})",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Build and validate records without writing output files",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Replace the three JSON outputs after all inputs validate",
    )
    args = parser.parse_args()

    try:
        build(
            args.shelter_json_dir,
            args.emergency_dir,
            args.bus_dir,
            args.output_dir,
            check_only=args.check,
            overwrite=args.overwrite,
        )
    except (OSError, UnicodeError, ValueError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
