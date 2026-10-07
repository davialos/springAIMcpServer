package com.springaimcpservercommon.ruleengine.library;

import com.springaimcpservercommon.ruleengine.domain.Model.SysAttribute;
import com.springaimcpservercommon.ruleengine.domain.Model.SysObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable snapshot of the parameter library: every sys object with its attributes. {@code object.attribute} is
 * the CEL name of an attribute ({@code customer.age}). The snapshot is what the application caches; rules are compiled
 * against it, and a new snapshot (with a new {@code version}) replaces it when the library changes.
 *
 * @param version a value that changes whenever the library does
 * @param objects objects by code
 */
public record ParameterLibrary(String version, Map<String, SysObject> objects) {

    /** Compact constructor: ordered, unmodifiable copy. */
    public ParameterLibrary {
        objects = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(objects));
    }

    /**
     * Builds a snapshot.
     *
     * @param version version stamp
     * @param list    the objects
     * @return the snapshot
     */
    public static ParameterLibrary of(String version, List<SysObject> list) {
        Map<String, SysObject> map = new LinkedHashMap<>();
        list.forEach(o -> map.put(o.code(), o));
        return new ParameterLibrary(version, map);
    }

    /**
     * An attribute.
     *
     * @param object    object code
     * @param attribute attribute code
     * @return the attribute, if the library has it
     */
    public Optional<SysAttribute> attribute(String object, String attribute) {
        SysObject o = objects.get(object);
        return o == null ? Optional.empty() : o.attributes().stream().filter(a -> a.code().equals(attribute)).findFirst();
    }

    /**
     * Every attribute as the CEL name an expression uses.
     *
     * @return {@code object.attribute} names, in library order
     */
    public List<String> celNames() {
        return objects.values().stream().flatMap(o -> o.attributes().stream().map(a -> o.code() + "." + a.code()))
                .toList();
    }
}
