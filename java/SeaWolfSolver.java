import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class SeaWolfSolver {
    private static final Path DEFAULT_SCENARIO = Path.of("data", "sample_scenario.json");

    static final class Range {
        final double minValue;
        final double maxValue;

        Range(double minValue, double maxValue) {
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        double midpoint() {
            return (minValue + maxValue) / 2.0;
        }
    }

    static final class Microbe {
        final String name;
        final double density;
        final double energy;
        final double size;
        final List<String> traits;
        final int inputIndex;

        Microbe(String name, double density, double energy, double size, List<String> traits, int inputIndex) {
            this.name = name;
            this.density = density;
            this.energy = energy;
            this.size = size;
            this.traits = traits;
            this.inputIndex = inputIndex;
        }
    }

    static final class SiteProfile {
        final String name;
        final Map<String, Range> ranges;
        final String desiredTrait;
        final String undesiredTrait;

        SiteProfile(String name, Map<String, Range> ranges, String desiredTrait, String undesiredTrait) {
            this.name = name;
            this.ranges = ranges;
            this.desiredTrait = desiredTrait;
            this.undesiredTrait = undesiredTrait;
        }
    }

    static final class CategorizationResult {
        final String category;
        final double score;
        final int violations;
        final int unknownConstraints;
        final String explanation;

        CategorizationResult(String category, double score, int violations, int unknownConstraints, String explanation) {
            this.category = category;
            this.score = score;
            this.violations = violations;
            this.unknownConstraints = unknownConstraints;
            this.explanation = explanation;
        }
    }

    static final class TreatmentResult {
        final List<String> microbes;
        final double averageDensity;
        final double averageEnergy;
        final double averageSize;
        final double attributeScore;
        final double traitScore;
        final double score;
        final int violations;

        TreatmentResult(List<String> microbes, double averageDensity, double averageEnergy, double averageSize,
                        double attributeScore, double traitScore, double score, int violations) {
            this.microbes = microbes;
            this.averageDensity = averageDensity;
            this.averageEnergy = averageEnergy;
            this.averageSize = averageSize;
            this.attributeScore = attributeScore;
            this.traitScore = traitScore;
            this.score = score;
            this.violations = violations;
        }
    }

    static final class Scenario {
        final List<Microbe> microbes;
        final Map<String, SiteProfile> sites;
        final List<String> initialPool;
        final List<String> availableAdditional;
        final int additionalPickCount;
        final int treatmentPickCount;
        final String targetSite;
        final double attributeWeight;
        final double traitWeight;

        Scenario(List<Microbe> microbes, Map<String, SiteProfile> sites, List<String> initialPool,
                 List<String> availableAdditional, int additionalPickCount, int treatmentPickCount,
                 String targetSite, double attributeWeight, double traitWeight) {
            this.microbes = microbes;
            this.sites = sites;
            this.initialPool = initialPool;
            this.availableAdditional = availableAdditional;
            this.additionalPickCount = additionalPickCount;
            this.treatmentPickCount = treatmentPickCount;
            this.targetSite = targetSite;
            this.attributeWeight = attributeWeight;
            this.traitWeight = traitWeight;
        }
    }

    private static final class SiteScore {
        final String siteName;
        final double score;
        final int violations;
        final int unknownConstraints;
        final String explanation;

        SiteScore(String siteName, double score, int violations, int unknownConstraints, String explanation) {
            this.siteName = siteName;
            this.score = score;
            this.violations = violations;
            this.unknownConstraints = unknownConstraints;
            this.explanation = explanation;
        }
    }

    private static double[] closeness(double value, Range range) {
        double span = range.maxValue - range.minValue;
        if (span <= 0) {
            boolean inside = value == range.minValue;
            return new double[]{inside ? 1.0 : 0.0, inside ? 0.0 : 1.0};
        }
        double half = span / 2.0;
        double distance = Math.abs(value - range.midpoint());
        double score = Math.max(0.0, 1.0 - (distance / half));
        boolean outside = value < range.minValue || value > range.maxValue;
        if (outside) {
            score = 0.0;
        }
        return new double[]{score, outside ? 1.0 : 0.0};
    }

    static Scenario loadScenario(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        Object parsed = new JsonParser(json).parse();
        Map<String, Object> root = asObject(parsed);

        List<Map<String, Object>> microbeRows = asObjectList(root.get("microbes"));
        List<Microbe> microbes = new ArrayList<>();
        for (int i = 0; i < microbeRows.size(); i++) {
            Map<String, Object> row = microbeRows.get(i);
            List<String> traits = asStringList(row.getOrDefault("traits", List.of()));
            microbes.add(new Microbe(
                    asString(row.get("name")),
                    asDouble(row.get("density")),
                    asDouble(row.get("energy")),
                    asDouble(row.get("size")),
                    traits,
                    i
            ));
        }

        Map<String, SiteProfile> sites = new LinkedHashMap<>();
        Map<String, Object> siteRows = asObject(root.get("sites"));
        for (String siteName : siteRows.keySet()) {
            Map<String, Object> siteRow = asObject(siteRows.get(siteName));
            Map<String, Range> ranges = new HashMap<>();
            Map<String, Object> rangeRows = asObject(siteRow.getOrDefault("ranges", Map.of()));
            for (String attr : rangeRows.keySet()) {
                List<Object> values = asList(rangeRows.get(attr));
                ranges.put(attr, new Range(asDouble(values.get(0)), asDouble(values.get(1))));
            }
            sites.put(siteName, new SiteProfile(
                    siteName,
                    ranges,
                    asNullableString(siteRow.get("desired_trait")),
                    asNullableString(siteRow.get("undesired_trait"))
            ));
        }

        Map<String, Object> prospect = asObject(root.get("prospect"));
        Map<String, Object> weights = asObject(root.getOrDefault("weights", Map.of()));

        return new Scenario(
                microbes,
                sites,
                asStringList(prospect.getOrDefault("initial_pool", List.of())),
                asStringList(prospect.getOrDefault("available_additional", List.of())),
                (int) asDouble(prospect.getOrDefault("additional_pick_count", 4.0)),
                (int) asDouble(prospect.getOrDefault("treatment_pick_count", 3.0)),
                asString(prospect.getOrDefault("target_site", "Site 1")),
                asDouble(weights.getOrDefault("attribute_weight", 0.7)),
                asDouble(weights.getOrDefault("trait_weight", 0.3))
        );
    }

    private static SiteScore scoreSite(Microbe microbe, SiteProfile site) {
        List<Double> attrScores = new ArrayList<>();
        int rangeViolations = 0;
        for (String attr : List.of("density", "energy", "size")) {
            Range range = site.ranges.get(attr);
            if (range == null) {
                continue;
            }
            double value = switch (attr) {
                case "density" -> microbe.density;
                case "energy" -> microbe.energy;
                default -> microbe.size;
            };
            double[] scored = closeness(value, range);
            attrScores.add(scored[0]);
            rangeViolations += (int) scored[1];
        }
        int unknownConstraints = 3 - site.ranges.size();
        double attrComponent = attrScores.isEmpty() ? 0.5 : attrScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);

        double desiredComponent = 0.0;
        boolean desiredMissing = false;
        if (site.desiredTrait != null) {
            desiredComponent = microbe.traits.contains(site.desiredTrait) ? 1.0 : 0.0;
            desiredMissing = desiredComponent == 0.0;
        }

        boolean undesiredPresent = site.undesiredTrait != null && microbe.traits.contains(site.undesiredTrait);
        double undesiredComponent = undesiredPresent ? 0.0 : 1.0;

        double score = 0.6 * attrComponent + 0.2 * desiredComponent + 0.2 * undesiredComponent;
        int violations = rangeViolations + (desiredMissing ? 1 : 0) + (undesiredPresent ? 1 : 0);
        if (undesiredPresent) {
            score = 0.0;
        }

        String explanation = String.format(Locale.ROOT,
                "attr=%.3f, desired=%.3f, undesired_ok=%.3f, range_violations=%d, unknown_constraints=%d",
                attrComponent, desiredComponent, undesiredComponent, rangeViolations, unknownConstraints);
        return new SiteScore(site.name, score, violations, unknownConstraints, explanation);
    }

    static CategorizationResult recommendCategory(Microbe microbe, Scenario scenario) {
        List<SiteScore> scores = new ArrayList<>();
        for (String siteName : new TreeSet<>(scenario.sites.keySet())) {
            scores.add(scoreSite(microbe, scenario.sites.get(siteName)));
        }
        scores.sort(Comparator.comparingDouble((SiteScore s) -> s.score).reversed()
                .thenComparingInt(s -> s.violations)
                .thenComparing(s -> s.siteName));
        SiteScore best = scores.get(0);

        if (best.score < 0.45 || best.violations >= 2) {
            return new CategorizationResult(
                    "Return", best.score, best.violations, best.unknownConstraints,
                    "Return recommended: insufficient fit. Best site " + best.siteName + ": " + best.explanation
            );
        }

        return new CategorizationResult(
                best.siteName, best.score, best.violations, best.unknownConstraints,
                "Best fit " + best.siteName + ": " + best.explanation
        );
    }

    static TreatmentResult evaluateTreatment(List<Microbe> microbes, SiteProfile site, double attributeWeight, double traitWeight) {
        if (microbes.size() != 3) {
            throw new IllegalArgumentException("Treatment must contain exactly 3 microbes");
        }

        double avgDensity = microbes.stream().mapToDouble(m -> m.density).average().orElse(0.0);
        double avgEnergy = microbes.stream().mapToDouble(m -> m.energy).average().orElse(0.0);
        double avgSize = microbes.stream().mapToDouble(m -> m.size).average().orElse(0.0);

        List<Double> attrScores = new ArrayList<>();
        int violations = 0;
        Map<String, Double> avgMap = Map.of("density", avgDensity, "energy", avgEnergy, "size", avgSize);
        for (String attr : avgMap.keySet()) {
            Range range = site.ranges.get(attr);
            if (range == null) {
                continue;
            }
            double[] scored = closeness(avgMap.get(attr), range);
            attrScores.add(scored[0]);
            violations += (int) scored[1];
        }
        double attrScore = attrScores.isEmpty() ? 0.5 : attrScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);

        Set<String> traits = new TreeSet<>();
        for (Microbe microbe : microbes) {
            traits.addAll(microbe.traits);
        }
        List<Double> traitComponents = new ArrayList<>();
        if (site.desiredTrait != null) {
            boolean desiredPresent = traits.contains(site.desiredTrait);
            traitComponents.add(desiredPresent ? 1.0 : 0.0);
            if (!desiredPresent) {
                violations++;
            }
        }
        if (site.undesiredTrait != null) {
            boolean undesiredPresent = traits.contains(site.undesiredTrait);
            traitComponents.add(undesiredPresent ? 0.0 : 1.0);
            if (undesiredPresent) {
                violations++;
            }
        }
        double traitScore = traitComponents.isEmpty() ? 1.0 : traitComponents.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        double weighted = (attributeWeight * attrScore) + (traitWeight * traitScore);
        double score = Math.round(Math.max(0.0, Math.min(1.0, weighted)) * 10000.0) / 100.0;

        List<String> names = new ArrayList<>();
        for (Microbe microbe : microbes) {
            names.add(microbe.name);
        }
        names.sort(String::compareToIgnoreCase);

        return new TreatmentResult(names, avgDensity, avgEnergy, avgSize, attrScore, traitScore, score, violations);
    }

    static List<TreatmentResult> rankTreatments(List<Microbe> pool, SiteProfile site, double attributeWeight, double traitWeight) {
        if (pool.size() < 3) {
            return List.of();
        }
        List<TreatmentResult> results = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            for (int j = i + 1; j < pool.size(); j++) {
                for (int k = j + 1; k < pool.size(); k++) {
                    results.add(evaluateTreatment(List.of(pool.get(i), pool.get(j), pool.get(k)), site, attributeWeight, traitWeight));
                }
            }
        }
        results.sort(Comparator.comparingDouble((TreatmentResult r) -> r.score).reversed()
                .thenComparingInt(r -> r.violations)
                .thenComparing(r -> String.join("|", r.microbes).toLowerCase(Locale.ROOT)));
        return results;
    }

    static List<Map<String, Object>> rankAdditionalProspects(Scenario scenario, String siteName) {
        SiteProfile site = scenario.sites.get(siteName == null ? scenario.targetSite : siteName);
        Map<String, Microbe> lookup = microbeLookup(scenario);
        List<Microbe> initial = lookupNames(lookup, scenario.initialPool);
        List<Microbe> available = lookupNames(lookup, scenario.availableAdditional);

        if (scenario.additionalPickCount > available.size()) {
            throw new IllegalArgumentException("Not enough available microbes to satisfy additional pick count");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        List<int[]> combos = combinations(available.size(), scenario.additionalPickCount);
        for (int[] indexes : combos) {
            List<Microbe> additions = new ArrayList<>();
            for (int idx : indexes) {
                additions.add(available.get(idx));
            }
            List<Microbe> pool = new ArrayList<>(initial);
            pool.addAll(additions);
            List<TreatmentResult> ranked = rankTreatments(pool, site, scenario.attributeWeight, scenario.traitWeight);
            if (ranked.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            List<String> additionNames = new ArrayList<>();
            for (Microbe microbe : additions) {
                additionNames.add(microbe.name);
            }
            row.put("additional_microbes", additionNames);
            row.put("best_treatment", ranked.get(0));
            row.put("top_treatments", ranked);
            rows.add(row);
        }

        rows.sort(Comparator
                .comparingDouble((Map<String, Object> row) -> ((TreatmentResult) row.get("best_treatment")).score).reversed()
                .thenComparingInt(row -> ((TreatmentResult) row.get("best_treatment")).violations)
                .thenComparing(row -> String.join("|", asStringList(row.get("additional_microbes"))).toLowerCase(Locale.ROOT)));
        return rows;
    }

    private static List<int[]> combinations(int n, int r) {
        List<int[]> result = new ArrayList<>();
        int[] idx = new int[r];
        for (int i = 0; i < r; i++) {
            idx[i] = i;
        }
        while (true) {
            result.add(Arrays.copyOf(idx, idx.length));
            int i;
            for (i = r - 1; i >= 0; i--) {
                if (idx[i] != i + n - r) {
                    break;
                }
            }
            if (i < 0) {
                break;
            }
            idx[i]++;
            for (int j = i + 1; j < r; j++) {
                idx[j] = idx[j - 1] + 1;
            }
        }
        return result;
    }

    private static Map<String, Microbe> microbeLookup(Scenario scenario) {
        Map<String, Microbe> map = new HashMap<>();
        for (Microbe microbe : scenario.microbes) {
            map.put(microbe.name, microbe);
        }
        return map;
    }

    private static List<Microbe> lookupNames(Map<String, Microbe> lookup, List<String> names) {
        List<Microbe> microbes = new ArrayList<>();
        for (String name : names) {
            Microbe microbe = lookup.get(name);
            if (microbe == null) {
                throw new IllegalArgumentException("Unknown microbe: " + name);
            }
            microbes.add(microbe);
        }
        return microbes;
    }

    private static String summary(Scenario scenario) {
        return "Sea Wolf-style reconstruction summary\n"
                + "Microbes: " + scenario.microbes.size() + "\n"
                + "Sites: " + String.join(", ", scenario.sites.keySet()) + "\n"
                + "Prospect picks: +" + scenario.additionalPickCount + ", then " + scenario.treatmentPickCount + " for treatment";
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            runSelfTest();
            return;
        }

        Path scenarioPath = DEFAULT_SCENARIO;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--scenario".equals(args[i]) && i + 1 < args.length) {
                scenarioPath = Path.of(args[++i]);
            } else {
                rest.add(args[i]);
            }
        }

        Scenario scenario = loadScenario(scenarioPath);
        String command = rest.isEmpty() ? "summary" : rest.get(0);

        switch (command) {
            case "summary" -> System.out.println(summary(scenario));
            case "categorize" -> printCategorize(scenario, rest);
            case "prospects" -> printProspects(scenario, rest);
            case "optimize" -> printOptimize(scenario, rest);
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private static void printCategorize(Scenario scenario, List<String> args) {
        String filter = null;
        for (int i = 1; i < args.size(); i++) {
            if ("--microbe".equals(args.get(i)) && i + 1 < args.size()) {
                filter = args.get(++i);
            }
        }

        for (Microbe microbe : scenario.microbes) {
            if (filter != null && !microbe.name.equals(filter)) {
                continue;
            }
            CategorizationResult result = recommendCategory(microbe, scenario);
            System.out.printf(Locale.ROOT, "%s: %s | score=%.3f | violations=%d | unknown=%d%n  %s%n",
                    microbe.name, result.category, result.score, result.violations, result.unknownConstraints, result.explanation);
        }
    }

    private static void printProspects(Scenario scenario, List<String> args) {
        String site = null;
        int top = 5;
        for (int i = 1; i < args.size(); i++) {
            if ("--site".equals(args.get(i)) && i + 1 < args.size()) {
                site = args.get(++i);
            } else if ("--top".equals(args.get(i)) && i + 1 < args.size()) {
                top = Integer.parseInt(args.get(++i));
            }
        }

        List<Map<String, Object>> ranked = rankAdditionalProspects(scenario, site);
        for (int i = 0; i < Math.min(top, ranked.size()); i++) {
            Map<String, Object> row = ranked.get(i);
            TreatmentResult best = (TreatmentResult) row.get("best_treatment");
            System.out.printf(Locale.ROOT, "Add %s -> best treatment %s score=%.2f%% violations=%d%n",
                    row.get("additional_microbes"), best.microbes, best.score, best.violations);
        }
    }

    private static void printOptimize(Scenario scenario, List<String> args) {
        String site = null;
        String poolCsv = null;
        int top = 5;
        for (int i = 1; i < args.size(); i++) {
            if ("--site".equals(args.get(i)) && i + 1 < args.size()) {
                site = args.get(++i);
            } else if ("--pool".equals(args.get(i)) && i + 1 < args.size()) {
                poolCsv = args.get(++i);
            } else if ("--top".equals(args.get(i)) && i + 1 < args.size()) {
                top = Integer.parseInt(args.get(++i));
            }
        }

        SiteProfile profile = scenario.sites.get(site == null ? scenario.targetSite : site);
        Map<String, Microbe> lookup = microbeLookup(scenario);
        List<String> poolNames = scenario.initialPool;
        if (poolCsv != null && !poolCsv.isBlank()) {
            poolNames = new ArrayList<>();
            for (String raw : poolCsv.split(",")) {
                String name = raw.trim();
                if (!name.isEmpty()) {
                    poolNames.add(name);
                }
            }
        }
        List<Microbe> pool = lookupNames(lookup, poolNames);

        List<TreatmentResult> ranked = rankTreatments(pool, profile, scenario.attributeWeight, scenario.traitWeight);
        for (int i = 0; i < Math.min(top, ranked.size()); i++) {
            TreatmentResult r = ranked.get(i);
            System.out.printf(Locale.ROOT,
                    "%s: score=%.2f%% attr=%.3f trait=%.3f avg=(%.2f,%.2f,%.2f)%n",
                    r.microbes, r.score, r.attributeScore, r.traitScore,
                    r.averageDensity, r.averageEnergy, r.averageSize);
        }
    }

    static void runSelfTest() throws Exception {
        Scenario scenario = loadScenario(DEFAULT_SCENARIO);
        if (scenario.microbes.size() != 10) {
            throw new IllegalStateException("Expected 10 microbes");
        }

        Microbe drava = scenario.microbes.stream()
                .filter(m -> m.name.equals("Drava Volvox"))
                .findFirst()
                .orElseThrow();
        CategorizationResult cat = recommendCategory(drava, scenario);
        if (cat.category == null || cat.category.isBlank()) {
            throw new IllegalStateException("Categorization failed");
        }

        SiteProfile site1 = scenario.sites.get("Site 1");
        List<TreatmentResult> treatments = rankTreatments(scenario.microbes, site1, scenario.attributeWeight, scenario.traitWeight);
        if (treatments.isEmpty()) {
            throw new IllegalStateException("No treatment rankings generated");
        }
        if (treatments.get(0).score < treatments.get(treatments.size() - 1).score) {
            throw new IllegalStateException("Treatment sorting invalid");
        }

        List<Map<String, Object>> prospects = rankAdditionalProspects(scenario, null);
        if (prospects.isEmpty()) {
            throw new IllegalStateException("No prospect rankings generated");
        }

        boolean invalidRaised = false;
        try {
            evaluateTreatment(List.of(scenario.microbes.get(0), scenario.microbes.get(1)), site1, 0.7, 0.3);
        } catch (IllegalArgumentException expected) {
            invalidRaised = true;
        }
        if (!invalidRaised) {
            throw new IllegalStateException("Invalid combination check failed");
        }

        System.out.println("Java self-test passed");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object obj) {
        if (!(obj instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        return (List<Object>) list;
    }

    private static List<Map<String, Object>> asObjectList(Object obj) {
        List<Object> list = asList(obj);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            out.add(asObject(item));
        }
        return out;
    }

    private static String asString(Object obj) {
        if (!(obj instanceof String s)) {
            throw new IllegalArgumentException("Expected string value");
        }
        return s;
    }

    private static String asNullableString(Object obj) {
        if (obj == null) {
            return null;
        }
        return asString(obj);
    }

    private static double asDouble(Object obj) {
        if (obj instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("Expected numeric value");
    }

    private static List<String> asStringList(Object obj) {
        if (obj instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(asString(item));
            }
            return out;
        }
        throw new IllegalArgumentException("Expected string array");
    }

    private static final class JsonParser {
        private final String input;
        private int index;

        JsonParser(String input) {
            this.input = Objects.requireNonNull(input);
        }

        Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (index != input.length()) {
                throw new IllegalArgumentException("Unexpected trailing JSON content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char ch = input.charAt(index);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            skipWhitespace();
            Map<String, Object> map = new LinkedHashMap<>();
            if (peek('}')) {
                expect('}');
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            skipWhitespace();
            List<Object> list = new ArrayList<>();
            if (peek(']')) {
                expect(']');
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (index < input.length()) {
                char ch = input.charAt(index++);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\') {
                    if (index >= input.length()) {
                        throw new IllegalArgumentException("Invalid escape");
                    }
                    char esc = input.charAt(index++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (index + 4 > input.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            String hex = input.substring(index, index + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        }
                        default -> throw new IllegalArgumentException("Unknown escape sequence: \\" + esc);
                    }
                } else {
                    sb.append(ch);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw new IllegalArgumentException("Invalid token at position " + index);
            }
            index += literal.length();
            return value;
        }

        private Number parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (peek('.')) {
                index++;
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            if (peek('e') || peek('E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            String token = input.substring(start, index);
            if (token.isEmpty() || "-".equals(token)) {
                throw new IllegalArgumentException("Invalid number at " + start);
            }
            return Double.parseDouble(token);
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + index);
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < input.length() && input.charAt(index) == expected;
        }
    }
}
