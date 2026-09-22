import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sea Wolf-style educational solver reconstructed from observed gameplay cues.
 * Not an official implementation of proprietary assessment rules.
 */
public class SeaWolfSolver {
    private static final String DEFAULT_SCENARIO = "scenarios/sample_scenario.json";

    enum Category {
        SITE_1("Site 1"),
        SITE_2("Site 2"),
        RETURN("Return");

        final String label;

        Category(String label) {
            this.label = label;
        }
    }

    static class RangeRule {
        final int min;
        final int max;

        RangeRule(int min, int max) {
            this.min = min;
            this.max = max;
        }

        boolean contains(int value) {
            return value >= min && value <= max;
        }

        double midpoint() {
            return (min + max) / 2.0;
        }
    }

    static class Microbe {
        final String id;
        final String name;
        final int density;
        final int energy;
        final int size;
        final List<String> traits;

        Microbe(String id, String name, int density, int energy, int size, List<String> traits) {
            this.id = id;
            this.name = name;
            this.density = density;
            this.energy = energy;
            this.size = size;
            this.traits = traits;
        }

        int attribute(String key) {
            switch (key) {
                case "density":
                    return density;
                case "energy":
                    return energy;
                case "size":
                    return size;
                default:
                    throw new IllegalArgumentException("Unknown attribute: " + key);
            }
        }
    }

    static class SiteProfile {
        final String name;
        final Map<String, RangeRule> ranges;
        final List<String> desiredTraits;
        final List<String> undesiredTraits;
        final boolean isCurrent;
        final boolean hardRejectUndesired;

        SiteProfile(
                String name,
                Map<String, RangeRule> ranges,
                List<String> desiredTraits,
                List<String> undesiredTraits,
                boolean isCurrent,
                boolean hardRejectUndesired) {
            this.name = name;
            this.ranges = ranges;
            this.desiredTraits = desiredTraits;
            this.undesiredTraits = undesiredTraits;
            this.isCurrent = isCurrent;
            this.hardRejectUndesired = hardRejectUndesired;
        }
    }

    static class SiteFit {
        final String siteName;
        final boolean hardRejected;
        final double score;
        final double confidence;
        final List<String> reasons;

        SiteFit(String siteName, boolean hardRejected, double score, double confidence, List<String> reasons) {
            this.siteName = siteName;
            this.hardRejected = hardRejected;
            this.score = score;
            this.confidence = confidence;
            this.reasons = reasons;
        }
    }

    static class ClassificationResult {
        final String microbeId;
        final Category recommended;
        final double score;
        final double confidence;
        final List<String> reasons;
        final List<Map.Entry<Category, Double>> candidates;

        ClassificationResult(
                String microbeId,
                Category recommended,
                double score,
                double confidence,
                List<String> reasons,
                List<Map.Entry<Category, Double>> candidates) {
            this.microbeId = microbeId;
            this.recommended = recommended;
            this.score = score;
            this.confidence = confidence;
            this.reasons = reasons;
            this.candidates = candidates;
        }
    }

    static class CombinationScore {
        final List<String> microbeIds;
        final double averageDensity;
        final double averageEnergy;
        final double averageSize;
        final double midpointDistance;
        final double efficiencyPercent;
        final boolean desiredTraitPresent;
        final boolean undesiredTraitAbsent;
        final double score;
        final List<String> reasons;

        CombinationScore(
                List<String> microbeIds,
                double averageDensity,
                double averageEnergy,
                double averageSize,
                double midpointDistance,
                double efficiencyPercent,
                boolean desiredTraitPresent,
                boolean undesiredTraitAbsent,
                double score,
                List<String> reasons) {
            this.microbeIds = microbeIds;
            this.averageDensity = averageDensity;
            this.averageEnergy = averageEnergy;
            this.averageSize = averageSize;
            this.midpointDistance = midpointDistance;
            this.efficiencyPercent = efficiencyPercent;
            this.desiredTraitPresent = desiredTraitPresent;
            this.undesiredTraitAbsent = undesiredTraitAbsent;
            this.score = score;
            this.reasons = reasons;
        }
    }

    static class Scenario {
        final String name;
        final List<Microbe> microbes;
        final SiteProfile site1;
        final SiteProfile site2;
        final int combinationSize;

        Scenario(String name, List<Microbe> microbes, SiteProfile site1, SiteProfile site2, int combinationSize) {
            this.name = name;
            this.microbes = microbes;
            this.site1 = site1;
            this.site2 = site2;
            this.combinationSize = combinationSize;
        }
    }

    static class Engine {
        final Scenario scenario;

