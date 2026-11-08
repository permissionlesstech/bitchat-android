#!/usr/bin/env python3
"""Fetch and prepare public language-model training candidates, without changing APK weights.

Raw corpora, text and detailed manifests remain in an ignored local cache. Only
public corpus URLs are requested; no app, account, device or workspace data is sent.
Preparation preserves punctuation/diacritics and never uses the current spam model
to select examples. Synthetic repository tests remain separate from these downloads.
"""

import argparse
from collections import Counter, defaultdict
from contextlib import ExitStack, closing
import bz2
import csv
import gzip
import hashlib
import heapq
import json
from pathlib import Path
import re
import shutil
import sqlite3
import tarfile
import tempfile
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET

try:
    from .corpus_http import public_request
    from .fetch_dialogue_subset import digest_file, fetch as fetch_dialogues
except ImportError:
    from corpus_http import public_request
    from fetch_dialogue_subset import digest_file, fetch as fetch_dialogues


DIRECTORY = Path(__file__).resolve().parent
ROOT = DIRECTORY.parents[1]
CATALOG = DIRECTORY / "corpus-sources.json"
CONTACT = re.compile(r"https?://|www\.|[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|(?<!\w)@\w{2,}|\b(?:\d{1,3}\.){3}\d{1,3}\b", re.I)
LONG_ID = re.compile(r"\b[0-9a-fA-F]{32,}\b")
THINK = re.compile(r"<think>.*?</think>", re.S)


def load_catalog():
    catalog = json.loads(CATALOG.read_text())
    tags = ["en"] + [element.get("{http://schemas.android.com/apk/res/android}name")
                     for element in ET.parse(ROOT / "app/src/main/res/xml/locales_config.xml").getroot()]
    if set(catalog["languages"]) != {tag.split("-")[0] for tag in tags}:
        raise ValueError("Corpus language coverage differs from supported locales")
    for source in catalog["sources"]:
        if not source.get("license") or not source.get("licenseUrl"):
            raise ValueError("Every selected source needs a declared license")
        for artifact in source.get("artifacts", []):
            relative = Path(artifact["localFile"])
            if relative.is_absolute() or ".." in relative.parts or relative.parts[0] != "raw":
                raise ValueError("Invalid corpus artifact path")
            public_request(artifact["url"])
    return catalog


def fetch_artifacts(cache, catalog):
    for source in catalog["sources"]:
        for artifact in source.get("artifacts", []):
            target = cache / artifact["localFile"]
            if target.is_file() and digest_file(target) == artifact["sha256"]:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            part = target.with_suffix(".part")
            count = 0
            try:
                with urllib.request.urlopen(public_request(artifact["url"]), timeout=60) as response, part.open("wb") as output:
                    while chunk := response.read(1024 * 1024):
                        count += len(chunk)
                        if count > artifact["bytes"]:
                            raise ValueError("Public corpus download exceeds pinned size")
                        output.write(chunk)
                if count != artifact["bytes"] or digest_file(part) != artifact["sha256"]:
                    raise ValueError("Public corpus snapshot differs from pinned checksum")
                part.replace(target)
                print(f'Downloaded corpus artifact: {source["id"]}', flush=True)
            finally:
                part.unlink(missing_ok=True)


def matches_script(text, family, spec):
    """Reject incompatible scripts, particularly Gurmukhi for the declared pa-PK locale."""
    text = unicodedata.normalize("NFKC", text)
    letters = [char for char in text if char.isalpha()]
    if not letters:
        return True
    if family == "pa" and any(0x0a00 <= ord(char) <= 0x0a7f for char in letters):
        return False
    ranges = {
        "Arab": [(0x600, 0x6ff), (0x750, 0x77f), (0x8a0, 0x8ff)],
        "Beng": [(0x980, 0x9ff)], "Deva": [(0x900, 0x97f)],
        "Cyrl": [(0x400, 0x52f)], "Geor": [(0x10a0, 0x10ff), (0x1c90, 0x1cbf)],
        "Hebr": [(0x590, 0x5ff)], "Hang": [(0x1100, 0x11ff), (0xac00, 0xd7af)],
        "Taml": [(0xb80, 0xbff)], "Thai": [(0xe00, 0xe7f)],
        "Hans": [(0x3400, 0x4dbf), (0x4e00, 0x9fff), (0x20000, 0x323af)],
        "Hant": [(0x3400, 0x4dbf), (0x4e00, 0x9fff), (0x20000, 0x323af)],
        "Jpan": [(0x3040, 0x30ff), (0x3400, 0x9fff), (0x20000, 0x323af)],
    }
    def expected(char):
        if "Latn" in spec["scripts"]:
            return "LATIN" in unicodedata.name(char, "")
        return any(low <= ord(char) <= high for script in spec["scripts"] for low, high in ranges[script])
    return sum(map(expected, letters)) / len(letters) >= 0.6


