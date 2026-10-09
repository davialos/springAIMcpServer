package com.springaimcpservercommon.celfaker.importer;

import com.springaimcpservercommon.celfaker.contract.ApiRole;
import com.springaimcpservercommon.celfaker.contract.ApiSpec;
import com.springaimcpservercommon.celfaker.payload.JsonValues;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a cURL command (as copied from a terminal, browser DevTools "Copy as cURL" or Postman) into an {@link ApiSpec}.
 * Secrets in well-known headers are never kept: the value becomes an {@code {{env.NAME}}} placeholder.
 */
public final class CurlParser {

    private static final Set<String> SECRET_HEADERS = Set.of("authorization", "cookie", "x-api-key", "api-key",
            "x-auth-token", "x-access-token", "proxy-authorization");
    private static final Set<String> IGNORED = Set.of("-s", "--silent", "-S", "--show-error", "-i", "--include", "-L",
            "--location", "-k", "--insecure", "--compressed", "-v", "--verbose", "-f", "--fail", "-g", "--globoff",
            "-N", "--no-buffer", "--http1.1", "--http2", "-#", "--progress-bar", "--fail-with-body", "-sS", "-sk", "-sSL");
    private static final Set<String> WITH_VALUE_IGNORED = Set.of("-o", "--output", "-m", "--max-time", "--connect-timeout",
            "-A", "--user-agent", "-e", "--referer", "--retry", "-x", "--proxy", "-w", "--write-out", "--cacert", "--cert", "--key");

    private CurlParser() {
    }

