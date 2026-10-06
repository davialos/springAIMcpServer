package com.springaimcpservercommon.ruleengine.eval;

import com.springaimcpservercommon.ruleengine.api.RuleEngineException;
import com.springaimcpservercommon.ruleengine.api.TenantResolver;
import com.springaimcpservercommon.ruleengine.channel.ChannelService;
import com.springaimcpservercommon.ruleengine.channel.Notification;
import com.springaimcpservercommon.ruleengine.config.RuleEngineProperties;
import com.springaimcpservercommon.ruleengine.domain.Action;
import com.springaimcpservercommon.ruleengine.domain.Enums.Outcome;
import com.springaimcpservercommon.ruleengine.domain.Model.ActionBinding;
import com.springaimcpservercommon.ruleengine.domain.Model.Module;
import com.springaimcpservercommon.ruleengine.domain.Model.Organization;
import com.springaimcpservercommon.ruleengine.domain.Model.RuleGroup;
import com.springaimcpservercommon.ruleengine.domain.Model.Tenant;
import com.springaimcpservercommon.ruleengine.domain.Model.TriggerPoint;
import com.springaimcpservercommon.ruleengine.eval.Results.Dispatch;
import com.springaimcpservercommon.ruleengine.eval.Results.EvaluationResponse;
import com.springaimcpservercommon.ruleengine.eval.Results.FinalMessage;
import com.springaimcpservercommon.ruleengine.eval.Results.GroupResult;
import com.springaimcpservercommon.ruleengine.eval.Results.RuleResult;
import com.springaimcpservercommon.ruleengine.library.ContextCoercer;
import com.springaimcpservercommon.ruleengine.library.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.library.ParameterLibraryService;
import com.springaimcpservercommon.ruleengine.repo.BundleRepository;
import com.springaimcpservercommon.ruleengine.repo.ChannelRepository;
import com.springaimcpservercommon.ruleengine.repo.LogRepository;
import com.springaimcpservercommon.ruleengine.repo.RuleRepository;
import com.springaimcpservercommon.ruleengine.repo.TriggerRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The evaluation: finds the rule groups of a trigger point (or the groups asked for), evaluates each by its policy,
 * and turns the results into the answer the caller gets — final messages in the caller's language and one action
 * (allow, warn or block). Unless it is a dry run, the channels bound to the outcomes are run and the evaluation is
 * written to the audit log (attribute names only, never values).
 */
@Service
public class EvaluationService {

    private final TenantResolver tenants;
    private final TriggerRepository triggers;
    private final RuleRepository rules;
    private final BundleRepository bundles;
    private final ChannelRepository channelRepository;
    private final LogRepository logs;
    private final ParameterLibraryService libraryService;
    private final ContextCoercer coercer;
    private final RuleEvaluator ruleEvaluator;
    private final ChannelService channels;
    private final RuleEngineProperties properties;
    private final JsonMapper mapper;

