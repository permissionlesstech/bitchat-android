#!/usr/bin/env python3
"""Measure private held-out corpus files through the app's exact JVM scoring code.

Run train_languages.py --evaluation-dir first, then compile the app's debug classes.
Raw compiler output stays local. The resulting JSON contains aggregate counts only.
Requires JDK 21+, compiled app classes, and the existing cached Kotlin/Gson jars.
"""

import argparse
import base64
import gzip
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tomllib


ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/conversational-retraining")
    parser.add_argument("--classes", type=Path, default=ROOT / "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
    args = parser.parse_args()
    versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]
    dependencies = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    jars = []
    for group, artifact, version in [("org.jetbrains.kotlin", "kotlin-stdlib", versions["kotlin"]),
                                     ("com.google.code.gson", "gson", versions["gson"])]:
        candidates = list((dependencies / group / artifact / version).glob("*/*.jar"))
        if len(candidates) != 1:
            raise SystemExit("Required existing JVM dependency missing or ambiguous; build app debug classes first")
        jars.append(candidates[0])
    if not (args.classes / "com/bitchat/android/spam/LanguageModelPack.class").is_file():
        raise SystemExit("Compile app debug classes before evaluating")
    pack = (args.output / "baseline-pack.kt").read_text()
    baseline_directory = args.output / "baseline-models"
    baseline_directory.mkdir(exist_ok=True)
    count = 0
    for language, digest, chunks in re.findall(r'private val (\w+) by lazy \{ decode\("([a-f0-9]+)",\s*(.*?)\n    \) \}', pack, re.S):
        compressed = base64.b64decode("".join(re.findall(r'"([A-Za-z0-9+/=]+)"', chunks)))
        if hashlib.sha256(gzip.decompress(compressed)).hexdigest() != digest:
            raise SystemExit("Historical model checksum mismatch")
        (baseline_directory / f"{language}.bcm.gz").write_bytes(compressed)
        count += 1
    if count != 32:
        raise SystemExit("Historical pack must contain all 32 models")
    classes = args.output / "evaluator-classes"
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join(str(p.resolve()) for p in [args.classes, *jars])
    with (args.output / "evaluation-private.log").open("w") as log:
        compile_result = subprocess.run(["javac", "--release", "21", "-cp", classpath, "-d", str(classes),
                                         str(ROOT / "tools/spam_filter/OfflineCorpusEvaluation.java")], stdout=log, stderr=log)
        if compile_result.returncode:
            raise SystemExit("Evaluator compilation failed; diagnostics remain in the private local log")
        result = subprocess.run(["java", "-Xmx512m", "-cp", str(classes.resolve()) + os.pathsep + classpath,
                                 "com.bitchat.android.spam.OfflineCorpusEvaluation", str(args.output)], stdout=log, stderr=log)
    for line in (args.output / "evaluation-private.log").read_text().splitlines():
        if line.startswith("HELD_OUT"): print(line)
    if result.returncode:
        raise SystemExit("Offline evaluation failed; diagnostics remain in the private local log")


if __name__ == "__main__":
    main()
