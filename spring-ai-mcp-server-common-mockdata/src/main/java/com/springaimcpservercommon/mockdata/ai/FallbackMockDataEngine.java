package com.springaimcpservercommon.mockdata.ai;

import com.springaimcpservercommon.mockdata.MockDataEngine;
import com.springaimcpservercommon.mockdata.engine.DatafakerEngine;
import com.springaimcpservercommon.mockdata.engine.FieldGap;
import com.springaimcpservercommon.mockdata.engine.Generated;
import com.springaimcpservercommon.mockdata.engine.PathAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Datafaker first, the AI where Datafaker has a gap. In {@link Mode#AI_FALLBACK} only the fields Datafaker cannot
 * produce (and {@code @FakeAi} fields) go to the model; in {@link Mode#AI} every unannotated field does. The model is
 * consulted through an {@link AiMockPool}, never once per generated object, and its absence or failure only means
 * placeholders stay (reported once per field in the log).
 */
public final class FallbackMockDataEngine implements MockDataEngine {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackMockDataEngine.class);

    /** Who fills what. */
    public enum Mode {
        /** Datafaker only; the AI is never asked. */
        FAKER,
        /** Datafaker, plus the AI for gaps and {@code @FakeAi} fields. */
        AI_FALLBACK,
        /** The AI for every field without a Datafaker annotation. */
        AI
    }

    private final DatafakerEngine engine;
    private final @Nullable AiMockPool pool;
    private final Mode mode;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /**
     * Creates the engine. For {@link Mode#AI} the Datafaker engine must be built with {@code aiForUnannotated}.
     *
     * @param engine the Datafaker engine
     * @param pool   AI sample pool, or {@code null} when no model is available
     * @param mode   who fills what
     */
    public FallbackMockDataEngine(DatafakerEngine engine, @Nullable AiMockPool pool, Mode mode) {
        this.engine = engine;
        this.pool = pool;
        this.mode = mode;
    }

    @Override
    public <T> T generate(Class<T> type) {
        Generated<T> first = engine.generate(type, Map.of());
        if (first.complete()) {
            return first.value();
        }
        if (mode == Mode.FAKER || pool == null) {
            report(type, first, mode == Mode.FAKER ? "AI is off" : "no AI model available");
            return first.value();
        }
        Optional<T> sample = pool.sample(type, first.gaps());
        if (sample.isEmpty()) {
            report(type, first, "the AI gave no sample");
            return first.value();
        }
        Map<String, Object> overrides = new HashMap<>();
        for (FieldGap gap : first.gaps()) {
            Object v = PathAccess.get(sample.get(), gap.path());
            if (v != null) {
                overrides.put(gap.path(), v);
            }
        }
        return overrides.isEmpty() ? first.value() : engine.generate(type, overrides).value();
    }

    /** Logs each (type, field, reason) once, so a load test of millions of objects does not flood the log. */
    private void report(Class<?> type, Generated<?> g, String why) {
        for (FieldGap gap : g.gaps()) {
            if (mode == Mode.AI && gap.reason() == FieldGap.Reason.AI_REQUESTED && gap.hint().isEmpty()) {
                continue; // heuristics are the expected result of AI mode without a model
            }
            if (reported.add(type.getName() + "#" + gap.path() + "#" + gap.reason())) {
                LOG.warn("Mock data for {}.{} is a placeholder ({}; {})", type.getSimpleName(), gap.path(), gap.reason(), why);
            }
        }
    }
}