def records(source, cache, catalog, counters):
    """Yield only text and non-personal source references, never author/persona metadata."""
    ident = source["id"]
    aliases = {code: language for language, spec in catalog["languages"].items() for code in spec["providerCodes"]}
    if ident == "aya-soda":
        ledger = json.loads((cache / "aya-soda-downloads.json").read_text())
        if (ledger.get("revision") != source["revision"] or len(ledger["shards"]) != source["expectedTrainingShards"]
                or any(s["status"] != "downloaded" for s in ledger["shards"].values())):
            raise ValueError("Dialogue subset is incomplete or has a different revision")
        for shard in sorted(ledger["shards"]):
            artifact = ledger["shards"][shard]
            path = cache / artifact["localFile"]
            if digest_file(path) != artifact["sha256"]:
                raise ValueError("Dialogue subset checksum mismatch")
            with gzip.open(path, "rt", encoding="utf-8") as stream:
                for line in stream:
                    row = json.loads(line)
                    yield row["language"], row["text"], str(row["id"]), None
        return
    for artifact in source["artifacts"]:
        path = cache / artifact["localFile"]
        if not path.is_file() or path.stat().st_size != artifact["bytes"] or digest_file(path) != artifact["sha256"]:
            raise ValueError("Missing or changed corpus snapshot")
        if ident == "tatoeba":
            language = artifact["language"]
            with bz2.open(path, "rt", encoding="utf-8") as stream:
                for line in stream:
                    origin, code, text = line.rstrip("\r\n").split("\t", 2)
                    if code != catalog["languages"][language]["tatoebaCode"]:
                        raise ValueError("Tatoeba export has an unexpected language")
                    yield language, text, origin, origin
        elif ident == "massive":
            locales = {locale: language for language, spec in catalog["languages"].items() for locale in spec["massiveLocales"]}
            with tarfile.open(path, "r|gz") as archive:
                for member in archive:
                    if not member.isfile() or not member.name.endswith(".jsonl"):
                        continue
                    locale = Path(member.name).stem
                    if locale not in locales:
                        continue
                    stream = archive.extractfile(member)
                    for line in stream:
                        row = json.loads(line)
                        if row["partition"] == "train":
                            yield locales[locale], row["utt"], str(row["id"]), None
                        else:
                            counters["upstreamHeldOutSkipped"] += 1
        elif ident == "smol-pa":
            with path.open(encoding="utf-8") as stream:
                for line in stream:
                    row = json.loads(line)
                    if row["sl"] == "pa-Arab":
                        values = row["src"]
                    elif row["tl"] == "pa-Arab":
                        values = row["trg"]
                    else:
                        raise ValueError("Professional Punjabi corpus has an unexpected locale")
                    for text in values if isinstance(values, list) else [values]:
                        yield "pa", text, str(row["id"]), None
        elif ident in {"sacor-pa", "georgian-roleplay"}:
            with path.open(newline="", encoding="utf-8-sig") as stream:
                for index, row in enumerate(csv.DictReader(stream, delimiter="\t" if ident == "sacor-pa" else ",")):
                    fields = ["target_sentence"] if ident == "sacor-pa" else ["ინსტრუქცია", "შეყვანა", "პასუხი"]
                    for field in fields:
                        if row.get(field):
                            yield "pa" if ident == "sacor-pa" else "ka", row[field], str(index), None
        else:
            import pyarrow.parquet as parquet
            columns = {"aya": ["language_code", "annotation_type", "inputs", "targets"],
                       "soc": ["chat_id", "chat_parts"], "kakugo-mg": ["messages"],
                       "fasal-pa": ["instruction", "output", "language"]}[ident]
            offset = 0
            for batch in parquet.ParquetFile(path).iter_batches(batch_size=64, columns=columns):
                for row in batch.to_pylist():
                    offset += 1
                    if ident == "aya":
                        if row["annotation_type"].replace("_", "-") != "original-annotations":
                            counters["reannotationsSkipped"] += 1
                            continue
                        language = aliases.get(row["language_code"])
                        if language:
                            for field in ["inputs", "targets"]:
                                yield language, row[field], str(offset), None
                    elif ident == "soc":
                        for part in row["chat_parts"]:
                            for message in part["messages"]:
                                for language in source["languages"]:
                                    yield language, message[language], row["chat_id"], None
                    elif ident == "fasal-pa":
                        if row["language"] == "pa":
                            for field in ["instruction", "output"]:
                                yield "pa", row[field], str(offset), None
                    else:
                        for message in row["messages"]:
                            if message["role"] in {"user", "assistant"}:
                                if not isinstance(message["content"], str):
                                    counters["invalid"] += 1
                                    continue
                                text = THINK.sub("", message["content"])
                                if "<think>" not in text:
                                    yield "mg", text, str(offset), None