        Engine(Scenario scenario) {
            this.scenario = scenario;
        }

        static double rangeFitScore(int value, RangeRule rule) {
            if (rule.contains(value)) {
                return 1.0;
            }
            int distance = value < rule.min ? rule.min - value : value - rule.max;
            return Math.max(0.0, 1.0 - (distance / 9.0));
        }

        static double midpointScore(int value, RangeRule rule) {
            double distance = Math.abs(value - rule.midpoint());
            return Math.max(0.0, 1.0 - (distance / 9.0));
        }

        SiteFit evaluateSiteFit(Microbe microbe, SiteProfile site) {
            List<String> reasons = new ArrayList<>();
            Set<String> traits = new HashSet<>(microbe.traits);

            List<String> undesiredPresent = new ArrayList<>();
            for (String trait : site.undesiredTraits) {
                if (traits.contains(trait)) {
                    undesiredPresent.add(trait);
                }
            }

            if (!undesiredPresent.isEmpty() && site.hardRejectUndesired) {
                reasons.add("Hard reject: contains undesired traits " + String.join(", ", undesiredPresent));
                return new SiteFit(site.name, true, 0.0, 1.0, reasons);
            }

            List<Double> rangeScores = new ArrayList<>();
            List<Double> midpointScores = new ArrayList<>();
            for (String attr : new String[] {"density", "energy", "size"}) {
                RangeRule rule = site.ranges.get(attr);
                if (rule == null) {
                    continue;
                }
                int value = microbe.attribute(attr);
                double rangeScore = rangeFitScore(value, rule);
                double midpointScore = midpointScore(value, rule);
                rangeScores.add(rangeScore);
                midpointScores.add(midpointScore);
                reasons.add(String.format(
                        Locale.ROOT,
                        "%s=%d, target %d-%d, range fit=%.2f, midpoint fit=%.2f",
                        attr,
                        value,
                        rule.min,
                        rule.max,
                        rangeScore,
                        midpointScore));
            }

            double rangeComponent = rangeScores.isEmpty() ? 0.5 : average(rangeScores);
            double midpointComponent = midpointScores.isEmpty() ? 0.5 : average(midpointScores);

            List<String> desiredHits = new ArrayList<>();
            for (String trait : site.desiredTraits) {
                if (traits.contains(trait)) {
                    desiredHits.add(trait);
                }
            }

            double desiredComponent = 0.5;
            if (!site.desiredTraits.isEmpty()) {
                desiredComponent = desiredHits.size() / (double) site.desiredTraits.size();
            }

            if (!desiredHits.isEmpty()) {
                reasons.add("Desired traits matched: " + String.join(", ", desiredHits));
            } else if (!site.desiredTraits.isEmpty()) {
                reasons.add("Desired trait not present");
            }

            double softPenalty = 0.0;
            if (!undesiredPresent.isEmpty()) {
                softPenalty = 0.3;
                reasons.add("Soft penalty: contains undesired traits " + String.join(", ", undesiredPresent));
            }

            double score = 0.55 * rangeComponent + 0.25 * midpointComponent + 0.20 * desiredComponent - softPenalty;
            int knownSignals = site.ranges.size();
            if (!site.desiredTraits.isEmpty()) {
                knownSignals += 1;
            }
            if (!site.undesiredTraits.isEmpty()) {
                knownSignals += 1;
            }
            double confidence = Math.min(1.0, knownSignals / 5.0);

            return new SiteFit(site.name, false, Math.max(0.0, score), confidence, reasons);
        }

        ClassificationResult classifyMicrobe(Microbe microbe) {
            SiteFit fit1 = evaluateSiteFit(microbe, scenario.site1);
            SiteFit fit2 = evaluateSiteFit(microbe, scenario.site2);

            List<CandidateFit> candidates = new ArrayList<>();
            if (!fit1.hardRejected) {
                candidates.add(new CandidateFit(Category.SITE_1, fit1.score, fit1.confidence, fit1.reasons));
            }
            if (!fit2.hardRejected) {
                candidates.add(new CandidateFit(Category.SITE_2, fit2.score, fit2.confidence, fit2.reasons));
            }

            if (candidates.isEmpty()) {
                return new ClassificationResult(
                        microbe.id,
                        Category.RETURN,
                        0.0,
                        1.0,
                        Collections.singletonList("Rejected by explicit hard constraints for known sites"),
                        Collections.emptyList());
            }

            candidates.sort(Comparator
                    .comparingDouble((CandidateFit c) -> c.score).reversed()
                    .thenComparing(c -> c.category.label));

            CandidateFit top = candidates.get(0);
            List<String> reasons = new ArrayList<>(top.reasons);
            if (candidates.size() > 1) {
                double gap = top.score - candidates.get(1).score;
                if (gap < 0.10 || top.confidence < 0.45) {
                    reasons.add("Low certainty: partial clues or close scores across categories");
                }
            }

            List<Map.Entry<Category, Double>> candidatePairs = new ArrayList<>();
            for (CandidateFit c : candidates) {
                candidatePairs.add(Map.entry(c.category, round4(c.score)));
            }

            if (top.score < 0.35) {
                reasons.add("Weak fit to known site profiles");
                return new ClassificationResult(microbe.id, Category.RETURN, top.score, top.confidence, reasons, candidatePairs);
            }

            return new ClassificationResult(microbe.id, top.category, top.score, top.confidence, reasons, candidatePairs);
        }

