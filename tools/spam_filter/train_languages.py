#!/usr/bin/env python3
"""Build fixed Unicode bigram models using only repository text and synthetic examples.

No network, messages, device data, or runtime training. Python standard library only.
Resource keys determine disjoint train/calibration/test partitions across translations.
--check reproduces all generated files without modifying them.
"""

import argparse
import base64
from collections import Counter
import gzip
import hashlib
import json
import math
from pathlib import Path
import random
import re
import struct
import unicodedata
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / "tools/spam_filter"
SOURCE = ROOT / "app/src/main/java/com/bitchat/android/spam"
TEST = ROOT / "app/src/test/java/com/bitchat/android/spam"
SCALE = 4096
BACKOFF = 5.0
MAX_COST = 15.99
GROUPS = {
    "latin": "en de es eu fil fr id it mg ms nl pl pt sv tr vi".split(),
    "cyrillic": ["ru", "uk"], "arabic": ["ar", "fa", "pa", "ur"],
    "devanagari": ["hi", "ne"], "bengali": ["bn"], "tamil": ["ta"],
    "thai": ["th"], "georgian": ["ka"], "hebrew": ["he"],
    "hangul": ["ko"], "cjk": ["ja", "zh"],
}
LANGUAGES = sorted(language for languages in GROUPS.values() for language in languages)
# Exclude attribution, identifiers, accounts, location-related text, and dynamic templates.
# These are static UI resources, never actual device or conversation data.
EXCLUDED_KEY = re.compile(
    r"about|credit|author|contribut|licen[cs]e|donat|developer|app_name|"
    r"location|geo|map|nearby|distance|region|coordinate|latitude|longitude|"
    r"contact|account|profile|nickname|peer|identit|pubkey|privkey|nostr|relay|"
    r"password|fingerprint|social|website|github|lightning|cashu|tor_", re.I
)
EXCLUDED_TEXT = re.compile(r"https?://|www\.|@|npub1|nsec1|[0-9a-fA-F]{16,}|bitchat|©", re.I)
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")


def normalize(text):
    text = unicodedata.normalize("NFKC", text).lower()
    result = []
    for char in text:
        if char.isspace():
            if not result or result[-1] != " ":
                result.append(" ")
        else:
            result.append(char)
    return "".join(result)


def resource_rows(path, allowed):
    for element in ET.parse(path).getroot():
        key = element.get("name", "")
        if element.tag != "string" or key not in allowed:
            continue
        text = "".join(element.itertext()).strip().strip('"')
        if EXCLUDED_TEXT.search(text):
            continue
        text = PLACEHOLDER.sub(" ", text).replace("\\n", " ").replace("\\t", " ")
        text = text.replace("\\'", "'").replace('\\"', '"')
        if sum(char.isalpha() for char in text) < 4:
            continue
        partition = int(hashlib.sha256(key.encode()).hexdigest()[:8], 16) % 10
        yield ("train" if partition < 8 else "calibration" if partition == 8 else "test"), text


