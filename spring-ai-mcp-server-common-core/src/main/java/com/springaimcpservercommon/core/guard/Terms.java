package com.springaimcpservercommon.core.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns text into comparable content terms for {@link BusinessVocabulary}: splits camelCase and snake_case, lower
 * cases, drops stop words (English function words, request verbs such as "show" or "list", greetings) and numbers,
 * and reduces simple inflections ("invoices" → "invoice", "shipped" → "ship"). Applied identically to catalog text
 * and prompts, so imperfect stems still meet.
 */
final class Terms {

    private static final Pattern CAMEL = Pattern.compile("(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})");
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Function words, request verbs, pronouns, greetings: they say nothing about the business domain. */
    static final Set<String> STOP_WORDS = Set.of(
            "a", "about", "above", "after", "again", "against", "all", "also", "am", "an", "and", "any", "anything",
            "are", "as", "at", "be", "because", "been", "before", "being", "below", "between", "both", "but", "by",
            "can", "could", "did", "do", "does", "doing", "done", "down", "during", "each", "either", "else", "etc",
            "ever", "every", "few", "for", "from", "further", "get", "gets", "getting", "give", "given", "go", "got",
            "had", "has", "have", "having", "he", "her", "here", "hers", "him", "his", "how", "i", "if", "in", "into",
            "is", "it", "its", "just", "let", "lets", "like", "may", "me", "might", "mine", "more", "most", "much",
            "must", "my", "myself", "need", "no", "nor", "not", "now", "of", "off", "on", "once", "one", "only", "or",
            "other", "our", "ours", "out", "over", "own", "per", "please", "same", "shall", "she", "should", "so",
            "some", "such", "than", "that", "the", "their", "them", "then", "there", "these", "they", "this", "those",
            "through", "to", "too", "under", "until", "up", "upon", "us", "very", "via", "was", "we", "were", "what",
            "when", "where", "whether", "which", "while", "who", "whom", "whose", "why", "will", "with", "within",
            "without", "would", "yes", "yet", "you", "your", "yours", "yourself",
            // request verbs and generic nouns that every question uses
            "show", "list", "find", "fetch", "tell", "display", "provide", "return", "search", "look", "lookup",
            "see", "check", "know", "want", "help", "explain", "describe", "summarize", "summarise", "count", "many",
            "total", "number", "numbers", "info", "information", "detail", "details", "data", "record", "records",
            "item", "items", "thing", "things", "value", "values", "result", "results", "latest", "last", "first",
            "next", "previous", "new", "old", "recent", "today", "yesterday", "tomorrow", "week", "month", "year",
            "day", "time", "date", "top", "best", "ok", "okay", "hi", "hello", "hey", "thanks", "thank", "thx",
            "bye", "goodbye", "sure", "great", "good", "fine", "id", "ids", "name", "names");

    private Terms() {
    }

    /**
     * Content terms of a text, in order, duplicates kept.
     *
     * @param text any text
     * @return stemmed content terms
     */
    static List<String> of(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : SPLIT.split(CAMEL.matcher(text).replaceAll(" "))) {
            if (raw.isEmpty()) {
                continue;
            }
            String word = raw.toLowerCase(Locale.ROOT);
            if (word.length() < 2 || STOP_WORDS.contains(word) || word.chars().allMatch(Character::isDigit)) {
                continue;
            }
            String stem = stem(word);
            if (!STOP_WORDS.contains(stem)) {
                out.add(stem);
            }
        }
        return out;
    }

    /** Light suffix stripping (plurals, -ing, -ed); good enough when applied to both sides. */
    static String stem(String w) {
        String s = w;
        if (s.length() > 4 && s.endsWith("ies")) {
            s = s.substring(0, s.length() - 3) + "y";
        } else if (s.length() > 4 && s.endsWith("sses")) {
            s = s.substring(0, s.length() - 2);
        } else if (s.length() > 4 && (s.endsWith("xes") || s.endsWith("ches") || s.endsWith("shes")
                || s.endsWith("zes"))) {
            s = s.substring(0, s.length() - 2);
        } else if (s.length() > 3 && s.endsWith("s") && !s.endsWith("ss") && !s.endsWith("us")
                && !s.endsWith("is")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() > 5 && s.endsWith("ing")) {
            s = s.substring(0, s.length() - 3);
        } else if (s.length() > 4 && s.endsWith("ed")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }
}
