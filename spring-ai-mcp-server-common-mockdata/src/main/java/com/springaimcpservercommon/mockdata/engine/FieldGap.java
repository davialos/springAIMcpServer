package com.springaimcpservercommon.mockdata.engine;

/**
 * A field the Datafaker engine could not (or was asked not to) fill with a good value. The engine still puts a
 * placeholder there; a fallback may replace it.
 *
 * @param path   dotted path from the root object ({@code address.city})
 * @param type   the field's raw type
 * @param reason why it is a gap
 * @param hint   plain-English description for an AI model (the {@code @FakeAi} text), empty if none
 */
public record FieldGap(String path, Class<?> type, Reason reason, String hint) {

    /** Why a field is a gap. */
    public enum Reason {
        /** The field is marked {@code @FakeAi} (or the engine runs in AI mode and the field has no annotation). */
        AI_REQUESTED,
        /** No generator for this type (interface, abstract class, class without a no-argument constructor, ...). */
        UNSUPPORTED_TYPE,
        /** The Datafaker expression failed or its text could not be converted to the field's type. */
        EXPRESSION_FAILED,
        /** The object graph is deeper than the configured limit. */
        DEPTH_LIMIT
    }
}
