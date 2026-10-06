package com.springaimcpservercommon.ruleengine.repo;

import com.springaimcpservercommon.ruleengine.domain.Action;
import com.springaimcpservercommon.ruleengine.domain.Enums.CompositeMode;
import com.springaimcpservercommon.ruleengine.domain.Enums.OnError;
import com.springaimcpservercommon.ruleengine.domain.Enums.Status;
import com.springaimcpservercommon.ruleengine.domain.Model.GroupMember;
import com.springaimcpservercommon.ruleengine.domain.Model.Rule;
import com.springaimcpservercommon.ruleengine.domain.Model.RuleGroup;
import com.springaimcpservercommon.ruleengine.domain.Policy;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Rules, rule groups and group membership. */
@Repository
public class RuleRepository {

    private static final String RULE = "select id, tenant_id, organization_id, module_id, code, name, description, "
            + "expression, true_bundle_id, false_bundle_id, true_action, false_action, status, version from re_rule";
    private static final String GROUP = "select id, tenant_id, organization_id, module_id, code, name, description, "
            + "evaluation_policy as policy, (match_on = 'TRUE') as match_on_true, composite_mode, on_error, "
            + "true_bundle_id, false_bundle_id, true_action, false_action, status, version from re_rule_group";

    private final JdbcClient jdbc;

    public RuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── rules ──────────────────────────────────────────────────────────────────────────────────────

    /** The fields of a rule that can be written. */
    public record RuleWrite(@Nullable Long organizationId, long moduleId, String code, String name,
                            @Nullable String description, String expression, @Nullable Long trueBundleId,
                            @Nullable Long falseBundleId, Action trueAction, Action falseAction, Status status) { }

    public Optional<Rule> rule(long id) {
        return jdbc.sql(RULE + " where id = :i").param("i", id).query(Rule.class).optional();
    }

    public List<Rule> rules(long tenantId) {
        return jdbc.sql(RULE + " where tenant_id = :t order by code").param("t", tenantId).query(Rule.class).list();
    }

    public Rule createRule(long tenantId, RuleWrite w) {
        long id = jdbc.sql("insert into re_rule (tenant_id, organization_id, module_id, code, name, description, "
                        + "expression, true_bundle_id, false_bundle_id, true_action, false_action, status) values "
                        + "(:t, :o, :m, :c, :n, :d, :e, :tb, :fb, :ta, :fa, :s) returning id")
                .param("t", tenantId).param("o", w.organizationId()).param("m", w.moduleId()).param("c", w.code())
                .param("n", w.name()).param("d", w.description()).param("e", w.expression())
                .param("tb", w.trueBundleId()).param("fb", w.falseBundleId()).param("ta", w.trueAction().name())
                .param("fa", w.falseAction().name()).param("s", w.status().name()).query(Long.class).single();
        return rule(id).orElseThrow();
    }

    public Rule updateRule(long id, RuleWrite w) {
        jdbc.sql("update re_rule set organization_id = :o, module_id = :m, name = :n, description = :d, "
                        + "expression = :e, true_bundle_id = :tb, false_bundle_id = :fb, true_action = :ta, "
                        + "false_action = :fa, status = :s, version = version + 1, updated_at = now() where id = :i")
                .param("o", w.organizationId()).param("m", w.moduleId()).param("n", w.name())
                .param("d", w.description()).param("e", w.expression()).param("tb", w.trueBundleId())
                .param("fb", w.falseBundleId()).param("ta", w.trueAction().name()).param("fa", w.falseAction().name())
                .param("s", w.status().name()).param("i", id).update();
        return rule(id).orElseThrow();
    }

    /** The rules of a group, active members only, in sequence. */
    public List<Rule> rulesOfGroup(long groupId) {
        return jdbc.sql("select r.id, r.tenant_id, r.organization_id, r.module_id, r.code, r.name, r.description, "
                        + "r.expression, r.true_bundle_id, r.false_bundle_id, r.true_action, r.false_action, "
                        + "r.status, r.version from re_rule r join re_rule_group_member m on m.rule_id = r.id "
                        + "where m.group_id = :g and m.active and r.status = 'ACTIVE' order by m.sequence, m.id")
                .param("g", groupId).query(Rule.class).list();
    }

    // ── groups ─────────────────────────────────────────────────────────────────────────────────────

