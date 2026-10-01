package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.model.ParamLocation;

/**
 * The one naming scheme for request fields, shared by the data plan, the generated JS and {@code data/user.json}:
 * <ul>
 *   <li>{@code <apiId>.path.<name>}, {@code <apiId>.query.<name>}, {@code <apiId>.header.<name>}</li>
 *   <li>{@code <apiId>.body} / {@code <apiId>.body.<prop>[.<prop>…]} for inline bodies</li>
 *   <li>{@code <SchemaName>.<prop>[.<prop>…]} for properties of named DTOs, shared by every API that uses them</li>
 * </ul>
 */
public final class FieldKeys {

    private FieldKeys() {
    }

    /**
     * Key of a parameter.
     *
     * @param apiId    endpoint id
     * @param location location
     * @param name     parameter name
     * @return key
     */
    public static String param(String apiId, ParamLocation location, String name) {
        return apiId + "." + location.segment() + "." + name;
    }

    /**
     * Key of an inline body.
     *
     * @param apiId endpoint id
     * @return key
     */
    public static String body(String apiId) {
        return apiId + ".body";
    }

    /**
     * Key of a nested property.
     *
     * @param parent parent key (an API body key or a schema name)
     * @param prop   property name
     * @return key
     */
    public static String child(String parent, String prop) {
        return parent + "." + prop;
    }

    /**
     * Last segment of a key: the field's own name.
     *
     * @param key key
     * @return name
     */
    public static String name(String key) {
        return key.substring(key.lastIndexOf('.') + 1);
    }
}
