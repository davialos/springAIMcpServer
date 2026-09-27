package com.springaimcpservercommon.security.authz.condition;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.security.internal.TtlCache;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Default {@link GrantConditionEvaluator}: parses with {@link ConditionParser} and caches the parsed form per grant id
 * and content digest (an edited grant gets a new digest, so stale parses are never used).
 */
public final class ParsingGrantConditionEvaluator implements GrantConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ParsingGrantConditionEvaluator.class);

    /** Cached parse result; {@code conditions == null} means invalid. */
    private record Parsed(@Nullable GrantConditions conditions) {
    }

    private final ConditionParser parser;
    private final TtlCache<String, Parsed> cache;

    /**
     * Creates an evaluator with a cache of 10 000 parsed grants for up to one hour.
     *
     * @param clock time source
     */
    public ParsingGrantConditionEvaluator(Clock clock) {
        this(new ConditionParser(), new TtlCache<>(10_000, Duration.ofHours(1), clock));
    }

    private ParsingGrantConditionEvaluator(ConditionParser parser, TtlCache<String, Parsed> cache) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.cache = cache;
    }

    @Override
    public Result evaluate(UUID grantId, String conditionsJson, ConditionContext context) {
        String key = grantId + "|" + Sha256.of(conditionsJson);
        Parsed parsed = cache.get(key).orElseGet(() -> {
            Parsed result;
            try {
                result = new Parsed(parser.parse(conditionsJson));
            } catch (ConditionParseException e) {
                log.warn("grant {} conditions rejected: {}", grantId, e.getMessage());
                result = new Parsed(null);
            }
            cache.put(key, result);
            return result;
        });
        GrantConditions conditions = parsed.conditions();
        if (conditions == null) {
            return Result.INVALID;
        }
        return conditions.test(context) ? Result.SATISFIED : Result.NOT_SATISFIED;
    }
}
