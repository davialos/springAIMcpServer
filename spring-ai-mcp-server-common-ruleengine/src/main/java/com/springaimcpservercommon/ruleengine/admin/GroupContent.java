package com.springaimcpservercommon.ruleengine.admin;

import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.EvaluationPolicy;
import com.springaimcpservercommon.ruleengine.model.MatchOn;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The editable definition of a rule group (what a revision holds).
 *
 * @param name                   display name
 * @param description            free text
 * @param policy                 evaluation policy
 * @param matchOn                what FIRST_MATCH / ALL_MATCH select on
 * @param compositeTrueBundleId  COMPOSITE only: message when every rule is true
 * @param compositeFalseBundleId COMPOSITE only: message when any rule is false
 * @param compositeTrueAction    COMPOSITE only: action when every rule is true
 * @param compositeFalseAction   COMPOSITE only: action when any rule is false
 * @param onError                action for a rule that cannot be evaluated
 * @param members                member rules with their order
 */
public record GroupContent(String name, @Nullable String description, EvaluationPolicy policy, MatchOn matchOn,
                           @Nullable UUID compositeTrueBundleId, @Nullable UUID compositeFalseBundleId,
                           Action compositeTrueAction, Action compositeFalseAction, Action onError,
                           List<Member> members) {

    /**
     * A member rule.
     *
     * @param ruleId   the rule (must be published to be live)
     * @param sequence evaluation order, ascending, unique in the group
     * @param enabled  disabled members are kept but skipped
     */
    public record Member(UUID ruleId, int sequence, boolean enabled) {
    }

    /** Defensive copy. */
    public GroupContent {
        members = List.copyOf(members);
    }
}
