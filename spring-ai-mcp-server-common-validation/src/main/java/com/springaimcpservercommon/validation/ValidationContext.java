package com.springaimcpservercommon.validation;

import java.util.HashMap;
import java.util.Map;

/**
 * Where in the application a validation is being asked for. Rules and ordering overrides are selected by these
 * four coordinates (see {@link Scope}); any coordinate may be left unset.
 *
 * @param stage      processing stage, e.g. {@code CONTROLLER}, {@code SERVICE}, {@code PRE_PERSIST}, {@code EVENT}
 * @param state      business state of the subject, e.g. {@code DRAFT}, {@code SUBMITTED}
 * @param endpoint   API endpoint, conventionally {@code "<METHOD> <path>"}, e.g. {@code "POST /orders"}
 * @param action     user action, e.g. {@code CREATE}, {@code APPROVE}
 * @param attributes free-form extras a rule may read (caller roles, tenant, ...); never matched on
 */
public record ValidationContext(String stage, String state, String endpoint, String action,
                                Map<String, Object> attributes) {

    /** The empty context: matches only rules whose scope is fully open. */
    public static final ValidationContext NONE = new ValidationContext("", "", "", "", Map.of());

    /** Normalises nulls to empty strings and copies the attributes. */
    public ValidationContext {
        stage = stage == null ? "" : stage;
        state = state == null ? "" : state;
        endpoint = endpoint == null ? "" : endpoint;
        action = action == null ? "" : action;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * Creates a context.
     *
     * @param stage    processing stage
     * @param state    business state
     * @param endpoint API endpoint
     * @param action   user action
     * @return the context
     */
    public static ValidationContext of(String stage, String state, String endpoint, String action) {
        return new ValidationContext(stage, state, endpoint, action, Map.of());
    }

    /**
     * Copy with another stage, to re-validate the same request at a later point.
     *
     * @param newStage the stage
     * @return the copy
     */
    public ValidationContext withStage(String newStage) {
        return new ValidationContext(newStage, state, endpoint, action, attributes);
    }

    /**
     * Copy with another state.
     *
     * @param newState the state
     * @return the copy
     */
    public ValidationContext withState(String newState) {
        return new ValidationContext(stage, newState, endpoint, action, attributes);
    }

    /**
     * Copy with one more attribute.
     *
     * @param key   attribute name
     * @param value attribute value
     * @return the copy
     */
    public ValidationContext withAttribute(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(attributes);
        copy.put(key, value);
        return new ValidationContext(stage, state, endpoint, action, copy);
    }
}
