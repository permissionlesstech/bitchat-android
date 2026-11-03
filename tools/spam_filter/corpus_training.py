"""Local, bounded-memory corpus partitions for the offline model generator.

Conversations share a partition across translations. Duplicate text across groups
or languages is retained only in its most conservative partition (test before
calibration before train). Repository fixtures are excluded from every corpus
partition. No source text is written to repository files.
"""

from contextlib import AbstractContextManager
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile


PARTITIONS = ("train", "calibration", "test")


def group_partition(group):
    bucket = int(group, 16) % 10
    return 0 if bucket < 8 else 1 if bucket == 8 else 2


def text_key(text, normalize):
    return hashlib.sha256(normalize(text).encode()).digest()


class CorpusPartitions(AbstractContextManager):
    def __init__(self, directory, protected, normalize, languages):
        self.directory = Path(directory)
        self.normalize = normalize
        self.protected = {text_key(text, normalize) for text in protected}
        self.languages = languages

    def __enter__(self):
        self.manifest = json.loads((self.directory / "manifest.json").read_text())
        if set(self.manifest["languages"]) != set(self.languages):
            raise ValueError("Prepared corpus language coverage differs")
        self.temporary = tempfile.TemporaryDirectory(prefix="training-index-", dir=self.directory)
        self.database = sqlite3.connect(Path(self.temporary.name) / "partitions.sqlite")
        self.database.execute("CREATE TABLE texts (hash BLOB PRIMARY KEY, part INTEGER) WITHOUT ROWID")
        try:
            for language in self.languages:
                stats = self.manifest["languages"][language]
                path = self.directory / stats["file"]
                digest = hashlib.sha256()
                with path.open("rb") as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(block)
                if digest.hexdigest() != stats["sha256"]:
                    raise ValueError("Prepared corpus checksum mismatch")
                pending = []
                for row in self.rows(language):
                    key = text_key(row["text"], self.normalize)
                    if key not in self.protected:
                        pending.append((key, group_partition(row["group"])))
                    if len(pending) >= 2000:
                        self.insert(pending)
                        pending.clear()
                self.insert(pending)
            self.database.commit()
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def insert(self, rows):
        self.database.executemany(
            "INSERT INTO texts VALUES (?, ?) ON CONFLICT(hash) DO UPDATE SET part=MAX(part, excluded.part)", rows
        )

    def rows(self, language):
        with gzip.open(self.directory / self.manifest["languages"][language]["file"], "rt", encoding="utf-8") as stream:
            for line in stream:
                row = json.loads(line)
                if row["language"] != language:
                    raise ValueError("Prepared corpus contains an unexpected language")
                yield row

    def language(self, language):
        selected = {part: [] for part in PARTITIONS}
        sources = {part: Counter() for part in PARTITIONS}
        excluded = Counter()
        for row in self.rows(language):
            key = text_key(row["text"], self.normalize)
            if key in self.protected:
                excluded["repositoryFixtureOverlap"] += 1
                continue
            part = group_partition(row["group"])
            assigned = self.database.execute("SELECT part FROM texts WHERE hash=?", (key,)).fetchone()[0]
            if assigned != part:
                excluded["crossPartitionDuplicate"] += 1
                continue
            name = PARTITIONS[part]
            selected[name].append(row["text"])
            sources[name][row["source"]] += 1
        return selected, {part: dict(counts) for part, counts in sources.items()}, dict(excluded)

    def __exit__(self, *args):
        self.database.close()
        self.temporary.cleanup()
