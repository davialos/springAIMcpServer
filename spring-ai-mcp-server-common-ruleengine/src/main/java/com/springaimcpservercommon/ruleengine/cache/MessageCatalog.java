package com.springaimcpservercommon.ruleengine.cache;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Multilingual messages (sys bundle): one text per language per bundle. Immutable.
 *
 * <p>Language fallback for a request: each requested tag exactly, then its primary language ({@code pt-BR} →
 * {@code pt}), then the default language, then the alphabetically first available text.
 */
public final class MessageCatalog {

    /**
     * A resolved message.
     *
     * @param language the language actually used
     * @param text     the text
     */
    public record Localized(String language, String text) {
    }

    private final Map<UUID, Map<String, String>> bundles;
    private final String defaultLanguage;

    /**
     * Creates the catalog.
     *
     * @param bundles         bundle id → language tag → text
     * @param defaultLanguage fallback language tag, e.g. {@code en}
     */
    public MessageCatalog(Map<UUID, Map<String, String>> bundles, String defaultLanguage) {
        Map<UUID, Map<String, String>> copy = new java.util.HashMap<>();
        bundles.forEach((id, texts) -> {
            Map<String, String> lower = new TreeMap<>();
            texts.forEach((lang, text) -> lower.put(lang.toLowerCase(Locale.ROOT), text));
            copy.put(id, Map.copyOf(lower));
        });
        this.bundles = Map.copyOf(copy);
        this.defaultLanguage = defaultLanguage.toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves a bundle for the caller's languages.
     *
     * @param bundleId  the bundle, or {@code null} for "no message"
     * @param languages preferred language tags, most preferred first (may be empty)
     * @return the message, or {@code null} when there is no bundle or it has no text at all
     */
    public @Nullable Localized resolve(@Nullable UUID bundleId, List<String> languages) {
        if (bundleId == null) {
            return null;
        }
        Map<String, String> texts = bundles.get(bundleId);
        if (texts == null || texts.isEmpty()) {
            return null;
        }
        List<String> chain = new ArrayList<>();
        for (String tag : languages) {
            String lower = tag.toLowerCase(Locale.ROOT).replace('_', '-');
            chain.add(lower);
            int dash = lower.indexOf('-');
            if (dash > 0) {
                chain.add(lower.substring(0, dash));
            }
        }
        chain.add(defaultLanguage);
        for (String lang : chain) {
            String text = texts.get(lang);
            if (text != null) {
                return new Localized(lang, text);
            }
        }
        Map.Entry<String, String> first = ((TreeMap<String, String>) new TreeMap<>(texts)).firstEntry();
        return new Localized(first.getKey(), first.getValue());
    }
}
