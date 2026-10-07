package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ruleengine.RuleEngine;
import com.springaimcpservercommon.ruleengine.admin.ExpressionTester;
import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin;
import com.springaimcpservercommon.ruleengine.admin.RuleLifecycle;
import com.springaimcpservercommon.ruleengine.store.OutboxStore;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * The REST authoring API of the rule engine (LLD-18): rule/group lifecycle, library, messages, templates, endpoints,
 * channels, triggers, evaluation log and deliveries under {@code /dynamic-ai/admin/api/v1}. Registered only in a web
 * application where the admin plane ({@link AdminApi}, {@link AdminAudit}) and the rule-engine services exist. It is
 * a separate auto-configuration, ordered after {@link DaiRuleEngineAutoConfiguration}, so that its
 * {@code @ConditionalOnBean} conditions can see the services that one defines.
 */
@NullMarked
@AutoConfiguration(after = {DaiRuleEngineAutoConfiguration.class, DaiAdminAutoConfiguration.class})
@ConditionalOnProperty(prefix = "dynamic.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "dynamic.ai.agent.rule-engine", name = "enabled", havingValue = "true")
@ConditionalOnClass(name = {"jakarta.servlet.http.HttpServletRequest", "com.springaimcpservercommon.ruleengine.RuleEngine"})
@ConditionalOnBean({AdminApi.class, AdminAudit.class, RuleEngine.class})
public class DaiRuleEngineAuthoringAutoConfiguration {

    /**
     * Gate and audit plumbing shared by the controllers.
     *
     * @param api   admin gate
     * @param audit audit recorder
     * @return the support object
     */
    @Bean
    @ConditionalOnMissingBean(RuleAdminSupport.class)
    RuleAdminSupport ruleAdminSupport(AdminApi api, AdminAudit audit) {
        return new RuleAdminSupport(api, audit);
    }

    /**
     * Rule and group lifecycle endpoints.
     *
     * @param lifecycle lifecycle service
     * @param tester    expression validator
     * @param support   gate and audit
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(RuleLifecycleAdminController.class)
    @ConditionalOnBean({RuleLifecycle.class, ExpressionTester.class})
    RuleLifecycleAdminController ruleLifecycleAdminController(RuleLifecycle lifecycle, ExpressionTester tester,
                                                              RuleAdminSupport support) {
        return new RuleLifecycleAdminController(lifecycle, tester, support);
    }

    /**
     * Workspace configuration endpoints.
     *
     * @param config  authoring service
     * @param outbox  delivery outbox
     * @param support gate and audit
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(RuleConfigAdminController.class)
    @ConditionalOnBean({RuleConfigAdmin.class, OutboxStore.class})
    RuleConfigAdminController ruleConfigAdminController(RuleConfigAdmin config, OutboxStore outbox, RuleAdminSupport support) {
        return new RuleConfigAdminController(config, outbox, support);
    }

    /**
     * Platform library endpoints.
     *
     * @param config  authoring service
     * @param support gate and audit
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(RuleLibraryAdminController.class)
    @ConditionalOnBean(RuleConfigAdmin.class)
    RuleLibraryAdminController ruleLibraryAdminController(RuleConfigAdmin config, RuleAdminSupport support) {
        return new RuleLibraryAdminController(config, support);
    }

    /**
     * Problem responses for the three controllers.
     *
     * @return the advice
     */
    @Bean
    @ConditionalOnMissingBean(RuleAdminExceptionHandler.class)
    RuleAdminExceptionHandler ruleAdminExceptionHandler() {
        return new RuleAdminExceptionHandler();
    }
}