def input_data():
    config = ROOT / "app/src/main/res/xml/locales_config.xml"
    tags = ["en"] + [e.attrib["{http://schemas.android.com/apk/res/android}name"] for e in ET.parse(config).getroot()]
    aliases = {tag: tag.split("-")[0] for tag in tags}
    if sorted(set(aliases.values())) != LANGUAGES:
        raise ValueError("Update language coverage when supported locales change")
    synthetic = json.loads((DIRECTORY / "language-examples.json").read_text())
    if sorted(synthetic) != LANGUAGES:
        raise ValueError("Each language needs separate synthetic training, calibration and test examples")
    base = ROOT / "app/src/main/res/values/strings.xml"
    allowed = {e.get("name") for e in ET.parse(base).getroot() if e.tag == "string"
               and not EXCLUDED_KEY.search(e.get("name", ""))
               and not EXCLUDED_TEXT.search("".join(e.itertext()))}
    data = {lang: {part: [] for part in ("train", "calibration", "test")} for lang in LANGUAGES}
    sources = {lang: [] for lang in LANGUAGES}
    for tag, language in aliases.items():
        qualifier = "values" if tag == "en" else "values-" + tag.replace("-", "-r")
        path = ROOT / "app/src/main/res" / qualifier / "strings.xml"
        sources[language].append(str(path.relative_to(ROOT)))
        for part, text in resource_rows(path, allowed):
            data[language][part].append(text)
    english = [line for line in (DIRECTORY / "english-training.txt").read_text().splitlines()
               if line and not line.startswith("#")]
    data["en"]["train"].extend(english)
    # Original synthetic format examples; deliberately different from the existing evaluation set.
    formats = [
        "The sample has 48 items and needs 25% more space.",
        "Use version 7.3.1; the fictional cost is 6.75 + 2.25 = 9.00.",
        "Example only: [small] {medium} (large) <extra>; yes/no, true/false.",
        "A synthetic expression: 3 * 7 = 21; 16 / 4 = 4; 8 - 3 = 5.",
        'Example JSON: {"count": 48, "ready": true, "label": "sample"}',
        "Example code: val count = 48; if (ready) { show(count) }",
        "A fictional URL: https://example.invalid/sample?count=48&ready=true",
        "Example markup: **sample** _text_ `value` # heading > quote",
        "ok yes no thanks fine hello hi bye lol wow hmm sure done",
        "Thank you! Great :) All done 👍 Nice work 🎉 Hello 😀",
        "Numbers: 0123456789 9876543210 13579 24680 101010 202020",
    ]
    data["en"]["train"].extend(formats)
    for language in LANGUAGES:
        for part in data[language]:
            data[language][part].extend(synthetic[language][part])
        # Identical labels across translations/variants must never leak into held-out partitions.
        seen = set()
        for part in ("train", "calibration", "test"):
            unique = []
            for text in data[language][part]:
                normalized = normalize(text)
                if normalized and normalized not in seen:
                    unique.append(text)
                    seen.add(normalized)
            data[language][part] = sorted(unique)
        if any(normalize(t) not in {normalize(x) for x in data[language]["test"]}
               for t in synthetic[language]["test"]):
            raise ValueError("Synthetic test data overlaps training/calibration")
    # Candidates share scripts, so held-out text must be disjoint across models too.
    training = {normalize(t) for rows in data.values() for t in rows["train"]}
    for rows in data.values():
        rows["calibration"] = [t for t in rows["calibration"] if normalize(t) not in training]
    calibration = {normalize(t) for rows in data.values() for t in rows["calibration"]}
    for language, rows in data.items():
        rows["test"] = [t for t in rows["test"] if normalize(t) not in training | calibration]
        for t in synthetic[language]["calibration"]:
            if normalize(t) in training:
                raise ValueError("Synthetic calibration overlaps training")
        for t in synthetic[language]["test"]:
            if normalize(t) in training | calibration:
                raise ValueError("Synthetic test overlaps training/calibration")
    return aliases, data, synthetic, sources


