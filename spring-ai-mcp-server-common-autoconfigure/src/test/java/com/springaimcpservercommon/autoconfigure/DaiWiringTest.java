package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.model.ModelRouter;
import com.springaimcpservercommon.ai.runtime.AgentInvoker;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.principal.AuthorityMapper;
import com.springaimcpservercommon.webmvc.endpoint.AgentChatController;
import com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the conditional bean chain that switches the HTTP layer on: store, then the security ports, then the
 * authorization engine and principal mapper, then the principal resolver and admin gate, then every controller.
 * A missing link (for example a port nobody implements) silently removes all controllers, so this test checks the
 * bean <em>definitions</em> that the auto-configurations register, without instantiating anything (all definitions
 * are made lazy, and the store is a stub that never touches a database).
 */
class DaiWiringTest {

    private static final List<Class<?>> HTTP_LAYER = List.of(
            AuthorizationEngine.class,
            AuthorityMapper.class,
            GenericDynamicHandler.DaiPrincipalResolver.class,
            ModelRouter.class,
            AgentInvoker.class,
            AgentChatController.class,
            AdminApi.class,
            AdminAudit.class,
            AdminExceptionHandler.class,
            CatalogAdminController.class,
            ResourceAdminController.class,
            AuditAdminController.class,
            KillSwitchAdminController.class,
            ClusterAdminController.class,
            MeAdminController.class,
            ProposalReviewController.class,
            WorkspaceAdminController.class,
            RoleMappingAdminController.class,
            GrantAdminController.class,
            ServiceAccountAdminController.class,
            BudgetAdminController.class,
            UsageAdminController.class,
            TraceAdminController.class,
            ConversationController.class);

