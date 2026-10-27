package com.bitchat.android.spam;

import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Standalone offline measurement, not an app component or repository test fixture.
 * Public held-out text stays in the ignored corpus cache; only aggregate counts leave it.
 */
public final class OfflineCorpusEvaluation {
    private static Method method(Class<?> owner, String prefix) {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().startsWith(prefix)) return method;
        }
        throw new IllegalStateException("Required scoring method is missing");
    }

    private static final Method NORMALIZE = method(MultilingualGibberishDetector.class, "normalize");
    private static final Method CANDIDATES = method(MultilingualGibberishDetector.class, "candidates");
    private static final Method DECODE = method(CharacterMarkovModel.Companion.getClass(), "decode");

    private static CharacterMarkovModel baseline(Path directory, String language) throws Exception {
        byte[] compressed = Files.readAllBytes(directory.resolve(language + ".bcm.gz"));
        byte[] raw;
        try (GZIPInputStream input = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            raw = input.readAllBytes();
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        return (CharacterMarkovModel) DECODE.invoke(CharacterMarkovModel.Companion, digest,
                Base64.getEncoder().encodeToString(compressed));
    }

    private static boolean hidden(int[] points, List<String> candidates, Map<String, CharacterMarkovModel> models) {
        if (points.length == 0) return false;
        for (String language : candidates) {
            CharacterMarkovModel model = models.get(language);
            if (model.score(points) < model.getThreshold()) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, CharacterMarkovModel> old = new LinkedHashMap<>();
        Map<String, CharacterMarkovModel> current = new LinkedHashMap<>();
        List<String> languages = new ArrayList<>(LanguageModelPack.INSTANCE.getAliases().values().stream().distinct().sorted().toList());
        for (String language : languages) {
            old.put(language, baseline(directory.resolve("baseline-models"), language));
            current.put(language, LanguageModelPack.INSTANCE.model(language));
        }
        Map<String, Object> results = new LinkedHashMap<>();
        long total = 0, oldHidden = 0, newHidden = 0;
        for (String language : languages) {
            long count = 0, before = 0, after = 0, shortCount = 0, shortBefore = 0, shortAfter = 0;
            try (BufferedReader input = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                    Files.newInputStream(directory.resolve("held-out").resolve(language + ".jsonl.gz"))), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    String text = (String) gson.fromJson(line, Map.class).get("text");
                    int[] points = (int[]) NORMALIZE.invoke(MultilingualGibberishDetector.INSTANCE, text);
                    List<String> candidates = (List<String>) CANDIDATES.invoke(MultilingualGibberishDetector.INSTANCE, points);
                    // Try the declared language first when eligible; the minimum-model decision is identical.
                    candidates = new ArrayList<>(candidates);
                    if (candidates.remove(language)) candidates.add(0, language);
                    boolean wasHidden = hidden(points, candidates, old);
                    boolean nowHidden = hidden(points, candidates, current);
                    count++;
                    before += wasHidden ? 1 : 0;
                    after += nowHidden ? 1 : 0;
                    if (points.length < 24) {
                        shortCount++;
                        shortBefore += wasHidden ? 1 : 0;
                        shortAfter += nowHidden ? 1 : 0;
                    }
                }
            }
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("total", count);
            stats.put("baselineHidden", before);
            stats.put("retrainedHidden", after);
            stats.put("shortTexts", Map.of("total", shortCount, "baselineHidden", shortBefore, "retrainedHidden", shortAfter));
            results.put(language, stats);
            total += count;
            oldHidden += before;
            newHidden += after;
            System.out.printf("HELD_OUT language=%s total=%d baselineHidden=%d retrainedHidden=%d%n", language, count, before, after);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("measurement", "Exact JVM normalization, script selection, decoder and quantized scoring; aggregate counts only");
        report.put("limitations", "Public held-out corpus candidates, not verified real-world spam labels or a population accuracy estimate");
        report.put("total", total);
        report.put("baselineHidden", oldHidden);
        report.put("retrainedHidden", newHidden);
        report.put("languages", results);
        Files.writeString(directory.resolve("held-out-evaluation.json"), gson.toJson(report) + "\n");
        System.out.printf("HELD_OUT_TOTAL total=%d baselineHidden=%d retrainedHidden=%d%n", total, oldHidden, newHidden);
    }
}