    public EvaluationService(TenantResolver tenants, TriggerRepository triggers, RuleRepository rules,
                             BundleRepository bundles, ChannelRepository channelRepository, LogRepository logs,
                             ParameterLibraryService libraryService, ContextCoercer coercer,
                             RuleEvaluator ruleEvaluator, ChannelService channels, RuleEngineProperties properties,
                             JsonMapper mapper) {
        this.tenants = tenants;
        this.triggers = triggers;
        this.rules = rules;
        this.bundles = bundles;
        this.channelRepository = channelRepository;
        this.logs = logs;
        this.libraryService = libraryService;
        this.coercer = coercer;
        this.ruleEvaluator = ruleEvaluator;
        this.channels = channels;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * Evaluates a request.
     *
     * @param tenant       the tenant
     * @param organization the organization, or {@code null}
     * @param request      what to evaluate
     * @return the answer
     */
    public EvaluationResponse evaluate(Tenant tenant, @Nullable Organization organization, EvaluateRequest request) {
        Module module = tenants.module(tenant, request.module());
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        Long organizationId = organization == null ? null : organization.id();

        // which groups
        List<RuleGroup> groups = new ArrayList<>();
        String triggerRef;
        if (request.groups() != null && !request.groups().isEmpty()) {
            for (String code : request.groups()) {
                RuleGroup g = rules.groupByCode(tenant.id(), organizationId, code)
                        .orElseThrow(() -> RuleEngineException.notFound("rule group " + code));
                if (g.moduleId() != module.id()) {
                    throw RuleEngineException.badRequest("rule group " + code + " belongs to another module than "
                            + module.code());
                }
                groups.add(g);
            }
            triggerRef = "GROUPS:" + String.join(",", request.groups());
        } else if (request.trigger() != null) {
            EvaluateRequest.Trigger t = request.trigger();
            if (t.type() != null && !t.type().equalsIgnoreCase("FORM")) {
                throw RuleEngineException.badRequest("trigger type " + t.type() + " is not supported (FORM only)");
            }
            TriggerPoint point = triggers.pointFor(tenant.id(), module.id(), t.form(), t.action(), blankToNull(t.field()))
                    .orElseThrow(() -> RuleEngineException.notFound("trigger point " + t.form() + "/" + t.action()
                            + (t.field() == null ? "" : "/" + t.field()) + " in module " + module.code()));
            groups.addAll(organizationScoped(triggers.groupsOf(point.id()), tenant.id(), organizationId));
            triggerRef = "FORM:" + t.form() + "/" + t.action().toUpperCase() + (t.field() == null ? "" : "/" + t.field());
        } else {
            throw RuleEngineException.badRequest("give a trigger or the rule groups to evaluate");
        }

        ParameterLibrary library = libraryService.current();
        Map<String, Object> variables;
        try {
            variables = coercer.coerce(request.contextOrEmpty(), library);
        } catch (IllegalArgumentException e) {
            throw RuleEngineException.badRequest(e.getMessage());
        }
        List<String> languages = new ArrayList<>();
        for (String l : new String[]{request.language(), tenant.defaultLanguage(), properties.defaultLanguage(), "en"}) {
            if (l != null && !l.isBlank() && !languages.contains(l)) {
                languages.add(l);
            }
        }
        MessageResolver messages = new MessageResolver(bundles, languages, MessageResolver.flatten(request.contextOrEmpty()));

        List<GroupResult> results = new ArrayList<>();
        for (RuleGroup g : groups) {
            results.add(PolicyStrategy.of(g.policy()).evaluate(new PolicyStrategy.Input(g, this.rules.rulesOfGroup(g.id()),
                    rule -> ruleEvaluator.evaluate(rule, variables, messages, g.code(), g.onError()), messages)));
        }

        boolean result = results.stream().allMatch(GroupResult::result);
        Action action = results.stream().map(GroupResult::action).reduce(Action.ALLOW, Action::max);
        List<FinalMessage> finalMessages = results.stream().flatMap(r -> r.messages().stream()).toList();
        UUID evaluationId = UUID.randomUUID();
        List<Dispatch> dispatches = List.of();
        if (!dryRun) {
            dispatches = dispatch(evaluationId, tenant, module, triggerRef, languages.getFirst(), action, results,
                    MessageResolver.flatten(request.contextOrEmpty()));
            audit(evaluationId, tenant, organizationId, module, triggerRef, result, action, languages.getFirst(),
                    request, results);
        }
        boolean detailed = Boolean.TRUE.equals(request.detailed());
        return new EvaluationResponse(evaluationId, tenant.code(), module.code(), triggerRef, result, action,
                action != Action.BLOCK, languages.getFirst(), finalMessages, detailed ? results : null,
                detailed ? dispatches : null, dryRun);
    }

    /** An organization-specific group of the same code replaces the tenant-wide one. */
    private List<RuleGroup> organizationScoped(List<RuleGroup> bound, long tenantId, @Nullable Long organizationId) {
        List<RuleGroup> out = new ArrayList<>();
        for (RuleGroup g : bound) {
            out.add(rules.groupByCode(tenantId, organizationId, g.code()).orElse(g));
        }
        return out;
    }

    private List<Dispatch> dispatch(UUID evaluationId, Tenant tenant, Module module, String triggerRef, String language,
                                    Action action, List<GroupResult> results, Map<String, String> context) {
        Set<Long> used = new LinkedHashSet<>();
        List<Dispatch> out = new ArrayList<>();
        for (GroupResult g : results) {
            Outcome outcome = g.result() ? Outcome.TRUE : Outcome.FALSE;
            List<ActionBinding> groupBindings = channelRepository.bindingsForGroup(g.groupId(), outcome);
            out.addAll(channels.dispatch(groupBindings, new Notification(evaluationId, tenant.code(), module.code(),
                    triggerRef, "GROUP", g.code(), g.result(), g.action(), language, g.messages(), context), used));
            for (RuleResult r : g.rules()) {
                if (r.selected() && r.evaluated()) {
                    List<ActionBinding> ruleBindings = channelRepository.bindingsForRule(r.ruleId(),
                            r.result() ? Outcome.TRUE : Outcome.FALSE);
                    out.addAll(channels.dispatch(ruleBindings, new Notification(evaluationId, tenant.code(),
                            module.code(), triggerRef, "RULE", r.code(), r.result(), r.action(), language,
                            g.messages(), context), used));
                }
            }
        }
        return out;
    }

    private void audit(UUID id, Tenant tenant, @Nullable Long organizationId, Module module, String triggerRef,
                       boolean result, Action action, String language, EvaluateRequest request,
                       List<GroupResult> results) {
        String keys = null;
        if (properties.storeContextKeys()) {
            List<String> names = new ArrayList<>();
            request.contextOrEmpty().forEach((o, attrs) -> attrs.keySet().forEach(a -> names.add(o + "." + a)));
            keys = mapper.writeValueAsString(names);
        }
        List<Map<String, Object>> summary = new ArrayList<>();
        for (GroupResult g : results) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("group", g.code());
            group.put("policy", g.policy().name());
            group.put("result", g.result());
            group.put("action", g.action().name());
            group.put("rules", g.rules().stream().map(r -> {
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("rule", r.code());
                rule.put("result", r.result());
                rule.put("selected", r.selected());
                if (r.error() != null) {
                    rule.put("error", r.error());
                }
                return rule;
            }).toList());
            summary.add(group);
        }
        logs.saveEvaluation(id, tenant.id(), organizationId, module.code(), triggerRef, result ? "TRUE" : "FALSE",
                action.name(), language, keys, mapper.writeValueAsString(summary));
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
