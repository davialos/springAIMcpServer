package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.cache.MessageCatalog;
import com.springaimcpservercommon.ruleengine.cache.MessageCatalog.Localized;
import com.springaimcpservercommon.ruleengine.cache.TenantCatalog;
import com.springaimcpservercommon.ruleengine.cel.CompiledExpression;
import com.springaimcpservercommon.ruleengine.cel.FactException;
import com.springaimcpservercommon.ruleengine.cel.Facts;
import com.springaimcpservercommon.ruleengine.evaluation.GroupResult;
import com.springaimcpservercommon.ruleengine.evaluation.RuleResult;
import com.springaimcpservercommon.ruleengine.model.ChannelBinding;
import com.springaimcpservercommon.ruleengine.model.Outcome;
import com.springaimcpservercommon.ruleengine.model.OwnerType;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import dev.cel.runtime.CelEvaluationException;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which communications an evaluation asks for. Rule-owned bindings fire per rule result; group-owned
 * bindings fire on the group's outcome: ERROR if any rule errored, otherwise FALSE if any evaluated rule was false
 * (COMPOSITE: unless every rule was true), otherwise TRUE. A binding bound to ERROR fires whenever any rule errored,
 * whatever the group outcome. Stateless and thread-safe.
 */
public final class ChannelPlanner {

    /**
     * Plans the communications.
     *
     * @param catalog   the tenant snapshot the group came from
     * @param result    the raw result
     * @param facts     the caller's values (for recipient expressions)
     * @param languages the end user's preferred languages
     * @return the planned channels, group bindings first, each owner's bindings in sequence order
     */
    public List<PlannedChannel> plan(TenantCatalog catalog, GroupResult result, Facts facts, List<String> languages) {
        List<PlannedChannel> out = new ArrayList<>();
        Set<Outcome> groupOutcomes = groupOutcomes(result);
        for (ChannelBinding b : catalog.channels(OwnerType.GROUP, result.group().id())) {
            if (groupOutcomes.stream().anyMatch(b.on()::fires)) {
                out.add(resolve(catalog, b, null, facts, languages));
            }
        }
        for (RuleResult r : result.evaluated()) {
            for (ChannelBinding b : catalog.channels(OwnerType.RULE, r.ruleId())) {
                if (b.on().fires(r.outcome())) {
                    out.add(resolve(catalog, b, r.ruleCode(), facts, languages));
                }
            }
        }
        return out;
    }

    private static Set<Outcome> groupOutcomes(GroupResult result) {
        Set<Outcome> outcomes = EnumSet.noneOf(Outcome.class);
        boolean anyError = result.evaluated().stream().anyMatch(r -> r.outcome() == Outcome.ERROR);
        boolean anyFalse = result.evaluated().stream().anyMatch(r -> r.outcome() == Outcome.FALSE);
        if (anyError) {
            outcomes.add(Outcome.ERROR);
        }
        outcomes.add(anyError || anyFalse ? Outcome.FALSE : Outcome.TRUE);
        return outcomes;
    }

    private static PlannedChannel resolve(TenantCatalog catalog, ChannelBinding b, @Nullable String ruleCode,
                                          Facts facts, List<String> languages) {
        MessageCatalog messages = catalog.messages();
        String language = languages.isEmpty() ? "" : languages.getFirst();
        Localized title = messages.resolve(b.pushTitleMessage(), languages);
        Localized body = messages.resolve(b.pushBodyMessage(), languages);
        if (body != null) {
            language = body.language();
        }
        return new PlannedChannel(b, ruleCode, recipient(catalog, b, facts),
                language,
                b.emailTemplateId() == null ? null : catalog.data().emailTemplates().get(b.emailTemplateId()),
                b.apiEndpointId() == null ? null : catalog.data().apiEndpoints().get(b.apiEndpointId()),
                title == null ? null : title.text(), body == null ? null : body.text());
    }

    private static @Nullable String recipient(TenantCatalog catalog, ChannelBinding b, Facts facts) {
        CompiledExpression expression = catalog.recipient(b).orElse(null);
        if (expression == null) {
            return null;
        }
        Map<String, Object> bindings = new HashMap<>();
        try {
            for (Parameter p : expression.referenced()) {
                bindings.put(p.celName(), facts.bind(p));
            }
            return expression.program().eval(bindings) instanceof String s && !s.isBlank() ? s : null;
        } catch (FactException | CelEvaluationException | RuntimeException e) {
            return null;
        }
    }
}
