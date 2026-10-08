package com.springaimcpservercommon.celfaker.payload;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conversions between JSON trees and the plain Java values (String, Long, Double, Boolean, List, Map) CEL facts use. */
public final class JsonValues {

    /** Shared mapper (thread-safe, immutable). */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private JsonValues() {
    }

    /**
     * Converts a JSON node to a plain Java value.
     *
     * @param node the node
     * @return {@code null}, String, Long, Double, Boolean, List or Map
     */
    public static Object toJava(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue().bitLength() < 64 ? (Object) node.longValue() : (Object) node.doubleValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>(node.size());
            for (JsonNode e : node) {
                out.add(toJava(e));
            }
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            out.put(e.getKey(), toJava(e.getValue()));
        }
        return out;
    }

    /**
     * Converts a plain Java value to a JSON node.
     *
     * @param value the value
     * @return the node
     */
    public static JsonNode toNode(Object value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * Reads the value at a dotted path ({@code a.b.c}); {@code [n]} indexes arrays.
     *
     * @param root the document
     * @param path the path
     * @return the node, or a missing node
     */
    public static JsonNode at(JsonNode root, String path) {
        JsonNode cur = root;
        for (String seg : segments(path)) {
            cur = seg.startsWith("[") ? cur.path(Integer.parseInt(seg.substring(1, seg.length() - 1))) : cur.path(seg);
        }
        return cur;
    }

    /**
     * Sets the value at a dotted path, creating intermediate objects.
     *
     * @param root  the object to modify
     * @param path  the path
     * @param value the new value
     */
    public static void set(ObjectNode root, String path, JsonNode value) {
        List<String> segs = segments(path);
        JsonNode cur = root;
        for (int i = 0; i < segs.size() - 1; i++) {
            String seg = segs.get(i);
            JsonNode next = seg.startsWith("[") ? cur.path(Integer.parseInt(seg.substring(1, seg.length() - 1))) : cur.path(seg);
            if (!next.isObject() && !next.isArray()) {
                next = ((ObjectNode) cur).putObject(seg);
            }
            cur = next;
        }
        String last = segs.getLast();
        if (cur instanceof ObjectNode o) {
            o.set(last, value);
        } else if (cur instanceof ArrayNode a && last.startsWith("[")) {
            a.set(Integer.parseInt(last.substring(1, last.length() - 1)), value);
        }
    }

    /**
     * Removes the value at a dotted path.
     *
     * @param root the object to modify
     * @param path the path
     */
    public static void remove(ObjectNode root, String path) {
        List<String> segs = segments(path);
        JsonNode parent = segs.size() == 1 ? root : at(root, String.join(".", segs.subList(0, segs.size() - 1)));
        if (parent instanceof ObjectNode o) {
            o.remove(segs.getLast());
        }
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("\\.")) {
            int br = part.indexOf('[');
            if (br < 0) {
                out.add(part);
                continue;
            }
            if (br > 0) {
                out.add(part.substring(0, br));
            }
            for (String idx : part.substring(br).split("(?<=\\])")) {
                out.add(idx);
            }
        }
        return out;
    }

    /**
     * Whether a big integer fits a CEL int.
     *
     * @param value the number
     * @return true when it fits 64 bits
     */
    public static boolean fitsLong(BigInteger value) {
        return value.bitLength() < 64;
    }
}
