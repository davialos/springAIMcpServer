package com.springaimcpservercommon.ruleengine.response;

import com.springaimcpservercommon.ruleengine.cache.MessageCatalog;
import com.springaimcpservercommon.ruleengine.cache.MessageCatalog.Localized;
import com.springaimcpservercommon.ruleengine.evaluation.GroupResult;
import com.springaimcpservercommon.ruleengine.evaluation.RuleResult;
import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.EvaluationPolicy;
import com.springaimcpservercommon.ruleengine.model.Outcome;
import com.springaimcpservercommon.ruleengine.model.RuleGroup;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the raw group result into the caller's response: picks the messages the policy reports, localizes them and
 * chooses the single headline message. Stateless and thread-safe.
 */
public final class ResponseComposer {

    /**
     * Composes the response.
     *
     * @param result    raw group result
     * @param messages  message catalog
     * @param languages caller's preferred languages, most preferred first
     * @param detail    whether to include the raw rule results
     * @return the response
     */
    public EvaluationResponse compose(GroupResult result, MessageCatalog messages, List<String> languages,
                                      ResponseDetail detail) {
        RuleGroup group = result.group();
        List<ResponseMessage> out = new ArrayList<>();
        if (group.policy() == EvaluationPolicy.COMPOSITE) {
            ResponseMessage head = result.matched()
                    ? groupMessage(messages, languages, group.compositeTrueMessage(), Outcome.TRUE,
                            group.compositeTrueAction())
                    : groupMessage(messages, languages, group.compositeFalseMessage(), Outcome.FALSE,
                            group.compositeFalseAction());
            if (head != null) {
                out.add(head);
            }
        }
        for (RuleResult r : result.selected()) {
            Localized text = messages.resolve(r.messageBundle(), languages);
            if (text != null) {
                out.add(new ResponseMessage(MessageSource.RULE, r.ruleCode(), r.outcome(), r.action(),
                        text.language(), text.text()));
            }
        }
        return new EvaluationResponse(group.moduleCode(), group.code(), group.policy(), result.decision(),
                result.matched(), primary(group, out), out,
                detail == ResponseDetail.WITH_RAW ? result.evaluated() : List.of());
    }

    private static @Nullable ResponseMessage groupMessage(MessageCatalog messages, List<String> languages,
                                                         java.util.@Nullable UUID bundle, Outcome outcome, Action action) {
        Localized text = messages.resolve(bundle, languages);
        return text == null ? null
                : new ResponseMessage(MessageSource.GROUP, null, outcome, action, text.language(), text.text());
    }

    /** Composite: the group message. Otherwise the most severe message, first one winning ties. */
    private static @Nullable ResponseMessage primary(RuleGroup group, List<ResponseMessage> messages) {
        if (messages.isEmpty()) {
            return null;
        }
        if (group.policy() == EvaluationPolicy.COMPOSITE && messages.getFirst().source() == MessageSource.GROUP) {
            return messages.getFirst();
        }
        ResponseMessage best = messages.getFirst();
        for (ResponseMessage m : messages) {
            if (m.action().compareTo(best.action()) > 0) {
                best = m;
            }
        }
        return best;
    }
}
