package com.springaimcpservercommon.loadtest.traffic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An API of the suite as traffic is matched against: its method and URI template.
 *
 * @param id     API id
 * @param method HTTP method
 * @param path   URI template ({@code /api/orders/{id}}), relative to the base URL
 */
public record Route(String id, String method, String path) {

    /**
     * Matches request paths and template names to routes, most specific template first.
     */
    public static final class Index {

        private record Entry(Route route, Pattern pattern, int literals, int variables) {
        }

        private final List<Entry> entries = new ArrayList<>();

        /**
         * Creates an index.
         *
         * @param routes the suite's APIs
         */
        public Index(List<Route> routes) {
            for (Route r : routes) {
                String[] segments = r.path().replaceAll("/+$", "").split("/");
                StringBuilder re = new StringBuilder();
                int literals = 0;
                int variables = 0;
                for (String seg : segments) {
                    if (seg.isEmpty()) {
                        continue;
                    }
                    re.append('/');
                    if (seg.contains("{")) {
                        re.append("[^/]+");
                        variables++;
                    } else {
                        re.append(Pattern.quote(seg));
                        literals++;
                    }
                }
                entries.add(new Entry(r, Pattern.compile(re.isEmpty() ? "/" : re.toString(), Pattern.CASE_INSENSITIVE),
                        literals, variables));
            }
            entries.sort(Comparator.<Entry>comparingInt(e -> -e.literals()).thenComparingInt(Entry::variables));
        }

        /**
         * The route a request hit.
         *
         * @param method HTTP method
         * @param path   request path without query and context path (ids filled in)
         * @return the route, if one matches
         */
        public Optional<Route> match(String method, String path) {
            String p = path.replaceAll("/+$", "");
            String m = method.toUpperCase(Locale.ROOT);
            for (Entry e : entries) {
                if (e.route().method().equals(m) && e.pattern().matcher(p.isEmpty() ? "/" : p).matches()) {
                    return Optional.of(e.route());
                }
            }
            return Optional.empty();
        }

        /**
         * The route a URI template (a Prometheus {@code uri} label) names; variable names do not matter.
         *
         * @param method   HTTP method
         * @param template e.g. {@code /api/orders/{orderId}}
         * @return the route, if one has the same shape
         */
        public Optional<Route> matchTemplate(String method, String template) {
            String shape = shape(template);
            String m = method.toUpperCase(Locale.ROOT);
            for (Entry e : entries) {
                if (e.route().method().equals(m) && shape(e.route().path()).equals(shape)) {
                    return Optional.of(e.route());
                }
            }
            return match(method, template.replaceAll("\\{[^}]+}", "1")); // a template with a literal where we have a variable
        }

        private static String shape(String path) {
            return path.replaceAll("\\{[^}]*}", "{}").replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        }
    }
}