class BottomSamples:
    """Deterministic content-hash sampling across the whole source, with bounded memory."""
    def __init__(self, capacity):
        self.capacity, self.heap, self.members = capacity, [], set()

    def add(self, text, group, origin):
        normalized = " ".join(unicodedata.normalize("NFKC", text).lower().split())
        digest = hashlib.sha256(normalized.encode()).digest()
        rank = int.from_bytes(digest, "big")
        if digest in self.members or len(self.heap) == self.capacity and rank >= -self.heap[0][0]:
            return
        row = (-rank, digest, text, group, origin)
        if len(self.heap) == self.capacity:
            old = heapq.heapreplace(self.heap, row)
            self.members.remove(old[1])
        else:
            heapq.heappush(self.heap, row)
        self.members.add(digest)


def prepare(cache, catalog, limit=10_000):
    if not 1 <= limit <= 50_000:
        raise ValueError("Per-source sample limit must be between 1 and 50000")
    temporary = Path(tempfile.mkdtemp(prefix="preparing-", dir=cache))
    summary = {
        "formatVersion": 1, "catalogSha256": digest_file(CATALOG),
        "purpose": "Local training candidates only; app weights and synthetic repository tests are unchanged",
        "sampling": {"method": "bottom SHA-256 of normalized content per source and language", "limitPerSourceLanguage": limit},
        "privacy": "Metadata projection and obvious contact/ID filtering are not a guarantee of anonymization. Keep all corpus text private.",
        "sources": {}, "languages": {language: {"records": 0, "characters": 0, "bySource": {}} for language in catalog["languages"]},
    }
    vocabulary = defaultdict(set)
    try:
        with ExitStack() as stack:
            outputs = {}
            for language in catalog["languages"]:
                raw = stack.enter_context((temporary / f"{language}.jsonl.gz").open("wb"))
                outputs[language] = stack.enter_context(gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0))
            database = stack.enter_context(closing(sqlite3.connect(temporary / "dedup.sqlite")))
            database.execute("CREATE TABLE seen (language TEXT, hash BLOB, PRIMARY KEY(language, hash)) WITHOUT ROWID")
            for source in catalog["sources"]:
                counts = Counter()
                samples = {language: BottomSamples(limit) for language in catalog["languages"]}
                for language, text, origin_group, origin in records(source, cache, catalog, counts):
                    counts["seen"] += 1
                    if not isinstance(text, str):
                        counts["invalid"] += 1
                        continue
                    text = text.strip()
                    if not text or len(text) > 4096 or any(unicodedata.category(c) in {"Cc", "Cs"} and c not in "\n\r\t" for c in text):
                        counts["emptyOversizedOrControl"] += 1
                    elif CONTACT.search(text) or LONG_ID.search(text):
                        counts["obviousContactOrIdentifier"] += 1
                    elif not matches_script(text, language, catalog["languages"][language]):
                        counts["incompatibleScript"] += 1
                    else:
                        counts["eligibleBeforeDedupAndSampling"] += 1
                        group = hashlib.sha256((source["id"] + ":" + origin_group).encode()).hexdigest()
                        samples[language].add(text, group, origin)
                counts["sampledDistinctBeforeCrossSourceDedup"] = sum(len(r.heap) for r in samples.values())
                selected = Counter()
                for language, reservoir in samples.items():
                    for _, key, text, group, origin in sorted(reservoir.heap, key=lambda row: row[1]):
                        inserted = database.execute("INSERT OR IGNORE INTO seen VALUES (?, ?)", (language, key)).rowcount
                        if not inserted:
                            counts["crossSourceDuplicate"] += 1
                            continue
                        row = {"language": language, "source": source["id"], "group": group, "text": text}
                        if origin is not None:
                            row["tatoebaSentenceId"] = origin
                        outputs[language].write((json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n").encode())
                        selected[language] += 1
                        stats = summary["languages"][language]
                        stats["records"] += 1
                        stats["characters"] += len(text)
                        vocabulary[language].update(map(ord, text))
                        stats["bySource"][source["id"]] = stats["bySource"].get(source["id"], 0) + 1
                database.commit()
                summary["sources"][source["id"]] = {"kind": source["kind"], "homepage": source["homepage"], "license": source["license"],
                    "licenseUrl": source["licenseUrl"], "revision": source.get("revision"), "counters": dict(counts), "selectedByLanguage": dict(selected)}
                print(f'Prepared {source["id"]}: {sum(selected.values())} text records', flush=True)
        (temporary / "dedup.sqlite").unlink()
        for language, stats in summary["languages"].items():
            path = temporary / f"{language}.jsonl.gz"
            stats.update(file="prepared/" + path.name, gzipBytes=path.stat().st_size, sha256=digest_file(path))
            stats["observedCodepoints"] = len(vocabulary[language])
            stats["conversationalDialogueRecords"] = sum(count for ident, count in stats["bySource"].items()
                if summary["sources"][ident]["kind"] in {"synthetic-online-chat", "machine-translated-synthetic-dialogue"})
            if not stats["records"]:
                raise ValueError("A supported language has no usable training candidates")
        previous, destination = cache / "prepared.previous", cache / "prepared"
        if previous.exists():
            shutil.rmtree(previous)
        if destination.exists():
            destination.rename(previous)
        temporary.rename(destination)
        manifest_part = cache / "manifest.part"
        manifest_part.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        manifest_part.replace(cache / "manifest.json")
        if previous.exists():
            shutil.rmtree(previous)
        return summary
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def verify(cache):
    summary = json.loads((cache / "manifest.json").read_text())
    if summary["catalogSha256"] != digest_file(CATALOG):
        raise ValueError("Prepared corpus catalog differs")
    for stats in summary["languages"].values():
        if digest_file(cache / stats["file"]) != stats["sha256"]:
            raise ValueError("Prepared corpus checksum mismatch")
    print(f'Verified {len(summary["languages"])} prepared language files')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["fetch", "prepare", "verify"])
    parser.add_argument("--output", type=Path, default=ROOT / "build/corpora/conversational")
    parser.add_argument("--per-source-limit", type=int, default=10_000)
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()
    catalog = load_catalog()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.action == "fetch":
        fetch_artifacts(args.output, catalog)
        ledger = fetch_dialogues(args.output, catalog, jobs=args.jobs)
        if any(row["status"] != "downloaded" for row in ledger["shards"].values()):
            raise SystemExit("Some dialogue shards remain unavailable; see the private local ledger")
    elif args.action == "prepare":
        summary = prepare(args.output, catalog, args.per_source_limit)
        print(f'Prepared {sum(s["records"] for s in summary["languages"].values())} records across {len(summary["languages"])} languages')
    else:
        verify(args.output)


if __name__ == "__main__":
    main()