def train(lines):
    unigrams = Counter()
    pairs = Counter()
    previous = Counter()
    for text in lines:
        text = " " + normalize(text) + " "
        unigrams.update(text)
        pairs.update(zip(text, text[1:]))
        previous.update(text[:-1])
    # All printable ASCII characters get a learned/backoff score, never a content exemption.
    alphabet = sorted(set(unigrams) | {chr(i) for i in range(32, 127)})
    if len(alphabet) > 4096:
        raise ValueError("Alphabet exceeds the bounded decoder")
    positions = {c: i for i, c in enumerate(alphabet)}
    denominator = sum(unigrams.values()) + 0.25 * len(alphabet)
    unigram_costs = [-math.log((unigrams[c] + 0.25) / denominator) for c in alphabet]
    backoff_costs = [math.log((previous[c] + BACKOFF) / BACKOFF) for c in alphabet]
    entries = []
    for (a, b), count in pairs.items():
        probability = (count + BACKOFF * math.exp(-unigram_costs[positions[b]])) / (previous[a] + BACKOFF)
        entries.append((positions[a] * len(alphabet) + positions[b], -math.log(probability)))
    entries.sort()
    quantize = lambda x: min(round(MAX_COST * SCALE), round(x * SCALE))
    unigram_costs = [quantize(x) / SCALE for x in unigram_costs]
    backoff_costs = [quantize(x) / SCALE for x in backoff_costs]
    observed = {key: quantize(cost) / SCALE for key, cost in entries}
    unknown = min(MAX_COST, max(unigram_costs) + max(backoff_costs))
    unknown = quantize(unknown) / SCALE

    def score(text):
        points = normalize(text)[:1000]
        if not points:
            return None
        if len(points) == 1:
            points = " " + points
        costs = []
        for a, b in zip(points, points[1:]):
            if a not in positions or b not in positions:
                costs.append(unknown)
            else:
                left, right = positions[a], positions[b]
                costs.append(observed.get(left * len(alphabet) + right,
                                          min(MAX_COST, backoff_costs[left] + unigram_costs[right])))
        return sum(costs) / len(costs)

    return alphabet, unigram_costs, backoff_costs, observed, unknown, score


