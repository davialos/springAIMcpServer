package com.springaimcpservercommon.core.display;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An answer split into prose and structured data: a whole-JSON answer (a {@code JSON_SCHEMA} agent) is all data; a
 * text answer may carry one fenced {@code ```json} block, which becomes the data while the text around it stays
 * prose. Data is converted to plain Java values ({@link Map} in document order, {@link List}, {@link String},
 * {@link Number}, {@link Boolean}, {@code null}); nesting deeper than {@value #MAX_DEPTH} is cut.
 *
 * @param prose text of the answer without the data block (may be empty)
 * @param data  parsed data, or {@code null} when the answer carries none
 */
public record AnswerContent(String prose, @Nullable Object data) {

    /** Largest answer that is parsed for data. */
    public static final int MAX_JSON_CHARS = 1_000_000;
    /** Deepest nesting kept in the data. */
    public static final int MAX_DEPTH = 32;

    private static final Pattern FENCED = Pattern.compile("```(?:json|JSON)?[ \\t]*\\R(.*?)\\R?[ \\t]*```",
            Pattern.DOTALL);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * Splits an answer.
     *
     * @param answer the model's answer
     * @return prose and data
     */
    public static AnswerContent parse(String answer) {
        if (answer.length() > MAX_JSON_CHARS) {
            return new AnswerContent(answer, null);
        }
        String trimmed = answer.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            JsonNode node = readTree(trimmed);
            if (node != null) {
                return new AnswerContent("", toJava(node, 0));
            }
        }
        Matcher m = FENCED.matcher(answer);
        while (m.find()) {
            String body = m.group(1).strip();
            if (body.startsWith("{") || body.startsWith("[")) {
                JsonNode node = readTree(body);
                if (node != null) {
                    String prose = (answer.substring(0, m.start()) + answer.substring(m.end())).strip();
                    return new AnswerContent(prose, toJava(node, 0));
                }
            }
        }
        return new AnswerContent(answer, null);
    }

    /**
     * Parses JSON text into plain Java values.
     *
     * @param json JSON text
     * @return the value, or {@code null} when the text is not valid JSON
     */
    public static @Nullable Object parseJson(String json) {
        JsonNode node = readTree(json);
        return node == null ? null : toJava(node, 0);
    }

    private static @Nullable JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static @Nullable Object toJava(JsonNode n, int depth) {
        if (n.isObject()) {
            if (depth >= MAX_DEPTH) {
                return "{…}";
            }
            Map<String, @Nullable Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                map.put(e.getKey(), toJava(e.getValue(), depth + 1));
            }
            return map;
        }
        if (n.isArray()) {
            if (depth >= MAX_DEPTH) {
                return "[…]";
            }
            List<@Nullable Object> list = new ArrayList<>(n.size());
            for (JsonNode child : n.values()) {
                list.add(toJava(child, depth + 1));
            }
            return list;
        }
        if (n.isString()) {
            return n.stringValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isIntegralNumber()) {
            return n.canConvertToLong() ? (Object) n.longValue() : n.bigIntegerValue();
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        return null;
    }
}
