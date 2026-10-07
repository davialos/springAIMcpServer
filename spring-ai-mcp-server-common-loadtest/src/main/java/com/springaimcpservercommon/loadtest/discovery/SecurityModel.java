package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.Access;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the project's Spring Security setup says about authentication: how callers authenticate, whether form login
 * needs a CSRF token, and which request matchers protect which URLs. The generator turns it into the suite's
 * {@code auth} block (type, one identity per role) and a {@code role} per API.
 *
 * @param style     how callers authenticate: from {@code httpBasic}, {@code formLogin}, {@code oauth2ResourceServer}
 *                  or the default Boot setup
 * @param csrf      whether CSRF protection is on (form login and cookie sessions need the token on writes)
 * @param loginPage the configured {@code loginPage("/x")} or {@code loginProcessingUrl}, if any
 * @param rules     the filter chain's matchers in declaration order (first match wins, as in Spring)
 */
public record SecurityModel(Style style, boolean csrf, @Nullable String loginPage, List<Rule> rules) {

    /** How callers authenticate. */
    public enum Style {
        /** No Spring Security found. */
        NONE,
        /** HTTP Basic. */
        BASIC,
        /** Form login with a session cookie (and CSRF). */
        FORM,
        /** OAuth2 resource server / JWT: a bearer token. */
        BEARER
    }

    /**
     * One request-matcher rule of the filter chain.
     *
     * @param methods  HTTP methods it applies to; empty = all
     * @param patterns Ant patterns ({@code /admin/**}); {@code /**} for {@code anyRequest()}
     * @param access   the protection it declares
     */
    public record Rule(Set<HttpMethod> methods, List<String> patterns, Access access) {

        /** Compact constructor: defensive copies. */
        public Rule {
            methods = Set.copyOf(methods);
            patterns = List.copyOf(patterns);
        }

        boolean matches(HttpMethod method, String path) {
            if (!methods.isEmpty() && !methods.contains(method)) {
                return false;
            }
            for (String p : patterns) {
                if (ant(p).matcher(path).matches()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Compact constructor: defensive copy. */
    public SecurityModel {
        rules = List.copyOf(rules);
    }

    /**
     * @return the model of a project without Spring Security
     */
    public static SecurityModel none() {
        return new SecurityModel(Style.NONE, false, null, List.of());
    }

    /**
     * Gives every endpoint without an annotation-based access the first filter-chain rule that matches it.
     *
     * @param endpoints the endpoints
     * @return the endpoints with {@link ApiEndpoint#access()} filled in where it is known
     */
    public List<ApiEndpoint> annotate(List<ApiEndpoint> endpoints) {
        List<ApiEndpoint> out = new ArrayList<>();
        for (ApiEndpoint e : endpoints) {
            if (e.access() != null) {
                out.add(e);
                continue;
            }
            Access found = null;
            for (Rule r : rules) {
                if (r.matches(e.method(), e.path())) {
                    found = r.access();
                    break;
                }
            }
            out.add(found == null ? e : e.withAccess(found));
        }
        return out;
    }

    /**
     * Every role the endpoints (after {@link #annotate}) or rules name.
     *
     * @param endpoints the annotated endpoints
     * @return role names in first-seen order
     */
    public List<String> roles(List<ApiEndpoint> endpoints) {
        Set<String> roles = new LinkedHashSet<>();
        for (ApiEndpoint e : endpoints) {
            if (e.access() != null) {
                roles.addAll(e.access().roles());
            }
        }
        for (Rule r : rules) {
            roles.addAll(r.access().roles());
        }
        return List.copyOf(roles);
    }

    /** An Ant pattern as a regex: {@code **} any depth, {@code *} one segment, {@code {var}} one segment. */
    static Pattern ant(String pattern) {
        StringBuilder re = new StringBuilder();
        String p = pattern.replaceAll("/+$", "");
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*' && i + 1 < p.length() && p.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '{') {
                int end = p.indexOf('}', i);
                re.append("[^/]+");
                i = end < 0 ? p.length() : end;
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re + "/?", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public String toString() {
        return "SecurityModel[" + style.name().toLowerCase(Locale.ROOT) + ", " + rules.size() + " rules]";
    }
}
