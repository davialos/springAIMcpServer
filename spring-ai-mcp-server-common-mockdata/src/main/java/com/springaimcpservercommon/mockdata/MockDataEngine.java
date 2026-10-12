package com.springaimcpservercommon.mockdata;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates populated instances of annotated classes and records ({@code @FakeData}, {@code @CustomFakeData},
 * {@code @FakeAi}, {@code @FakeIgnore}); unannotated fields are filled from their name and type. Implementations are
 * thread-safe: every virtual user of a simulation shares one engine.
 */
public interface MockDataEngine {

    /**
     * Generates one instance.
     *
     * @param type class with a no-argument constructor, or a record
     * @param <T>  its type
     * @return a populated instance (never {@code null}; fields the engine cannot produce keep their defaults)
     */
    <T> T generate(Class<T> type);

    /**
     * Generates several instances.
     *
     * @param type  the class or record
     * @param count how many
     * @param <T>   its type
     * @return {@code count} instances
     */
    default <T> List<T> generate(Class<T> type, int count) {
        List<T> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(generate(type));
        }
        return out;
    }
}
