package com.springaimcpservercommon.annotations;

import java.util.random.RandomGenerator;

/**
 * Supplies values for {@link CustomFakeData} fields. Implementations must be thread-safe: one instance serves all
 * virtual users of a simulation.
 */
@FunctionalInterface
public interface CustomFakerProvider {

    /**
     * Produces one value.
     *
     * @param random the engine's random source (seeded when the engine is, so runs can be reproduced)
     * @return a value assignable to the annotated field's type (or convertible from a string)
     */
    Object provide(RandomGenerator random);
}
