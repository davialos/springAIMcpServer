package com.springaimcpservercommon.ruleengine.store;

import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.ApiEndpoint;
import com.springaimcpservercommon.ruleengine.model.ApiEnvironment;
import com.springaimcpservercommon.ruleengine.model.ChannelBinding;
import com.springaimcpservercommon.ruleengine.model.ChannelTrigger;
import com.springaimcpservercommon.ruleengine.model.ChannelType;
import com.springaimcpservercommon.ruleengine.model.DataType;
import com.springaimcpservercommon.ruleengine.model.EmailTemplate;
import com.springaimcpservercommon.ruleengine.model.EvaluationPolicy;
import com.springaimcpservercommon.ruleengine.model.GroupRule;
import com.springaimcpservercommon.ruleengine.model.MatchOn;
import com.springaimcpservercommon.ruleengine.model.OwnerType;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import com.springaimcpservercommon.ruleengine.model.Rule;
import com.springaimcpservercommon.ruleengine.model.RuleGroup;
import com.springaimcpservercommon.ruleengine.model.TriggerPoint;
import com.springaimcpservercommon.ruleengine.model.TriggerType;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Plain-JDBC {@link RuleStore} over the {@code dai_re_*} tables of the {@code dynamic_ai} schema (migration V11).
 *
 * <p>Every load runs in one read-only REPEATABLE READ transaction that reads the change marker first, so the data is
 * a consistent snapshot that is at least as new as the version it is labelled with. The connection's schema is set
 * per use ({@link Connection#setSchema(String)}), so the data source needs no search_path of its own.
 */
public final class JdbcRuleStore implements RuleStore {

    private final DataSource dataSource;
    private final String schema;

    /**
     * Creates the store.
     *
     * @param dataSource runtime data source (not closed by the store)
     * @param schema     schema holding the tables, normally {@code dynamic_ai}
     */
    public JdbcRuleStore(DataSource dataSource, String schema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    @Override
    public long changeMarker(Scope scope) {
        return inReadTransaction(c -> marker(c, scope));
    }

    @Override
    public Versioned<List<Parameter>> loadParameters() {
        return inReadTransaction(c -> {
            long version = marker(c, Scope.PARAMETERS);
            List<Parameter> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT attribute_id, object_code, attribute_code, data_type, required FROM dai_re_parameter_v ORDER BY cel_name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Parameter(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                            DataType.valueOf(rs.getString(4)), rs.getBoolean(5)));
                }
            }
            return new Versioned<>(version, out);
        });
    }

    @Override
    public Versioned<Map<UUID, Map<String, String>>> loadMessages() {
        return inReadTransaction(c -> {
            long version = marker(c, Scope.BUNDLES);
            Map<UUID, Map<String, String>> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT bundle_id, language, message_text FROM dai_re_sys_bundle_message");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashMap<>())
                            .put(rs.getString(2), rs.getString(3));
                }
            }
            return new Versioned<>(version, out);
        });
    }

    @Override
    public Versioned<TenantData> loadTenant(UUID tenantId) {
        return inReadTransaction(c -> {
            long version = marker(c, Scope.RULES);
            Map<UUID, List<GroupRule>> membership = loadMembership(c, tenantId);
            List<RuleGroup> groups = loadGroups(c, tenantId, membership);
            List<TriggerPoint> triggers = loadTriggers(c, tenantId);
            List<ChannelBinding> channels = loadChannels(c, tenantId);
            Map<UUID, EmailTemplate> templates = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, template_ref, name FROM dai_re_email_template WHERE tenant_id = ? AND active")) {
                ps.setObject(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        templates.put(rs.getObject(1, UUID.class), new EmailTemplate(rs.getString(2), rs.getString(3)));
                    }
                }
            }
            Map<UUID, ApiEndpoint> endpoints = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, http_method, url, environment, timeout_ms, auth_secret_ref, external_confirmed_by,"
                            + " external_confirmed_at FROM dai_re_api_endpoint WHERE tenant_id = ? AND active")) {
                ps.setObject(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        java.sql.Timestamp confirmedAt = rs.getTimestamp(9);
                        endpoints.put(rs.getObject(1, UUID.class), new ApiEndpoint(rs.getObject(1, UUID.class),
                                rs.getString(2), rs.getString(3), rs.getString(4), ApiEnvironment.valueOf(rs.getString(5)),
                                rs.getInt(6), rs.getString(7), rs.getString(8),
                                confirmedAt == null ? null : confirmedAt.toInstant()));
                    }
                }
            }
            return new Versioned<>(version, new TenantData(tenantId, groups, triggers, channels, templates, endpoints));
        });
    }

    private static Map<UUID, List<GroupRule>> loadMembership(Connection c, UUID tenantId) throws SQLException {
        Map<UUID, List<GroupRule>> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT gr.group_id, gr.sequence, r.id, r.code, r.name, r.cel_expression, r.true_message_bundle_id,"
                        + " r.false_message_bundle_id, r.true_action, r.false_action"
                        + " FROM dai_re_rule_group_rule gr"
                        + " JOIN dai_re_rule r ON r.id = gr.rule_id AND r.status = 'ACTIVE'"
                        + " JOIN dai_re_rule_group g ON g.id = gr.group_id AND g.status = 'ACTIVE'"
                        + " WHERE g.tenant_id = ? AND r.tenant_id = ? AND gr.enabled ORDER BY gr.group_id, gr.sequence")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Rule rule = new Rule(rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getObject(7, UUID.class), rs.getObject(8, UUID.class),
                            Action.valueOf(rs.getString(9)), Action.valueOf(rs.getString(10)));
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new GroupRule(rule, rs.getInt(2)));
                }
            }
        }
        return out;
    }

    private static List<RuleGroup> loadGroups(Connection c, UUID tenantId, Map<UUID, List<GroupRule>> membership)
            throws SQLException {
        List<RuleGroup> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT g.id, g.organization_id, m.code, g.code, g.name, g.evaluation_policy, g.match_on,"
                        + " g.composite_true_bundle_id, g.composite_false_bundle_id, g.composite_true_action,"
                        + " g.composite_false_action, g.on_error"
                        + " FROM dai_re_rule_group g JOIN dai_re_module m ON m.id = g.module_id"
                        + " WHERE g.tenant_id = ? AND g.status = 'ACTIVE' AND m.active ORDER BY g.code")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    out.add(new RuleGroup(id, rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                            rs.getString(5), EvaluationPolicy.valueOf(rs.getString(6)), MatchOn.valueOf(rs.getString(7)),
                            rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
                            Action.valueOf(rs.getString(10)), Action.valueOf(rs.getString(11)),
                            Action.valueOf(rs.getString(12)), membership.getOrDefault(id, List.of())));
                }
            }
        }
        return out;
    }

    private static List<TriggerPoint> loadTriggers(Connection c, UUID tenantId) throws SQLException {
        List<TriggerPoint> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT t.id, t.organization_id, t.application, t.trigger_type, t.form_code, t.action_code, t.field_code,"
                        + " t.rule_group_id, t.sequence FROM dai_re_trigger_point t"
                        + " WHERE t.tenant_id = ? AND t.enabled ORDER BY t.sequence, t.id")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TriggerPoint(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            TriggerType.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getObject(8, UUID.class), rs.getInt(9)));
                }
            }
        }
        return out;
    }

    private static List<ChannelBinding> loadChannels(Connection c, UUID tenantId) throws SQLException {
        List<ChannelBinding> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, owner_type, owner_id, on_result, channel_type, sequence, email_template_id, api_endpoint_id,"
                        + " push_title_bundle_id, push_body_bundle_id, recipient_expression FROM dai_re_outcome_channel"
                        + " WHERE tenant_id = ? AND enabled ORDER BY owner_id, sequence, id")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ChannelBinding(rs.getObject(1, UUID.class), OwnerType.valueOf(rs.getString(2)),
                            rs.getObject(3, UUID.class), ChannelTrigger.valueOf(rs.getString(4)),
                            ChannelType.valueOf(rs.getString(5)), rs.getInt(6), rs.getObject(7, UUID.class),
                            rs.getObject(8, UUID.class), rs.getObject(9, UUID.class), rs.getObject(10, UUID.class),
                            rs.getString(11)));
                }
            }
        }
        return out;
    }

    private static long marker(Connection c, Scope scope) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT version FROM dai_re_change_marker WHERE scope = ?")) {
            ps.setString(1, scope.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("dai_re_change_marker has no row for scope " + scope);
                }
                return rs.getLong(1);
            }
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T inReadTransaction(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setSchema(schema);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setReadOnly(false);
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuleStoreException("rule store read failed: " + e.getMessage(), e);
        }
    }

    /** Raised when the database cannot be read; the cache keeps serving its last snapshot. */
    public static final class RuleStoreException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message message
         * @param cause   cause
         */
        public RuleStoreException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
