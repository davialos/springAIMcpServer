package com.springaimcpservercommon.celfaker.payload;

import com.springaimcpservercommon.ruleengine.model.DataType;
import tools.jackson.databind.JsonNode;

/**
 * One value of a JSON payload that can become a parameter-library entry.
 *
 * @param path          dotted JSON path of the value inside the payload ({@code customer.address.city})
 * @param objectCode    sys object code (first half of the CEL name)
 * @param attributeCode sys object attribute code (second half of the CEL name)
 * @param type          CEL data type inferred from the sample
 * @param sample        the value found in the payload
 */
public record Candidate(String path, String objectCode, String attributeCode, DataType type, JsonNode sample) {

    /**
     * The CEL variable name.
     *
     * @return {@code objectCode.attributeCode}
     */
    public String celName() {
        return objectCode + "." + attributeCode;
    }
}
