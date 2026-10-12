package com.springaimcpservercommon.mockdata.ai;

import com.springaimcpservercommon.mockdata.engine.FieldGap;

import java.util.List;

/**
 * Port to an AI model that produces realistic mock objects. The Spring AI implementation lives in the autoconfigure
 * module; tests and hosts without a model use fakes. Implementations must honour timeouts: the pool calls this on a
 * request path.
 */
public interface AiMockGenerator {

    /**
     * Produces several objects of a type in one model call.
     *
     * @param type  the class or record
     * @param count how many objects to ask for
     * @param hints the fields that need the model's judgement, with their {@code @FakeAi} hints
     * @param <T>   the type
     * @return the objects (may be fewer than asked for)
     * @throws AiMockException when the model fails or answers with something unusable
     */
    <T> List<T> generate(Class<T> type, int count, List<FieldGap> hints) throws AiMockException;
}
