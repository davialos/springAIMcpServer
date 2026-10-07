package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin;
import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin.ChannelInput;
import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin.TriggerInput;
import com.springaimcpservercommon.ruleengine.model.ChannelTrigger;
import com.springaimcpservercommon.ruleengine.model.ChannelType;
import com.springaimcpservercommon.ruleengine.model.OwnerType;
import com.springaimcpservercommon.ruleengine.model.TriggerType;
import com.springaimcpservercommon.ruleengine.store.OutboxStore;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything around the rules that a workspace maintains: messages, e-mail templates, API endpoints (with the
 * environment confirmation flow), outcome channels, trigger points, the evaluation log and the delivery outbox
 * (LLD-18, OQ-65/67/68). The workspace is the rule engine's tenant; reads need {@link Permission#RULES_READ}, writes
 * {@link Permission#RULES_AUTHOR} plus the AUTHORING capability of the environment, retrying a dead delivery
 * {@link Permission#RULES_PUBLISH}. Changes are audited. Messages never carry input values.
 *
 * <p>An API endpoint save answers <b>409 {@code confirmation_required}</b> when the URL cannot be classified as one of
 * this environment's APIs; the UI shows the pop-up and repeats the request with {@code confirmExternal=true}. An
 * endpoint of another environment is a 400 {@code api_environment_mismatch}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/workspaces/{workspaceId}/rule-engine")
public final class RuleConfigAdminController {

    /**
     * Create a bundle.
     *
     * @param code        bundle code
     * @param description free text
     * @param texts       language tag → text
     */
    public record CreateBundle(String code, @Nullable String description, Map<String, String> texts) {}

    /**
     * One translation.
     *
     * @param text the text
     */
    public record Text(String text) {}

    /**
     * An e-mail template.
     *
     * @param templateRef the id in the caller's mail system
     * @param name        display name
     * @param description free text
     * @param active      default true
     */
    public record Template(String templateRef, String name, @Nullable String description, @Nullable Boolean active) {}

    /**
     * An API endpoint.
     *
     * @param name            display name
     * @param method          POST (default), PUT, PATCH or GET
     * @param url             absolute http(s) URL
     * @param timeoutMs       100..30000, default 3000
     * @param authSecretRef   reference the host resolves to a credential (never the secret)
     * @param confirmExternal the user confirmed the "external API" pop-up
     * @param active          default true
     */
    public record Endpoint(String name, @Nullable String method, String url, @Nullable Integer timeoutMs,
                           @Nullable String authSecretRef, @Nullable Boolean confirmExternal, @Nullable Boolean active) {}

    /**
     * A URL to classify.
     *
     * @param url endpoint URL
     */
    public record UrlCheck(String url) {}

    /**
     * An outcome channel.
     *
     * @param ownerType            RULE or GROUP
     * @param ownerId              rule or group
     * @param onResult             TRUE, FALSE, ERROR or ANY
     * @param channelType          EMAIL, PUSH or API
     * @param sequence             order among the owner's channels (default 0)
     * @param enabled              default true
     * @param emailTemplateId      EMAIL: template
     * @param apiEndpointId        API: endpoint
     * @param pushTitleBundleId    PUSH: title bundle
     * @param pushBodyBundleId     PUSH: body bundle
     * @param recipientExpression  CEL expression yielding the recipient (EMAIL, PUSH)
     */
    public record Channel(OwnerType ownerType, UUID ownerId, ChannelTrigger onResult, ChannelType channelType,
                          @Nullable Integer sequence, @Nullable Boolean enabled, @Nullable UUID emailTemplateId,
                          @Nullable UUID apiEndpointId, @Nullable UUID pushTitleBundleId, @Nullable UUID pushBodyBundleId,
                          @Nullable String recipientExpression) {}

    /**
     * A trigger point.
     *
     * @param organizationId organization, or {@code null} = whole workspace
     * @param application    integrating application
     * @param moduleCode     module
     * @param triggerType    FORM_ACTION or FORM_FIELD
     * @param formCode       form
     * @param actionCode     action (SUBMIT, APPROVE, ADD, BUY …) or ON_CHANGE
     * @param fieldCode      field for FORM_FIELD
     * @param ruleGroupId    group to evaluate
     * @param sequence       order (default 0)
     * @param enabled        default true
     */
    public record Trigger(@Nullable UUID organizationId, String application, String moduleCode, TriggerType triggerType,
                          String formCode, String actionCode, @Nullable String fieldCode, UUID ruleGroupId,
                          @Nullable Integer sequence, @Nullable Boolean enabled) {}

    private final RuleConfigAdmin config;
    private final OutboxStore outbox;
    private final RuleAdminSupport support;

    RuleConfigAdminController(RuleConfigAdmin config, OutboxStore outbox, RuleAdminSupport support) {
        this.config = Objects.requireNonNull(config, "config");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.support = Objects.requireNonNull(support, "support");
    }

    // ---- library (read) -----------------------------------------------------------------------------------------

    /**
     * The parameter library for the expression editor: modules, and sys objects with their attributes.
     *
     * @param workspaceId workspace (tenant)
     * @param request     current request
     * @return 200 with modules and objects
     */
    @GetMapping("/library")
    public ResponseEntity<?> library(@PathVariable UUID workspaceId, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modules", config.modules());
        body.put("objects", config.objects());
        return ResponseEntity.ok(body);
    }

    // ---- bundles ------------------------------------------------------------------------------------------------

    /**
     * Message bundles visible to the workspace: the platform's and its own.
     *
     * @param workspaceId workspace (tenant)
     * @param request     current request
     * @return 200 with bundles and their translations
     */
    @GetMapping("/bundles")
    public ResponseEntity<?> bundles(@PathVariable UUID workspaceId, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(config.bundles(workspaceId)) : gate.denied();
    }

    /**
     * Creates a workspace bundle.
     *
     * @param workspaceId workspace (tenant)
     * @param body        code and translations
     * @param request     current request
     * @return 201 with the bundle
     */
    @PostMapping("/bundles")
    public ResponseEntity<?> createBundle(@PathVariable UUID workspaceId, @RequestBody CreateBundle body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        var bundle = config.createBundle(workspaceId, body.code(), body.description(), body.texts());
        support.audit(gate, "RULES_BUNDLE_CREATED", workspaceId, "rule_bundle", bundle.id().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(bundle);
    }

    /**
     * Sets or replaces one translation of a workspace bundle.
     *
     * @param workspaceId workspace (tenant)
     * @param id          bundle
     * @param language    BCP 47 tag
     * @param body        the text
     * @param request     current request
     * @return 204
     */
    @PutMapping("/bundles/{id}/texts/{language}")
    public ResponseEntity<?> putText(@PathVariable UUID workspaceId, @PathVariable UUID id, @PathVariable String language,
                                     @RequestBody Text body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        config.putText(workspaceId, id, language, body.text());
        support.audit(gate, "RULES_BUNDLE_TEXT_SET", workspaceId, "rule_bundle", id.toString(), null, Map.of("language", language));
        return ResponseEntity.noContent().build();
    }

    /**
     * Removes one translation (the last one cannot be removed).
     *
     * @param workspaceId workspace (tenant)
     * @param id          bundle
     * @param language    BCP 47 tag
     * @param request     current request
     * @return 204
     */
    @DeleteMapping("/bundles/{id}/texts/{language}")
    public ResponseEntity<?> deleteText(@PathVariable UUID workspaceId, @PathVariable UUID id, @PathVariable String language,
                                        HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        config.deleteText(workspaceId, id, language);
        support.audit(gate, "RULES_BUNDLE_TEXT_DELETED", workspaceId, "rule_bundle", id.toString(), null, Map.of("language", language));
        return ResponseEntity.noContent().build();
    }

    /**
     * Deletes a workspace bundle that nothing uses.
     *
     * @param workspaceId workspace (tenant)
     * @param id          bundle
     * @param request     current request
     * @return 204; 409 {@code bundle_in_use}
     */
    @DeleteMapping("/bundles/{id}")
    public ResponseEntity<?> deleteBundle(@PathVariable UUID workspaceId, @PathVariable UUID id, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        config.deleteBundle(workspaceId, id);
        support.audit(gate, "RULES_BUNDLE_DELETED", workspaceId, "rule_bundle", id.toString());
        return ResponseEntity.noContent().build();
    }

    // ---- e-mail templates ---------------------------------------------------------------------------------------

    /**
     * E-mail templates (caller-side id and name).
     *
     * @param workspaceId workspace (tenant)
     * @param request     current request
     * @return 200 with templates
     */
    @GetMapping("/email-templates")
    public ResponseEntity<?> emailTemplates(@PathVariable UUID workspaceId, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(config.emailTemplates(workspaceId)) : gate.denied();
    }

    /**
     * Creates an e-mail template.
     *
     * @param workspaceId workspace (tenant)
     * @param body        template
     * @param request     current request
     * @return 201 with the template
     */
    @PostMapping("/email-templates")
    public ResponseEntity<?> createTemplate(@PathVariable UUID workspaceId, @RequestBody Template body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        var t = config.saveEmailTemplate(workspaceId, null, body.templateRef(), body.name(), body.description(),
                body.active() == null || body.active());
        support.audit(gate, "RULES_TEMPLATE_SAVED", workspaceId, "rule_email_template", t.id().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(t);
    }

    /**
     * Updates an e-mail template.
     *
     * @param workspaceId workspace (tenant)
     * @param id          template
     * @param body        template
     * @param request     current request
     * @return 200 with the template
     */
    @PutMapping("/email-templates/{id}")
    public ResponseEntity<?> updateTemplate(@PathVariable UUID workspaceId, @PathVariable UUID id, @RequestBody Template body,
                                            HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        var t = config.saveEmailTemplate(workspaceId, id, body.templateRef(), body.name(), body.description(),
                body.active() == null || body.active());
        support.audit(gate, "RULES_TEMPLATE_SAVED", workspaceId, "rule_email_template", id.toString());
        return ResponseEntity.ok(t);
    }

    // ---- API endpoints ------------------------------------------------------------------------------------------

    /**
     * API endpoints with their environment and confirmation record.
     *
     * @param workspaceId workspace (tenant)
     * @param request     current request
     * @return 200 with endpoints
     */
    @GetMapping("/api-endpoints")
    public ResponseEntity<?> apiEndpoints(@PathVariable UUID workspaceId, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(config.apiEndpoints(workspaceId)) : gate.denied();
    }

    /**
     * Pre-checks a URL: its environment and whether the user must confirm it (drives the pop-up).
     *
     * @param workspaceId workspace (tenant)
     * @param body        the URL
     * @param request     current request
     * @return 200 with environment, verdict and the pop-up text
     */
    @PostMapping("/api-endpoints/check")
    public ResponseEntity<?> checkUrl(@PathVariable UUID workspaceId, @RequestBody UrlCheck body, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_AUTHOR, workspaceId);
        return gate.open() ? ResponseEntity.ok(config.checkApiUrl(body.url())) : gate.denied();
    }

    /**
     * Creates an API endpoint (see the class description for the confirmation flow).
     *
     * @param workspaceId workspace (tenant)
     * @param body        endpoint
     * @param request     current request
     * @return 201 with the endpoint; 409 {@code confirmation_required}; 400 {@code api_environment_mismatch}
     */
    @PostMapping("/api-endpoints")
    public ResponseEntity<?> createEndpoint(@PathVariable UUID workspaceId, @RequestBody Endpoint body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        var e = saveEndpoint(workspaceId, null, body, RuleAdminSupport.actor(gate));
        support.audit(gate, "RULES_ENDPOINT_SAVED", workspaceId, "rule_api_endpoint", e.id().toString(), null,
                Map.of("environment", e.environment().name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(e);
    }

    /**
     * Updates an API endpoint.
     *
     * @param workspaceId workspace (tenant)
     * @param id          endpoint
     * @param body        endpoint
     * @param request     current request
     * @return 200 with the endpoint
     */
    @PutMapping("/api-endpoints/{id}")
    public ResponseEntity<?> updateEndpoint(@PathVariable UUID workspaceId, @PathVariable UUID id, @RequestBody Endpoint body,
                                            HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        var e = saveEndpoint(workspaceId, id, body, RuleAdminSupport.actor(gate));
        support.audit(gate, "RULES_ENDPOINT_SAVED", workspaceId, "rule_api_endpoint", id.toString(), null,
                Map.of("environment", e.environment().name()));
        return ResponseEntity.ok(e);
    }

    private RuleConfigAdmin.ApiEndpointView saveEndpoint(UUID workspaceId, @Nullable UUID id, Endpoint b, String actor) {
        return config.saveApiEndpoint(workspaceId, id, b.name(), b.method() == null ? "POST" : b.method(), b.url(),
                b.timeoutMs() == null ? 3000 : b.timeoutMs(), b.authSecretRef(), Boolean.TRUE.equals(b.confirmExternal()),
                actor, b.active() == null || b.active());
    }

    // ---- channels -----------------------------------------------------------------------------------------------

    /**
     * Outcome channels.
     *
     * @param workspaceId workspace (tenant)
     * @param ownerType   RULE or GROUP filter
     * @param ownerId     owner filter
     * @param request     current request
     * @return 200 with channels
     */
    @GetMapping("/channels")
    public ResponseEntity<?> channels(@PathVariable UUID workspaceId, @RequestParam(required = false) @Nullable OwnerType ownerType,
                                      @RequestParam(required = false) @Nullable UUID ownerId, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(config.channels(workspaceId, ownerType, ownerId)) : gate.denied();
    }

    /**
     * Creates a channel.
     *
     * @param workspaceId workspace (tenant)
     * @param body        channel
     * @param request     current request
     * @return 201 with the channel
     */
    @PostMapping("/channels")
    public ResponseEntity<?> createChannel(@PathVariable UUID workspaceId, @RequestBody Channel body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        var c = config.saveChannel(workspaceId, null, channel(body));
        support.audit(gate, "RULES_CHANNEL_SAVED", workspaceId, "rule_channel", c.id().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(c);
    }

    /**
     * Updates a channel.
     *
     * @param workspaceId workspace (tenant)
     * @param id          channel
     * @param body        channel
     * @param request     current request
     * @return 200 with the channel
     */
    @PutMapping("/channels/{id}")
    public ResponseEntity<?> updateChannel(@PathVariable UUID workspaceId, @PathVariable UUID id, @RequestBody Channel body,
                                           HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        var c = config.saveChannel(workspaceId, id, channel(body));
        support.audit(gate, "RULES_CHANNEL_SAVED", workspaceId, "rule_channel", id.toString());
        return ResponseEntity.ok(c);
    }

    /**
     * Deletes a channel.
     *
     * @param workspaceId workspace (tenant)
     * @param id          channel
     * @param request     current request
     * @return 204
     */
    @DeleteMapping("/channels/{id}")
    public ResponseEntity<?> deleteChannel(@PathVariable UUID workspaceId, @PathVariable UUID id, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        config.deleteChannel(workspaceId, id);
        support.audit(gate, "RULES_CHANNEL_DELETED", workspaceId, "rule_channel", id.toString());
        return ResponseEntity.noContent().build();
    }

    private static ChannelInput channel(Channel b) {
        return new ChannelInput(b.ownerType(), b.ownerId(), b.onResult(), b.channelType(), b.sequence() == null ? 0 : b.sequence(),
                b.enabled() == null || b.enabled(), b.emailTemplateId(), b.apiEndpointId(), b.pushTitleBundleId(),
                b.pushBodyBundleId(), b.recipientExpression());
    }

    // ---- triggers -----------------------------------------------------------------------------------------------

    /**
     * Trigger points.
     *
     * @param workspaceId workspace (tenant)
     * @param application application filter
     * @param request     current request
     * @return 200 with triggers
     */
    @GetMapping("/triggers")
    public ResponseEntity<?> triggers(@PathVariable UUID workspaceId, @RequestParam(required = false) @Nullable String application,
                                      HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(config.triggers(workspaceId, application)) : gate.denied();
    }

    /**
     * Creates a trigger point.
     *
     * @param workspaceId workspace (tenant)
     * @param body        trigger
     * @param request     current request
     * @return 201 with the trigger
     */
    @PostMapping("/triggers")
    public ResponseEntity<?> createTrigger(@PathVariable UUID workspaceId, @RequestBody Trigger body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        var t = config.saveTrigger(workspaceId, null, trigger(body));
        support.audit(gate, "RULES_TRIGGER_SAVED", workspaceId, "rule_trigger", t.id().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(t);
    }

    /**
     * Updates a trigger point.
     *
     * @param workspaceId workspace (tenant)
     * @param id          trigger
     * @param body        trigger
     * @param request     current request
     * @return 200 with the trigger
     */
    @PutMapping("/triggers/{id}")
    public ResponseEntity<?> updateTrigger(@PathVariable UUID workspaceId, @PathVariable UUID id, @RequestBody Trigger body,
                                           HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        var t = config.saveTrigger(workspaceId, id, trigger(body));
        support.audit(gate, "RULES_TRIGGER_SAVED", workspaceId, "rule_trigger", id.toString());
        return ResponseEntity.ok(t);
    }

    /**
     * Deletes a trigger point.
     *
     * @param workspaceId workspace (tenant)
     * @param id          trigger
     * @param request     current request
     * @return 204
     */
    @DeleteMapping("/triggers/{id}")
    public ResponseEntity<?> deleteTrigger(@PathVariable UUID workspaceId, @PathVariable UUID id, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        config.deleteTrigger(workspaceId, id);
        support.audit(gate, "RULES_TRIGGER_DELETED", workspaceId, "rule_trigger", id.toString());
        return ResponseEntity.noContent().build();
    }

    private static TriggerInput trigger(Trigger b) {
        return new TriggerInput(b.organizationId(), b.application(), b.moduleCode(), b.triggerType(), b.formCode(),
                b.actionCode(), b.fieldCode(), b.ruleGroupId(), b.sequence() == null ? 0 : b.sequence(),
                b.enabled() == null || b.enabled());
    }

    // ---- evaluation log and deliveries --------------------------------------------------------------------------

    /**
     * Recent evaluations (decisions only; input values are never stored).
     *
     * @param workspaceId workspace (tenant)
     * @param groupId     group filter
     * @param limit       most rows, default 50, at most 500
     * @param request     current request
     * @return 200 with evaluations
     */
    @GetMapping("/evaluations")
    public ResponseEntity<?> evaluations(@PathVariable UUID workspaceId, @RequestParam(required = false) @Nullable UUID groupId,
                                         @RequestParam(required = false) @Nullable Integer limit, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(config.evaluations(workspaceId, groupId, limit == null ? 50 : limit)) : gate.denied();
    }

    /**
     * Deliveries that failed all their attempts (no recipients or payloads are shown).
     *
     * @param workspaceId workspace (tenant)
     * @param limit       most rows, default 50, at most 500
     * @param request     current request
     * @return 200 with dead letters
     */
    @GetMapping("/deliveries/dead")
    public ResponseEntity<?> deadDeliveries(@PathVariable UUID workspaceId, @RequestParam(required = false) @Nullable Integer limit,
                                            HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(outbox.deadLetters(workspaceId, limit == null ? 50 : limit)) : gate.denied();
    }

    /**
     * Gives a dead delivery another round of attempts.
     *
     * @param workspaceId workspace (tenant)
     * @param id          dispatch id
     * @param request     current request
     * @return 204; 404 when it is not a dead delivery of this workspace
     */
    @PostMapping("/deliveries/{id}/retry")
    public ResponseEntity<?> retryDelivery(@PathVariable UUID workspaceId, @PathVariable UUID id, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_PUBLISH, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        if (!outbox.retry(workspaceId, id)) {
            return AdminApi.problem(ProblemCode.NOT_FOUND, "not_found", "no dead delivery " + id, request);
        }
        support.audit(gate, "RULES_DELIVERY_RETRIED", workspaceId, "rule_dispatch", id.toString());
        return ResponseEntity.noContent().build();
    }
}