        List<ClassificationResult> recommendAll() {
            List<ClassificationResult> results = new ArrayList<>();
            for (Microbe m : scenario.microbes) {
                results.add(classifyMicrobe(m));
            }
            results.sort(Comparator
                    .comparing((ClassificationResult r) -> r.recommended.label)
                    .thenComparing((ClassificationResult r) -> -r.score)
                    .thenComparing(r -> r.microbeId));
            return results;
        }

        List<CombinationScore> rankCombinations(int size) {
            List<CombinationScore> output = new ArrayList<>();
            combinations(scenario.microbes, size, 0, new ArrayList<>(), output);
            output.sort(Comparator
                    .comparingDouble((CombinationScore c) -> c.score).reversed()
                    .thenComparingDouble(c -> c.midpointDistance)
                    .thenComparing(c -> String.join(",", c.microbeIds)));
            return output;
        }

        private void combinations(
                List<Microbe> microbes,
                int size,
                int index,
                List<Microbe> picked,
                List<CombinationScore> output) {
            if (picked.size() == size) {
                output.add(scoreCombination(picked, scenario.site1));
                return;
            }
            for (int i = index; i < microbes.size(); i++) {
                picked.add(microbes.get(i));
                combinations(microbes, size, i + 1, picked, output);
                picked.remove(picked.size() - 1);
            }
        }

        private CombinationScore scoreCombination(List<Microbe> combo, SiteProfile site) {
            int size = combo.size();
            double avgDensity = combo.stream().mapToInt(m -> m.density).average().orElse(0.0);
            double avgEnergy = combo.stream().mapToInt(m -> m.energy).average().orElse(0.0);
            double avgSize = combo.stream().mapToInt(m -> m.size).average().orElse(0.0);

            Map<String, Double> averages = new LinkedHashMap<>();
            averages.put("density", avgDensity);
            averages.put("energy", avgEnergy);
            averages.put("size", avgSize);

            List<Double> distances = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            for (Map.Entry<String, Double> entry : averages.entrySet()) {
                RangeRule rule = site.ranges.get(entry.getKey());
                if (rule == null) {
                    continue;
                }
                double midpoint = rule.midpoint();
                double distance = Math.abs(entry.getValue() - midpoint);
                distances.add(distance);
                reasons.add(String.format(
                        Locale.ROOT,
                        "Average %s=%.2f vs midpoint %.2f (distance %.2f)",
                        entry.getKey(),
                        entry.getValue(),
                        midpoint,
                        distance));
            }

            double midpointDistance = distances.isEmpty() ? 0.0 : average(distances);
            double efficiency = Math.max(0.0, 100.0 * (1.0 - midpointDistance / 9.0));

            Set<String> allTraits = new HashSet<>();
            for (Microbe m : combo) {
                allTraits.addAll(m.traits);
            }

            boolean desiredPresent = site.desiredTraits.isEmpty()
                    || site.desiredTraits.stream().anyMatch(allTraits::contains);
            boolean undesiredAbsent = site.undesiredTraits.stream().noneMatch(allTraits::contains);

            double score = efficiency;
            if (desiredPresent) {
                score += 8.0;
                reasons.add("Desired trait condition satisfied");
            } else {
                score -= 10.0;
                reasons.add("Missing desired trait condition");
            }

            if (undesiredAbsent) {
                score += 5.0;
                reasons.add("Undesired trait condition satisfied");
            } else {
                score -= 15.0;
                reasons.add("Contains undesired trait");
            }

            List<String> ids = new ArrayList<>();
            for (Microbe m : combo) {
                ids.add(m.id);
            }

            return new CombinationScore(ids, avgDensity, avgEnergy, avgSize, midpointDistance, efficiency, desiredPresent, undesiredAbsent, score, reasons);
        }