    /** The fields of a group that can be written. */
    public record GroupWrite(@Nullable Long organizationId, long moduleId, String code, String name,
                             @Nullable String description, Policy policy, boolean matchOnTrue,
                             CompositeMode compositeMode, OnError onError, @Nullable Long trueBundleId,
                             @Nullable Long falseBundleId, Action trueAction, Action falseAction, Status status) { }

    public Optional<RuleGroup> group(long id) {
        return jdbc.sql(GROUP + " where id = :i").param("i", id).query(RuleGroup.class).optional();
    }

    /** The group a tenant/organization uses for a code: the organization's own, else the tenant-wide one. */
    public Optional<RuleGroup> groupByCode(long tenantId, @Nullable Long organizationId, String code) {
        return jdbc.sql(GROUP + " where tenant_id = :t and code = :c and status = 'ACTIVE' "
                        + "and (organization_id is null or organization_id = :o) "
                        + "order by organization_id nulls last limit 1")
                .param("t", tenantId).param("c", code).param("o", organizationId).query(RuleGroup.class).optional();
    }

    public List<RuleGroup> groups(long tenantId) {
        return jdbc.sql(GROUP + " where tenant_id = :t order by code").param("t", tenantId)
                .query(RuleGroup.class).list();
    }

    public RuleGroup createGroup(long tenantId, GroupWrite w) {
        long id = jdbc.sql("insert into re_rule_group (tenant_id, organization_id, module_id, code, name, "
                        + "description, evaluation_policy, match_on, composite_mode, on_error, true_bundle_id, "
                        + "false_bundle_id, true_action, false_action, status) values (:t, :o, :m, :c, :n, :d, :p, "
                        + ":mo, :cm, :oe, :tb, :fb, :ta, :fa, :s) returning id")
                .param("t", tenantId).param("o", w.organizationId()).param("m", w.moduleId()).param("c", w.code())
                .param("n", w.name()).param("d", w.description()).param("p", w.policy().name())
                .param("mo", w.matchOnTrue() ? "TRUE" : "FALSE").param("cm", w.compositeMode().name())
                .param("oe", w.onError().name()).param("tb", w.trueBundleId()).param("fb", w.falseBundleId())
                .param("ta", w.trueAction().name()).param("fa", w.falseAction().name()).param("s", w.status().name())
                .query(Long.class).single();
        return group(id).orElseThrow();
    }

    public RuleGroup updateGroup(long id, GroupWrite w) {
        jdbc.sql("update re_rule_group set organization_id = :o, module_id = :m, name = :n, description = :d, "
                        + "evaluation_policy = :p, match_on = :mo, composite_mode = :cm, on_error = :oe, "
                        + "true_bundle_id = :tb, false_bundle_id = :fb, true_action = :ta, false_action = :fa, "
                        + "status = :s, version = version + 1, updated_at = now() where id = :i")
                .param("o", w.organizationId()).param("m", w.moduleId()).param("n", w.name())
                .param("d", w.description()).param("p", w.policy().name()).param("mo", w.matchOnTrue() ? "TRUE" : "FALSE")
                .param("cm", w.compositeMode().name()).param("oe", w.onError().name()).param("tb", w.trueBundleId())
                .param("fb", w.falseBundleId()).param("ta", w.trueAction().name()).param("fa", w.falseAction().name())
                .param("s", w.status().name()).param("i", id).update();
        return group(id).orElseThrow();
    }

    public List<GroupMember> members(long groupId) {
        return jdbc.sql("select id, group_id, rule_id, sequence, active from re_rule_group_member where group_id = :g "
                + "order by sequence, id").param("g", groupId).query(GroupMember.class).list();
    }

    public void setMember(long groupId, long ruleId, int sequence, boolean active) {
        jdbc.sql("insert into re_rule_group_member (group_id, rule_id, sequence, active) values (:g, :r, :s, :a) "
                        + "on conflict (group_id, rule_id) do update set sequence = :s, active = :a")
                .param("g", groupId).param("r", ruleId).param("s", sequence).param("a", active).update();
        jdbc.sql("update re_rule_group set version = version + 1, updated_at = now() where id = :g")
                .param("g", groupId).update();
    }

    public void removeMember(long groupId, long ruleId) {
        jdbc.sql("delete from re_rule_group_member where group_id = :g and rule_id = :r")
                .param("g", groupId).param("r", ruleId).update();
    }
}
