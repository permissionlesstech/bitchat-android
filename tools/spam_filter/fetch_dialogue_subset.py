#!/usr/bin/env python3
"""Fetch a bounded multilingual SODA training subset without downloading whole shards.

Only public, pinned corpus URLs are requested. Never reads app/device messages.
Text and detailed provenance stay in the ignored local corpus cache.
"""

import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
import gzip
import hashlib
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

try:
    from .corpus_http import BoundedHttpFile, public_request
except ImportError:
    from corpus_http import BoundedHttpFile, public_request


DIRECTORY = Path(__file__).resolve().parent


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def public_json(url):
    with urllib.request.urlopen(public_request(url), timeout=45) as response:
        content = response.read(4 * 1024**2 + 1)
    if len(content) > 4 * 1024**2:
        raise ValueError("Public corpus metadata exceeds budget")
    return json.loads(content)


def select_groups(metadata, columns, languages, limit, shard):
    """Use only homogeneous language/script groups; distribute samples across the shard."""
    aliases = {code: language for language, spec in languages.items() for code in spec["providerCodes"]}
    grouped = defaultdict(list)
    language_index, script_index = columns.index("language"), columns.index("script")
    incompatible = set()
    for index in range(metadata.num_row_groups):
        group = metadata.row_group(index)
        language = group.column(language_index).statistics
        script = group.column(script_index).statistics
        if not language or not script or not language.has_min_max or not script.has_min_max:
            continue
        if language.min != language.max or script.min != script.max:
            continue
        code, writing = language.min, script.min
        family = aliases.get(code)
        if family is None:
            continue
        if writing not in languages[family]["scripts"]:
            incompatible.add((family, code, writing))
            continue
        grouped[(family, code, writing)].append(index)
    selected = []
    for (family, code, writing), groups in sorted(grouped.items()):
        ranked = sorted(groups, key=lambda index: hashlib.sha256(
            f"{shard}:{code}:{writing}:{index}".encode()
        ).digest())[:limit]
        selected.extend((index, family, code, writing) for index in sorted(ranked))
    return selected, sorted(incompatible)