        private static double average(List<Double> values) {
            if (values.isEmpty()) {
                return 0.0;
            }
            double sum = 0.0;
            for (double v : values) {
                sum += v;
            }
            return sum / values.size();
        }

        private static double round4(double value) {
            return Math.round(value * 10000.0) / 10000.0;
        }

        private static class CandidateFit {
            final Category category;
            final double score;
            final double confidence;
            final List<String> reasons;

            CandidateFit(Category category, double score, double confidence, List<String> reasons) {
                this.category = category;
                this.score = score;
                this.confidence = confidence;
                this.reasons = reasons;
            }
        }
    }

    static Scenario loadScenario(Path path) throws IOException {
        Object parsed = new JsonParser(Files.readString(path, StandardCharsets.UTF_8)).parse();
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Scenario must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed;

        @SuppressWarnings("unchecked")
        List<Object> sitesRaw = (List<Object>) root.get("sites");
        List<SiteProfile> sites = new ArrayList<>();
        for (Object obj : sitesRaw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawSite = (Map<String, Object>) obj;
            @SuppressWarnings("unchecked")
            Map<String, Object> rawRanges = (Map<String, Object>) rawSite.getOrDefault("ranges", Map.of());
            Map<String, RangeRule> ranges = new HashMap<>();
            for (Map.Entry<String, Object> e : rawRanges.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rr = (Map<String, Object>) e.getValue();
                ranges.put(e.getKey(), new RangeRule(toInt(rr.get("min")), toInt(rr.get("max"))));
            }

            sites.add(new SiteProfile(
                    toStr(rawSite.get("name")),
                    ranges,
                    toStringList(rawSite.getOrDefault("desiredTraits", List.of())),
                    toStringList(rawSite.getOrDefault("undesiredTraits", List.of())),
                    toBool(rawSite.getOrDefault("isCurrent", false)),
                    toBool(rawSite.getOrDefault("hardRejectUndesired", true))));
        }

        SiteProfile site1 = sites.stream().filter(s -> "Site 1".equals(s.name)).findFirst().orElse(sites.get(0));
        SiteProfile site2 = sites.stream().filter(s -> "Site 2".equals(s.name)).findFirst().orElse(sites.get(1));

        @SuppressWarnings("unchecked")
        List<Object> microbesRaw = (List<Object>) root.get("microbes");
        List<Microbe> microbes = new ArrayList<>();
        for (Object obj : microbesRaw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) obj;
            microbes.add(new Microbe(
                    toStr(m.get("id")),
                    toStr(m.get("name")),
                    toInt(m.get("density")),
                    toInt(m.get("energy")),
                    toInt(m.get("size")),
                    toStringList(m.getOrDefault("traits", List.of()))));
        }

