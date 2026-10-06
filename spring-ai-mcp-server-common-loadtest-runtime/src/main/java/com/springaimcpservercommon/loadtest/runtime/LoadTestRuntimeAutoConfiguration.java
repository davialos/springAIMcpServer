package com.springaimcpservercommon.loadtest.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;

/**
 * Registers the {@code loadtest} actuator endpoint when {@code loadtest.runtime.enabled=true} (and no production
 * profile is active). Expose it over HTTP with {@code management.endpoints.web.exposure.include=loadtest}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Endpoint.class, RequestMappingHandlerMapping.class})
@ConditionalOnProperty(prefix = "loadtest.runtime", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(LoadTestRuntimeProperties.class)
public class LoadTestRuntimeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LoadTestRuntimeAutoConfiguration.class);

    /**
     * The endpoint, unless a production profile is active and {@code allow-production} is not set.
     *
     * @param properties  settings
     * @param environment the environment (profiles, context path)
     * @param mappings    all Spring MVC handler mappings, read on each request
     * @return the endpoint bean
     */
    @Bean
    @ConditionalOnMissingBean
    public LoadTestEndpoint loadTestEndpoint(LoadTestRuntimeProperties properties, Environment environment,
                                             org.springframework.beans.factory.ObjectProvider<RequestMappingHandlerMapping> mappings) {
        boolean production = Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.matches("(?i).*prod(uction)?.*"));
        if (production && !properties.allowProduction()) {
            log.warn("loadtest.runtime.enabled=true but a production profile is active: /actuator/loadtest answers "
                    + "empty (set loadtest.runtime.allow-production=true to expose the API model anyway)");
            return LoadTestEndpoint.disabled();
        }
        log.warn("load-test runtime model is exposed at /actuator/loadtest - for development and test environments only");
        return new LoadTestEndpoint(properties, environment.getProperty("server.servlet.context-path", ""),
                environment.getProperty("spring.application.name", "application"), mappings);
    }
}
