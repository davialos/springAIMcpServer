package com.springaimcpservercommon.ruleengine.eval;

import com.springaimcpservercommon.ruleengine.repo.BundleRepository;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the text of a message bundle in the caller's language and fills in placeholders. One resolver serves one
 * evaluation: it remembers the bundles it loaded. Language order: the requested one, the tenant's default, English,
 * then whatever language the bundle has. {@code {customer.age}} is replaced by the value the caller supplied; a
 * placeholder without a value stays visible so a bad template is noticed.
 */
public final class MessageResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][\\w]*(?:\\.[\\w]+)*)}");

    private final BundleRepository bundles;
    private final List<String> languages;
    private final Map<String, String> values;
    private final Map<Long, Map<String, String>> loaded = new HashMap<>();

    /**
     * Creates a resolver.
     *
     * @param bundles   the bundle store
     * @param languages preferred languages, best first
     * @param values    placeholder values: {@code object.attribute} → value as text
     */
    public MessageResolver(BundleRepository bundles, List<String> languages, Map<String, String> values) {
        this.bundles = bundles;
        this.languages = languages;
        this.values = values;
    }

    /**
     * The message of a bundle.
     *
     * @param bundleId the bundle, or {@code null}
     * @param extra    more placeholder values ({@code rule.code}, {@code group.code})
     * @return the text, or {@code null} when there is no bundle or it has no text at all
     */
    public @Nullable String text(@Nullable Long bundleId, Map<String, String> extra) {
        if (bundleId == null) {
            return null;
        }
        Map<String, String> texts = loaded.computeIfAbsent(bundleId,
                id -> bundles.texts(List.of(id)).getOrDefault(id, Map.of()));
        String template = null;
        for (String language : languages) {
            template = texts.get(language);
            if (template != null) {
                break;
            }
        }
        if (template == null && !texts.isEmpty()) {
            template = texts.values().iterator().next();
        }
        return template == null ? null : fill(template, extra);
    }

    private String fill(String template, Map<String, String> extra) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = extra.getOrDefault(m.group(1), values.get(m.group(1)));
            m.appendReplacement(out, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Flattens the caller's context into placeholder values.
     *
     * @param context object → attribute → value
     * @return {@code object.attribute} → text
     */
    public static Map<String, String> flatten(Map<String, Map<String, Object>> context) {
        Map<String, String> out = new HashMap<>();
        context.forEach((object, attributes) -> attributes.forEach((attribute, value) -> {
            if (value != null) {
                out.put(object + "." + attribute, String.valueOf(value));
            }
        }));
        return out;
    }
}