def fetch_shard(artifact, source, languages, cache, limit):
    import pyarrow.parquet as parquet

    name = Path(artifact["path"]).name
    index = int(re.fullmatch(r"train-(\d+)-of-\d+\.parquet", name).group(1))
    destination = cache / "raw" / f"aya-soda-{index:05d}.jsonl.gz"
    relative = "raw/" + destination.name
    url = f'https://huggingface.co/datasets/{source["repository"]}/resolve/{source["revision"]}/{artifact["path"]}'
    part = destination.with_suffix(".part")
    counts = defaultdict(int)
    try:
        with BoundedHttpFile(url, artifact["size"]) as remote:
            data = parquet.ParquetFile(remote)
            groups, incompatible = select_groups(data.metadata, data.schema_arrow.names, languages, limit, name)
            with part.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as output:
                for group_index, family, code, writing in groups:
                    # Adjacent id/input/target chunks are small; fetch the span once so
                    # reading id and targets reuses a validated range instead of two trips.
                    metadata = data.metadata.row_group(group_index)
                    chunks = [metadata.column(data.schema_arrow.names.index(column)) for column in ["id", "targets"]]
                    starts = [chunk.dictionary_page_offset if chunk.has_dictionary_page else chunk.data_page_offset for chunk in chunks]
                    first = min(starts)
                    last = max(start + chunk.total_compressed_size for start, chunk in zip(starts, chunks))
                    if last - first <= 2 * 1024**2 and remote.downloaded + last - first <= remote.max_download:
                        position = remote.tell()
                        remote.seek(first)
                        remote.read(last - first)
                        remote.seek(position)
                    table = data.read_row_group(group_index, columns=["id", "targets"])
                    for row in table.to_pylist():
                        if not isinstance(row["targets"], str):
                            continue
                        record = {"id": row["id"], "language": family, "providerLanguage": code,
                                  "script": writing, "text": row["targets"]}
                        output.write((json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n").encode())
                        counts[family] += 1
            result = {
                "shard": artifact["path"], "sourceUrl": url, "sourceBytes": artifact["size"],
                "declaredOriginalSha256": artifact.get("lfs", {}).get("oid"),
                "verification": "Pinned revision and exact HTTP ranges; full original shard hash is not verified",
                "localFile": relative, "bytes": part.stat().st_size, "sha256": digest_file(part),
                "downloadedBytes": remote.downloaded, "ranges": remote.ranges,
                "selectedGroups": [row[0] for row in groups], "languageRecords": dict(counts),
                "incompatibleScripts": [dict(zip(["language", "providerCode", "script"], row)) for row in incompatible],
                "status": "downloaded",
            }
        part.replace(destination)
        return result
    except Exception:
        part.unlink(missing_ok=True)
        raise


def fetch(cache, catalog, jobs=2, limit=None):
    source = next(s for s in catalog["sources"] if s["id"] == "aya-soda")
    limit = limit or source["rowGroupsPerLanguageScriptPerShard"]
    if not 1 <= limit <= 20 or not 1 <= jobs <= 3:
        raise ValueError("Dialogue acquisition exceeds worker or group limit")
    cache.mkdir(parents=True, exist_ok=True)
    (cache / "raw").mkdir(exist_ok=True)
    url = f'https://huggingface.co/api/datasets/{source["repository"]}/tree/{source["revision"]}/{source["directory"]}?recursive=false&expand=false&limit=1000'
    artifacts = [entry for entry in public_json(url) if re.fullmatch(
        re.escape(source["directory"]) + r"/train-\d+-of-\d+\.parquet", entry["path"]
    )]
    if not artifacts:
        raise ValueError("No pinned dialogue training shards")
    if len(artifacts) != source["expectedTrainingShards"]:
        raise ValueError("Pinned dialogue shard count differs from the source catalog")
    ledger_path = cache / "aya-soda-downloads.json"
    ledger = {"source": "aya-soda", "revision": source["revision"], "rowGroupsPerLanguageScriptPerShard": limit, "shards": {}}
    if ledger_path.exists():
        old = json.loads(ledger_path.read_text())
        if old.get("revision") == source["revision"] and old.get("rowGroupsPerLanguageScriptPerShard") == limit:
            ledger = old
    pending = []
    for artifact in artifacts:
        old = ledger["shards"].get(artifact["path"], {})
        local = cache / old.get("localFile", "not-present")
        if old.get("status") != "downloaded" or not local.is_file() or digest_file(local) != old.get("sha256"):
            pending.append(artifact)
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = {pool.submit(fetch_shard, artifact, source, catalog["languages"], cache, limit): artifact for artifact in pending}
        for future in as_completed(futures):
            artifact = futures[future]
            try:
                result = future.result()
                print(f'Dialogue shard {Path(artifact["path"]).name}: {sum(result["languageRecords"].values())} selected records', flush=True)
            except Exception as error:
                result = {"status": "failed", "errorType": type(error).__name__, "httpStatus": getattr(error, "code", None)}
                print(f'Dialogue shard {Path(artifact["path"]).name}: unavailable ({type(error).__name__})', flush=True)
            ledger["shards"][artifact["path"]] = result
            temporary = ledger_path.with_suffix(".part")
            temporary.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n")
            temporary.replace(ledger_path)
    return ledger


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DIRECTORY.parents[1] / "build/corpora/conversational")
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--groups", type=int)
    args = parser.parse_args()
    catalog = json.loads((DIRECTORY / "corpus-sources.json").read_text())
    ledger = fetch(args.output, catalog, args.jobs, args.groups)
    failed = sum(s["status"] != "downloaded" for s in ledger["shards"].values())
    print(f"Dialogue source acquisition finished; unavailable shards: {failed}")
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
