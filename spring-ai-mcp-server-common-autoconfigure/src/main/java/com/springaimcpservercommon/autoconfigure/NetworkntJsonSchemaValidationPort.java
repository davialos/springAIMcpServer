package com.springaimcpservercommon.autoconfigure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.springaimcpservercommon.ai.advisor.JsonSchemaValidationPort;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Set;

/**
 * {@link JsonSchemaValidationPort} backed by {@code com.networknt:json-schema-validator}
 * (JSON Schema draft-07).
 *
 * <p>Package-private — instantiated exclusively through
 * {@link DaiAiAutoConfiguration.NetworkntSchemaConfiguration} when networknt is on the classpath.
 */
@NullMarked
final class NetworkntJsonSchemaValidationPort implements JsonSchemaValidationPort {

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    // Jackson ObjectMapper is instantiated locally — never registered as a bean (ADR-0019).
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<String> validate(String jsonSchema, String jsonDocument) {
        try {
            JsonSchema schema = FACTORY.getSchema(jsonSchema);
            JsonNode node = MAPPER.readTree(jsonDocument);
            Set<ValidationMessage> errors = schema.validate(node);
            if (errors.isEmpty()) {
                return List.of();
            }
            return errors.stream()
                    .map(ValidationMessage::getMessage)
                    .toList();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "jsonDocument is not valid JSON: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "JSON Schema validation error: " + e.getMessage(), e);
        }
    }
}
