package com.springaimcpservercommon.ruleengine.repo;

import com.springaimcpservercommon.ruleengine.domain.Model.RuleGroup;
import com.springaimcpservercommon.ruleengine.domain.Model.TriggerBinding;
import com.springaimcpservercommon.ruleengine.domain.Model.TriggerPoint;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Trigger points and the rule groups bound to them. */
@Repository
public class TriggerRepository {

    private static final String POINT = "select id, tenant_id, module_id, code, name, form_code, action_type, "
            + "field_code, active from re_trigger_point";

    private final JdbcClient jdbc;

    public TriggerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TriggerPoint> point(long id) {
        return jdbc.sql(POINT + " where id = :i").param("i", id).query(TriggerPoint.class).optional();
    }

    public Optional<TriggerPoint> pointByCode(long tenantId, String code) {
        return jdbc.sql(POINT + " where tenant_id = :t and code = :c and active").param("t", tenantId)
                .param("c", code).query(TriggerPoint.class).optional();
    }

    /** The trigger point of a form action (and field): exact match, a null field means "the whole form". */
    public Optional<TriggerPoint> pointFor(long tenantId, long moduleId, String form, String action,
                                           @Nullable String field) {
        return jdbc.sql(POINT + " where tenant_id = :t and module_id = :m and upper(form_code) = upper(:f) "
                        + "and upper(action_type) = upper(:a) and active and "
                        + "(field_code is not distinct from :fc) limit 1")
                .param("t", tenantId).param("m", moduleId).param("f", form).param("a", action).param("fc", field)
                .query(TriggerPoint.class).optional();
    }

    public List<TriggerPoint> points(long tenantId) {
        return jdbc.sql(POINT + " where tenant_id = :t order by code").param("t", tenantId)
                .query(TriggerPoint.class).list();
    }

    public TriggerPoint createPoint(long tenantId, long moduleId, String code, String name, String form, String action,
                                    @Nullable String field) {
        long id = jdbc.sql("insert into re_trigger_point (tenant_id, module_id, code, name, form_code, action_type, "
                        + "field_code) values (:t, :m, :c, :n, :f, :a, :fc) returning id")
                .param("t", tenantId).param("m", moduleId).param("c", code).param("n", name).param("f", form)
                .param("a", action.toUpperCase()).param("fc", field).query(Long.class).single();
        return point(id).orElseThrow();
    }

    /** Groups bound to a trigger point, in sequence (active bindings of active groups). */
    public List<RuleGroup> groupsOf(long triggerPointId) {
        return jdbc.sql("select g.id, g.tenant_id, g.organization_id, g.module_id, g.code, g.name, g.description, "
                        + "g.evaluation_policy as policy, (g.match_on = 'TRUE') as match_on_true, g.composite_mode, "
                        + "g.on_error, g.true_bundle_id, g.false_bundle_id, g.true_action, g.false_action, g.status, "
                        + "g.version from re_rule_group g join re_trigger_binding b on b.rule_group_id = g.id "
                        + "where b.trigger_point_id = :p and b.active and g.status = 'ACTIVE' order by b.sequence, b.id")
                .param("p", triggerPointId).query(RuleGroup.class).list();
    }

    public List<TriggerBinding> bindings(long triggerPointId) {
        return jdbc.sql("select id, trigger_point_id, rule_group_id, sequence, active from re_trigger_binding "
                + "where trigger_point_id = :p order by sequence, id").param("p", triggerPointId)
                .query(TriggerBinding.class).list();
    }

    public void bind(long triggerPointId, long ruleGroupId, int sequence, boolean active) {
        jdbc.sql("insert into re_trigger_binding (trigger_point_id, rule_group_id, sequence, active) "
                        + "values (:p, :g, :s, :a) on conflict (trigger_point_id, rule_group_id) "
                        + "do update set sequence = :s, active = :a")
                .param("p", triggerPointId).param("g", ruleGroupId).param("s", sequence).param("a", active).update();
    }

    public void unbind(long triggerPointId, long ruleGroupId) {
        jdbc.sql("delete from re_trigger_binding where trigger_point_id = :p and rule_group_id = :g")
                .param("p", triggerPointId).param("g", ruleGroupId).update();
    }
}
