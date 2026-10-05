package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.cache.TenantCatalog;
import com.springaimcpservercommon.ruleengine.cel.Facts;
import com.springaimcpservercommon.ruleengine.channel.ChannelDispatcher;
import com.springaimcpservercommon.ruleengine.channel.ChannelPlanner;
import com.springaimcpservercommon.ruleengine.channel.DispatchResult;
import com.springaimcpservercommon.ruleengine.channel.PlannedChannel;
import com.springaimcpservercommon.ruleengine.evaluation.GroupEvaluator;
import com.springaimcpservercommon.ruleengine.evaluation.GroupResult;
import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.RuleGroup;
import com.springaimcpservercommon.ruleengine.model.TriggerPoint;
import com.springaimcpservercommon.ruleengine.response.EvaluationResponse;
import com.springaimcpservercommon.ruleengine.response.ResponseComposer;
import com.springaimcpservercommon.ruleengine.response.ResponseDetail;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The entry point for applications that integrate the rule engine: evaluate a rule group directly, or evaluate
 * whatever is bound to a trigger point (a form action or a form field). Per call it
 * <ol>
 *   <li>takes the tenant's cached snapshot ({@link RuleCatalogCache}),</li>
 *   <li>evaluates the group's CEL rules under the group's policy ({@link GroupEvaluator}),</li>
 *   <li>composes the localized response ({@link ResponseComposer}),</li>
 *   <li>plans the communications ({@link ChannelPlanner}) and, if asked, sends them ({@link ChannelDispatcher}),</li>
 *   <li>records the decision without the input values ({@link EvaluationRecorder}).</li>
 * </ol>
 * Thread-safe; holds no per-request state.
 */
public final class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleCatalogCache cache;
    private final @Nullable ChannelDispatcher dispatcher;
    private final EvaluationRecorder recorder;
    private final GroupEvaluator evaluator = new GroupEvaluator();
    private final ResponseComposer composer = new ResponseComposer();
    private final ChannelPlanner planner = new ChannelPlanner();

    /**
     * Creates the engine.
     *
     * @param cache      rule cache
     * @param dispatcher sends communications, or {@code null} to only plan them
     * @param recorder   evaluation log
     */
    public RuleEngine(RuleCatalogCache cache, @Nullable ChannelDispatcher dispatcher, EvaluationRecorder recorder) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.dispatcher = dispatcher;
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    /**
     * Evaluates a rule group and returns messages only; communications are planned, not sent.
     *
     * @param request what to evaluate
     * @return the response and planned communications
     * @throws UnknownRuleGroupException if the tenant has no such active group
     */
    public EvaluationResult evaluate(EvaluationRequest request) {
        return evaluate(request, ResponseDetail.MESSAGES, false);
    }

    /**
     * Evaluates a rule group.
     *
     * @param request  what to evaluate
     * @param detail   whether the response carries the raw per-rule results
     * @param dispatch whether to send the planned communications
     * @return the response, planned communications and (if dispatched) their results
     * @throws UnknownRuleGroupException if the tenant has no such active group
     */
    public EvaluationResult evaluate(EvaluationRequest request, ResponseDetail detail, boolean dispatch) {
        TenantCatalog catalog = cache.catalog(request.tenantId());
        RuleGroup group = catalog.group(request.moduleCode(), request.groupCode(), request.organizationId())
                .orElseThrow(() -> new UnknownRuleGroupException(request.moduleCode(), request.groupCode()));
        return run(catalog, group, request.tenantId(), request.organizationId(), null, new Facts(request.facts()),
                request.languages(), detail, dispatch);
    }

    /**
     * Evaluates every rule group bound to a trigger point and returns messages only.
     *
     * @param request the application event
     * @return the strictest decision and one result per bound group
     */
    public TriggerResult evaluate(TriggerRequest request) {
        return evaluate(request, ResponseDetail.MESSAGES, false);
    }

    /**
     * Evaluates every rule group bound to a trigger point, in trigger sequence order.
     *
     * @param request  the application event
     * @param detail   whether responses carry the raw per-rule results
     * @param dispatch whether to send the planned communications
     * @return the strictest decision and one result per bound group; ALLOW with no groups if nothing is bound
     */
    public TriggerResult evaluate(TriggerRequest request, ResponseDetail detail, boolean dispatch) {
        TenantCatalog catalog = cache.catalog(request.tenantId());
        Facts facts = new Facts(request.facts());
        Action decision = Action.ALLOW;
        List<EvaluationResult> results = new ArrayList<>();
        for (TriggerPoint t : catalog.triggers(request.application(), request.type(), request.formCode(),
                request.actionCode(), request.fieldCode(), request.organizationId())) {
            RuleGroup group = catalog.group(t.ruleGroupId()).orElse(null);
            if (group == null) {
                continue; // bound group is retired or in another state: the trigger does nothing
            }
            EvaluationResult r = run(catalog, group, request.tenantId(), request.organizationId(), t.id(), facts,
                    request.languages(), detail, dispatch);
            results.add(r);
            decision = Action.strictest(decision, r.response().decision());
        }
        return new TriggerResult(decision, results);
    }

    private EvaluationResult run(TenantCatalog catalog, RuleGroup group, UUID tenantId, @Nullable UUID organizationId,
                                 @Nullable UUID triggerId, Facts facts, List<String> languages,
                                 ResponseDetail detail, boolean dispatch) {
        long start = System.nanoTime();
        GroupResult raw = evaluator.evaluate(catalog, group, facts);
        long micros = (System.nanoTime() - start) / 1_000;
        EvaluationResponse response = composer.compose(raw, catalog.messages(), languages, detail);
        List<PlannedChannel> planned = planner.plan(catalog, raw, facts, languages);
        List<DispatchResult> dispatched = dispatch && dispatcher != null
                ? dispatcher.dispatch(planned, response) : List.of();
        try {
            recorder.record(tenantId, organizationId, triggerId, languages.isEmpty() ? null : languages.getFirst(),
                    micros, raw);
        } catch (RuntimeException e) {
            log.warn("could not record evaluation of group {}: {}", group.code(), e.getClass().getSimpleName());
        }
        return new EvaluationResult(response, planned, dispatched);
    }
}
