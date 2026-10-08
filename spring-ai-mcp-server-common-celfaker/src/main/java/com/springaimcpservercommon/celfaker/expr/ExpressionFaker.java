package com.springaimcpservercommon.celfaker.expr;

import com.springaimcpservercommon.celfaker.values.AttributeValueMap;
import com.springaimcpservercommon.celfaker.values.AttributeValues;
import com.springaimcpservercommon.ruleengine.cel.CompiledExpression;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.cel.RuleCompilationException;
import com.springaimcpservercommon.ruleengine.model.DataType;
import com.springaimcpservercommon.ruleengine.model.Parameter;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fakes valid CEL expressions for the parameters of a library: every operator and macro that makes sense for the
 * parameter's data type, with thresholds taken from the attribute value map, then combinations across parameters.
 *
 * <p>Every candidate is compiled against the {@link ParameterLibrary}; what does not type-check is reported in
 * {@link Result#rejected()} and never returned, so the output is valid by construction. Deterministic for a seed.
 */
public final class ExpressionFaker {

    /**
     * Generation options.
     *
     * @param categories        features to generate (empty = all)
     * @param maxPerParameter   cap on single-parameter expressions per parameter, spread across categories (0 = no cap)
     * @param combined          number of cross-parameter expressions to build (0 = none)
     */
    public record Options(Set<Category> categories, int maxPerParameter, int combined) {

        /**
         * Defaults: every category, no cap, 30 combinations.
         *
         * @return options
         */
        public static Options defaults() {
            return new Options(Set.of(), 0, 30);
        }
    }

    /**
     * Output of {@link #generate(Options)}.
     *
     * @param expressions accepted expressions
     * @param rejected    candidates the CEL checker refused, with its message
     */
    public record Result(List<GeneratedExpression> expressions, List<String> rejected) {
    }

    private record Draft(Category category, String expression, String description) {
    }

    private final ParameterLibrary library;
    private final AttributeValueMap values;
    private final long seed;

    /**
     * Creates a faker.
     *
     * @param library parameter library the expressions must type-check against
     * @param values  value map providing thresholds and members
     * @param seed    seed for reproducible output
     */
    public ExpressionFaker(ParameterLibrary library, AttributeValueMap values, long seed) {
        this.library = library;
        this.values = values;
        this.seed = seed;
    }

    /**
     * Generates expressions.
     *
     * @param options options
     * @return accepted and rejected expressions
     */
    public Result generate(Options options) {
        Random rnd = new Random(seed);
        List<GeneratedExpression> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, List<GeneratedExpression>> singles = new LinkedHashMap<>();

        for (Parameter p : library.parameters()) {
            AttributeValues av = values.find(p.celName()).orElse(null);
            if (av == null) {
                continue;
            }
            List<GeneratedExpression> ok = new ArrayList<>();
            for (Draft d : drafts(p, av)) {
                if (!options.categories().isEmpty() && !options.categories().contains(d.category())) {
                    continue;
                }
                if (!seen.add(d.expression())) {
                    continue;
                }
                accept(d, ok, rejected);
            }
            List<GeneratedExpression> kept = options.maxPerParameter() > 0
                    ? spread(ok, options.maxPerParameter()) : ok;
            singles.put(p.celName(), kept);
            accepted.addAll(kept);
        }
        if (options.combined() > 0 && (options.categories().isEmpty()
                || options.categories().contains(Category.CROSS_PARAMETER))) {
            for (Draft d : cross(singles, rnd, options.combined())) {
                if (seen.add(d.expression())) {
                    accept(d, accepted, rejected);
                }
            }
        }
        return new Result(accepted, rejected);
    }

    private void accept(Draft d, List<GeneratedExpression> into, List<String> rejected) {
        try {
            CompiledExpression c = library.compileBoolean(d.expression());
            into.add(new GeneratedExpression(d.expression(), d.category(),
                    c.referenced().stream().map(Parameter::celName).toList(), d.description()));
        } catch (RuleCompilationException e) {
            rejected.add(d.expression() + "  --  " + firstLine(e.getMessage()));
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static List<GeneratedExpression> spread(List<GeneratedExpression> all, int max) {
        Map<Category, List<GeneratedExpression>> byCat = new EnumMap<>(Category.class);
        all.forEach(e -> byCat.computeIfAbsent(e.category(), k -> new ArrayList<>()).add(e));
        List<GeneratedExpression> out = new ArrayList<>();
        for (int round = 0; out.size() < max; round++) {
            boolean any = false;
            for (List<GeneratedExpression> l : byCat.values()) {
                if (round < l.size() && out.size() < max) {
                    out.add(l.get(round));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ drafts per type

    private List<Draft> drafts(Parameter p, AttributeValues av) {
        Drafts d = new Drafts(p.celName());
        switch (p.dataType()) {
            case INT -> numeric(d, av, true);
            case DOUBLE -> numeric(d, av, false);
            case STRING -> string(d, av);
            case BOOL -> bool(d);
            case TIMESTAMP -> timestamp(d, av);
            case DURATION -> duration(d, av);
            case LIST_STRING -> list(d, av, DataType.STRING);
            case LIST_INT -> list(d, av, DataType.INT);
            case LIST_DOUBLE -> list(d, av, DataType.DOUBLE);
            case MAP -> map(d, av);
            case ANY -> any(d, av);
        }
        return d.list;
    }

    private static final class Drafts {
        final String p;
        final List<Draft> list = new ArrayList<>();

        Drafts(String p) {
            this.p = p;
        }

        void add(Category c, String expression, String description) {
            list.add(new Draft(c, expression, description));
        }
    }

    private static String join(List<String> lits) {
        return lits.stream().collect(Collectors.joining(", ", "[", "]"));
    }

    private static <T> List<T> firstN(List<T> l, int n) {
        return l.subList(0, Math.min(n, l.size()));
    }

    // -- INT / DOUBLE

    private void numeric(Drafts d, AttributeValues av, boolean isInt) {
        String p = d.p;
        List<Double> nums = av.valid().stream().filter(v -> v instanceof Number).map(v -> ((Number) v).doubleValue())
                .distinct().sorted().toList();
        if (nums.isEmpty()) {
            return;
        }
        Function<Double, String> lit = x -> isInt ? Long.toString(Math.round(x)) : Literals.dbl(x);
        double s = av.sample() instanceof Number n ? n.doubleValue() : nums.get(nums.size() / 2);
        double lo = nums.getFirst();
        double hi = nums.getLast();
        double mid = nums.get(nums.size() / 2);
        String k = isInt ? "5" : "0.5";
        String two = isInt ? "2" : "2.0";
        String zero = isInt ? "0" : "0.0";
        List<String> members = firstN(nums, 3).stream().map(lit).toList();
        String list3 = join(members);

        d.add(Category.COMPARISON, p + " == " + lit.apply(s), p + " equals " + lit.apply(s));
        d.add(Category.COMPARISON, p + " != " + lit.apply(s), p + " differs from " + lit.apply(s));
        d.add(Category.COMPARISON, p + " > " + lit.apply(mid), p + " is greater than " + lit.apply(mid));
        d.add(Category.COMPARISON, p + " >= " + lit.apply(mid), p + " is at least " + lit.apply(mid));
        d.add(Category.COMPARISON, p + " < " + lit.apply(mid), p + " is below " + lit.apply(mid));
        d.add(Category.COMPARISON, p + " <= " + lit.apply(mid), p + " is at most " + lit.apply(mid));
        d.add(Category.LOGICAL, p + " >= " + lit.apply(lo) + " && " + p + " <= " + lit.apply(hi), p + " is within [" + lit.apply(lo) + ", " + lit.apply(hi) + "]");
        d.add(Category.LOGICAL, p + " < " + lit.apply(lo) + " || " + p + " > " + lit.apply(hi), p + " is outside the valid range");
        d.add(Category.LOGICAL, "!(" + p + " > " + lit.apply(hi) + ")", p + " does not exceed " + lit.apply(hi));
        d.add(Category.LOGICAL, "!(" + p + " == " + lit.apply(s) + ") && " + p + " != " + zero, p + " is neither the sample nor zero");
        d.add(Category.MEMBERSHIP, p + " in " + list3, p + " is one of " + list3);
        d.add(Category.MEMBERSHIP, "!(" + p + " in [" + zero + ", " + (isInt ? "-1" : "-0.5") + "])", p + " is not a sentinel");
        if (isInt) {
            d.add(Category.ARITHMETIC, p + " % 2 == 0", p + " is even");
            d.add(Category.ARITHMETIC, p + " % 2 != 0", p + " is odd");
            d.add(Category.ARITHMETIC, p + " % 3 == 1", p + " leaves remainder 1 modulo 3");
        }
        d.add(Category.ARITHMETIC, p + " + " + k + " >= " + lit.apply(hi), p + " plus " + k + " reaches " + lit.apply(hi));
        d.add(Category.ARITHMETIC, p + " - " + k + " < " + lit.apply(lo), p + " minus " + k + " falls below " + lit.apply(lo));
        d.add(Category.ARITHMETIC, p + " * " + two + " > " + lit.apply(hi), "double " + p + " exceeds " + lit.apply(hi));
        d.add(Category.ARITHMETIC, p + " / " + two + " < " + lit.apply(mid), "half of " + p + " is below " + lit.apply(mid));
        d.add(Category.ARITHMETIC, "-" + p + " < " + zero, p + " is positive");
        d.add(Category.ARITHMETIC, "(" + p + " + 1" + (isInt ? "" : ".0") + ") * " + two + " - " + (isInt ? "3" : "3.0") + " >= " + lit.apply(hi), "affine function of " + p + " reaches " + lit.apply(hi));
        d.add(Category.CONDITIONAL, "(" + p + " > " + lit.apply(mid) + " ? \"high\" : \"low\") == \"high\"", p + " classifies as high");
        d.add(Category.CONDITIONAL, "(" + p + " >= " + lit.apply(mid) + " ? " + p + " : " + lit.apply(mid) + ") >= " + lit.apply(mid), "max(" + p + ", " + lit.apply(mid) + ") floor");
        d.add(Category.MACRO, list3 + ".exists(x, x == " + p + ")", p + " matches some member");
        d.add(Category.MACRO, list3 + ".all(x, x != " + p + ")", p + " matches no member");
        d.add(Category.MACRO, list3 + ".exists_one(x, x == " + p + ")", p + " matches exactly one member");
        d.add(Category.MACRO, list3 + ".filter(x, x <= " + p + ").size() >= 1", "some member is at most " + p);
        d.add(Category.MACRO, list3 + ".map(x, x + " + k + ").exists(y, y > " + p + ")", "some member plus " + k + " exceeds " + p);
        d.add(Category.TYPE, "type(" + p + ") == " + (isInt ? "int" : "double"), p + " has the declared type");
        d.add(Category.TYPE, "dyn(" + p + ") == " + lit.apply(s), p + " equals the sample as dyn");
        if (isInt) {
            d.add(Category.CONVERSION, "double(" + p + ") / 2.0 > " + Literals.dbl(mid / 2.0), "double(" + p + ") halved exceeds " + Literals.dbl(mid / 2.0));
            d.add(Category.CONVERSION, "string(" + p + ").size() <= 12", p + " prints in at most 12 characters");
            d.add(Category.CONVERSION, "int(double(" + p + ")) == " + lit.apply(s), p + " survives a double round trip");
            d.add(Category.CONVERSION, "string(" + p + ") == \"" + Math.round(s) + "\"", p + " prints as the sample");
        } else {
            d.add(Category.CONVERSION, "int(" + p + ") >= " + Math.round(lo), "int(" + p + ") reaches " + Math.round(lo));
            d.add(Category.CONVERSION, "string(" + p + ").size() > 0", p + " prints");
            d.add(Category.CONVERSION, "double(int(" + p + ")) <= " + Literals.dbl(hi), "truncated " + p + " is at most " + Literals.dbl(hi));
        }
    }

    // -- STRING

    private void string(Drafts d, AttributeValues av) {
        String p = d.p;
        String s = av.sample() instanceof String x ? x : "";
        List<String> pool = av.valid().stream().filter(v -> v instanceof String).map(String::valueOf).distinct().toList();
        String q = Literals.string(s);
        int n = s.length();
        String pre = s.substring(0, Math.min(3, n));
        String suf = s.substring(Math.max(0, n - 3));
        String mid = n >= 3 ? s.substring(n / 2 - 1, n / 2 + 1) : s;
        List<String> members = firstN(pool, 3).stream().map(Literals::string).toList();

        d.add(Category.COMPARISON, p + " == " + q, p + " equals the sample");
        d.add(Category.COMPARISON, p + " != \"\"", p + " is not empty");
        d.add(Category.COMPARISON, p + " != " + q, p + " differs from the sample");
        d.add(Category.COMPARISON, p + " >= \"A\"", p + " sorts at or after \"A\"");
        d.add(Category.COMPARISON, p + " < \"~\"", p + " sorts before \"~\"");
        d.add(Category.STRING_FUNCTION, "size(" + p + ") > 0", p + " has characters");
        d.add(Category.STRING_FUNCTION, p + ".size() >= " + Math.max(1, n / 2), p + " is long enough");
        d.add(Category.STRING_FUNCTION, p + ".size() <= 255", p + " fits 255 characters");
        d.add(Category.STRING_FUNCTION, p + ".size() == " + n, p + " has the sample's length");
        d.add(Category.STRING_FUNCTION, "!" + p + ".contains(\" \")", p + " has no spaces");
        d.add(Category.STRING_FUNCTION, p + " + \"-x\" == " + Literals.string(s + "-x"), "suffix concatenation of " + p);
        d.add(Category.STRING_FUNCTION, "(" + p + " + " + p + ").size() == " + (2 * n), "doubling " + p + " doubles its length");
        if (!s.isEmpty()) {
            d.add(Category.STRING_FUNCTION, p + ".startsWith(" + Literals.string(pre) + ")", p + " starts with " + pre);
            d.add(Category.STRING_FUNCTION, p + ".endsWith(" + Literals.string(suf) + ")", p + " ends with " + suf);
            d.add(Category.STRING_FUNCTION, p + ".contains(" + Literals.string(mid) + ")", p + " contains " + mid);
            d.add(Category.LOGICAL, p + ".startsWith(" + Literals.string(pre) + ") && " + p + ".endsWith(" + Literals.string(suf) + ")", p + " has the sample's prefix and suffix");
            d.add(Category.LOGICAL, "!(" + p + ".startsWith(\"~\") || " + p + ".endsWith(\"~\"))", p + " is not wrapped in ~");
        }
        d.add(Category.REGEX, p + ".matches(" + Literals.string("^[A-Za-z0-9._@ -]+$") + ")", p + " uses a safe alphabet");
        d.add(Category.REGEX, p + ".matches(" + Literals.string("^.{1,255}$") + ")", p + " is 1 to 255 characters");
        d.add(Category.REGEX, p + ".matches(" + Literals.string("(?i)^" + pre.replaceAll("[^A-Za-z0-9]", "\\\\$0") + ".*") + ")", p + " starts with the prefix, ignoring case");
        switch (av.kind()) {
            case "EMAIL" -> d.add(Category.REGEX, p + ".matches(" + Literals.string("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$") + ")", p + " is an e-mail address");
            case "UUID" -> d.add(Category.REGEX, p + ".matches(" + Literals.string("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$") + ")", p + " is a UUID");
            case "URL" -> d.add(Category.REGEX, p + ".matches(" + Literals.string("^https?://[^\\s]+$") + ")", p + " is an http(s) URL");
            case "PHONE" -> d.add(Category.REGEX, p + ".matches(" + Literals.string("^\\+?[0-9 ()-]{7,}$") + ")", p + " is a phone number");
            case "DATE" -> d.add(Category.REGEX, p + ".matches(" + Literals.string("^\\d{4}-\\d{2}-\\d{2}$") + ")", p + " is an ISO date");
            default -> d.add(Category.REGEX, p + ".matches(" + Literals.string("^[A-Za-z].*") + ")", p + " starts with a letter");
        }
        if (!members.isEmpty()) {
            d.add(Category.MEMBERSHIP, p + " in " + join(members), p + " is one of the known values");
            d.add(Category.MEMBERSHIP, "!(" + p + " in " + join(members) + ")", p + " is not a known value");
            d.add(Category.MACRO, join(members) + ".exists(x, x == " + p + ")", p + " matches some known value");
            d.add(Category.MACRO, join(members) + ".exists(x, " + p + ".startsWith(x))", p + " starts with some known value");
            d.add(Category.MACRO, join(members) + ".map(x, x + " + p + ").exists(y, y.size() > 3)", "prefixed " + p + " is longer than 3");
        }
        d.add(Category.MACRO, "[\"<\", \">\", \";\", \"'\"].all(c, !" + p + ".contains(c))", p + " has no injection characters");
        d.add(Category.MACRO, "[\"<\", \">\", \";\"].filter(c, " + p + ".contains(c)).size() == 0", p + " has no markup characters");
        d.add(Category.MACRO, "[\"a\", \"e\", \"i\", \"o\", \"u\"].exists_one(v, " + p + ".contains(v))", p + " contains exactly one vowel kind");
        d.add(Category.CONVERSION, "size(bytes(" + p + ")) <= 1024", p + " fits 1 KiB as UTF-8");
        d.add(Category.CONVERSION, p + " == string(" + p + ")", p + " survives string()");
        d.add(Category.CONVERSION, "string(size(" + p + ")) != \"0\"", "length of " + p + " prints non-zero");
        if (s.matches("-?\\d+")) {
            d.add(Category.CONVERSION, "int(" + p + ") > 0", p + " parses to a positive int");
        }
        if (s.matches("-?\\d+(\\.\\d+)?")) {
            d.add(Category.CONVERSION, "double(" + p + ") >= 0.0", p + " parses to a non-negative double");
        }
        d.add(Category.CONDITIONAL, "(" + p + ".size() > " + n + " ? \"long\" : \"short\") == \"short\"", p + " is not longer than the sample");
        d.add(Category.CONDITIONAL, "(" + p + " == " + q + " ? 1 : 0) == 1", p + " is the sample, via ternary");
        d.add(Category.TYPE, "type(" + p + ") == string", p + " is a string");
    }

    // -- BOOL

    private void bool(Drafts d) {
        String p = d.p;
        d.add(Category.LOGICAL, p, p + " is true");
        d.add(Category.LOGICAL, "!" + p, p + " is false");
        d.add(Category.LOGICAL, p + " && true", p + " and true");
        d.add(Category.LOGICAL, p + " || false", p + " or false");
        d.add(Category.LOGICAL, "!!" + p, "double negation of " + p);
        d.add(Category.COMPARISON, p + " == true", p + " equals true");
        d.add(Category.COMPARISON, p + " != false", p + " differs from false");
        d.add(Category.CONDITIONAL, "(" + p + " ? 1 : 0) == 1", p + " selects 1");
        d.add(Category.CONDITIONAL, "(" + p + " ? \"on\" : \"off\") == \"off\"", p + " selects off");
        d.add(Category.CONVERSION, "string(" + p + ") == \"true\"", p + " prints true");
        d.add(Category.CONVERSION, "bool(string(" + p + ")) == " + p, p + " survives a string round trip");
        d.add(Category.MACRO, "[true, false].exists(x, x == " + p + ")", p + " is a boolean");
        d.add(Category.MACRO, "[true, false].exists_one(x, x == " + p + ")", p + " matches exactly one boolean");
        d.add(Category.MEMBERSHIP, p + " in [true]", p + " is in [true]");
        d.add(Category.TYPE, "type(" + p + ") == bool", p + " is a bool");
    }

    // -- TIMESTAMP

    private void timestamp(Drafts d, AttributeValues av) {
        String p = d.p;
        List<Instant> ts = av.valid().stream().filter(v -> v instanceof String).map(v -> Instant.parse((String) v))
                .distinct().sorted().toList();
        if (ts.isEmpty()) {
            return;
        }
        Instant s = av.sample() instanceof String x ? Instant.parse(x) : ts.get(ts.size() / 2);
        Instant lo = ts.getFirst();
        Instant hi = ts.getLast();
        Instant mid = ts.get(ts.size() / 2);
        ZonedDateTime z = s.atZone(ZoneOffset.UTC);
        Function<Instant, String> lit = i -> "timestamp(" + Literals.string(i.toString()) + ")";
        String list3 = join(firstN(ts, 3).stream().map(lit).toList());

        d.add(Category.COMPARISON, p + " == " + lit.apply(s), p + " is the sample instant");
        d.add(Category.COMPARISON, p + " != " + lit.apply(s), p + " differs from the sample instant");
        d.add(Category.COMPARISON, p + " > " + lit.apply(mid), p + " is after " + mid);
        d.add(Category.COMPARISON, p + " >= " + lit.apply(mid), p + " is at or after " + mid);
        d.add(Category.COMPARISON, p + " < " + lit.apply(mid), p + " is before " + mid);
        d.add(Category.COMPARISON, p + " <= " + lit.apply(mid), p + " is at or before " + mid);
        d.add(Category.LOGICAL, p + " >= " + lit.apply(lo) + " && " + p + " <= " + lit.apply(hi), p + " lies in the sampled window");
        d.add(Category.LOGICAL, p + " < " + lit.apply(lo) + " || " + p + " > " + lit.apply(hi), p + " lies outside the sampled window");
        d.add(Category.TEMPORAL, p + ".getFullYear() == " + z.getYear(), p + " is in " + z.getYear());
        d.add(Category.TEMPORAL, p + ".getFullYear() >= 2000", p + " is in this millennium");
        d.add(Category.TEMPORAL, p + ".getMonth() == " + (z.getMonthValue() - 1), p + " is in month index " + (z.getMonthValue() - 1));
        d.add(Category.TEMPORAL, p + ".getDate() >= 1", p + " has a calendar day");
        d.add(Category.TEMPORAL, p + ".getDayOfMonth() < 28", p + " is early in the month");
        d.add(Category.TEMPORAL, p + ".getDayOfWeek() != 0", p + " is not a Sunday");
        d.add(Category.TEMPORAL, p + ".getDayOfWeek() in [1, 2, 3, 4, 5]", p + " is a weekday");
        d.add(Category.TEMPORAL, p + ".getDayOfYear() >= 0", p + " has a day of year");
        d.add(Category.TEMPORAL, p + ".getHours() == " + z.getHour(), p + " is in hour " + z.getHour());
        d.add(Category.TEMPORAL, p + ".getHours(\"UTC\") < 12", p + " is before noon UTC");
        d.add(Category.TEMPORAL, p + ".getMinutes() < 30", p + " is in the first half hour");
        d.add(Category.TEMPORAL, p + ".getSeconds() >= 0", p + " has seconds");
        d.add(Category.TEMPORAL, p + ".getMilliseconds() >= 0", p + " has milliseconds");
        d.add(Category.TEMPORAL, p + ".getFullYear(\"Europe/Paris\") >= 2000", p + " year in Paris");
        d.add(Category.ARITHMETIC, p + " - " + lit.apply(lo) + " >= duration(\"86400s\")", p + " is a day past the window start");
        d.add(Category.ARITHMETIC, lit.apply(hi) + " - " + p + " < duration(\"2592000s\")", p + " is within 30 days of the window end");
        d.add(Category.ARITHMETIC, p + " + duration(\"86400s\") > " + lit.apply(mid), p + " plus a day passes " + mid);
        d.add(Category.ARITHMETIC, p + " - duration(\"3600s\") < " + lit.apply(lo), p + " minus an hour precedes the window");
        d.add(Category.CONVERSION, "int(" + p + ") > 0", p + " is after the epoch");
        d.add(Category.CONVERSION, "string(" + p + ").startsWith(\"" + z.getYear() + "\")", p + " prints with the sample year");
        d.add(Category.CONVERSION, "timestamp(string(" + p + ")) == " + p, p + " survives a string round trip");
        d.add(Category.CONVERSION, "timestamp(int(" + p + ")) == " + p, p + " survives an epoch round trip");
        d.add(Category.CONDITIONAL, "(" + p + " > " + lit.apply(mid) + " ? \"late\" : \"early\") == \"late\"", p + " classifies as late");
        d.add(Category.MEMBERSHIP, p + ".getFullYear() in [" + z.getYear() + ", " + (z.getYear() + 1) + "]", p + " is in the sample year or the next");
        d.add(Category.MEMBERSHIP, p + " in " + list3, p + " is one of the sampled instants");
        d.add(Category.MACRO, list3 + ".exists(t, " + p + " > t)", p + " is after some sampled instant");
        d.add(Category.MACRO, list3 + ".all(t, " + p + " != t)", p + " is none of the sampled instants");
        d.add(Category.MACRO, list3 + ".filter(t, t < " + p + ").size() >= 1", "an instant precedes " + p);
        d.add(Category.TYPE, "type(" + p + ") == type(timestamp(\"1970-01-01T00:00:00Z\"))", p + " is a timestamp");
    }

    // -- DURATION

    private void duration(Drafts d, AttributeValues av) {
        String p = d.p;
        List<Duration> ds = av.valid().stream().filter(v -> v instanceof String).map(v -> Duration.parse((String) v))
                .distinct().sorted(Comparator.naturalOrder()).toList();
        if (ds.isEmpty()) {
            return;
        }
        Duration s = av.sample() instanceof String x ? Duration.parse(x) : ds.get(ds.size() / 2);
        Duration lo = ds.getFirst();
        Duration hi = ds.getLast();
        Duration mid = ds.get(ds.size() / 2);
        Function<Duration, String> lit = Literals::duration;
        String list3 = join(firstN(ds, 3).stream().map(lit).toList());

        d.add(Category.COMPARISON, p + " == " + lit.apply(s), p + " equals the sample");
        d.add(Category.COMPARISON, p + " != " + lit.apply(s), p + " differs from the sample");
        d.add(Category.COMPARISON, p + " > " + lit.apply(mid), p + " is longer than " + mid);
        d.add(Category.COMPARISON, p + " >= " + lit.apply(mid), p + " is at least " + mid);
        d.add(Category.COMPARISON, p + " < " + lit.apply(mid), p + " is shorter than " + mid);
        d.add(Category.COMPARISON, p + " <= " + lit.apply(mid), p + " is at most " + mid);
        d.add(Category.LOGICAL, p + " >= " + lit.apply(lo) + " && " + p + " <= " + lit.apply(hi), p + " is within the sampled span");
        d.add(Category.LOGICAL, p + " < " + lit.apply(lo) + " || " + p + " > " + lit.apply(hi), p + " is outside the sampled span");
        d.add(Category.ARITHMETIC, p + " + duration(\"1800s\") <= " + lit.apply(hi), p + " plus 30 minutes fits the maximum");
        d.add(Category.ARITHMETIC, p + " - duration(\"60s\") < " + lit.apply(mid), p + " minus a minute is below " + mid);
        d.add(Category.ARITHMETIC, p + " + " + p + " > " + lit.apply(lo), "twice " + p + " exceeds the minimum");
        d.add(Category.TEMPORAL, p + ".getHours() >= 1", p + " is an hour or more");
        d.add(Category.TEMPORAL, p + ".getMinutes() > 5", p + " is over five minutes");
        d.add(Category.TEMPORAL, p + ".getSeconds() >= 60", p + " is a minute or more");
        d.add(Category.TEMPORAL, p + ".getMilliseconds() >= 0", p + " has milliseconds");
        d.add(Category.CONVERSION, "string(" + p + ") == \"" + s.toSeconds() + "s\"", p + " prints as the sample");
        d.add(Category.CONVERSION, "duration(string(" + p + ")) == " + p, p + " survives a string round trip");
        d.add(Category.CONDITIONAL, "(" + p + " > " + lit.apply(mid) + " ? \"long\" : \"short\") == \"long\"", p + " classifies as long");
        d.add(Category.MEMBERSHIP, p + " in " + list3, p + " is a sampled duration");
        d.add(Category.MACRO, list3 + ".exists(x, " + p + " > x)", p + " exceeds some sampled duration");
        d.add(Category.MACRO, list3 + ".all(x, " + p + " != x)", p + " is none of the sampled durations");
        d.add(Category.TYPE, "type(" + p + ") == type(duration(\"1s\"))", p + " is a duration");
    }

    // -- LISTS

    private void list(Drafts d, AttributeValues av, DataType elem) {
        String p = d.p;
        List<Object> pool = av.valid().stream().filter(v -> v instanceof List<?>).flatMap(v -> ((List<?>) v).stream())
                .map(v -> (Object) v).distinct().toList();
        List<?> sample = av.sample() instanceof List<?> l ? l : List.of();
        int n = sample.size();
        d.add(Category.COMPARISON, "size(" + p + ") > 0", p + " is not empty");
        d.add(Category.COMPARISON, p + ".size() >= 1", p + " has an element");
        d.add(Category.COMPARISON, p + ".size() <= 50", p + " has at most 50 elements");
        d.add(Category.COMPARISON, p + ".size() == " + n, p + " has the sample's size");
        d.add(Category.COMPARISON, p + " != []", p + " differs from the empty list");
        d.add(Category.COMPARISON, p + " == " + Literals.of(elem == DataType.STRING ? DataType.LIST_STRING : elem == DataType.INT ? DataType.LIST_INT : DataType.LIST_DOUBLE, sample), p + " equals the sample list");
        d.add(Category.TYPE, "type(" + p + ") == list", p + " is a list");
        if (pool.isEmpty()) {
            return;
        }
        List<String> lits = firstN(pool, 3).stream().map(e -> Literals.of(elem, e)).toList();
        String e1 = lits.getFirst();
        String e2 = lits.get(Math.min(1, lits.size() - 1));
        String last = Literals.of(elem, pool.getLast());
        d.add(Category.MEMBERSHIP, e1 + " in " + p, p + " contains " + e1);
        d.add(Category.MEMBERSHIP, "!(" + e2 + " in " + p + ")", p + " lacks " + e2);
        d.add(Category.ACCESS, p + "[0] == " + e1, "first element of " + p + " is " + e1);
        d.add(Category.ACCESS, p + ".size() > 0 && " + p + "[0] == " + e1, "non-empty " + p + " starts with " + e1);
        d.add(Category.ACCESS, p + ".size() > 0 && " + p + "[" + p + ".size() - 1] == " + last, "non-empty " + p + " ends with " + last);
        d.add(Category.ARITHMETIC, "(" + p + " + [" + e1 + "]).size() > " + p + ".size()", "appending grows " + p);
        d.add(Category.MACRO, p + ".exists(x, x == " + e1 + ")", "some element of " + p + " is " + e1);
        d.add(Category.MACRO, p + ".all(x, x != " + e2 + ")", "no element of " + p + " is " + e2);
        d.add(Category.MACRO, p + ".exists_one(x, x == " + e1 + ")", "exactly one element of " + p + " is " + e1);
        d.add(Category.MACRO, p + ".filter(x, x == " + e1 + ").size() >= 1", p + " filters to a non-empty list");
        d.add(Category.MACRO, p + ".map(x, x).size() == " + p + ".size()", "map keeps the size of " + p);
        switch (elem) {
            case STRING -> {
                d.add(Category.MACRO, p + ".all(x, x.size() > 0)", "no element of " + p + " is empty");
                d.add(Category.MACRO, p + ".exists(x, x.startsWith(" + Literals.string(String.valueOf(pool.getFirst()).substring(0, 1)) + "))", "some element of " + p + " has a known first letter");
                d.add(Category.MACRO, p + ".map(x, x.size()).all(n, n < 100)", "all elements of " + p + " are short");
                d.add(Category.MACRO, p + ".filter(x, x.contains(\"a\")).size() > 0", "some element of " + p + " contains a");
            }
            case INT, DOUBLE -> {
                String zero = elem == DataType.INT ? "0" : "0.0";
                String two = elem == DataType.INT ? "2" : "2.0";
                d.add(Category.MACRO, p + ".all(x, x >= " + zero + ")", "no element of " + p + " is negative");
                d.add(Category.MACRO, p + ".map(x, x * " + two + ").exists(y, y > " + e1 + ")", "a doubled element of " + p + " exceeds " + e1);
                d.add(Category.MACRO, p + ".filter(x, x > " + e1 + ").size() == 0", "no element of " + p + " exceeds " + e1);
            }
            default -> {
            }
        }
    }

    // -- MAP

    private void map(Drafts d, AttributeValues av) {
        String p = d.p;
        Map<?, ?> sample = av.sample() instanceof Map<?, ?> m ? m : Map.of();
        d.add(Category.ACCESS, p + ".size() > 0", p + " is not empty");
        d.add(Category.ACCESS, "size(" + p + ") == " + sample.size(), p + " has the sample's size");
        d.add(Category.COMPARISON, p + " != {}", p + " differs from the empty map");
        d.add(Category.COMPARISON, p + " == " + Literals.of(DataType.MAP, sample), p + " equals the sample map");
        d.add(Category.ACCESS, "!(\"zz_missing\" in " + p + ")", p + " lacks a key");
        d.add(Category.MACRO, p + ".all(k, k.size() > 0)", "no key of " + p + " is empty");
        d.add(Category.MACRO, p + ".map(k, k).size() == " + p + ".size()", "map keeps the size of " + p);
        d.add(Category.TYPE, "type(" + p + ") == map", p + " is a map");
        for (Map.Entry<?, ?> e : firstN(new ArrayList<Map.Entry<?, ?>>(sample.entrySet()), 2)) {
            String key = String.valueOf(e.getKey());
            String kq = Literals.string(key);
            d.add(Category.ACCESS, kq + " in " + p, p + " has key " + key);
            d.add(Category.ACCESS, "has(" + p + "." + key + ")", p + " has field " + key);
            d.add(Category.ACCESS, p + "[" + kq + "] == " + Literals.of(DataType.ANY, e.getValue()), p + "[" + key + "] equals the sample");
            d.add(Category.ACCESS, p + "." + key + " == " + Literals.of(DataType.ANY, e.getValue()), p + "." + key + " equals the sample");
            d.add(Category.LOGICAL, kq + " in " + p + " && " + p + ".size() > 0", p + " has key " + key + " and is not empty");
            d.add(Category.MACRO, p + ".exists(k, k == " + kq + ")", "some key of " + p + " is " + key);
            d.add(Category.MACRO, p + ".exists_one(k, k == " + kq + ")", "exactly one key of " + p + " is " + key);
            d.add(Category.MACRO, p + ".filter(k, k.startsWith(" + Literals.string(key.substring(0, Math.min(1, key.length()))) + ")).size() >= 1", "a key of " + p + " has the same first letter");
        }
    }

    // -- ANY

    private void any(Drafts d, AttributeValues av) {
        String p = d.p;
        Object s = av.sample();
        if (s != null) {
            d.add(Category.COMPARISON, p + " == " + Literals.of(DataType.ANY, s), p + " equals the sample");
            d.add(Category.COMPARISON, p + " != " + Literals.of(DataType.ANY, s), p + " differs from the sample");
            String type = s instanceof String ? "string" : s instanceof Boolean ? "bool" : s instanceof Double ? "double"
                    : s instanceof Number ? "int" : s instanceof List<?> ? "list" : "map";
            d.add(Category.TYPE, "type(" + p + ") == " + type, p + " has the sampled runtime type");
        }
        d.add(Category.TYPE, "dyn(" + p + ") == dyn(" + p + ")", p + " equals itself as dyn");
        d.add(Category.COMPARISON, p + " == null", p + " is null");
        d.add(Category.COMPARISON, p + " != null", p + " is not null");
    }

    // ------------------------------------------------------------------ cross-parameter

    private List<Draft> cross(Map<String, List<GeneratedExpression>> singles, Random rnd, int budget) {
        List<Draft> out = new ArrayList<>();
        List<Parameter> params = new ArrayList<>(library.parameters());
        for (int i = 0; i < params.size(); i++) {
            for (int j = i + 1; j < params.size() && out.size() < budget; j++) {
                Parameter a = params.get(i);
                Parameter b = params.get(j);
                String x = a.celName();
                String y = b.celName();
                if (a.dataType() == b.dataType()) {
                    switch (a.dataType()) {
                        case INT, DOUBLE -> {
                            out.add(new Draft(Category.CROSS_PARAMETER, x + " > " + y, x + " exceeds " + y));
                            out.add(new Draft(Category.CROSS_PARAMETER, x + " <= " + y, x + " is at most " + y));
                        }
                        case STRING -> {
                            out.add(new Draft(Category.CROSS_PARAMETER, x + " != " + y, x + " differs from " + y));
                            out.add(new Draft(Category.CROSS_PARAMETER, x + ".contains(" + y + ")", x + " contains " + y));
                            out.add(new Draft(Category.CROSS_PARAMETER, x + ".size() >= " + y + ".size()", x + " is at least as long as " + y));
                        }
                        case TIMESTAMP -> {
                            out.add(new Draft(Category.CROSS_PARAMETER, x + " < " + y, x + " precedes " + y));
                            out.add(new Draft(Category.CROSS_PARAMETER, y + " - " + x + " < duration(\"31536000s\")", y + " is within a year of " + x));
                        }
                        case DURATION -> out.add(new Draft(Category.CROSS_PARAMETER, x + " + " + y + " > duration(\"0s\")", x + " plus " + y + " is positive"));
                        case BOOL -> {
                            out.add(new Draft(Category.CROSS_PARAMETER, x + " && " + y, x + " and " + y));
                            out.add(new Draft(Category.CROSS_PARAMETER, x + " || !" + y, x + " or not " + y));
                            out.add(new Draft(Category.CROSS_PARAMETER, x + " == " + y, x + " equals " + y));
                        }
                        default -> {
                        }
                    }
                } else if (a.dataType() == DataType.INT && b.dataType() == DataType.DOUBLE) {
                    out.add(new Draft(Category.CROSS_PARAMETER, "double(" + x + ") > " + y, "double(" + x + ") exceeds " + y));
                } else if (a.dataType() == DataType.DOUBLE && b.dataType() == DataType.INT) {
                    out.add(new Draft(Category.CROSS_PARAMETER, x + " > double(" + y + ")", x + " exceeds double(" + y + ")"));
                }
            }
        }
        List<GeneratedExpression> pool = singles.values().stream().flatMap(List::stream).toList();
        List<String> names = new ArrayList<>(singles.keySet());
        int guard = 0;
        while (out.size() < budget * 2 && names.size() > 1 && guard++ < budget * 20) {
            GeneratedExpression e1 = randomOf(singles, names.get(rnd.nextInt(names.size())), rnd);
            GeneratedExpression e2 = randomOf(singles, names.get(rnd.nextInt(names.size())), rnd);
            GeneratedExpression e3 = pool.isEmpty() ? e1 : pool.get(rnd.nextInt(pool.size()));
            if (e1 == null || e2 == null || e1.parameters().equals(e2.parameters())) {
                continue;
            }
            String a = "(" + e1.expression() + ")";
            String b = "(" + e2.expression() + ")";
            String c = "(" + e3.expression() + ")";
            switch (rnd.nextInt(5)) {
                case 0 -> out.add(new Draft(Category.CROSS_PARAMETER, a + " && " + b, e1.description() + " and " + e2.description()));
                case 1 -> out.add(new Draft(Category.CROSS_PARAMETER, a + " || " + b, e1.description() + " or " + e2.description()));
                case 2 -> out.add(new Draft(Category.CROSS_PARAMETER, "!" + a + " || " + b, "if " + e1.description() + " then " + e2.description()));
                case 3 -> out.add(new Draft(Category.CROSS_PARAMETER, a + " ? " + b + " : " + c, "when " + e1.description() + " require " + e2.description() + ", else " + e3.description()));
                default -> out.add(new Draft(Category.CROSS_PARAMETER, "(" + a + " && " + b + ") || " + c, "(" + e1.description() + " and " + e2.description() + ") or " + e3.description()));
            }
        }
        return out;
    }

    private static GeneratedExpression randomOf(Map<String, List<GeneratedExpression>> singles, String name, Random rnd) {
        List<GeneratedExpression> l = singles.get(name);
        return l == null || l.isEmpty() ? null : l.get(rnd.nextInt(l.size()));
    }
}