    /**
     * Parses a command.
     *
     * @param command the cURL command, possibly multi-line with backslash continuations
     * @return the API, base URL and warnings
     * @throws IllegalArgumentException when no URL can be found or the quoting is broken
     */
    public static Imported parse(String command) {
        List<String> t = tokenize(command);
        if (!t.isEmpty() && t.getFirst().equals("curl")) {
            t = t.subList(1, t.size());
        }
        String method = null;
        String url = null;
        List<String> data = new ArrayList<>();
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        boolean get = false;
        boolean json = false;
        for (int i = 0; i < t.size(); i++) {
            String a = t.get(i);
            String inline = null;
            if (a.startsWith("--") && a.contains("=")) {
                inline = a.substring(a.indexOf('=') + 1);
                a = a.substring(0, a.indexOf('='));
            }
            switch (a) {
                case "-X", "--request" -> method = value(t, i, inline).toUpperCase(Locale.ROOT);
                case "-H", "--header" -> {
                    String h = value(t, i, inline);
                    int c = h.indexOf(':');
                    if (c > 0) {
                        headers.put(h.substring(0, c).trim(), h.substring(c + 1).trim());
                    }
                }
                case "-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode" -> data.add(value(t, i, inline));
                case "--json" -> {
                    data.add(value(t, i, inline));
                    json = true;
                }
                case "-G", "--get" -> get = true;
                case "--url" -> url = value(t, i, inline);
                case "-u", "--user" -> {
                    value(t, i, inline);
                    headers.put("Authorization", "Basic {{env.BASIC_AUTH}}");
                    warnings.add("credentials of -u were replaced by {{env.BASIC_AUTH}} (base64 of user:password); pass it with -e BASIC_AUTH=…");
                }
                case "-F", "--form" -> {
                    value(t, i, inline);
                    warnings.add("multipart form data (-F) is not supported; the field was ignored");
                }
                case "-b", "--cookie" -> {
                    value(t, i, inline);
                    headers.put("Cookie", "{{env.COOKIE}}");
                }
                default -> {
                    if (WITH_VALUE_IGNORED.contains(a)) {
                        value(t, i, inline);
                    } else if (!IGNORED.contains(a) && !a.startsWith("-") && url == null) {
                        url = a;
                    } else if (!IGNORED.contains(a) && a.startsWith("-") && !a.startsWith("--")
                            && a.length() > 2 && !a.equals("-sS")) {
                        // combined short flags such as -sSL: ignore
                        continue;
                    }
                }
            }
            if (consumesValue(a) && inline == null) {
                i++;
            }
        }
        if (url == null) {
            throw new IllegalArgumentException("no URL found in the cURL command");
        }
        URI uri = URI.create(url.contains("://") ? url : "http://" + url);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("not a valid URL: " + url);
        }
        String base = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        if (get && !data.isEmpty()) {
            path += (path.contains("?") ? "&" : "?") + String.join("&", data);
            data = new ArrayList<>();
        }
        if (method == null) {
            method = data.isEmpty() ? "GET" : "POST";
        }
        for (Map.Entry<String, String> h : new ArrayList<>(headers.entrySet())) {
            if (SECRET_HEADERS.contains(h.getKey().toLowerCase(Locale.ROOT)) && !h.getValue().contains("{{env.")) {
                String name = h.getKey().toUpperCase(Locale.ROOT).replace('-', '_');
                String scheme = h.getValue().contains(" ") ? h.getValue().substring(0, h.getValue().indexOf(' ') + 1) : "";
                headers.put(h.getKey(), scheme + "{{env." + name + "}}");
                warnings.add("the value of header " + h.getKey() + " was replaced by {{env." + name + "}}; pass it with -e " + name + "=…");
            }
        }
        headers.keySet().removeIf(k -> k.equalsIgnoreCase("content-length") || k.equalsIgnoreCase("host"));
        JsonNode body = null;
        if (!data.isEmpty()) {
            String raw = String.join("&", data);
            try {
                body = JsonValues.MAPPER.readTree(raw);
            } catch (RuntimeException e) {
                warnings.add("the body is not JSON; only JSON bodies can be faked, the raw text was dropped");
            }
            if (body != null && !body.isObject() && !body.isArray()) {
                body = null;
            }
            if (json) {
                headers.putIfAbsent("Content-Type", "application/json");
            }
        }
        String id = id(method, uri.getRawPath());
        ApiSpec api = new ApiSpec(id, method + " " + (uri.getRawPath() == null ? "/" : uri.getRawPath()), method, path,
                "Imported from cURL.", ApiRole.ACTION, null, headers, body, null, List.of(), List.of(), List.of(), null, null, null, null);
        return new Imported(id, base, List.of(api), warnings);
    }

    private static boolean consumesValue(String a) {
        return Set.of("-X", "--request", "-H", "--header", "-d", "--data", "--data-raw", "--data-binary", "--data-ascii",
                "--data-urlencode", "--json", "--url", "-u", "--user", "-F", "--form", "-b", "--cookie").contains(a)
                || WITH_VALUE_IGNORED.contains(a);
    }

    private static String value(List<String> t, int i, String inline) {
        if (inline != null) {
            return inline;
        }
        if (i + 1 >= t.size()) {
            throw new IllegalArgumentException("option " + t.get(i) + " needs a value");
        }
        return t.get(i + 1);
    }

    static String id(String method, String path) {
        String p = path == null ? "" : path.replaceAll("\\{([^}]*)}", "By_$1").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
        String m = method.toLowerCase(Locale.ROOT);
        String camel = p.isEmpty() ? "root" : p;
        StringBuilder sb = new StringBuilder(m);
        for (String part : camel.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    /** Shell-like tokenizer: single and double quotes, {@code $'…'}, backslash escapes and line continuations. */
    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        String in = s.replace("\\\r\n", " ").replace("\\\n", " ").replace("^\r\n", " ").trim();
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '\'' || (c == '$' && i + 1 < in.length() && in.charAt(i + 1) == '\'')) {
                boolean ansi = c == '$';
                i += ansi ? 2 : 1;
                inToken = true;
                for (; i < in.length() && in.charAt(i) != '\''; i++) {
                    if (ansi && in.charAt(i) == '\\' && i + 1 < in.length()) {
                        char n = in.charAt(++i);
                        cur.append(n == 'n' ? '\n' : n == 't' ? '\t' : n == 'r' ? '\r' : n);
                    } else {
                        cur.append(in.charAt(i));
                    }
                }
                if (i >= in.length()) {
                    throw new IllegalArgumentException("unterminated single quote");
                }
            } else if (c == '"') {
                inToken = true;
                for (i++; i < in.length() && in.charAt(i) != '"'; i++) {
                    if (in.charAt(i) == '\\' && i + 1 < in.length() && "\"\\$`".indexOf(in.charAt(i + 1)) >= 0) {
                        i++;
                    }
                    cur.append(in.charAt(i));
                }
                if (i >= in.length()) {
                    throw new IllegalArgumentException("unterminated double quote");
                }
            } else if (c == '\\' && i + 1 < in.length()) {
                cur.append(in.charAt(++i));
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            out.add(cur.toString());
        }
        return out;
    }
}