        return new Scenario(
                toStr(root.getOrDefault("name", "unnamed")),
                microbes,
                site1,
                site2,
                toInt(root.getOrDefault("combinationSize", 3)));
    }

    private static int toInt(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return Integer.parseInt(String.valueOf(v));
    }

    private static String toStr(Object v) {
        return String.valueOf(v);
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object v) {
        List<Object> values = (List<Object>) v;
        List<String> out = new ArrayList<>();
        for (Object x : values) {
            out.add(String.valueOf(x));
        }
        return out;
    }

    static int runSelfTest() {
        RangeRule rr = new RangeRule(6, 8);
        if (Math.abs(Engine.rangeFitScore(7, rr) - 1.0) > 1e-9) {
            return 1;
        }
        if (Engine.rangeFitScore(3, rr) >= 1.0) {
            return 2;
        }

        SiteProfile site = new SiteProfile(
                "Site 1",
                Map.of("density", rr),
                List.of("Good"),
                List.of("Bad"),
                true,
                true);
        SiteProfile site2 = new SiteProfile("Site 2", Map.of(), List.of(), List.of(), false, false);
        Microbe m = new Microbe("x", "x", 7, 3, 9, List.of("Bad"));
        Scenario s = new Scenario("t", List.of(m), site, site2, 1);
        Engine e = new Engine(s);
        SiteFit fit = e.evaluateSiteFit(m, site);
        if (!fit.hardRejected) {
            return 3;
        }
        return 0;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            int code = runSelfTest();
            if (code == 0) {
                System.out.println("SELF_TEST_OK");
            } else {
                System.out.println("SELF_TEST_FAIL:" + code);
            }
            System.exit(code);
        }

        String scenarioPath = args.length > 0 ? args[0] : DEFAULT_SCENARIO;
        int top = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        Scenario scenario = loadScenario(Paths.get(scenarioPath));
        Engine engine = new Engine(scenario);

        System.out.println("Scenario: " + scenario.name);
        System.out.println("Classification recommendations:");

        Map<String, Microbe> byId = new HashMap<>();
        for (Microbe m : scenario.microbes) {
            byId.put(m.id, m);
        }

        for (ClassificationResult r : engine.recommendAll()) {
            Microbe m = byId.get(r.microbeId);
            StringBuilder candidate = new StringBuilder();
            for (int i = 0; i < r.candidates.size(); i++) {
                Map.Entry<Category, Double> c = r.candidates.get(i);
                if (i > 0) {
                    candidate.append(", ");
                }
                candidate.append(c.getKey().label).append(":").append(String.format(Locale.ROOT, "%.2f", c.getValue()));
            }
            System.out.printf(
                    Locale.ROOT,
                    "- %s (%s) -> %s [score=%.2f, confidence=%.2f]%n",
                    m.name,
                    m.id,
                    r.recommended.label,
                    r.score,
                    r.confidence);
            System.out.println("  candidates: " + (candidate.length() == 0 ? "none" : candidate));
            if (!r.reasons.isEmpty()) {
                System.out.println("  reason: " + r.reasons.get(0));
            }
        }

        System.out.println();
        System.out.println("Top " + top + " combinations for Site 1:");
        List<CombinationScore> combos = engine.rankCombinations(scenario.combinationSize);
        for (int i = 0; i < Math.min(top, combos.size()); i++) {
            CombinationScore c = combos.get(i);
            System.out.printf(
                    Locale.ROOT,
                    "%d. %s score=%.2f efficiency=%.2f%%%n",
                    i + 1,
                    String.join(", ", c.microbeIds),
                    c.score,
                    c.efficiencyPercent);
            if (!c.reasons.isEmpty()) {
                System.out.println("   " + c.reasons.get(0));
            }
        }
    }

    /** Lightweight JSON parser for scenario loading (standard library only). */
    static final class JsonParser {
        private final String src;
        private int idx;

        JsonParser(String src) {
            this.src = src;
            this.idx = 0;
        }

        Object parse() {
            skipWs();
            Object value = parseValue();
            skipWs();
            if (idx != src.length()) {
                throw new IllegalArgumentException("Trailing content at position " + idx);
            }
            return value;
        }

        private Object parseValue() {
            skipWs();
            if (idx >= src.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = src.charAt(idx);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return true;
                case 'f':
                    expect("false");
                    return false;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || Character.isDigit(c)) {
                        return parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character '" + c + "' at " + idx);
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> out = new LinkedHashMap<>();
            idx++; // {
            skipWs();
            if (peek('}')) {
                idx++;
                return out;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                out.put(key, value);
                skipWs();
                if (peek('}')) {
                    idx++;
                    break;
                }
                expect(',');
            }
            return out;
        }

        private List<Object> parseArray() {
            List<Object> out = new ArrayList<>();
            idx++; // [
            skipWs();
            if (peek(']')) {
                idx++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWs();
                if (peek(']')) {
                    idx++;
                    break;
                }
                expect(',');
            }
            return out;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (idx < src.length()) {
                char c = src.charAt(idx++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (idx >= src.length()) {
                        throw new IllegalArgumentException("Invalid escape at end of input");
                    }
                    char esc = src.charAt(idx++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (idx + 4 > src.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            String hex = src.substring(idx, idx + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            idx += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape \\" + esc + " at " + idx);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Number parseNumber() {
            int start = idx;
            if (peek('-')) {
                idx++;
            }
            while (idx < src.length() && Character.isDigit(src.charAt(idx))) {
                idx++;
            }
            if (idx < src.length() && src.charAt(idx) == '.') {
                idx++;
                while (idx < src.length() && Character.isDigit(src.charAt(idx))) {
                    idx++;
                }
            }
            String token = src.substring(start, idx);
            if (token.contains(".")) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        }

        private void expect(char c) {
            skipWs();
            if (idx >= src.length() || src.charAt(idx) != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at position " + idx);
            }
            idx++;
        }

        private void expect(String token) {
            if (!src.startsWith(token, idx)) {
                throw new IllegalArgumentException("Expected '" + token + "' at position " + idx);
            }
            idx += token.length();
        }

        private boolean peek(char c) {
            return idx < src.length() && src.charAt(idx) == c;
        }

        private void skipWs() {
            while (idx < src.length()) {
                char c = src.charAt(idx);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    idx++;
                } else {
                    break;
                }
            }
        }
    }
}
