package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.model.ApiEnvironment;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Classifies an endpoint URL as DEV, QA or PROD by host patterns ({@code *} wildcards, e.g. {@code *.dev.acme.com}),
 * so the admin UI can pre-select the environment. A host that matches no pattern, or patterns of more than one
 * environment, is {@link ApiEnvironment#EXTERNAL} (cannot be validated, needs confirmation). Thread-safe.
 */
public final class ApiEnvironmentClassifier {

    private final Map<ApiEnvironment, List<Pattern>> patterns;

    /**
     * Creates the classifier.
     *
     * @param hostPatterns host glob patterns per environment (EXTERNAL entries are ignored)
     */
    public ApiEnvironmentClassifier(Map<ApiEnvironment, List<String>> hostPatterns) {
        Map<ApiEnvironment, List<Pattern>> compiled = new java.util.EnumMap<>(ApiEnvironment.class);
        hostPatterns.forEach((env, globs) -> {
            if (env != ApiEnvironment.EXTERNAL) {
                List<Pattern> list = new ArrayList<>();
                globs.forEach(g -> list.add(glob(g)));
                compiled.put(env, List.copyOf(list));
            }
        });
        this.patterns = Map.copyOf(compiled);
    }

    private static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (String part : glob.toLowerCase(Locale.ROOT).split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * Classifies a URL.
     *
     * @param url absolute http(s) URL
     * @return the environment, or EXTERNAL if it cannot be determined unambiguously
     */
    public ApiEnvironment classify(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return ApiEnvironment.EXTERNAL;
        }
        if (host == null) {
            return ApiEnvironment.EXTERNAL;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        ApiEnvironment found = null;
        for (Map.Entry<ApiEnvironment, List<Pattern>> e : patterns.entrySet()) {
            if (e.getValue().stream().anyMatch(p -> p.matcher(lower).matches())) {
                if (found != null) {
                    return ApiEnvironment.EXTERNAL;
                }
                found = e.getKey();
            }
        }
        return found == null ? ApiEnvironment.EXTERNAL : found;
    }
}
