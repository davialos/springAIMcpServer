package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.ApiEndpointView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.Attribute;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.ChannelView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.EmailTemplateView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.GroupRuleView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.GroupView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.LibraryObject;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.Module;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.RuleView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.SetupSummary;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.TriggerView;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of the setup: everything the caller may see, straight from the {@code dai_re_*} tables (including DRAFT and
 * RETIRED items, which the engine's own cache does not hold). Every query is tenant-scoped; an organization user sees the
 * tenant-wide items plus the items of their own organization, a tenant-wide user sees them all.
 */
@Repository
class CatalogRepository {

    /** SQL fragment: the row's organization is visible to the caller. {@code col} is a column of the queried table. */
    static String visible(String col) {
        return "(CAST(:org AS uuid) IS NULL OR " + col + " IS NULL OR " + col + " = CAST(:org AS uuid))";
    }

    private final JdbcClient jdbc;

    CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Named parameters shared by the scoped queries: the tenant and the (possibly null) organization. */
    static Map<String, Object> scope(Caller c) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", c.tenantId());
        p.put("org", c.organizationId());
        return p;
    }

    static String scopeLabel(@Nullable UUID organizationId) {
        return organizationId == null ? "TENANT" : "ORGANIZATION";
    }

    List<Module> modules() {
        return jdbc.sql("SELECT code, name, description FROM dai_re_module WHERE active ORDER BY code")
                .query((rs, n) -> new Module(rs.getString("code"), rs.getString("name"), rs.getString("description")))
                .list();
    }

    List<LibraryObject> library(Caller c) {
        Map<String, LibraryObject> objects = new LinkedHashMap<>();
        Map<String, List<Attribute>> attributes = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT o.code AS object_code, o.name AS object_name, m.code AS module_code, a.code AS attr_code,
                       a.name AS attr_name, a.data_type, a.required, a.sample_value,
                       (SELECT count(*) FROM dai_re_rule_parameter rp JOIN dai_re_rule r ON r.id = rp.rule_id
                         WHERE rp.attribute_id = a.id AND r.tenant_id = :t AND r.status <> 'RETIRED') AS used_by
                FROM dai_re_sys_object o
                         LEFT JOIN dai_re_module m ON m.id = o.module_id
                         JOIN dai_re_sys_object_attribute a ON a.object_id = o.id
                WHERE o.active AND a.active
                ORDER BY o.code, a.code
                """).params(scope(c)).query((rs, n) -> {
            String object = rs.getString("object_code");
            attributes.computeIfAbsent(object, k -> new ArrayList<>()).add(new Attribute(rs.getString("attr_code"),
                    rs.getString("attr_name"), rs.getString("data_type"), rs.getBoolean("required"),
                    rs.getString("sample_value"), object + "." + rs.getString("attr_code"), rs.getInt("used_by")));
            objects.putIfAbsent(object, new LibraryObject(object, rs.getString("object_name"),
                    rs.getString("module_code"), List.of()));
            return object;
        }).list();
        List<LibraryObject> out = new ArrayList<>();
        objects.forEach((code, o) -> out.add(new LibraryObject(o.code(), o.name(), o.moduleCode(),
                attributes.get(code))));
        return out;
    }

    List<RuleView> rules(Caller c, @Nullable String moduleCode, @Nullable String status) {
        Map<String, Object> p = scope(c);
        p.put("module", moduleCode);
        p.put("status", status);
        List<RuleRow> rows = jdbc.sql("""
                SELECT r.id, m.code AS module_code, r.organization_id, r.code, r.name, r.description, r.cel_expression,
                       r.status, r.true_action, r.false_action, r.true_message_bundle_id, r.false_message_bundle_id,
                       r.row_version, r.updated_at
                FROM dai_re_rule r JOIN dai_re_module m ON m.id = r.module_id
                WHERE r.tenant_id = :t AND %s
                  AND (CAST(:module AS text) IS NULL OR m.code = CAST(:module AS text))
                  AND (CAST(:status AS text) IS NULL OR r.status = CAST(:status AS text))
                ORDER BY m.code, r.code, r.organization_id NULLS FIRST
                """.formatted(visible("r.organization_id"))).params(p).query(CatalogRepository::ruleRow).list();
        return enrichRules(rows);
    }

    RuleView rule(Caller c, UUID id) {
        Map<String, Object> p = scope(c);
        p.put("id", id);
        List<RuleRow> rows = jdbc.sql("""
                SELECT r.id, m.code AS module_code, r.organization_id, r.code, r.name, r.description, r.cel_expression,
                       r.status, r.true_action, r.false_action, r.true_message_bundle_id, r.false_message_bundle_id,
                       r.row_version, r.updated_at
                FROM dai_re_rule r JOIN dai_re_module m ON m.id = r.module_id
                WHERE r.tenant_id = :t AND r.id = :id AND %s
                """.formatted(visible("r.organization_id"))).params(p).query(CatalogRepository::ruleRow).list();
        if (rows.isEmpty()) {
            throw ApiProblem.notFound("rule");
        }
        return enrichRules(rows).getFirst();
    }

    private record RuleRow(UUID id, String moduleCode, @Nullable UUID organizationId, String code, String name,
                           @Nullable String description, String expression, String status, String trueAction,
                           String falseAction, @Nullable UUID trueBundle, @Nullable UUID falseBundle, long rowVersion,
                           Instant updatedAt) {
    }

    private static RuleRow ruleRow(ResultSet rs, int n) throws SQLException {
        return new RuleRow(rs.getObject("id", UUID.class), rs.getString("module_code"),
                rs.getObject("organization_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("cel_expression"), rs.getString("status"),
                rs.getString("true_action"), rs.getString("false_action"),
                rs.getObject("true_message_bundle_id", UUID.class), rs.getObject("false_message_bundle_id", UUID.class),
                rs.getLong("row_version"), rs.getTimestamp("updated_at").toInstant());
    }

    private List<RuleView> enrichRules(List<RuleRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(RuleRow::id).toList();
        List<UUID> bundles = new ArrayList<>();
        rows.forEach(r -> {
            if (r.trueBundle() != null) {
                bundles.add(r.trueBundle());
            }
            if (r.falseBundle() != null) {
                bundles.add(r.falseBundle());
            }
        });
        Map<UUID, Map<String, String>> messages = messages(bundles);
        Map<UUID, List<String>> parameters = pairs("""
                SELECT rp.rule_id AS k, o.code || '.' || a.code AS v FROM dai_re_rule_parameter rp
                JOIN dai_re_sys_object_attribute a ON a.id = rp.attribute_id JOIN dai_re_sys_object o ON o.id = a.object_id
                WHERE rp.rule_id IN (:ids) ORDER BY 2""", ids);
        Map<UUID, List<String>> groups = pairs("""
                SELECT gr.rule_id AS k, g.code AS v FROM dai_re_rule_group_rule gr
                JOIN dai_re_rule_group g ON g.id = gr.group_id WHERE gr.rule_id IN (:ids) ORDER BY 2""", ids);
        List<RuleView> out = new ArrayList<>();
        for (RuleRow r : rows) {
            out.add(new RuleView(r.id(), r.moduleCode(), r.code(), r.name(), r.description(), r.expression(),
                    r.status(), scopeLabel(r.organizationId()), r.trueAction(), r.falseAction(),
                    messages.getOrDefault(r.trueBundle(), Map.of()), messages.getOrDefault(r.falseBundle(), Map.of()),
                    parameters.getOrDefault(r.id(), List.of()), groups.getOrDefault(r.id(), List.of()),
                    r.rowVersion(), r.updatedAt()));
        }
        return out;
    }

    List<GroupView> groups(Caller c, @Nullable String moduleCode, @Nullable String status) {
        Map<String, Object> p = scope(c);
        p.put("module", moduleCode);
        p.put("status", status);
        return enrichGroups(groupRows(jdbc.sql(groupSql("""
                WHERE g.tenant_id = :t AND %s
                  AND (CAST(:module AS text) IS NULL OR m.code = CAST(:module AS text))
                  AND (CAST(:status AS text) IS NULL OR g.status = CAST(:status AS text))
                ORDER BY m.code, g.code, g.organization_id NULLS FIRST""".formatted(visible("g.organization_id"))))
                .params(p)));
    }

    GroupView group(Caller c, UUID id) {
        Map<String, Object> p = scope(c);
        p.put("id", id);
        List<GroupView> found = enrichGroups(groupRows(jdbc.sql(groupSql(
                "WHERE g.tenant_id = :t AND g.id = :id AND " + visible("g.organization_id"))).params(p)));
        if (found.isEmpty()) {
            throw ApiProblem.notFound("rule group");
        }
        return found.getFirst();
    }

    private static String groupSql(String where) {
        return """
                SELECT g.id, m.code AS module_code, g.organization_id, g.code, g.name, g.description, g.status,
                       g.evaluation_policy, g.match_on, g.on_error, g.composite_true_action, g.composite_false_action,
                       g.composite_true_bundle_id, g.composite_false_bundle_id, g.row_version, g.updated_at,
                       (SELECT count(*) FROM dai_re_outcome_channel c WHERE c.owner_type = 'GROUP' AND c.owner_id = g.id) AS channels
                FROM dai_re_rule_group g JOIN dai_re_module m ON m.id = g.module_id
                """ + where;
    }

    private record GroupRow(UUID id, String moduleCode, @Nullable UUID organizationId, String code, String name,
                            @Nullable String description, String status, String policy, String matchOn, String onError,
                            String trueAction, String falseAction, @Nullable UUID trueBundle,
                            @Nullable UUID falseBundle, long rowVersion, Instant updatedAt, int channels) {
    }

    private List<GroupRow> groupRows(JdbcClient.StatementSpec spec) {
        return spec.query((rs, n) -> new GroupRow(rs.getObject("id", UUID.class), rs.getString("module_code"),
                rs.getObject("organization_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), rs.getString("evaluation_policy"),
                rs.getString("match_on"), rs.getString("on_error"), rs.getString("composite_true_action"),
                rs.getString("composite_false_action"), rs.getObject("composite_true_bundle_id", UUID.class),
                rs.getObject("composite_false_bundle_id", UUID.class), rs.getLong("row_version"),
                rs.getTimestamp("updated_at").toInstant(), rs.getInt("channels"))).list();
    }

    private List<GroupView> enrichGroups(List<GroupRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(GroupRow::id).toList();
        List<UUID> bundles = new ArrayList<>();
        rows.forEach(r -> {
            if (r.trueBundle() != null) {
                bundles.add(r.trueBundle());
            }
            if (r.falseBundle() != null) {
                bundles.add(r.falseBundle());
            }
        });
        Map<UUID, Map<String, String>> messages = messages(bundles);
        Map<UUID, List<GroupRuleView>> members = new HashMap<>();
        jdbc.sql("""
                SELECT gr.group_id, r.code, r.name, r.status, gr.sequence, gr.enabled
                FROM dai_re_rule_group_rule gr JOIN dai_re_rule r ON r.id = gr.rule_id
                WHERE gr.group_id IN (:ids) ORDER BY gr.group_id, gr.sequence""").param("ids", ids)
                .query((rs, n) -> {
                    members.computeIfAbsent(rs.getObject("group_id", UUID.class), k -> new ArrayList<>())
                            .add(new GroupRuleView(rs.getString("code"), rs.getString("name"), rs.getString("status"),
                                    rs.getInt("sequence"), rs.getBoolean("enabled")));
                    return 0;
                }).list();
        Map<UUID, List<TriggerView>> triggers = new HashMap<>();
        jdbc.sql(TRIGGER_SQL + " WHERE tp.rule_group_id IN (:ids) ORDER BY tp.application, tp.form_code, tp.sequence")
                .param("ids", ids).query((rs, n) -> {
                    TriggerView t = trigger(rs);
                    triggers.computeIfAbsent(rs.getObject("rule_group_id", UUID.class), k -> new ArrayList<>()).add(t);
                    return 0;
                }).list();
        List<GroupView> out = new ArrayList<>();
        for (GroupRow r : rows) {
            out.add(new GroupView(r.id(), r.moduleCode(), r.code(), r.name(), r.description(), r.status(),
                    scopeLabel(r.organizationId()), r.policy(), r.matchOn(), r.onError(), r.trueAction(),
                    r.falseAction(), messages.getOrDefault(r.trueBundle(), Map.of()),
                    messages.getOrDefault(r.falseBundle(), Map.of()), members.getOrDefault(r.id(), List.of()),
                    triggers.getOrDefault(r.id(), List.of()), r.channels(), r.rowVersion(), r.updatedAt()));
        }
        return out;
    }

    private static final String TRIGGER_SQL = """
            SELECT tp.id, tp.rule_group_id, tp.application, tp.trigger_type, tp.form_code, tp.action_code, tp.field_code,
                   m.code AS module_code, g.code AS group_code, tp.sequence, tp.enabled, tp.organization_id
            FROM dai_re_trigger_point tp
                     JOIN dai_re_module m ON m.id = tp.module_id
                     JOIN dai_re_rule_group g ON g.id = tp.rule_group_id
            """;

    private static TriggerView trigger(ResultSet rs) throws SQLException {
        return new TriggerView(rs.getObject("id", UUID.class), rs.getString("application"),
                rs.getString("trigger_type"), rs.getString("form_code"), rs.getString("action_code"),
                rs.getString("field_code"), rs.getString("module_code"), rs.getString("group_code"),
                rs.getInt("sequence"), rs.getBoolean("enabled"),
                scopeLabel(rs.getObject("organization_id", UUID.class)));
    }

    List<TriggerView> triggers(Caller c) {
        return jdbc.sql(TRIGGER_SQL + " WHERE tp.tenant_id = :t AND " + visible("tp.organization_id")
                + " ORDER BY tp.application, tp.form_code, tp.action_code, tp.sequence").params(scope(c))
                .query((rs, n) -> trigger(rs)).list();
    }

    List<ChannelView> channels(Caller c) {
        return jdbc.sql("""
                SELECT ch.id, ch.owner_type, COALESCE(r.code, g.code) AS owner_code, ch.on_result, ch.channel_type,
                       ch.sequence, ch.enabled, et.name || ' (' || et.template_ref || ')' AS email_template,
                       ep.name AS api_endpoint, ch.recipient_expression IS NOT NULL AS has_recipient
                FROM dai_re_outcome_channel ch
                         LEFT JOIN dai_re_rule r ON ch.owner_type = 'RULE' AND r.id = ch.owner_id
                         LEFT JOIN dai_re_rule_group g ON ch.owner_type = 'GROUP' AND g.id = ch.owner_id
                         LEFT JOIN dai_re_email_template et ON et.id = ch.email_template_id
                         LEFT JOIN dai_re_api_endpoint ep ON ep.id = ch.api_endpoint_id
                WHERE ch.tenant_id = :t AND %s
                ORDER BY COALESCE(r.code, g.code), ch.sequence
                """.formatted(visible("COALESCE(r.organization_id, g.organization_id)"))).params(scope(c))
                .query((rs, n) -> new ChannelView(rs.getObject("id", UUID.class), rs.getString("owner_type"),
                        rs.getString("owner_code"), rs.getString("on_result"), rs.getString("channel_type"),
                        rs.getInt("sequence"), rs.getBoolean("enabled"), rs.getString("email_template"),
                        rs.getString("api_endpoint"), rs.getBoolean("has_recipient"))).list();
    }

    List<EmailTemplateView> emailTemplates(Caller c) {
        return jdbc.sql("SELECT id, template_ref, name, active FROM dai_re_email_template WHERE tenant_id = :t "
                + "ORDER BY name").param("t", c.tenantId())
                .query((rs, n) -> new EmailTemplateView(rs.getObject("id", UUID.class), rs.getString("template_ref"),
                        rs.getString("name"), rs.getBoolean("active"))).list();
    }

    List<ApiEndpointView> apiEndpoints(Caller c) {
        return jdbc.sql("SELECT id, name, http_method, url, environment, timeout_ms, "
                + "external_confirmed_at IS NOT NULL AS confirmed, active FROM dai_re_api_endpoint "
                + "WHERE tenant_id = :t ORDER BY name").param("t", c.tenantId())
                .query((rs, n) -> new ApiEndpointView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("http_method"), rs.getString("url"), rs.getString("environment"),
                        rs.getInt("timeout_ms"), rs.getBoolean("confirmed"), rs.getBoolean("active"))).list();
    }

    SetupSummary summary(Caller c) {
        Map<String, Object> p = scope(c);
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM dai_re_module WHERE active) AS modules,
                       (SELECT count(*) FROM dai_re_sys_object WHERE active) AS objects,
                       (SELECT count(*) FROM dai_re_parameter_v) AS parameters,
                       (SELECT count(*) FROM dai_re_rule r WHERE r.tenant_id = :t AND %1$s) AS rules,
                       (SELECT count(*) FROM dai_re_rule r WHERE r.tenant_id = :t AND r.status = 'ACTIVE' AND %1$s) AS active_rules,
                       (SELECT count(*) FROM dai_re_rule_group g WHERE g.tenant_id = :t AND %2$s) AS groups,
                       (SELECT count(*) FROM dai_re_rule_group g WHERE g.tenant_id = :t AND g.status = 'ACTIVE' AND %2$s) AS active_groups,
                       (SELECT count(*) FROM dai_re_trigger_point tp WHERE tp.tenant_id = :t AND %3$s) AS triggers,
                       (SELECT count(*) FROM dai_re_outcome_channel WHERE tenant_id = :t) AS channels,
                       (SELECT count(*) FROM dai_re_email_template WHERE tenant_id = :t) AS templates,
                       (SELECT count(*) FROM dai_re_api_endpoint WHERE tenant_id = :t) AS endpoints,
                       (SELECT count(*) FROM dai_re_sys_bundle WHERE tenant_id IS NULL OR tenant_id = :t) AS messages
                """.formatted(visible("r.organization_id"), visible("g.organization_id"),
                visible("tp.organization_id"))).params(p).query((rs, n) -> new SetupSummary(c.tenantName(),
                c.organizationName(), c.role(), rs.getInt("modules"), rs.getInt("objects"), rs.getInt("parameters"),
                rs.getInt("rules"), rs.getInt("active_rules"), rs.getInt("groups"), rs.getInt("active_groups"),
                rs.getInt("triggers"), rs.getInt("channels"), rs.getInt("templates"), rs.getInt("endpoints"),
                rs.getInt("messages"), List.of("FIRST_MATCH", "ALL_MATCH", "EVALUATE_ALL", "COMPOSITE"),
                List.of("ALLOW", "WARN", "BLOCK"), languages(c))).single();
    }

    private List<String> languages(Caller c) {
        return jdbc.sql("SELECT DISTINCT language FROM dai_re_sys_bundle_message m JOIN dai_re_sys_bundle b "
                + "ON b.id = m.bundle_id WHERE b.tenant_id IS NULL OR b.tenant_id = :t ORDER BY language")
                .param("t", c.tenantId()).query(String.class).list();
    }

    /** Language → text of each bundle. */
    Map<UUID, Map<String, String>> messages(Collection<UUID> bundleIds) {
        Map<UUID, Map<String, String>> out = new HashMap<>();
        if (bundleIds.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT bundle_id, language, message_text FROM dai_re_sys_bundle_message WHERE bundle_id IN (:ids) "
                + "ORDER BY language").param("ids", bundleIds).query((rs, n) -> {
            out.computeIfAbsent(rs.getObject("bundle_id", UUID.class), k -> new LinkedHashMap<>())
                    .put(rs.getString("language"), rs.getString("message_text"));
            return 0;
        }).list();
        return out;
    }

    private Map<UUID, List<String>> pairs(String sql, Collection<UUID> ids) {
        Map<UUID, List<String>> out = new HashMap<>();
        jdbc.sql(sql).param("ids", ids).query((rs, n) -> {
            out.computeIfAbsent(rs.getObject("k", UUID.class), k -> new ArrayList<>()).add(rs.getString("v"));
            return 0;
        }).list();
        return out;
    }
}
