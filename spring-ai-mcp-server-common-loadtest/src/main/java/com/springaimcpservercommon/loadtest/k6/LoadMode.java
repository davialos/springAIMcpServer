package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Load-test profiles. Each one runs per API (every API on its own, in turn — isolates which endpoint degrades),
 * with the {@code mixed-} prefix as one weighted mix of all APIs (realistic production-like traffic), or with the
 * {@code journey-} prefix as replays of a recorded browser flow (each iteration = the whole recorded sequence).
 * The defaults below seed {@code loadtest.config.json → modes}; the suite reads the profile from there, so
 * teams tune stages without regenerating.
 */
public enum LoadMode {

    /** Sanity check: one VU, a few iterations per API. Run before anything heavier. */
    SMOKE("Sanity: 1 VU, 3 iterations per API — proves every request is well-formed"),
    /** Expected production load, steady. */
    LOAD("Average load: ramp to base VUs, hold 5 minutes, ramp down"),
    /** Step increases beyond normal load until the system degrades. */
    STRESS("Stress: steps of 1x, 2x, 3x, 4x base VUs, 3 minutes each — finds the degradation point"),
    /** A sudden surge and the recovery after it. */
    SPIKE("Spike: baseline, jump to 10x within 10 s, hold 1 minute, drop back and watch recovery"),
    /** Long steady run: leaks, pool exhaustion, slow degradation. */
    SOAK("Soak: base VUs for 1 hour — memory leaks, connection-pool exhaustion, slow drift"),
    /** Open-model ramp of arrival rate until a threshold fails (test aborts there). */
    BREAKPOINT("Breakpoint: arrival rate ramps to 20x base rate; aborts at the first failed threshold");

    private final String description;

    LoadMode(String description) {
        this.description = description;
    }

    /**
     * Profile name used in {@code MODE} and in the config.
     *
     * @return lower-case name
     */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Human description.
     *
     * @return description
     */
    public String description() {
        return description;
    }

    /**
     * Every {@code MODE} value the suite accepts: each profile, its {@code mixed-} and {@code journey-} forms,
     * {@code preview} and {@code journey-preview}.
     *
     * @return mode names
     */
    public static List<String> modeNames() {
        List<String> out = new ArrayList<>();
        for (LoadMode m : values()) {
            out.add(m.id());
        }
        for (LoadMode m : values()) {
            out.add("mixed-" + m.id());
        }
        for (LoadMode m : values()) {
            out.add("journey-" + m.id());
        }
        out.add("preview");
        out.add("journey-preview");
        return out;
    }

    /**
     * The default profile as JSON.
     *
     * @return profile node
     */
    public ObjectNode defaultProfile() {
        ObjectNode p = Documents.json().createObjectNode();
        p.put("description", description);
        switch (this) {
            case SMOKE -> {
                p.put("executor", "per-vu-iterations");
                p.put("vus", 1);
                p.put("iterations", 3);
                p.put("maxDuration", "2m");
            }
            case LOAD -> ramping(p, 10, new Object[][]{{"1m", 1}, {"5m", 1}, {"1m", 0}});
            case STRESS -> {
                ramping(p, 10, new Object[][]{{"2m", 1}, {"3m", 1}, {"2m", 2}, {"3m", 2}, {"2m", 3}, {"3m", 3},
                        {"2m", 4}, {"3m", 4}, {"2m", 0}});
                thresholds(p, "rate<0.05", "p(95)<2000");
                p.put("maxErrorRate", 0.05);
            }
            case SPIKE -> {
                ramping(p, 5, new Object[][]{{"30s", 1}, {"1m", 1}, {"10s", 10}, {"1m", 10}, {"10s", 1},
                        {"2m", 1}, {"10s", 0}});
                thresholds(p, "rate<0.10", "p(95)<3000");
                p.put("maxErrorRate", 0.10);
            }
            case SOAK -> ramping(p, 10, new Object[][]{{"2m", 1}, {"1h", 1}, {"2m", 0}});
            case BREAKPOINT -> {
                p.put("executor", "ramping-arrival-rate");
                p.put("baseRate", 10);
                p.put("startRate", 1);
                p.put("timeUnit", "1s");
                p.put("preAllocatedVUs", 20);
                p.put("maxVUs", 1000);
                stages(p, new Object[][]{{"10m", 20}});
                p.put("abortOnFail", true);
                p.put("delayAbortEval", "30s");
            }
        }
        return p;
    }

    private static void ramping(ObjectNode p, int baseVus, Object[][] stages) {
        p.put("executor", "ramping-vus");
        p.put("baseVus", baseVus);
        p.put("_stages", "target = multiplier of baseVus (env VUS overrides baseVus)");
        stages(p, stages);
    }

    private static void stages(ObjectNode p, Object[][] stages) {
        ArrayNode arr = p.putArray("stages");
        for (Object[] s : stages) {
            arr.addObject().put("duration", (String) s[0]).put("target", ((Number) s[1]).doubleValue());
        }
    }

    private static void thresholds(ObjectNode p, String failed, String duration) {
        ObjectNode t = p.putObject("thresholds");
        t.putArray("http_req_failed").add(failed);
        t.putArray("http_req_duration").add(duration);
    }
}
