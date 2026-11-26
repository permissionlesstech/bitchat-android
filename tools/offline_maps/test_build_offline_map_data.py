import contextlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_offline_map_data as builder


class OfflineMapDataBuilderTests(unittest.TestCase):
    def test_shelter_geojson_cleans_invalid_capacity_as_shelter_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            (source / "county.json").write_text(
                json.dumps(
                    {
                        "type": "FeatureCollection",
                        "features": [
                            {
                                "type": "Feature",
                                "properties": {
                                    "地址": "鹿港鎮中正路445號",
                                    "電腦編號": "255.0",
                                },
                                "geometry": {
                                    "type": "Point",
                                    "coordinates": [120.438029, 24.060203],
                                },
                            }
                        ],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            self.assertEqual(
                builder.build_shelters(source),
                [[24.060203, 120.438029, "鹿港鎮中正路445號"]],
            )

    def test_shelter_builder_deduplicates_identical_map_records(self):
        feature = {
            "type": "Feature",
            "properties": {"地址": "測試路1號"},
            "geometry": {"type": "Point", "coordinates": [121.5, 25.0]},
        }
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            (source / "county.json").write_text(
                json.dumps(
                    {
                        "type": "FeatureCollection",
                        "features": [feature, feature],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            warnings = io.StringIO()

            with contextlib.redirect_stderr(warnings):
                shelters = builder.build_shelters(source)

            self.assertEqual(shelters, [[25.0, 121.5, "測試路1號"]])
            self.assertIn("Skipped 1 duplicate shelter map records", warnings.getvalue())

    def test_shelter_geojson_uses_placemark_address_and_reports_empty_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            (source / "county.json").write_text(
                json.dumps(
                    {
                        "type": "FeatureCollection",
                        "features": [
                            {
                                "type": "Feature",
                                "properties": {
                                    "placemark_address": "花蓮市公園路53號",
                                    "unnamed (1)": "SMA03001",
                                },
                                "geometry": {
                                    "type": "Point",
                                    "coordinates": [121.614212, 23.978029],
                                },
                            },
                            {
                                "type": "Feature",
                                "properties": {},
                                "geometry": {
                                    "type": "Point",
                                    "coordinates": [0, 0],
                                },
                            },
                            {
                                "type": "Feature",
                                "properties": {"地址": "不合理座標測試"},
                                "geometry": {
                                    "type": "Point",
                                    "coordinates": [2121.192218, 25.01545271],
                                },
                            },
                        ],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            warnings = io.StringIO()

            with contextlib.redirect_stderr(warnings):
                shelters = builder.build_shelters(source)

            self.assertEqual(
                shelters,
                [[23.978029, 121.614212, "花蓮市公園路53號"]],
            )
            self.assertIn("Skipped 1 shelter records with (0, 0)", warnings.getvalue())
            self.assertIn(
                "Skipped 1 shelter records with out-of-range coordinates",
                warnings.getvalue(),
            )

    def test_bus_zip_generates_map_records_from_tdx_stop_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            csv_data = (
                "StopUID,StopName,StopAddres,Lon,Lat\n"
                "TPE123,測試站,測試路1號,121.5,25.0\n"
            )
            with zipfile.ZipFile(source / "B1A-test.zip", "w") as archive:
                archive.writestr("stops.csv", csv_data)

            self.assertEqual(
                builder.build_bus_stops(source),
                [["bus:TPE123", "busStop", 25.0, 121.5, "測試站", "測試路1號"]],
            )

    def test_police_twd97_coordinates_transform_to_taiwan_wgs84(self):
        transformer = builder.Transformer.from_crs(
            builder.CRS.from_epsg(3826),
            builder.CRS.from_epsg(4326),
            always_xy=True,
        )
        longitude, latitude = transformer.transform(301442.6468, 2770733.688)

        self.assertAlmostEqual(longitude, 121.505, delta=0.02)
        self.assertAlmostEqual(latitude, 25.031, delta=0.02)

    def test_fire_station_loader_skips_and_reports_zero_coordinates(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            source_file = source / "救援單位點位.csv"
            source_file.write_text(
                "消防隊名稱,地址,X座標_TWD97TM121,Y座標_TWD97TM121\n"
                "測試消防隊,測試路1號,121.5,25.0\n"
                "未定位消防隊,測試路2號,0,0\n",
                encoding="utf-8",
                newline="",
            )
            warnings = io.StringIO()

            with contextlib.redirect_stderr(warnings):
                facilities = builder._load_fire_stations(source)

            self.assertEqual(len(facilities), 1)
            self.assertTrue(facilities[0][0].startswith("fire:"))
            self.assertIn("Skipped 1 fire-station records", warnings.getvalue())


if __name__ == "__main__":
    unittest.main()
