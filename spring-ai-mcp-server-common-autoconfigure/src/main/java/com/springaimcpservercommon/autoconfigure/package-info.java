/**
 * Spring Boot auto-configuration for the {@code springAIMcpServerCommon} library (ADR-0001).
 *
 * <p>This package contains only {@link org.springframework.boot.autoconfigure.AutoConfiguration} classes
 * and their {@link org.springframework.boot.context.properties.ConfigurationProperties} bindings.
 * No policy logic lives here; every wired bean is an instance of a class defined in one of the other
 * modules ({@code core}, {@code security}, {@code persistence}, {@code query}, {@code ai}, {@code mcp},
 * {@code webmvc}).
 *
 * <h2>Namespace</h2>
 * Configuration properties are under {@code dynamic.ai.agent.*} (CLAUDE.md).
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Every default bean carries {@code @ConditionalOnMissingBean}; hosts may replace any bean.</li>
 *   <li>Optional-module beans are gated with {@code @ConditionalOnClass} / {@code @ConditionalOnBean}.</li>
 *   <li>No bean is {@code @Component}; all registrations are explicit {@code @Bean} methods.</li>
 *   <li>No global Spring side effects (LLD-12 §4).</li>
 * </ul>
 */
@NullMarked
package com.springaimcpservercommon.autoconfigure;

import org.jspecify.annotations.NullMarked;
