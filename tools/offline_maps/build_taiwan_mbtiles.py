#!/usr/bin/env python3
"""Build a Taiwan-and-islands MBTiles package from an Esri exploded cache."""

from __future__ import annotations

import argparse
import math
import re
import sqlite3
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path
from typing import Iterator


REGION_BOUNDS = (
    (120.0, 21.7, 122.2, 25.5),  # Taiwan, Green Island, and Orchid Island
    (119.1, 23.1, 119.9, 23.9),  # Penghu
    (117.8, 24.2, 118.7, 24.7),  # Kinmen
    (119.8, 25.8, 120.6, 26.8),  # Matsu
    (116.3, 20.5, 117.3, 21.4),  # Dongsha
    (114.2, 9.5, 114.6, 10.5),  # Taiping Island
)
TILE_PATTERN = re.compile(r"R([0-9a-f]{8})[\\/]+C([0-9a-f]{8})\.jpg$", re.I)
SOF_MARKERS = {
    0xC0,
    0xC1,
    0xC2,
    0xC3,
    0xC5,
    0xC6,
    0xC7,
    0xC9,
    0xCA,
    0xCB,
    0xCD,
    0xCE,
    0xCF,
}


def _tile_latitude(tile_y: int, zoom: int) -> float:
    n = 1 << zoom
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * tile_y / n))))


def tile_intersects_region(
    tile_x: int, tile_y: int, zoom: int, bounds: tuple[float, float, float, float]
) -> bool:
    west, south, east, north = bounds
    n = 1 << zoom
    tile_west = tile_x / n * 360 - 180
    tile_east = (tile_x + 1) / n * 360 - 180
    tile_north = _tile_latitude(tile_y, zoom)
    tile_south = _tile_latitude(tile_y + 1, zoom)
    return (
        tile_west < east
        and tile_east > west
        and tile_south < north
        and tile_north > south
    )


def _tile_range(west: float, south: float, east: float, north: float, zoom: int):
    n = 1 << zoom

    def x_for(lon: float) -> int:
        return max(0, min(n - 1, math.floor((lon + 180) / 360 * n)))

    def y_for(lat: float) -> int:
        lat = max(-85.05112878, min(85.05112878, lat))
        mercator = math.asinh(math.tan(math.radians(lat)))
        return max(
            0,
            min(n - 1, math.floor((1 - mercator / math.pi) / 2 * n)),
        )

    return range(y_for(north), y_for(south) + 1), range(
        x_for(west), x_for(east) + 1
    )


def selected_tiles(
    root: Path,
) -> Iterator[tuple[int, int, int, Path]]:
    levels = sorted(
        (path for path in root.glob("L[0-9][0-9]") if path.is_dir()),
        key=lambda path: int(path.name[1:]),
    )
    for level_dir in levels:
        zoom = int(level_dir.name[1:])
        if zoom > 30:
            raise ValueError(f"Unsupported zoom level: {zoom}")
        candidate_indices: set[tuple[int, int]] = set()
        for bounds in REGION_BOUNDS:
            rows, columns = _tile_range(*bounds, zoom)
            for tile_y in rows:
                for tile_x in columns:
                    if tile_intersects_region(tile_x, tile_y, zoom, bounds):
                        candidate_indices.add((tile_y, tile_x))

        for tile_y, tile_x in sorted(candidate_indices):
            tile_path = (
                level_dir
                / f"R{tile_y:08x}"
                / f"C{tile_x:08x}.jpg"
            )
            if tile_path.is_file():
                yield zoom, tile_x, tile_y, tile_path


def jpeg_dimensions(data: bytes) -> tuple[int, int] | None:
    if not data.startswith(b"\xff\xd8"):
        return None
    index = 2
    while index < len(data):
        while index < len(data) and data[index] != 0xFF:
            index += 1
        while index < len(data) and data[index] == 0xFF:
            index += 1
        if index >= len(data):
            break
        marker = data[index]
        index += 1
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            continue
        if index + 2 > len(data):
            break
        segment_length = int.from_bytes(data[index : index + 2], "big")
        if segment_length < 2 or index + segment_length > len(data):
            break
        if marker in SOF_MARKERS and segment_length >= 7:
            height = int.from_bytes(data[index + 3 : index + 5], "big")
            width = int.from_bytes(data[index + 5 : index + 7], "big")
            return width, height
        index += segment_length
    return None


