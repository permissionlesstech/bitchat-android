"""Corpus ingestion contracts, using invented non-identifying records only."""

from collections import Counter
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

from tools.spam_filter.conversational_corpora import (
    BottomSamples, load_catalog, matches_script, prepare, records,
)


class ConversationalCorpusTest(unittest.TestCase):
    def test_provider_codes_cover_all_families_with_explicit_regional_script_choices(self):
        catalog = load_catalog()
        self.assertEqual(32, len(catalog["languages"]))
        self.assertEqual(["Arab"], catalog["languages"]["pa"]["scripts"])
        self.assertEqual("pnb", catalog["languages"]["pa"]["tatoebaCode"])
        self.assertIn("npi", catalog["languages"]["ne"]["providerCodes"])
        self.assertIn("plt", catalog["languages"]["mg"]["providerCodes"])
        self.assertIn("zsm", catalog["languages"]["ms"]["providerCodes"])
        self.assertEqual(["Hans", "Hant"], catalog["languages"]["zh"]["scripts"])

    def test_shahmukhi_accepts_native_text_and_rejects_gurmukhi_even_in_mixed_text(self):
        spec = {"scripts": ["Arab"]}
        self.assertTrue(matches_script("ایہہ اک فرضی نمونہ اے۔", "pa", spec))
        self.assertFalse(matches_script("ਇਹ ਇੱਕ ਕਲਪਨਾਤਮਕ ਉਦਾਹਰਨ ਹੈ।", "pa", spec))
        self.assertFalse(matches_script("ایہہ اک فرضی نمونہ اے۔ ਇਹ", "pa", spec))
        self.assertTrue(matches_script("48% + 7 = 55 👍", "pa", spec))

    def test_sampling_is_independent_of_export_order_and_removes_case_whitespace_duplicates(self):
        texts = [f"The fictional robot inspects sample {index}." for index in range(40)]
        samples = [BottomSamples(7), BottomSamples(7)]
        for reservoir, rows in zip(samples, [texts, list(reversed(texts))]):
            for text in rows:
                reservoir.add(text, "synthetic-group", None)
                reservoir.add(text.upper(), "synthetic-group", None)
        self.assertEqual({row[1] for row in samples[0].heap}, {row[1] for row in samples[1].heap})
        self.assertEqual(7, len(samples[0].heap))

    def massive_fixture(self, directory):
        text = "Un robot inventato dice: «È già pronto?»\n48% + 7 — sì! 👍"
        rows = [
            {"id": 1, "partition": "train", "utt": text, "worker_id": "synthetic-worker", "annot_utt": "synthetic annotation"},
            {"id": 2, "partition": "test", "utt": "An independent fictional held-out example."},
        ]
        body = b"".join((json.dumps(row, ensure_ascii=False) + "\n").encode() for row in rows)
        path = directory / "raw/massive.tar.gz"
        path.parent.mkdir()
        with tarfile.open(path, "w:gz") as archive:
            entry = tarfile.TarInfo("data/it-IT.jsonl")
            entry.size = len(body)
            archive.addfile(entry, io.BytesIO(body))
        source = {"id": "massive", "kind": "authored-assistant-utterances", "homepage": "https://example.invalid/corpus",
                  "license": "CC-BY-4.0", "licenseUrl": "https://example.invalid/license", "revision": "synthetic",
                  "artifacts": [{"localFile": "raw/massive.tar.gz", "bytes": path.stat().st_size,
                                 "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}]}
        catalog = {"languages": {"it": {"scripts": ["Latn"], "providerCodes": ["ita"], "massiveLocales": ["it-IT"]}}, "sources": [source]}
        return source, catalog, text

    def test_projection_keeps_only_training_text_and_omits_worker_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            source, catalog, text = self.massive_fixture(path)
            counts = Counter()
            extracted = list(records(source, path, catalog, counts))
            self.assertEqual([("it", text, "1", None)], extracted)
            self.assertEqual(1, counts["upstreamHeldOutSkipped"])

    def test_preparation_preserves_accents_punctuation_digits_emoji_and_line_breaks(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            _, catalog, text = self.massive_fixture(path)
            summary = prepare(path, catalog, limit=10)
            self.assertEqual(1, summary["languages"]["it"]["records"])
            with gzip.open(path / "prepared/it.jsonl.gz", "rt", encoding="utf-8") as stream:
                row = json.loads(stream.readline())
                self.assertEqual("", stream.readline())
            self.assertEqual(text, row["text"])
            self.assertEqual({"language", "source", "group", "text"}, set(row))
            self.assertNotIn("synthetic-worker", json.dumps(row))
            repeated = prepare(path, catalog, limit=10)
            self.assertEqual(summary["languages"]["it"]["sha256"], repeated["languages"]["it"]["sha256"])

    def test_changed_raw_snapshot_is_rejected_before_parsing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            source, catalog, _ = self.massive_fixture(path)
            source["artifacts"][0]["sha256"] = "0" * 64
            with self.assertRaises(ValueError):
                list(records(source, path, catalog, Counter()))

    def test_parallel_directions_keep_the_correct_script_without_counting_the_pair_twice(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "raw").mkdir()
            text = "ایہہ اک فرضی سوال اے۔"
            source = {"id": "smol-pa", "kind": "professionally-translated-sentences",
                      "homepage": "https://example.invalid/corpus", "license": "CC-BY-4.0",
                      "licenseUrl": "https://example.invalid/license", "artifacts": []}
            for index, row in enumerate([
                {"id": "synthetic-pair", "sl": "en", "tl": "pa-Arab", "src": "An invented question.", "trg": text},
                {"id": "synthetic-pair", "sl": "pa-Arab", "tl": "en", "src": text, "trg": "An invented question."},
            ]):
                file = path / "raw" / f"parallel-{index}.jsonl"
                file.write_text(json.dumps(row, ensure_ascii=False) + "\n")
                source["artifacts"].append({"localFile": "raw/" + file.name, "bytes": file.stat().st_size,
                                             "sha256": hashlib.sha256(file.read_bytes()).hexdigest()})
            catalog = {"languages": {"pa": {"scripts": ["Arab"], "providerCodes": ["pnb"], "massiveLocales": []}}, "sources": [source]}
            extracted = list(records(source, path, catalog, Counter()))
            self.assertEqual([text, text], [row[1] for row in extracted])
            summary = prepare(path, catalog)
            self.assertEqual(1, summary["languages"]["pa"]["records"])
            self.assertEqual(2, summary["sources"]["smol-pa"]["counters"]["eligibleBeforeDedupAndSampling"])
            self.assertEqual(1, summary["sources"]["smol-pa"]["counters"]["sampledDistinctBeforeCrossSourceDedup"])


if __name__ == "__main__":
    unittest.main()
