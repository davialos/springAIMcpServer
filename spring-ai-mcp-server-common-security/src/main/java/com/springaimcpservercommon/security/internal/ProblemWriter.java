package com.springaimcpservercommon.security.internal;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes minimal RFC 9457 problem responses from security filters (before any MVC infrastructure runs). Only fixed,
 * code-defined titles and codes are written — never exception messages or request data (SEC-02 I5).
 */
public final class ProblemWriter {

    /** Problem type base URI of the framework. */
    public static final String TYPE_BASE = "urn:dynamic-ai:problem:";

    private ProblemWriter() {
    }

    /**
     * Writes a problem response.
     *
     * @param response response (must not be committed)
     * @param status   HTTP status
     * @param code     stable problem code, {@code [a-z-]+}
     * @param title    fixed human-readable title
     * @throws IOException on write failure
     */
    public static void write(HttpServletResponse response, int status, String code, String title) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        String body = "{\"type\":\"" + TYPE_BASE + code + "\",\"title\":\"" + escape(title) + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\"}";
        response.getWriter().write(body);
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c >= 0x20) {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