def validate_tile(path: Path, data: bytes) -> None:
    dimensions = jpeg_dimensions(data)
    if dimensions != (256, 256):
        raise ValueError(f"Expected a 256x256 JPEG tile: {path} (got {dimensions})")


def configured_tile_format(root: Path) -> str | None:
    config_path = root / "conf.xml"
    if not config_path.is_file():
        return None
    try:
        config = ET.parse(config_path)
    except ET.ParseError as error:
        print(f"warning: unable to parse {config_path}: {error}", file=sys.stderr)
        return None
    for element in config.iter():
        if element.tag.rsplit("}", 1)[-1] == "CacheTileFormat":
            return element.text
    return None


def _create_database(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA page_size = 4096")
    connection.execute("PRAGMA journal_mode = OFF")
    connection.execute("PRAGMA synchronous = OFF")
    connection.execute(
        "CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)"
    )
    connection.execute(
        """
        CREATE TABLE tiles (
            zoom_level INTEGER NOT NULL,
            tile_column INTEGER NOT NULL,
            tile_row INTEGER NOT NULL,
            tile_data BLOB NOT NULL,
            PRIMARY KEY (zoom_level, tile_column, tile_row)
        )
        """
    )
    metadata = {
        "name": "Taiwan Offline Map",
        "type": "baselayer",
        "version": "1",
        "description": "EPSG:3857 Esri raster cache for Taiwan and outlying islands.",
        "format": "jpg",
        "bounds": "114.2,9.5,122.2,26.8",
        "center": "121.5,25.0,15",
        "minzoom": "0",
        "maxzoom": "15",
    }
    connection.executemany(
        "INSERT INTO metadata (name, value) VALUES (?, ?)", metadata.items()
    )
    return connection


def build(root: Path, output: Path, dry_run: bool) -> None:
    if not root.is_dir():
        raise FileNotFoundError(f"Tile cache directory does not exist: {root}")
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite existing file: {output}")
    declared_format = configured_tile_format(root)
    if declared_format and declared_format.casefold() not in {"jpg", "jpeg"}:
        print(
            f"warning: conf.xml declares {declared_format}, but source JPEG tiles "
            "will be validated and stored as jpg",
            file=sys.stderr,
        )

    counts: dict[int, int] = defaultdict(int)
    input_bytes = 0
    tiles = list(selected_tiles(root))
    if not tiles:
        raise ValueError(f"No Taiwan tiles found in {root}")

    connection: sqlite3.Connection | None = None
    temporary_path = output.with_name(output.name + ".tmp")
    if not dry_run:
        if temporary_path.exists():
            raise FileExistsError(f"Temporary output already exists: {temporary_path}")
        output.parent.mkdir(parents=True, exist_ok=True)
        connection = _create_database(temporary_path)

    try:
        for index, (zoom, tile_x, tile_y, path) in enumerate(tiles, start=1):
            data = path.read_bytes()
            validate_tile(path, data)
            counts[zoom] += 1
            input_bytes += len(data)
            if connection is not None:
                tms_row = (1 << zoom) - 1 - tile_y
                connection.execute(
                    """
                    INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data)
                    VALUES (?, ?, ?, ?)
                    """,
                    (zoom, tile_x, tms_row, data),
                )
            if index % 5000 == 0:
                print(f"Validated {index:,}/{len(tiles):,} tiles")

        if connection is not None:
            connection.commit()
            result = connection.execute("PRAGMA integrity_check").fetchone()
            if result != ("ok",):
                raise sqlite3.DatabaseError(f"MBTiles integrity check failed: {result}")
            connection.close()
            connection = None
            temporary_path.replace(output)
    except Exception:
        if connection is not None:
            connection.close()
        if temporary_path.exists():
            temporary_path.unlink()
        raise

    for zoom, count in sorted(counts.items()):
        print(f"L{zoom:02d}: {count:,} tiles")
    print(
        f"{'Would package' if dry_run else 'Packaged'} {len(tiles):,} tiles "
        f"({input_bytes:,} source bytes; {input_bytes / (1024 * 1024):.1f} MiB)"
    )
    if not dry_run:
        print(f"Output: {output} ({output.stat().st_size:,} bytes)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tile-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="validate and count selected tiles without writing a database",
    )
    arguments = parser.parse_args()
    try:
        build(arguments.tile_root, arguments.output, arguments.dry_run)
    except (OSError, ValueError, sqlite3.Error) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
