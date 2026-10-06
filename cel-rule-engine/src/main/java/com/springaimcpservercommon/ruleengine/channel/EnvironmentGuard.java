package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.config.RuleEngineProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides how an API URL relates to the environment this deployment runs in, so a DEV system never calls QA or
 * production APIs and the other way round. Host patterns per environment come from
 * {@code ruleengine.api-hosts.<ENV>} ({@code localhost}, {@code *.qa.acme.com}).
 */
@Component
public class EnvironmentGuard {

    /** The relation of a URL to this environment. */
    public enum Kind {
        /** The host belongs to the environment this deployment runs in. */
        SAME_ENVIRONMENT,
        /** The host belongs to another environment: never allowed. */
        OTHER_ENVIRONMENT,
        /** The host is in no environment's list: cannot be validated, needs a confirmation. */
        EXTERNAL
    }

    /**
     * The classification of a URL.
     *
     * @param kind        how it relates to this environment
     * @param environment the environment the host belongs to ({@code null} for external)
     * @param host        the host that was looked at
     */
    public record Classification(Kind kind, String environment, String host) { }

    private final String current;
    private final Map<String, List<Pattern>> patterns = new java.util.LinkedHashMap<>();

    public EnvironmentGuard(RuleEngineProperties properties) {
        this.current = properties.environment();
        properties.apiHosts().forEach((env, hosts) -> patterns.put(env.toUpperCase(Locale.ROOT),
                hosts.stream().map(EnvironmentGuard::glob).toList()));
    }

    /**
     * @return the environment of this deployment (DEV, QA, PROD …)
     */
    public String environment() {
        return current;
    }

    /**
     * Classifies a URL by its host.
     *
     * @param url an absolute http(s) URL
     * @return the classification
     * @throws IllegalArgumentException when the URL is not an absolute http(s) URL
     */
    public Classification classify(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a valid URL: " + url);
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("an absolute http(s) URL is required: " + url);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (matches(current, host)) {
            return new Classification(Kind.SAME_ENVIRONMENT, current, host);
        }
        for (String env : patterns.keySet()) {
            if (!env.equals(current) && matches(env, host)) {
                return new Classification(Kind.OTHER_ENVIRONMENT, env, host);
            }
        }
        return new Classification(Kind.EXTERNAL, null, host);
    }

    private boolean matches(String env, String host) {
        return patterns.getOrDefault(env, List.of()).stream().anyMatch(p -> p.matcher(host).matches());
    }

    private static Pattern glob(String host) {
        return Pattern.compile(Pattern.quote(host.toLowerCase(Locale.ROOT)).replace("*", "\\E[^.]*(?:\\.[^.]+)*\\Q"));
    }
}
