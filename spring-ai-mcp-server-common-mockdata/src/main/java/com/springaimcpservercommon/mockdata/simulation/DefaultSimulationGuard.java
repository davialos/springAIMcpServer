package com.springaimcpservercommon.mockdata.simulation;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * The default guard: http(s) only; never in a production environment; loopback targets are allowed, every other host must
 * match the allow-list ({@code api.test.example.com}, {@code *.staging.example.com}); link-local addresses (cloud
 * metadata services) are always refused; caps on virtual users, duration and request rate.
 */
public final class DefaultSimulationGuard implements SimulationGuard {

    /**
     * Limits of the guard.
     *
     * @param allowedHosts       host patterns that may be targeted besides loopback; a leading {@code *.} matches any sub-domain
     * @param allowLoopback      allow {@code localhost}, {@code 127.0.0.0/8} and {@code ::1}
     * @param maxVirtualUsers    most virtual users
     * @param maxDuration        longest run
     * @param maxRequestsPerSecond highest rate a spec may ask for; 0 = no cap (an uncapped spec is then allowed)
     */
    public record Limits(List<String> allowedHosts, boolean allowLoopback, int maxVirtualUsers, Duration maxDuration,
                         int maxRequestsPerSecond) {

        /** Defaults: loopback only, 200 virtual users, 10 minutes, no rate cap. */
        public static Limits defaults() {
            return new Limits(List.of(), true, 200, Duration.ofMinutes(10), 0);
        }
    }

    private final Limits limits;
    private final BooleanSupplier productionEnvironment;

    /**
     * Creates the guard.
     *
     * @param limits                limits
     * @param productionEnvironment true when production rules apply (PROD or an undeclared tier): nothing may run then
     */
    public DefaultSimulationGuard(Limits limits, BooleanSupplier productionEnvironment) {
        this.limits = limits;
        this.productionEnvironment = productionEnvironment;
    }

    @Override
    public void check(SimulationSpec spec) {
        if (productionEnvironment.getAsBoolean()) {
            throw new SimulationRefusedException("load simulation is disabled when production rules apply (tier PROD or undeclared)");
        }
        String scheme = spec.target().getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SimulationRefusedException("only http and https targets are supported");
        }
        String host = spec.target().getHost();
        if (host == null) {
            throw new SimulationRefusedException("the target has no host");
        }
        String h = host.toLowerCase(Locale.ROOT).replaceAll("^\\[|]$", "");
        if (linkLocal(h)) {
            throw new SimulationRefusedException("link-local addresses (cloud metadata services) are never allowed");
        }
        if (!(limits.allowLoopback() && loopback(h)) && limits.allowedHosts().stream().noneMatch(p -> matches(p, h))) {
            throw new SimulationRefusedException("host '" + host + "' is not in the simulator allow-list");
        }
        if (spec.virtualUsers() > limits.maxVirtualUsers()) {
            throw new SimulationRefusedException("virtualUsers " + spec.virtualUsers() + " exceeds the limit " + limits.maxVirtualUsers());
        }
        if (spec.duration().compareTo(limits.maxDuration()) > 0) {
            throw new SimulationRefusedException("duration " + spec.duration() + " exceeds the limit " + limits.maxDuration());
        }
        int cap = limits.maxRequestsPerSecond();
        if (cap > 0 && (spec.maxRequestsPerSecond() == 0 || spec.maxRequestsPerSecond() > cap)) {
            throw new SimulationRefusedException("the request rate must be capped at or below " + cap + " per second");
        }
    }

    private static boolean loopback(String h) {
        return h.equals("localhost") || h.equals("::1") || h.matches("127(\\.\\d{1,3}){3}");
    }

    private static boolean linkLocal(String h) {
        if (!h.matches("[0-9a-f:.]+") || !(h.contains(".") || h.contains(":"))) {
            return false; // a name: resolved only by the client, the allow-list governs
        }
        try {
            return InetAddress.getByName(h).isLinkLocalAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    private static boolean matches(String pattern, String host) {
        String p = pattern.toLowerCase(Locale.ROOT).strip();
        return p.startsWith("*.") ? host.endsWith(p.substring(1)) : host.equals(p);
    }
}
