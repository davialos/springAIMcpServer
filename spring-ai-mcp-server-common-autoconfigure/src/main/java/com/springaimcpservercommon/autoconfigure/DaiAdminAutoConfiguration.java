package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.environment.EnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSignals;
import com.springaimcpservercommon.persistence.audit.AuditTrail;
import com.springaimcpservercommon.persistence.config.ConfigStore;
import com.springaimcpservercommon.persistence.config.GrantStore;
import com.springaimcpservercommon.persistence.config.KillSwitchStore;
import com.springaimcpservercommon.persistence.identity.ApiKeyStore;
import com.springaimcpservercommon.persistence.identity.RoleMappingStore;
import com.springaimcpservercommon.persistence.identity.WorkspaceStore;
import com.springaimcpservercommon.persistence.proposal.ChangeProposalStore;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.persistence.usage.BudgetStore;
import com.springaimcpservercommon.persistence.usage.PriceStore;
import com.springaimcpservercommon.persistence.usage.UsageLedger;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Auto-configuration for the admin control-plane API (LLD-08).
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link CatalogAdminController} — catalog read endpoints under
 *       {@code /dynamic-ai/admin/api/v1/catalog}</li>
 *   <li>{@link ResourceAdminController} — resource lifecycle endpoints under
 *       {@code /dynamic-ai/admin/api/v1/workspaces/{workspaceId}/resources}</li>
 *   <li>{@link AuditAdminController}, {@link KillSwitchAdminController}, {@link ClusterAdminController},
 *       {@link MeAdminController} — cross-cutting admin APIs for the UI (audit log viewer, kill switches,
 *       cluster status, caller/environment bootstrap), gated by {@link AdminApi} and answered with
 *       RFC 9457 problems by {@link AdminExceptionHandler}</li>
 *   <li>{@link ProposalReviewController} (data plane, {@code /dynamic-ai/api/proposals}) and the access
 *       management controllers {@link WorkspaceAdminController}, {@link RoleMappingAdminController},
 *       {@link GrantAdminController}, {@link ServiceAccountAdminController}</li>
 *   <li>{@link BudgetAdminController}, {@link UsageAdminController}, {@link TraceAdminController} (admin) and
 *       {@link ConversationController} (end users, {@code /dynamic-ai/api/conversations})</li>
 * </ul>
 *
 * <p>Catalog endpoints are registered whenever a {@link MetadataRegistry} bean is present
 * (requires only the core module). Resource endpoints additionally require a {@link ConfigStore}
 * bean (persistence module).
 *
 * <p>Both controllers are {@link ConditionalOnMissingBean} — host applications may replace either.
 */
@AutoConfiguration(after = {DaiCoreAutoConfiguration.class, DaiPersistenceAutoConfiguration.class,
                             DaiSecurityAutoConfiguration.class, DaiWebMvcAutoConfiguration.class})
@ConditionalOnClass(RequestMappingHandlerMapping.class)
@NullMarked
public class DaiAdminAutoConfiguration {

    /**
     * Catalog read controller: exposes the live {@link com.springaimcpservercommon.core.catalog.EffectiveCatalog}
     * over HTTP (LLD-08 §2.1).
     *
     * @param metadataRegistry live effective catalog
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(CatalogAdminController.class)
    @ConditionalOnBean(MetadataRegistry.class)
    public CatalogAdminController catalogAdminController(MetadataRegistry metadataRegistry) {
        return new CatalogAdminController(metadataRegistry);
    }

    /**
     * Resource lifecycle controller: create, read, draft, publish, suspend and resume (LLD-08 §2, LLD-09).
     *
     * @param configStore configuration lifecycle store
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(ResourceAdminController.class)
    @ConditionalOnBean(ConfigStore.class)
    public ResourceAdminController resourceAdminController(ConfigStore configStore) {
        return new ResourceAdminController(configStore);
    }

    /**
     * Shared authentication/authorization gate and problem builder for the cross-cutting admin controllers.
     * Present only when both the principal resolver and the authorization engine are.
     *
     * @param principalResolver resolves the caller
     * @param engine            authorizes permissions
     * @return the gate
     */
    @Bean
    @ConditionalOnMissingBean(AdminApi.class)
    @ConditionalOnBean({GenericDynamicHandler.DaiPrincipalResolver.class, AuthorizationEngine.class})
    AdminApi adminApi(GenericDynamicHandler.DaiPrincipalResolver principalResolver, AuthorizationEngine engine) {
        return new AdminApi(principalResolver, engine);
    }

    /**
     * Post-commit audit recorder shared by the admin controllers.
     *
     * @param auditTrail audit trail
     * @return the recorder
     */
    @Bean
    @ConditionalOnMissingBean(AdminAudit.class)
    @ConditionalOnBean(AuditTrail.class)
    AdminAudit adminAudit(AuditTrail auditTrail) {
        return new AdminAudit(auditTrail);
    }

    /**
     * RFC 9457 error mapping scoped to the cross-cutting admin controllers.
     *
     * @return the advice
     */
    @Bean
    @ConditionalOnMissingBean(AdminExceptionHandler.class)
    @ConditionalOnBean(AdminApi.class)
    public AdminExceptionHandler adminExceptionHandler() {
        return new AdminExceptionHandler();
    }

