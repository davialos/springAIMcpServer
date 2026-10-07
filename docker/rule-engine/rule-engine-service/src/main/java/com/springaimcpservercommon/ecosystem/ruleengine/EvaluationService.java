package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.Decision;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.EvaluateRequest;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.MessageView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.PlannedChannelView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.RuleOutcome;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.TriggerDecision;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.TriggerRequestBody;
import com.springaimcpservercommon.ruleengine.EvaluationRequest;
import com.springaimcpservercommon.ruleengine.EvaluationResult;
import com.springaimcpservercommon.ruleengine.RuleEngine;
import com.springaimcpservercommon.ruleengine.TriggerRequest;
import com.springaimcpservercommon.ruleengine.TriggerResult;
import com.springaimcpservercommon.ruleengine.channel.PlannedChannel;
import com.springaimcpservercommon.ruleengine.evaluation.RuleResult;
import com.springaimcpservercommon.ruleengine.model.TriggerType;
import com.springaimcpservercommon.ruleengine.response.EvaluationResponse;
import com.springaimcpservercommon.ruleengine.response.ResponseDetail;
import com.springaimcpservercommon.ruleengine.response.ResponseMessage;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runs the engine for the caller (the "test bench" and trigger evaluation). The scope is the caller's token scope. Facts
 * are validated for size and shape and never stored, logged or echoed back; the recipient of a planned communication is
 * never returned and nothing is dispatched: this API answers "what would happen", it does not e-mail or call anyone.
 * Each request leaves a value-free audit entry (who evaluated which group, the decision, the counts, the time taken).
 */
@Service
class EvaluationService {

    private static final Pattern FACT_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_.]{0,127}$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");
    private static final int MAX_LANGUAGES = 5;

    private final RuleEngine engine;
    private final AuditLog audit;
    private final RuleEngineProperties props;

    EvaluationService(RuleEngine engine, AuditLog audit, RuleEngineProperties props) {
        this.engine = engine;
        this.audit = audit;
        this.props = props;
    }

    Decision evaluate(Caller c, EvaluateRequest req) {
        String module = require(req.moduleCode(), "moduleCode");
        String group = require(req.groupCode(), "groupCode");
        Map<String, Object> facts = facts(req.facts());
        long start = System.nanoTime();
        EvaluationResult result = engine.evaluate(new EvaluationRequest(c.tenantId(), c.organizationId(), module, group,
                facts, languages(req.languages())), ResponseDetail.WITH_RAW, false);
        long micros = (System.nanoTime() - start) / 1_000;
        Decision decision = decision(result, micros);
        record(c, "TEST_BENCH", module + "/" + group, decision);
        return decision;
    }

    TriggerDecision trigger(Caller c, TriggerRequestBody req) {
        TriggerType type;
        try {
            type = TriggerType.valueOf(require(req.type(), "type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.bad("invalid_type", "type is FORM_ACTION or FORM_FIELD");
        }
        Map<String, Object> facts = facts(req.facts());
        long start = System.nanoTime();
        TriggerResult result = engine.evaluate(new TriggerRequest(c.tenantId(), c.organizationId(),
                require(req.application(), "application"), type, require(req.formCode(), "formCode"),
                require(req.actionCode(), "actionCode"), req.fieldCode(), facts, languages(req.languages())),
                ResponseDetail.WITH_RAW, false);
        long micros = (System.nanoTime() - start) / 1_000;
        List<Decision> groups = new ArrayList<>();
        for (EvaluationResult r : result.groups()) {
            Decision d = decision(r, micros);
            groups.add(d);
            record(c, "TRIGGER", d.moduleCode() + "/" + d.groupCode(), d);
        }
        return new TriggerDecision(result.decision().name(), groups);
    }

    private void record(Caller c, String source, String code, Decision d) {
        long errors = d.results().stream().filter(r -> "ERROR".equals(r.outcome())).count();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", source);
        details.put("decision", d.decision());
        details.put("policy", d.policy());
        details.put("matched", d.matched());
        details.put("rulesEvaluated", d.results().size());
        details.put("rulesError", errors);
        details.put("durationMicros", d.durationMicros());
        audit.record(c, c.organizationId(), "RULE_GROUP_EVALUATED", "EVALUATION", null, code,
                "Evaluated " + code + " → " + d.decision(), details);
    }

    private Decision decision(EvaluationResult result, long micros) {
        EvaluationResponse r = result.response();
        List<RuleOutcome> outcomes = new ArrayList<>();
        for (RuleResult x : r.results()) {
            outcomes.add(new RuleOutcome(x.ruleCode(), x.ruleName(), x.sequence(), x.outcome().name(),
                    x.action().name(), x.errorCode(), x.errorDetail()));
        }
        List<PlannedChannelView> channels = new ArrayList<>();
        for (PlannedChannel p : result.planned()) {
            channels.add(new PlannedChannelView(p.binding().type().name(), p.binding().on().name(), p.ruleCode(),
                    p.recipient() != null, target(p)));
        }
        return new Decision(r.moduleCode(), r.groupCode(), r.policy().name(), r.decision().name(), r.matched(),
                r.primaryMessage() == null ? null : message(r.primaryMessage()),
                r.messages().stream().map(EvaluationService::message).toList(), outcomes, channels, micros);
    }

    private static MessageView message(ResponseMessage m) {
        return new MessageView(m.source().name(), m.ruleCode(), m.outcome().name(), m.action().name(), m.language(),
                m.text());
    }

    private static @Nullable String target(PlannedChannel p) {
        if (p.emailTemplate() != null) {
            return p.emailTemplate().name() + " (" + p.emailTemplate().templateRef() + ")";
        }
        return p.apiEndpoint() != null ? p.apiEndpoint().name() : p.pushTitle();
    }

    private Map<String, Object> facts(@Nullable Map<String, Object> facts) {
        if (facts == null) {
            return Map.of();
        }
        if (facts.size() > props.maxFacts()) {
            throw ApiProblem.invalid("too_many_facts", "an evaluation takes at most " + props.maxFacts() + " facts");
        }
        for (String key : facts.keySet()) {
            if (key == null || !FACT_KEY.matcher(key).matches()) {
                throw ApiProblem.bad("invalid_fact_name", "fact names look like customer.age");
            }
        }
        return facts;
    }

    private static List<String> languages(@Nullable List<String> languages) {
        if (languages == null || languages.isEmpty()) {
            return List.of("en");
        }
        if (languages.size() > MAX_LANGUAGES) {
            throw ApiProblem.bad("too_many_languages", "at most " + MAX_LANGUAGES + " languages");
        }
        for (String l : languages) {
            if (l == null || !LANGUAGE.matcher(l).matches()) {
                throw ApiProblem.bad("invalid_language", "language tags look like en, hi, th or pt-BR");
            }
        }
        return List.copyOf(languages);
    }

    private static String require(@Nullable String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw ApiProblem.bad("invalid_" + field, field + " is required");
        }
        return value.strip();
    }
}
