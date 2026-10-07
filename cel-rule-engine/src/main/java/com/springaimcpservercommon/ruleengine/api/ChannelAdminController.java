package com.springaimcpservercommon.ruleengine.api;

import com.springaimcpservercommon.ruleengine.channel.ApiEndpointService;
import com.springaimcpservercommon.ruleengine.channel.EnvironmentGuard;
import com.springaimcpservercommon.ruleengine.domain.Enums.ChannelType;
import com.springaimcpservercommon.ruleengine.domain.Enums.Outcome;
import com.springaimcpservercommon.ruleengine.domain.Model.ActionBinding;
import com.springaimcpservercommon.ruleengine.domain.Model.ApiEndpoint;
import com.springaimcpservercommon.ruleengine.domain.Model.Channel;
import com.springaimcpservercommon.ruleengine.domain.Model.EmailTemplate;
import com.springaimcpservercommon.ruleengine.domain.Model.Rule;
import com.springaimcpservercommon.ruleengine.domain.Model.RuleGroup;
import com.springaimcpservercommon.ruleengine.domain.Model.Tenant;
import com.springaimcpservercommon.ruleengine.repo.ChannelRepository;
import com.springaimcpservercommon.ruleengine.repo.RuleRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Communication channels: the caller-side e-mail templates (id and name), the APIs API channels call, the channels
 * themselves (EMAIL, PUSH, API) and the action bindings that say which outcome of which rule or group uses which channel.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class ChannelAdminController {

    /** A caller-side e-mail template: the id its mail service knows and the name the UI shows. */
    public record TemplateRequest(@NotBlank String templateId, @NotBlank String name, @Nullable String description) { }

    /**
     * An API a channel calls. An API of another environment is refused; one the engine cannot place in an environment
     * is {@code EXTERNAL}: the first request answers 409 with {@code confirmationRequired}, the UI shows the pop-up and
     * repeats the request with {@code confirmExternal: true}.
     */
    public record EndpointRequest(@NotBlank String name, @NotBlank String url, @Nullable String method,
                                  @Nullable Map<String, String> headers, @Nullable Boolean confirmExternal,
                                  @Nullable String confirmedBy) { }

    /** How an URL relates to this environment (the pop-up decision, without saving anything). */
    public record UrlCheck(String url, String environment, String classification, @Nullable String hostEnvironment,
                           boolean confirmationRequired, boolean allowed, @Nullable String message) { }

    /** A channel. EMAIL needs {@code templateId}, API needs {@code endpoint} (by name), PUSH neither. */
    public record ChannelRequest(@NotBlank String name, @NotNull ChannelType type, @Nullable String templateId,
                                 @Nullable String endpoint, @Nullable Map<String, Object> config) { }

    /** "When this rule or group (code) evaluates to {@code outcome}, use that channel." */
    public record BindingRequest(@Nullable String rule, @Nullable String group, @NotNull Outcome outcome,
                                 @NotBlank String channel) { }

    private final TenantResolver tenants;
    private final ChannelRepository channels;
    private final RuleRepository rules;
    private final ApiEndpointService endpoints;
    private final EnvironmentGuard guard;
    private final JsonMapper mapper;

    public ChannelAdminController(TenantResolver tenants, ChannelRepository channels, RuleRepository rules,
                                  ApiEndpointService endpoints, EnvironmentGuard guard, JsonMapper mapper) {
        this.tenants = tenants;
        this.channels = channels;
        this.rules = rules;
        this.endpoints = endpoints;
        this.guard = guard;
        this.mapper = mapper;
    }

    // ── e-mail templates ───────────────────────────────────────────────────────────────────────────

    @GetMapping("/email-templates")
    public List<EmailTemplate> templates(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant) {
        return channels.templates(tenants.tenant(tenant).id());
    }

    @PostMapping("/email-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public EmailTemplate createTemplate(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant,
                                        @Valid @RequestBody TemplateRequest r) {
        return channels.createTemplate(tenants.tenant(tenant).id(), r.templateId(), r.name(), r.description());
    }

    // ── API endpoints ──────────────────────────────────────────────────────────────────────────────

    @GetMapping("/api-endpoints")
    public List<ApiEndpoint> endpoints(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant) {
        return channels.endpoints(tenants.tenant(tenant).id());
    }

    /** What the pop-up would say about a URL, before anything is saved. */
    @GetMapping("/api-endpoints/check")
    public UrlCheck check(@RequestParam String url) {
        EnvironmentGuard.Classification c = guard.classify(url);
        return switch (c.kind()) {
            case SAME_ENVIRONMENT -> new UrlCheck(url, guard.environment(), c.kind().name(), c.environment(), false,
                    true, null);
            case OTHER_ENVIRONMENT -> new UrlCheck(url, guard.environment(), c.kind().name(), c.environment(), false,
                    false, "The host belongs to the " + c.environment() + " environment; this system runs in "
                    + guard.environment() + ".");
            case EXTERNAL -> new UrlCheck(url, guard.environment(), c.kind().name(), null, true, true,
                    ApiEndpointService.EXTERNAL_MESSAGE);
        };
    }

    @PostMapping("/api-endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiEndpoint createEndpoint(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant,
                                      @Valid @RequestBody EndpointRequest r) {
        String headers = r.headers() == null || r.headers().isEmpty() ? null : mapper.writeValueAsString(r.headers());
        return endpoints.register(tenants.tenant(tenant).id(), r.name(), r.url(),
                r.method() == null ? "POST" : r.method().toUpperCase(), headers, Boolean.TRUE.equals(r.confirmExternal()),
                r.confirmedBy());
    }

    // ── channels ───────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/channels")
    public List<Channel> channels(@RequestHeader(TenantResolver.TENANT_HEADER) String tenant) {
        return channels.channels(tenants.tenant(tenant).id());
    }

    @PostMapping("/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public Channel createChannel(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                                 @Valid @RequestBody ChannelRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        Long templateId = null;
        Long endpointId = null;
        if (r.type() == ChannelType.EMAIL) {
            templateId = channels.templates(tenant.id()).stream().filter(t -> t.externalTemplateId().equals(r.templateId()))
                    .findFirst().orElseThrow(() -> RuleEngineException.badRequest(
                            "an EMAIL channel needs an existing templateId (the caller-side template id)")).id();
        } else if (r.type() == ChannelType.API) {
            endpointId = channels.endpoints(tenant.id()).stream().filter(e -> e.name().equals(r.endpoint()))
                    .findFirst().orElseThrow(() -> RuleEngineException.badRequest(
                            "an API channel needs an existing endpoint (by name)")).id();
        }
        String config = r.config() == null || r.config().isEmpty() ? null : mapper.writeValueAsString(r.config());
        return channels.createChannel(tenant.id(), r.name(), r.type(), templateId, endpointId, config);
    }

    // ── action bindings ────────────────────────────────────────────────────────────────────────────

    @PostMapping("/action-bindings")
    @ResponseStatus(HttpStatus.CREATED)
    public ActionBinding bind(@RequestHeader(TenantResolver.TENANT_HEADER) String tenantCode,
                              @Valid @RequestBody BindingRequest r) {
        Tenant tenant = tenants.tenant(tenantCode);
        if ((r.rule() == null) == (r.group() == null)) {
            throw RuleEngineException.badRequest("give either a rule or a group");
        }
        Long ruleId = null;
        Long groupId = null;
        if (r.rule() != null) {
            ruleId = rules.rules(tenant.id()).stream().filter(x -> x.code().equals(r.rule())).findFirst()
                    .orElseThrow(() -> RuleEngineException.notFound("rule " + r.rule())).id();
        } else {
            groupId = rules.groupByCode(tenant.id(), null, r.group())
                    .orElseThrow(() -> RuleEngineException.notFound("rule group " + r.group())).id();
        }
        Channel channel = channels.channels(tenant.id()).stream().filter(c -> c.name().equals(r.channel())).findFirst()
                .orElseThrow(() -> RuleEngineException.notFound("channel " + r.channel()));
        return channels.bind(tenant.id(), ruleId, groupId, r.outcome(), channel.id());
    }

    @DeleteMapping("/action-bindings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable long id) {
        channels.unbind(id);
    }
}
