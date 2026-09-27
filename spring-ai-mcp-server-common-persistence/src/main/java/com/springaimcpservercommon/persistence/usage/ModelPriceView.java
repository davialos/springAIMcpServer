package com.springaimcpservercommon.persistence.usage;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Immutable view of a model price version.
 *
 * @param provider                 model provider
 * @param model                    model name
 * @param validFrom                start of validity
 * @param currency                 ISO-4217 code
 * @param inputPerMtokMicros       price per million input tokens (micros)
 * @param outputPerMtokMicros      price per million output tokens (micros)
 * @param cachedInputPerMtokMicros price per million cached input tokens (micros)
 */
public record ModelPriceView(
        String provider,
        String model,
        Instant validFrom,
        String currency,
        long inputPerMtokMicros,
        long outputPerMtokMicros,
        long cachedInputPerMtokMicros) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    /**
     * Cost of a call in micros of {@link #currency()}, rounded half-up to whole micros. {@code inputTokens} are the
     * non-cached input tokens.
     *
     * @param inputTokens       non-cached input tokens
     * @param outputTokens      output tokens
     * @param cachedInputTokens cached input tokens
     * @return cost in micros
     * @throws ArithmeticException if the result does not fit in a {@code long}
     */
    public long costMicros(long inputTokens, long outputTokens, long cachedInputTokens) {
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        BigInteger total = BigInteger.valueOf(inputTokens).multiply(BigInteger.valueOf(inputPerMtokMicros))
                .add(BigInteger.valueOf(outputTokens).multiply(BigInteger.valueOf(outputPerMtokMicros)))
                .add(BigInteger.valueOf(cachedInputTokens).multiply(BigInteger.valueOf(cachedInputPerMtokMicros)));
        return new BigDecimal(total).divide(MILLION, 0, RoundingMode.HALF_UP).longValueExact();
    }
}