    private static final DaiStore STUB_STORE = new DaiStore() {
        @Override
        public String schema() {
            return "dynamic_ai";
        }

        @Override
        public EntityManager entityManager() {
            throw new UnsupportedOperationException("wiring test");
        }

        @Override
        public TransactionOperations transactions() {
            throw new UnsupportedOperationException("wiring test");
        }

        @Override
        public TransactionOperations readOnlyTransactions() {
            throw new UnsupportedOperationException("wiring test");
        }

        @Override
        public TransactionOperations newTransactions() {
            throw new UnsupportedOperationException("wiring test");
        }
    };

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DaiCoreAutoConfiguration.class,
                        DaiPersistenceAutoConfiguration.class,
                        DaiSecurityAutoConfiguration.class,
                        DaiAiAutoConfiguration.class,
                        DaiMcpAutoConfiguration.class,
                        DaiWebMvcAutoConfiguration.class,
                        DaiAdminAutoConfiguration.class))
                .withInitializer(context -> context.addBeanFactoryPostProcessor(beanFactory -> {
                    for (String name : beanFactory.getBeanDefinitionNames()) {
                        beanFactory.getBeanDefinition(name).setLazyInit(true);
                    }
                }));
    }

    @Test
    void withTheStoreEveryLayerOfTheHttpStackIsRegistered() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : HTTP_LAYER) {
                assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).hasSize(1);
            }
        });
    }

    @Test
    void theStoreBackedPortsReplaceTheDefaults() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context -> {
            assertThat(context.getBeanNamesForType(com.springaimcpservercommon.security.port.GrantSource.class))
                    .containsExactly("storeGrantSource");
            assertThat(context.getBeanNamesForType(com.springaimcpservercommon.security.port.KillSwitchView.class))
                    .containsExactly("storeKillSwitchView");
            assertThat(context.getBeanNamesForType(com.springaimcpservercommon.ai.runtime.TurnRecorder.class))
                    .containsExactly("storeTurnRecorder");
            assertThat(context.getBeanNamesForType(
                    com.springaimcpservercommon.ai.advisor.UsageMeteringAdvisor.UsageSink.class))
                    .containsExactly("ledgerUsageSink");
            assertThat(context.getBeanNamesForType(
                    com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor.BudgetChecker.class))
                    .containsExactly("ledgerBudgetChecker");
            assertThat(context.getBeanNamesForType(
                    com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor.KillSwitchChecker.class))
                    .containsExactly("storeAgentKillSwitchChecker");
        });
    }

    @Test
    void conversationRecordingIsOffUnlessEnabled() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context ->
                assertThat(context.getBeanNamesForType(com.springaimcpservercommon.ai.runtime.ConversationRecorder.class))
                        .containsExactly("conversationRecorder"));
        runner().withBean(DaiStore.class, () -> STUB_STORE)
                .withPropertyValues("dynamic.ai.agent.conversations.enabled=true")
                .run(context ->
                        assertThat(context.getBeanNamesForType(
                                com.springaimcpservercommon.ai.runtime.ConversationRecorder.class))
                                .containsExactly("storeConversationRecorder"));
    }

    @Test
    void withoutAStoreNoControllerIsRegistered() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(AdminApi.class)).isEmpty();
            assertThat(context.getBeanNamesForType(AuditAdminController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(AgentChatController.class)).isEmpty();
        });
    }

    @Test
    void theMasterSwitchRemovesEveryBean() {
        runner().withBean(DaiStore.class, () -> STUB_STORE)
                .withPropertyValues("dynamic.ai.agent.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    for (Class<?> type : HTTP_LAYER) {
                        assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty();
                    }
                    assertThat(context.getBeanNamesForType(com.springaimcpservercommon.core.catalog.MetadataRegistry.class))
                            .isEmpty();
                });
    }

    @Test
    void theMaintenanceRunnerIsRegisteredUnlessDisabled() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context ->
                assertThat(context.getBeanNamesForType(MaintenanceRunner.class)).containsExactly("maintenanceRunner"));
        runner().withBean(DaiStore.class, () -> STUB_STORE)
                .withPropertyValues("dynamic.ai.agent.store.maintenance.enabled=false")
                .run(context -> assertThat(context.getBeanNamesForType(MaintenanceRunner.class)).isEmpty());
        runner().run(context -> assertThat(context.getBeanNamesForType(MaintenanceRunner.class)).isEmpty());
    }

    @Test
    void toolCallsAreRecordedToTheStoreWhenItExists() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context ->
                assertThat(context.getBeanNamesForType(com.springaimcpservercommon.ai.tool.ToolCallRecorder.class))
                        .containsExactly("storeToolCallRecorder"));
        runner().run(context ->
                assertThat(context.getBeanNamesForType(com.springaimcpservercommon.ai.tool.ToolCallRecorder.class))
                        .containsExactly("toolCallRecorder"));
    }

    @Test
    void theToolPathIsWiredEndToEndWhenTheStoreExists() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context -> {
            assertThat(context.getBeanNamesForType(com.springaimcpservercommon.ai.tool.ToolBridge.class))
                    .containsExactly("toolBridge");
            assertThat(context.getBeanNamesForType(
                    com.springaimcpservercommon.ai.tool.ToolBridge.ToolBindingLoader.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(
                    com.springaimcpservercommon.mcp.server.McpToolBindingSource.class)).hasSize(1);
        });
    }

    @Test
    void theMcpEndpointIsOffUnlessEnabled() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context ->
                assertThat(context.getBeanNamesForType(
                        com.springaimcpservercommon.mcp.server.McpEndpointController.class)).isEmpty());
        runner().withBean(DaiStore.class, () -> STUB_STORE)
                .withPropertyValues("dynamic.ai.agent.mcp.enabled=true")
                .run(context -> {
                    assertThat(context.getBeanNamesForType(
                            com.springaimcpservercommon.mcp.server.McpEndpointController.class))
                            .containsExactly("mcpEndpointController");
                    assertThat(context.getBeanNamesForType(
                            com.springaimcpservercommon.security.mcp.McpClientApproval.class)).hasSize(1);
                });
        runner().withBean(DaiStore.class, () -> STUB_STORE)
                .withPropertyValues("dynamic.ai.agent.mcp.enabled=true",
                        "dynamic.ai.agent.mcp.require-approved-client=false")
                .run(context -> assertThat(context.getBeanNamesForType(
                        com.springaimcpservercommon.security.mcp.McpClientApproval.class)).isEmpty());
    }

    @Test
    void mcpRequestsAreRecordedToTheStoreWhenItExists() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context ->
                assertThat(context.getBeanNamesForType(com.springaimcpservercommon.mcp.server.McpRequestRecorder.class))
                        .containsExactly("storeMcpRequestRecorder"));
    }

    @Test
    void chatMemoryIsStoreBackedWhenTheStoreExistsAndOnlyThenAndCanBeTurnedOff() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(com.springaimcpservercommon.persistence.memory.ChatMemoryStore.class))
                    .isEmpty();
            assertThat(context.getBeanNamesForType(org.springframework.ai.chat.memory.ChatMemory.class)).hasSize(1);
        });
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(com.springaimcpservercommon.persistence.memory.ChatMemoryStore.class))
                    .containsExactly("chatMemoryStore");
            assertThat(context.getBean(org.springframework.ai.chat.memory.ChatMemory.class)).isNotNull();
        });
        runner().withBean(DaiStore.class, () -> STUB_STORE)
                .withPropertyValues("dynamic.ai.agent.memory.persistent=false")
                .run(context -> assertThat(context.getBean(org.springframework.ai.chat.memory.ChatMemory.class))
                        .isNotNull());
        runner().withBean(DaiStore.class, () -> STUB_STORE)
                .withPropertyValues("dynamic.ai.agent.memory.retention=0s")
                .run(context -> org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> context.getBean(org.springframework.ai.chat.memory.ChatMemory.class))
                        .hasStackTraceContaining("dynamic.ai.agent.memory.retention"));
    }

    @Test
    void theJsonSchemaValidatorIsTheNetworkntOneWhenTheLibraryIsPresent() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context ->
                assertThat(context.getBeanNamesForType(com.springaimcpservercommon.ai.advisor.JsonSchemaValidationPort.class))
                        .containsExactly("networkntJsonSchemaValidator"));
    }

    @Test
    void weNeverRegisterAnObservationRegistryOfOurOwn() {
        // a fallback registry could displace the host's (Spring Boot backs off when one exists)
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context ->
                assertThat(context.getBeanNamesForType(io.micrometer.observation.ObservationRegistry.class)).isEmpty());
        runner().withBean(DaiStore.class, () -> STUB_STORE)
                .withBean(io.micrometer.observation.ObservationRegistry.class,
                        io.micrometer.observation.ObservationRegistry::create)
                .run(context -> assertThat(
                        context.getBeanNamesForType(io.micrometer.observation.ObservationRegistry.class)).hasSize(1));
    }

    @Test
    void proposalsAreStoredWhenTheStoreExistsAndRefusedOtherwise() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context ->
                assertThat(context.getBeanNamesForType(com.springaimcpservercommon.ai.tool.ProposalService.class))
                        .containsExactly("storeProposalService"));
        runner().run(context ->
                assertThat(context.getBeanNamesForType(com.springaimcpservercommon.ai.tool.ProposalService.class))
                        .containsExactly("proposalService"));
    }

    @Test
    void theWriteExecutorExistsOnlyBehindTheReviewApi() {
        runner().withBean(DaiStore.class, () -> STUB_STORE).run(context -> {
            assertThat(context.getBeanNamesForType(ProposalApplier.class)).containsExactly("proposalApplier");
            assertThat(context.getBeanNamesForType(ProposalReviewController.class))
                    .containsExactly("proposalReviewController");
        });
        runner().run(context ->
                assertThat(context.getBeanNamesForType(ProposalApplier.class)).isEmpty());
    }
}
