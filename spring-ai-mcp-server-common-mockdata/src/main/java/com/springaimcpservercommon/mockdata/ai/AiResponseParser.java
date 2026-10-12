package com.springaimcpservercommon.mockdata.ai;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** Turns a model's answer (a JSON array, possibly wrapped in prose or a code fence) into objects. */
public final class AiResponseParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private AiResponseParser() {
    }

    /**
     * Parses the first top-level JSON array of the text.
     *
     * @param answer the model's answer
     * @param type   element type
     * @param <T>    the type
     * @return the objects
     * @throws AiMockException when there is no array or it does not match the type
     */
    public static <T> List<T> parse(String answer, Class<T> type) throws AiMockException {
        if (answer == null) {
            throw new AiMockException("the model returned no content");
        }
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new AiMockException("the model's answer contains no JSON array");
        }
        try {
            return MAPPER.readValue(answer.substring(start, end + 1), MAPPER.getTypeFactory().constructCollectionType(List.class, type));
        } catch (RuntimeException e) {
            // the message of a data-binding error can quote the offending value: keep only its class
            throw new AiMockException("the model's answer does not match " + type.getSimpleName() + " (" + e.getClass().getSimpleName() + ")");
        }
    }
}