    /**
     * Audit log viewer (F-66).
     *
     * @param auditTrail audit trail
     * @param api        admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(AuditAdminController.class)
    @ConditionalOnBean({AuditTrail.class, AdminApi.class})
    public AuditAdminController auditAdminController(AuditTrail auditTrail, AdminApi api) {
        return new AuditAdminController(auditTrail, api, java.time.Clock.systemUTC());
    }

    /**
     * Kill switch admin API (F-73).
     *
     * @param store kill switch store
     * @param audit audit recorder
     * @param api   admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(KillSwitchAdminController.class)
    @ConditionalOnBean({KillSwitchStore.class, AdminAudit.class, AdminApi.class})
    public KillSwitchAdminController killSwitchAdminController(KillSwitchStore store, AdminAudit audit,
                                                               AdminApi api) {
        return new KillSwitchAdminController(store, audit, api, java.time.Clock.systemUTC());
    }

    /**
     * Cluster status API.
     *
     * @param configStore config store (node heartbeats and generations)
     * @param api         admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(ClusterAdminController.class)
    @ConditionalOnBean({ConfigStore.class, AdminApi.class})
    public ClusterAdminController clusterAdminController(ConfigStore configStore, AdminApi api) {
        return new ClusterAdminController(configStore, api);
    }

    /**
     * Caller and environment bootstrap API for the UI.
     *
     * @param api          admin gate
     * @param safetyPolicy environment safety policy
     * @param signals      environment signals
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(MeAdminController.class)
    @ConditionalOnBean({AdminApi.class, EnvironmentSafetyPolicy.class, EnvironmentSignals.class})
    public MeAdminController meAdminController(AdminApi api, EnvironmentSafetyPolicy safetyPolicy,
                                               EnvironmentSignals signals) {
        return new MeAdminController(api, safetyPolicy, signals);
    }

    /**
     * Proposal review API (LLD-11 §8).
     *
     * @param store proposal store
     * @param audit audit recorder
     * @param api   admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(ProposalReviewController.class)
    @ConditionalOnBean({ChangeProposalStore.class, AdminAudit.class, AdminApi.class})
    public ProposalReviewController proposalReviewController(ChangeProposalStore store, AdminAudit audit,
                                                              AdminApi api) {
        return new ProposalReviewController(store, audit, api);
    }

    /**
     * Workspace and membership admin API.
     *
     * @param store workspace store
     * @param audit audit recorder
     * @param api   admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(WorkspaceAdminController.class)
    @ConditionalOnBean({WorkspaceStore.class, AdminAudit.class, AdminApi.class})
    public WorkspaceAdminController workspaceAdminController(WorkspaceStore store, AdminAudit audit, AdminApi api) {
        return new WorkspaceAdminController(store, audit, api, java.time.Clock.systemUTC());
    }

    /**
     * Role mapping admin API.
     *
     * @param store role mapping store
     * @param audit audit recorder
     * @param api   admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(RoleMappingAdminController.class)
    @ConditionalOnBean({RoleMappingStore.class, AdminAudit.class, AdminApi.class})
    public RoleMappingAdminController roleMappingAdminController(RoleMappingStore store, AdminAudit audit,
                                                                 AdminApi api) {
        return new RoleMappingAdminController(store, audit, api);
    }

    /**
     * Grant admin API.
     *
     * @param store grant store
     * @param audit audit recorder
     * @param api   admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(GrantAdminController.class)
    @ConditionalOnBean({GrantStore.class, AdminAudit.class, AdminApi.class})
    public GrantAdminController grantAdminController(GrantStore store, AdminAudit audit, AdminApi api) {
        return new GrantAdminController(store, audit, api, java.time.Clock.systemUTC());
    }

    /**
     * Service account and API key admin API.
     *
     * @param store API key store
     * @param audit audit recorder
     * @param api   admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(ServiceAccountAdminController.class)
    @ConditionalOnBean({ApiKeyStore.class, AdminAudit.class, AdminApi.class})
    public ServiceAccountAdminController serviceAccountAdminController(ApiKeyStore store, AdminAudit audit,
                                                                       AdminApi api) {
        return new ServiceAccountAdminController(store, audit, api, java.time.Clock.systemUTC());
    }

    /**
     * Budget admin API.
     *
     * @param store  budget store
     * @param ledger usage ledger (current-period usage)
     * @param audit  audit recorder
     * @param api    admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(BudgetAdminController.class)
    @ConditionalOnBean({BudgetStore.class, UsageLedger.class, AdminAudit.class, AdminApi.class})
    public BudgetAdminController budgetAdminController(BudgetStore store, UsageLedger ledger, AdminAudit audit,
                                                       AdminApi api) {
        return new BudgetAdminController(store, ledger, audit, api);
    }

    /**
     * Usage and price API.
     *
     * @param ledger usage ledger
     * @param prices price store
     * @param audit  audit recorder
     * @param api    admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(UsageAdminController.class)
    @ConditionalOnBean({UsageLedger.class, PriceStore.class, AdminAudit.class, AdminApi.class})
    public UsageAdminController usageAdminController(UsageLedger ledger, PriceStore prices, AdminAudit audit,
                                                     AdminApi api) {
        return new UsageAdminController(ledger, prices, audit, api, java.time.Clock.systemUTC());
    }

    /**
     * Trace viewer API.
     *
     * @param store telemetry store
     * @param api   admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(TraceAdminController.class)
    @ConditionalOnBean({TelemetryStore.class, AdminApi.class})
    public TraceAdminController traceAdminController(TelemetryStore store, AdminApi api) {
        return new TraceAdminController(store, api, java.time.Clock.systemUTC());
    }

    /**
     * Conversation history API for end users.
     *
     * @param store telemetry store
     * @param audit audit recorder
     * @param api   admin gate
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean(ConversationController.class)
    @ConditionalOnBean({TelemetryStore.class, AdminAudit.class, AdminApi.class})
    public ConversationController conversationController(TelemetryStore store, AdminAudit audit, AdminApi api) {
        return new ConversationController(store, audit, api);
    }
}
