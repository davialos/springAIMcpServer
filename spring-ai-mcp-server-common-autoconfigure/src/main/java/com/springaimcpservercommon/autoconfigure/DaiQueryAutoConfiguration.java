package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.query.criteria.CriteriaCompiler;
import com.springaimcpservercommon.query.criteria.CriteriaQueryExecutor;
import com.springaimcpservercommon.query.execution.QueryExecutor;
import jakarta.persistence.EntityManagerFactory;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the dynamic query engine.
 *
 * <p>Activated when {@link CriteriaQueryExecutor} is on the classpath (query module present) and
 * the host's {@link EntityManagerFactory} bean is available. The query executor runs against the host's
 * JPA persistence unit, never the framework's isolated one.
 */
@AutoConfiguration(after = DaiCoreAutoConfiguration.class)
@ConditionalOnClass(CriteriaQueryExecutor.class)
@NullMarked
public class DaiQueryAutoConfiguration {

    /**
     * Default query executor. Concurrency cap and timeout come from {@link DaiProperties.Query};
     * the host may replace this with a custom {@link QueryExecutor} bean.
     *
     * @param entityManagerFactory the host's entity manager factory (injected by Spring)
     * @param props                framework properties
     * @return the executor
     */
    @Bean
    @ConditionalOnMissingBean(QueryExecutor.class)
    @ConditionalOnBean(EntityManagerFactory.class)
    public CriteriaQueryExecutor queryExecutor(EntityManagerFactory entityManagerFactory,
                                                DaiProperties props) {
        DaiProperties.Query q = props.query();
        int maxConcurrent = clamp(q.maxConcurrency(), 1, 200, CriteriaQueryExecutor.DEFAULT_MAX_CONCURRENT);
        int timeoutMs = clampMs(q.timeout(), CriteriaQueryExecutor.DEFAULT_TIMEOUT_MS);
        return new CriteriaQueryExecutor(entityManagerFactory, new CriteriaCompiler(), maxConcurrent, timeoutMs);
    }

    private static int clamp(int value, int min, int max, int defaultValue) {
        if (value < min || value > max) return defaultValue;
        return value;
    }

    private static int clampMs(java.time.Duration d, int defaultMs) {
        if (d == null || d.isNegative() || d.isZero()) return defaultMs;
        long ms = d.toMillis();
        if (ms < 1000) return 1000;
        if (ms > 300_000) return 300_000;
        return (int) ms;
    }
}
