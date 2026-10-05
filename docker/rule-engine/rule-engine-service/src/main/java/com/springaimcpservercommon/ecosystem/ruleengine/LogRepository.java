package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.AuditEntry;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.Bucket;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.ChatMessage;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.ConversationDetail;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.ConversationLog;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.EvaluationDetail;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.EvaluationLog;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.GroupLoad;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.LogSummary;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.Page;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.RuleOutcome;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The operational logs an administrator reads: evaluations (the library's value-free log), the authoring audit trail, and
 * the AI chat conversations of the tenant. Everything is tenant-scoped, and an organization administrator sees tenant-wide
 * entries plus their own organization's. Filters are bound parameters or fixed SQL fragments, never concatenated values.
 * The logs hold no evaluation input values; chat messages are shown as stored (the library redacts before storing).
 */
@Repository
class LogRepository {

    private static final int MAX_PAGE_SIZE = 200;

    private final JdbcClient jdbc;

    LogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Optional filters of the evaluation log. */
    record EvaluationFilter(@Nullable String decision, @Nullable String module, @Nullable String group,
                            boolean errorsOnly, @Nullable Instant from, @Nullable Instant to) {
    }

    Page<EvaluationLog> evaluations(Caller c, EvaluationFilter f, int page, int size) {
        Map<String, Object> p = CatalogRepository.scope(c);
        p.put("decision", f.decision());
        p.put("module", f.module());
        p.put("group", f.group());
        p.put("errors", f.errorsOnly());
        p.put("from", ts(f.from()));
        p.put("to", ts(f.to()));
        String where = """
                WHERE e.tenant_id = :t AND %s
                  AND (CAST(:decision AS text) IS NULL OR e.decision = CAST(:decision AS text))
                  AND (CAST(:module AS text) IS NULL OR m.code = CAST(:module AS text))
                  AND (CAST(:group AS text) IS NULL OR g.code = CAST(:group AS text))
                  AND (CAST(:from AS timestamptz) IS NULL OR e.evaluated_at >= CAST(:from AS timestamptz))
                  AND (CAST(:to AS timestamptz) IS NULL OR e.evaluated_at < CAST(:to AS timestamptz))
                  AND (NOT CAST(:errors AS boolean) OR c.x > 0)
                """.formatted(CatalogRepository.visible("e.organization_id"));
        String from = """
                FROM dai_re_evaluation e
                         LEFT JOIN dai_re_rule_group g ON g.id = e.rule_group_id
                         LEFT JOIN dai_re_module m ON m.id = g.module_id
                         LEFT JOIN LATERAL (SELECT count(*) FILTER (WHERE r.outcome = 'TRUE') AS t,
                                                   count(*) FILTER (WHERE r.outcome = 'FALSE') AS f,
                                                   count(*) FILTER (WHERE r.outcome = 'ERROR') AS x
                                            FROM dai_re_evaluation_result r WHERE r.evaluation_id = e.id) c ON true
                """;
        long total = jdbc.sql("SELECT count(*) " + from + where).params(p).query(Long.class).single();
        int s = size(size);
        p.put("limit", s);
        p.put("offset", (long) Math.max(0, page) * s);
        List<EvaluationLog> items = jdbc.sql("""
                SELECT e.id, e.evaluated_at, m.code AS module_code, g.code AS group_code, g.name AS group_name,
                       e.policy, e.decision, e.language, e.duration_micros, c.t, c.f, c.x,
                       e.trigger_point_id IS NOT NULL AS via_trigger, e.organization_id
                """ + from + where + " ORDER BY e.evaluated_at DESC, e.id LIMIT :limit OFFSET :offset")
                .params(p).query((rs, n) -> evaluation(rs)).list();
        return new Page<>(items, total, Math.max(0, page), s);
    }

    private static EvaluationLog evaluation(ResultSet rs) throws SQLException {
        return new EvaluationLog(rs.getObject("id", UUID.class), rs.getTimestamp("evaluated_at").toInstant(),
                rs.getString("module_code"), rs.getString("group_code"), rs.getString("group_name"),
                rs.getString("policy"), rs.getString("decision"), rs.getString("language"),
                rs.getLong("duration_micros"), rs.getInt("t"), rs.getInt("f"), rs.getInt("x"),
                rs.getBoolean("via_trigger"), rs.getObject("organization_id", UUID.class));
    }

    EvaluationDetail evaluation(Caller c, UUID id) {
        Map<String, Object> p = CatalogRepository.scope(c);
        p.put("id", id);
        List<EvaluationLog> found = jdbc.sql("""
                SELECT e.id, e.evaluated_at, m.code AS module_code, g.code AS group_code, g.name AS group_name,
                       e.policy, e.decision, e.language, e.duration_micros, c.t, c.f, c.x,
                       e.trigger_point_id IS NOT NULL AS via_trigger, e.organization_id
                FROM dai_re_evaluation e
                         LEFT JOIN dai_re_rule_group g ON g.id = e.rule_group_id
                         LEFT JOIN dai_re_module m ON m.id = g.module_id
                         LEFT JOIN LATERAL (SELECT count(*) FILTER (WHERE r.outcome = 'TRUE') AS t,
                                                   count(*) FILTER (WHERE r.outcome = 'FALSE') AS f,
                                                   count(*) FILTER (WHERE r.outcome = 'ERROR') AS x
                                            FROM dai_re_evaluation_result r WHERE r.evaluation_id = e.id) c ON true
                WHERE e.tenant_id = :t AND e.id = :id AND %s
                """.formatted(CatalogRepository.visible("e.organization_id"))).params(p)
                .query((rs, n) -> evaluation(rs)).list();
        if (found.isEmpty()) {
            throw ApiProblem.notFound("evaluation");
        }
        List<RuleOutcome> results = jdbc.sql("""
                SELECT COALESCE(r.code, '(deleted rule)') AS code, COALESCE(r.name, '') AS name, x.sequence, x.outcome,
                       x.action, x.error_code
                FROM dai_re_evaluation_result x LEFT JOIN dai_re_rule r ON r.id = x.rule_id
                WHERE x.evaluation_id = :id ORDER BY x.sequence""").param("id", id)
                .query((rs, n) -> new RuleOutcome(rs.getString("code"), rs.getString("name"), rs.getInt("sequence"),
                        rs.getString("outcome"), rs.getString("action"), rs.getString("error_code"), null)).list();
        return new EvaluationDetail(found.getFirst(), results);
    }

    /** Optional filters of the audit trail. */
    record AuditFilter(@Nullable String action, @Nullable String entityType, @Nullable String actor,
                       @Nullable Instant from, @Nullable Instant to) {
    }

    Page<AuditEntry> audit(Caller c, AuditFilter f, int page, int size) {
        Map<String, Object> p = CatalogRepository.scope(c);
        p.put("action", f.action());
        p.put("type", f.entityType());
        p.put("actor", f.actor());
        p.put("from", ts(f.from()));
        p.put("to", ts(f.to()));
        String where = """
                WHERE a.tenant_id = :t AND %s
                  AND (CAST(:action AS text) IS NULL OR a.action = CAST(:action AS text))
                  AND (CAST(:type AS text) IS NULL OR a.entity_type = CAST(:type AS text))
                  AND (CAST(:actor AS text) IS NULL OR a.actor_name ILIKE '%%' || CAST(:actor AS text) || '%%')
                  AND (CAST(:from AS timestamptz) IS NULL OR a.occurred_at >= CAST(:from AS timestamptz))
                  AND (CAST(:to AS timestamptz) IS NULL OR a.occurred_at < CAST(:to AS timestamptz))
                """.formatted(CatalogRepository.visible("a.organization_id"));
        long total = jdbc.sql("SELECT count(*) FROM dai_re_audit_log a\n" + where).params(p).query(Long.class).single();
        int s = size(size);
        p.put("limit", s);
        p.put("offset", (long) Math.max(0, page) * s);
        List<AuditEntry> items = jdbc.sql("""
                SELECT a.id, a.occurred_at, a.actor_name, a.actor_role, a.action, a.entity_type, a.entity_id,
                       a.entity_code, a.summary, a.details::text AS details, a.organization_id
                FROM dai_re_audit_log a
                """ + where + " ORDER BY a.occurred_at DESC, a.id LIMIT :limit OFFSET :offset")
                .params(p).query((rs, n) -> new AuditEntry(rs.getObject("id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor_name"),
                        rs.getString("actor_role"), rs.getString("action"), rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class), rs.getString("entity_code"), rs.getString("summary"),
                        rs.getString("details"), rs.getObject("organization_id", UUID.class))).list();
        return new Page<>(items, total, Math.max(0, page), s);
    }

    // ── chat conversations (library table dai_conversation, scoped to the tenant through its workspace) ──

    Page<ConversationLog> conversations(Caller c, int page, int size) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", c.tenantId().toString());
        long total = jdbc.sql("""
                SELECT count(*) FROM dai_conversation v JOIN dai_workspace w ON w.id = v.workspace_id
                WHERE w.tenant_id = :t""").params(p).query(Long.class).single();
        int s = size(size);
        p.put("limit", s);
        p.put("offset", (long) Math.max(0, page) * s);
        List<ConversationLog> items = jdbc.sql("""
                SELECT v.id, v.title, v.channel, v.status, v.started_at, v.last_activity_at,
                       (SELECT count(*) FROM dai_conversation_message m WHERE m.conversation_id = v.id) AS messages
                FROM dai_conversation v JOIN dai_workspace w ON w.id = v.workspace_id
                WHERE w.tenant_id = :t ORDER BY v.last_activity_at DESC, v.id LIMIT :limit OFFSET :offset""")
                .params(p).query((rs, n) -> conversation(rs)).list();
        return new Page<>(items, total, Math.max(0, page), s);
    }

    private static ConversationLog conversation(ResultSet rs) throws SQLException {
        return new ConversationLog(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("channel"),
                rs.getString("status"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("last_activity_at").toInstant(), rs.getLong("messages"));
    }

    ConversationDetail conversation(Caller c, UUID id) {
        List<ConversationLog> found = jdbc.sql("""
                SELECT v.id, v.title, v.channel, v.status, v.started_at, v.last_activity_at,
                       (SELECT count(*) FROM dai_conversation_message m WHERE m.conversation_id = v.id) AS messages
                FROM dai_conversation v JOIN dai_workspace w ON w.id = v.workspace_id
                WHERE w.tenant_id = :t AND v.id = :id""").param("t", c.tenantId().toString()).param("id", id)
                .query((rs, n) -> conversation(rs)).list();
        if (found.isEmpty()) {
            throw ApiProblem.notFound("conversation");
        }
        List<ChatMessage> messages = jdbc.sql("""
                SELECT seq, role, content, redacted, token_count, created_at FROM dai_conversation_message
                WHERE conversation_id = :id ORDER BY seq""").param("id", id)
                .query((rs, n) -> new ChatMessage(rs.getInt("seq"), rs.getString("role"), rs.getString("content"),
                        rs.getBoolean("redacted"), (Integer) rs.getObject("token_count"),
                        rs.getTimestamp("created_at").toInstant())).list();
        return new ConversationDetail(found.getFirst(), messages);
    }

    // ── dashboard numbers ──────────────────────────────────────────────────────────────────────────────

    LogSummary summary(Caller c, int hours) {
        Map<String, Object> p = CatalogRepository.scope(c);
        p.put("since", Timestamp.from(Instant.now().minusSeconds(3600L * hours)));
        String vis = CatalogRepository.visible("e.organization_id");
        Map<String, Object> totals = jdbc.sql("""
                SELECT count(*) AS n, count(*) FILTER (WHERE decision = 'ALLOW') AS allow,
                       count(*) FILTER (WHERE decision = 'WARN') AS warn, count(*) FILTER (WHERE decision = 'BLOCK') AS block,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM dai_re_evaluation_result r
                                                      WHERE r.evaluation_id = e.id AND r.outcome = 'ERROR')) AS errors,
                       coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_micros), 0) AS p50,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_micros), 0) AS p95
                FROM dai_re_evaluation e WHERE e.tenant_id = :t AND e.evaluated_at >= :since AND %s""".formatted(vis))
                .params(p).query((rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("n", rs.getLong("n"));
                    m.put("allow", rs.getLong("allow"));
                    m.put("warn", rs.getLong("warn"));
                    m.put("block", rs.getLong("block"));
                    m.put("errors", rs.getLong("errors"));
                    m.put("p50", rs.getDouble("p50"));
                    m.put("p95", rs.getDouble("p95"));
                    return m;
                }).single();
        // hourly buckets for windows up to two days, otherwise daily
        String unit = hours <= 48 ? "hour" : "day";
        List<Bucket> series = jdbc.sql("""
                SELECT date_trunc('%s', e.evaluated_at) AS bucket, count(*) AS n,
                       count(*) FILTER (WHERE decision = 'ALLOW') AS allow, count(*) FILTER (WHERE decision = 'WARN') AS warn,
                       count(*) FILTER (WHERE decision = 'BLOCK') AS block
                FROM dai_re_evaluation e WHERE e.tenant_id = :t AND e.evaluated_at >= :since AND %s
                GROUP BY 1 ORDER BY 1""".formatted(unit, vis)).params(p)
                .query((rs, n) -> new Bucket(rs.getTimestamp("bucket").toInstant(), rs.getLong("n"),
                        rs.getLong("allow"), rs.getLong("warn"), rs.getLong("block"))).list();
        List<GroupLoad> top = jdbc.sql("""
                SELECT m.code AS module_code, coalesce(g.code, '(deleted)') AS group_code, count(*) AS n,
                       count(*) FILTER (WHERE e.decision = 'BLOCK') AS blocked,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM dai_re_evaluation_result r
                                                      WHERE r.evaluation_id = e.id AND r.outcome = 'ERROR')) AS errors,
                       avg(e.duration_micros) / 1000.0 AS avg_ms
                FROM dai_re_evaluation e LEFT JOIN dai_re_rule_group g ON g.id = e.rule_group_id
                         LEFT JOIN dai_re_module m ON m.id = g.module_id
                WHERE e.tenant_id = :t AND e.evaluated_at >= :since AND %s
                GROUP BY m.code, g.code ORDER BY n DESC LIMIT 5""".formatted(vis)).params(p)
                .query((rs, n) -> new GroupLoad(rs.getString("module_code"), rs.getString("group_code"),
                        rs.getLong("n"), rs.getLong("blocked"), rs.getLong("errors"), rs.getDouble("avg_ms"))).list();
        Map<String, Long> byAction = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT a.action, count(*) AS n FROM dai_re_audit_log a
                WHERE a.tenant_id = :t AND a.occurred_at >= :since AND %s GROUP BY a.action ORDER BY n DESC"""
                .formatted(CatalogRepository.visible("a.organization_id"))).params(p).query((rs, n) -> {
            byAction.put(rs.getString("action"), rs.getLong("n"));
            return 0;
        }).list();
        long authoring = byAction.entrySet().stream().filter(e -> !e.getKey().equals("RULE_GROUP_EVALUATED"))
                .mapToLong(Map.Entry::getValue).sum();
        long audit = byAction.values().stream().mapToLong(Long::longValue).sum();
        long conversations = jdbc.sql("""
                SELECT count(*) FROM dai_conversation v JOIN dai_workspace w ON w.id = v.workspace_id
                WHERE w.tenant_id = :t AND v.last_activity_at >= :since""")
                .param("t", c.tenantId().toString()).param("since", p.get("since")).query(Long.class).single();
        return new LogSummary(hours, (Long) totals.get("n"), (Long) totals.get("allow"), (Long) totals.get("warn"),
                (Long) totals.get("block"), (Long) totals.get("errors"), (Double) totals.get("p50") / 1000.0,
                (Double) totals.get("p95") / 1000.0, audit, authoring, conversations, series, top, byAction);
    }

    private static int size(int requested) {
        return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
    }

    private static @Nullable Timestamp ts(@Nullable Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
