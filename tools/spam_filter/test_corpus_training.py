"""Partition leakage contracts using invented, non-identifying examples only."""

import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from tools.spam_filter.corpus_training import CorpusPartitions, group_partition
from tools.spam_filter.train_languages import normalize


class CorpusTrainingTest(unittest.TestCase):
    def fixture(self, directory, rows):
        manifest = {"languages": {}}
        for language, contents in rows.items():
            path = directory / f"{language}.jsonl.gz"
            with gzip.open(path, "wt", encoding="utf-8") as output:
                for text, group in contents:
                    output.write(json.dumps({"language": language, "source": "synthetic", "text": text, "group": group}) + "\n")
            manifest["languages"][language] = {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        (directory / "manifest.json").write_text(json.dumps(manifest))

    def test_conversation_and_translations_stay_in_one_partition(self):
        self.assertEqual([0, 1, 2], [group_partition(f"{i:064x}") for i in (7, 8, 9)])
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            group = f"{8:064x}"
            self.fixture(directory, {"en": [("A fictional robot draws a box.", group), ("Its box is blue.", group)],
                                     "it": [("Un robot inventato disegna una scatola.", group)]})
            with CorpusPartitions(directory, [], normalize, ["en", "it"]) as corpus:
                for language, expected in [("en", 2), ("it", 1)]:
                    selected, _, _ = corpus.language(language)
                    self.assertEqual(expected, len(selected["calibration"]))
                    self.assertFalse(selected["train"] or selected["test"])

    def test_duplicate_text_cannot_leak_across_languages_or_conversation_groups(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory, {"en": [("A fictional example.", f"{0:064x}")],
                                     "it": [("A FICTIONAL EXAMPLE.", f"{9:064x}")]})
            with CorpusPartitions(directory, [], normalize, ["en", "it"]) as corpus:
                train, _, exclusions = corpus.language("en")
                test, _, _ = corpus.language("it")
                self.assertFalse(any(train.values()))
                self.assertEqual(1, exclusions["crossPartitionDuplicate"])
                self.assertEqual(["A FICTIONAL EXAMPLE."], test["test"])

    def test_repository_fixtures_are_excluded_from_all_corpus_partitions(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory, {"en": [("A fictional example.", f"{0:064x}")]})
            with CorpusPartitions(directory, ["A FICTIONAL EXAMPLE."], normalize, ["en"]) as corpus:
                rows, _, exclusions = corpus.language("en")
                self.assertFalse(any(rows.values()))
                self.assertEqual({"repositoryFixtureOverlap": 1}, exclusions)

    def test_changed_corpus_fails_before_training(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory, {"en": [("A fictional example.", f"{0:064x}")]})
            with (directory / "en.jsonl.gz").open("ab") as output:
                output.write(b"changed")
            with self.assertRaisesRegex(ValueError, "checksum"):
                with CorpusPartitions(directory, [], normalize, ["en"]):
                    self.fail("Corrupt corpus was accepted")


if __name__ == "__main__":
    unittest.main()
