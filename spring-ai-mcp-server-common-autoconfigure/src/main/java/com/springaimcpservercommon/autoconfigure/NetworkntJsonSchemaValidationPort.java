package com.springaimcpservercommon.autoconfigure;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.springaimcpservercommon.ai.advisor.JsonSchemaValidationPort;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * {@link JsonSchemaValidationPort} backed by {@code com.networknt:json-schema-validator} 3.x
 * (JSON Schema draft-07).
 *
 * <p>Package-private — instantiated exclusively through
 * {@link DaiAiAutoConfiguration.NetworkntSchemaConfiguration} when networknt is on the classpath.
 * The document is validated from its string form, so no Jackson mapper is needed here (ADR-0019).
 */
@NullMarked
final class NetworkntJsonSchemaValidationPort implements JsonSchemaValidationPort {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);

    @Override
    public List<String> validate(String jsonSchema, String jsonDocument) {
        try {
            Schema schema = REGISTRY.getSchema(jsonSchema);
            List<Error> errors = schema.validate(jsonDocument, InputFormat.JSON);
            return errors.stream().map(Error::getMessage).toList();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "JSON Schema validation error: " + e.getClass().getSimpleName(), e);
        }
    }
}
