package com.springaimcpservercommon.ruleengine.model;

import java.util.UUID;

/**
 * One entry of the parameter library: sys object + sys object attribute, addressed in CEL as
 * {@code objectCode.attributeCode} (e.g. {@code customer.age}).
 *
 * @param attributeId   id of the attribute row
 * @param objectCode    sys object code
 * @param attributeCode attribute code
 * @param dataType      declared type
 * @param required      metadata for UIs: callers are expected to send it
 */
public record Parameter(UUID attributeId, String objectCode, String attributeCode, DataType dataType, boolean required) {

    /**
     * The CEL variable name.
     *
     * @return {@code objectCode.attributeCode}
     */
    public String celName() {
        return objectCode + "." + attributeCode;
    }
}