def generate():
    aliases, data, synthetic, sources = input_data()
    metadata = {
        "formatVersion": 1, "training": "offline only; no device data", "aliases": aliases,
        "normalization": "NFKC, lowercase, collapsed whitespace; Hangul syllables remain intact",
        "bigramBackoff": BACKOFF, "unigramPseudocount": 0.25, "quantizationScale": SCALE,
        "positiveCalibrationQuantile": 0.95, "positiveCalibrationMargin": 0.35,
        "negativeCalibration": {"seed": 424242, "samples": 256, "length": 96, "quantile": 0.01, "marginMultiplier": 0.98},
        "models": [],
    }
    declarations, loaders = [], []
    tests = []
    for language in LANGUAGES:
        alphabet, unigrams, backoff, observed, unknown, score = train(data[language]["train"])
        # Calibrate only on the calibration partition, never on tests or flood samples.
        calibration = [score(text) for text in data[language]["calibration"]
                       if sum(c.isalpha() for c in text) >= 24]
        calibration.extend(score(t) for t in synthetic[language]["calibration"])
        calibration.sort()
        # Keep language support from opening a bypass for random native-script characters.
        # Negative calibration is generated only from the training alphabet with its own seed.
        positive_threshold = calibration[math.ceil(0.95 * len(calibration)) - 1] + 0.35
        noise_alphabet = [c for c in alphabet if c.isalpha() and
                          (language in GROUPS["latin"] or "LATIN" not in unicodedata.name(c, ""))]
        rng = random.Random(424242)
        negative_scores = sorted(score("".join(rng.choice(noise_alphabet) for _ in range(96)))
                                 for _ in range(256))
        negative_ceiling = negative_scores[math.floor(0.01 * len(negative_scores))] * 0.98
        threshold = round(min(positive_threshold, negative_ceiling), 6)
        quantize = lambda x: round(x * SCALE)
        raw = struct.pack(">4sHfHHI", b"BCM1", len(alphabet), threshold, quantize(unknown), SCALE, len(observed))
        raw += b"".join(struct.pack(">IHH", ord(c), quantize(u), quantize(b))
                        for c, u, b in zip(alphabet, unigrams, backoff))
        raw += b"".join(struct.pack(">IH", key, quantize(cost)) for key, cost in observed.items())
        digest = hashlib.sha256(raw).hexdigest()
        compressed = gzip.compress(raw, compresslevel=9, mtime=0)
        compressed = compressed[:9] + b"\xff" + compressed[10:]  # Platform-independent gzip OS byte.
        encoded = base64.b64encode(compressed).decode()
        chunks = [encoded[i:i + 12000] for i in range(0, len(encoded), 12000)]
        declarations.append("    private val " + language + " by lazy { decode(\"" + digest + "\",\n" +
                            ",\n".join('        "' + chunk + '"' for chunk in chunks) + "\n    ) }")
        loaders.append(f'        "{language}" -> {language}')
        rows = data[language]
        metadata["models"].append({
            "language": language, "group": next(g for g, ls in GROUPS.items() if language in ls),
            "sources": sources[language], "trainSamples": len(rows["train"]),
            "calibrationSamples": len(rows["calibration"]), "testSamples": len(rows["test"]),
            "trainingSha256": hashlib.sha256("\n".join(rows["train"]).encode()).hexdigest(),
            "calibrationSha256": hashlib.sha256("\n".join(rows["calibration"]).encode()).hexdigest(),
            "heldOutSha256": hashlib.sha256("\n".join(rows["test"]).encode()).hexdigest(),
            "alphabetSize": len(alphabet), "observedBigrams": len(observed),
            "threshold": threshold, "positiveCalibrationThreshold": round(positive_threshold, 6),
            "noiseCalibrationCeiling": round(negative_ceiling, 6),
            "calibrationConflict": positive_threshold > negative_ceiling,
            "rawBytes": len(raw), "gzipBytes": len(compressed), "sha256": digest,
        })
        for index, content in enumerate(rows["test"]):
            tests.append({"language": language, "id": f"{language}-{index:03d}", "content": content,
                          "synthetic": content in synthetic[language]["test"]})
    alias_source = ", ".join(f'"{tag}" to "{language}"' for tag, language in aliases.items())
    kotlin = '''package com.bitchat.android.spam

// Generated by tools/spam_filter/train_languages.py; do not edit compressed weights.
// Fixed offline training. Corpus partitions and hashes: tools/spam_filter/language-models.json.
internal object LanguageModelPack {
    val aliases: Map<String, String> = mapOf(ALIASES)

    fun model(language: String): CharacterMarkovModel = when (language) {
LOADERS
        else -> error("Unsupported model language")
    }

    private fun decode(digest: String, vararg chunks: String): CharacterMarkovModel =
        CharacterMarkovModel.decode(digest, chunks.joinToString(""))

DECLARATIONS
}
'''.replace("ALIASES", alias_source).replace("LOADERS", "\n".join(loaders)).replace("DECLARATIONS", "\n\n".join(declarations))
    # A generated Kotlin fixture is shared with Wear by the existing test-source sync.
    quote = lambda s: json.dumps(s, ensure_ascii=False).replace("$", "\\$")
    fixture = '''package com.bitchat.android.spam

// Generated disjoint held-out examples; never read by the application.
internal object LanguageHeldOutSamples {
    data class Sample(val language: String, val id: String, val content: String, val synthetic: Boolean)
    val samples = listOf(
''' + "\n".join(f'        Sample({quote(t["language"])}, {quote(t["id"])}, {quote(t["content"])}, {str(t["synthetic"]).lower()}),'
                       for t in tests) + "\n    )\n}\n"
    return {
        SOURCE / "LanguageModelPack.kt": kotlin,
        TEST / "LanguageHeldOutSamples.kt": fixture,
        DIRECTORY / "language-models.json": json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    arguments = parser.parse_args()
    generated = generate()
    for path, content in generated.items():
        if arguments.check:
            if not path.exists() or path.read_text() != content:
                raise SystemExit(f"Regenerate {path.relative_to(ROOT)} using tools/spam_filter/train_languages.py")
        else:
            path.write_text(content)
    print(f"{'Verified' if arguments.check else 'Generated'} 32 fixed models and disjoint held-out fixtures.")


if __name__ == "__main__":
    main()
