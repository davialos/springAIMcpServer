package com.springaimcpservercommon.core.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects secret-looking values in text that ends up in LLM prompts (descriptions, intents, meanings, keywords,
 * policy overrides). A match never echoes the secret: callers only get the name of the pattern that fired.
 *
 * <p>The patterns are deliberately conservative (high-signal token formats plus {@code key=value} assignments of
 * credential-like keys) so that ordinary prose such as "returns the password reset date" does not trigger.
 */
public final class SecretScanner {

    private record NamedPattern(String name, Pattern pattern) {
    }

    private static final List<NamedPattern> DEFAULT_PATTERNS = List.of(
            new NamedPattern("private-key-block", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
            new NamedPattern("aws-access-key-id", Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new NamedPattern("github-token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b")),
            new NamedPattern("slack-token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b")),
            new NamedPattern("api-key-prefixed", Pattern.compile("\\b(sk|pk|rk|csk)-[A-Za-z0-9_-]{20,}\\b")),
            new NamedPattern("google-api-key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
            new NamedPattern("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")),
            new NamedPattern("url-credentials", Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s/:@]+:[^\\s/@]+@")),
            new NamedPattern("credential-assignment", Pattern.compile(
                    "(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|token|client[_-]?secret)"
                            + "\\s*[:=]\\s*['\"]?[^\\s'\"]{6,}")));

    private final List<NamedPattern> patterns;

    /** Creates a scanner with the built-in patterns. */
    public SecretScanner() {
        this.patterns = DEFAULT_PATTERNS;
    }

    /**
     * Creates a scanner with the built-in patterns plus additional host-specific regular expressions.
     *
     * @param additionalPatterns extra regular expressions (e.g. internal token formats)
     */
    public SecretScanner(List<String> additionalPatterns) {
        List<NamedPattern> all = new ArrayList<>(DEFAULT_PATTERNS);
        for (int i = 0; i < additionalPatterns.size(); i++) {
            all.add(new NamedPattern("custom-" + i, Pattern.compile(additionalPatterns.get(i))));
        }
        this.patterns = List.copyOf(all);
    }

    /**
     * Returns the name of the first pattern that matches, without revealing the matched text.
     *
     * @param text text to check
     * @return the pattern name, or empty if the text looks clean
     */
    public Optional<String> findSecret(String text) {
        for (NamedPattern p : patterns) {
            if (p.pattern().matcher(text).find()) {
                return Optional.of(p.name());
            }
        }
        return Optional.empty();
    }
}
