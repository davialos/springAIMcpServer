package com.springaimcpservercommon.ruleengine.admin;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.ruleengine.admin.AdminException.Conflict;
import com.springaimcpservercommon.ruleengine.admin.AdminException.Invalid;
import com.springaimcpservercommon.ruleengine.admin.AdminException.NotFound;
import com.springaimcpservercommon.ruleengine.admin.Revision.Kind;
import com.springaimcpservercommon.ruleengine.admin.Summaries.GroupSummary;
import com.springaimcpservercommon.ruleengine.admin.Summaries.RuleSummary;
import com.springaimcpservercommon.ruleengine.cel.CompiledExpression;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.cel.RuleCompilationException;
import com.springaimcpservercommon.ruleengine.model.EvaluationPolicy;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import com.springaimcpservercommon.ruleengine.store.JdbcRunner;
import com.springaimcpservercommon.ruleengine.store.JdbcRunner.StoreFailure;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.springaimcpservercommon.ruleengine.store.JdbcRunner.query;
import static com.springaimcpservercommon.ruleengine.store.JdbcRunner.queryOne;
import static com.springaimcpservercommon.ruleengine.store.JdbcRunner.update;

/**
 * Lifecycle of rules and rule groups (OQ-66): every change is a <b>revision</b> that moves
 * <pre>DRAFT → SUBMITTED → APPROVED → PUBLISHED</pre>
 * (or SUBMITTED → REJECTED → edited back to DRAFT). Only publishing touches the live {@code dai_re_rule} /
 * {@code dai_re_rule_group} rows, in one transaction, so the engine never sees a half-applied or unreviewed change.
 *
 * <ul>
 *   <li><b>Four eyes:</b> whoever submitted a revision cannot approve or reject it (also enforced by a database CHECK).
 *       With {@code requireReview=false} an author may publish a DRAFT directly.</li>
 *   <li><b>Validation at every step:</b> an expression must compile against the parameter library when it is saved,
 *       again when submitted and again when published (the library may have changed meanwhile); a group's members must
 *       exist in the tenant, belong to the group's module, and be published to go live.</li>
 *   <li><b>History and rollback:</b> published revisions stay (SUPERSEDED when replaced); {@link #rollback} copies an
 *       old revision into a new DRAFT that goes through the same lifecycle.</li>
 *   <li><b>Impact checks:</b> a rule used by an active group cannot be retired.</li>
 *   <li><b>Tenant isolation:</b> every statement is scoped by tenant; another tenant's id is a "not found".</li>
 * </ul>
 * Thread-safe; state lives in PostgreSQL, subjects are row-locked while they change.
 */
public final class RuleLifecycle {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OPEN = "('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED')";
    private static final String REV_COLS = "id, kind, subject_id, revision_no, state, content::text, change_note,"
            + " rollback_of, created_by, created_at, submitted_by, reviewed_by, review_comment, published_by, published_at";

    private final JdbcRunner jdbc;
    private final Supplier<ParameterLibrary> library;
    private final boolean requireReview;

    /**
     * Creates the service.
     *
     * @param dataSource    data source (not closed)
     * @param schema        schema holding the tables
     * @param library       current parameter library (for expression validation)
     * @param requireReview {@code true}: a revision must be approved by a second person before it can be published
     */
    public RuleLifecycle(DataSource dataSource, String schema, Supplier<ParameterLibrary> library,
                         boolean requireReview) {
        this.jdbc = new JdbcRunner(dataSource, schema);
        this.library = Objects.requireNonNull(library, "library");
        this.requireReview = requireReview;
    }

    // ------------------------------------------------------------------------------------------------ create

    /**
     * Creates a rule as a DRAFT with revision 1. It is invisible to the engine until published.
     *
     * @param tenantId       tenant
     * @param organizationId organization, or {@code null} = whole tenant
     * @param moduleCode     module (must exist and be active)
     * @param code           rule code, unique per tenant, organization and module
     * @param content        the first draft
     * @param actor          who is creating it
     * @return revision 1
     */
    public Revision createRule(UUID tenantId, @Nullable UUID organizationId, String moduleCode, String code,
                               RuleContent content, String actor) {
        requireCode(code);
        return jdbc.write(c -> {
            UUID moduleId = moduleId(c, moduleCode);
            checkRule(c, tenantId, content, false);
            UUID id = Ids.newId();
            try {
                update(c, "INSERT INTO dai_re_rule (id, tenant_id, organization_id, module_id, code, name, description,"
                                + " cel_expression, status, true_message_bundle_id, false_message_bundle_id, true_action,"
                                + " false_action) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)",
                        id, tenantId, organizationId, moduleId, code, content.name(), content.description(),
                        content.expression(), content.trueMessageBundleId(), content.falseMessageBundleId(),
                        content.trueAction().name(), content.falseAction().name());
            } catch (SQLException e) {
                throw duplicate(e, "rule " + code + " already exists in this module");
            }
            return insertRevision(c, tenantId, Kind.RULE, id, 1, content, "initial draft", null, actor);
        });
    }

    /**
     * Creates a rule group as a DRAFT with revision 1.
     *
     * @param tenantId       tenant
     * @param organizationId organization, or {@code null} = whole tenant
     * @param moduleCode     module
     * @param code           group code
     * @param content        the first draft
     * @param actor          who is creating it
     * @return revision 1
     */
    public Revision createGroup(UUID tenantId, @Nullable UUID organizationId, String moduleCode, String code,
                                GroupContent content, String actor) {
        requireCode(code);
        return jdbc.write(c -> {
            UUID moduleId = moduleId(c, moduleCode);
            checkGroup(c, tenantId, moduleId, content, false);
            UUID id = Ids.newId();
            try {
                update(c, "INSERT INTO dai_re_rule_group (id, tenant_id, organization_id, module_id, code, name, description,"
                                + " status, evaluation_policy, match_on, composite_true_bundle_id, composite_false_bundle_id,"
                                + " composite_true_action, composite_false_action, on_error)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)",
                        id, tenantId, organizationId, moduleId, code, content.name(), content.description(),
                        content.policy().name(), content.matchOn().name(), content.compositeTrueBundleId(),
                        content.compositeFalseBundleId(), content.compositeTrueAction().name(),
                        content.compositeFalseAction().name(), content.onError().name());
            } catch (SQLException e) {
                throw duplicate(e, "group " + code + " already exists in this module");
            }
            return insertRevision(c, tenantId, Kind.GROUP, id, 1, content, "initial draft", null, actor);
        });
    }

    // ------------------------------------------------------------------------------------------------ edit

    /**
     * Saves a rule draft: updates the open DRAFT/REJECTED revision, or starts the next revision when none is open.
     *
     * @param tenantId tenant
     * @param ruleId   rule
     * @param content  the new definition
     * @param note     why it changes
     * @param actor    who edits
     * @return the draft revision
     */
    public Revision editRule(UUID tenantId, UUID ruleId, RuleContent content, @Nullable String note, String actor) {
        return jdbc.write(c -> {
            lockSubject(c, Kind.RULE, tenantId, ruleId);
            checkRule(c, tenantId, content, false);
            return saveDraft(c, tenantId, Kind.RULE, ruleId, content, note, actor);
        });
    }

    /**
     * Saves a group draft (see {@link #editRule}).
     *
     * @param tenantId tenant
     * @param groupId  group
     * @param content  the new definition
     * @param note     why it changes
     * @param actor    who edits
     * @return the draft revision
     */
    public Revision editGroup(UUID tenantId, UUID groupId, GroupContent content, @Nullable String note, String actor) {
        return jdbc.write(c -> {
            UUID moduleId = lockSubject(c, Kind.GROUP, tenantId, groupId);
            checkGroup(c, tenantId, moduleId, content, false);
            return saveDraft(c, tenantId, Kind.GROUP, groupId, content, note, actor);
        });
    }

    private Revision saveDraft(Connection c, UUID tenantId, Kind kind, UUID subject, Object content,
                               @Nullable String note, String actor) throws SQLException {
        Revision open = open(c, kind, subject);
        if (open == null) {
            int next = queryOne(c, "SELECT COALESCE(max(revision_no), 0) + 1 FROM dai_re_revision WHERE kind = ? AND subject_id = ?",
                    rs -> rs.getInt(1), kind.name(), subject);
            return insertRevision(c, tenantId, kind, subject, next, content, note, null, actor);
        }
        if (!open.state().equals("DRAFT") && !open.state().equals("REJECTED")) {
            throw new Conflict("revision_locked", "revision " + open.revisionNo() + " is " + open.state()
                    + "; withdraw it before editing", List.of());
        }
        update(c, "UPDATE dai_re_revision SET content = ?::jsonb, state = 'DRAFT', change_note = ?, submitted_by = NULL,"
                        + " submitted_at = NULL, reviewed_by = NULL, reviewed_at = NULL, review_comment = NULL WHERE id = ?",
                JSON.writeValueAsString(content), note, open.id());
        return revision(c, open.id());
    }

    // ------------------------------------------------------------------------------------------------ transitions

    /**
     * DRAFT → SUBMITTED. The content is validated as for publication, so a reviewer only sees publishable drafts.
     *
     * @param kind     RULE or GROUP
     * @param tenantId tenant
     * @param subject  rule or group id
     * @param actor    submitter
     * @return the revision
     */
    public Revision submit(Kind kind, UUID tenantId, UUID subject, String actor) {
        return jdbc.write(c -> {
            lockSubject(c, kind, tenantId, subject);
            Revision open = requireOpen(c, kind, subject, "DRAFT");
            checkContent(c, tenantId, kind, subject, open, true);
            update(c, "UPDATE dai_re_revision SET state = 'SUBMITTED', submitted_by = ?, submitted_at = now() WHERE id = ?",
                    actor, open.id());
            return revision(c, open.id());
        });
    }

    /**
     * SUBMITTED / APPROVED / REJECTED → DRAFT, so the author can edit again.
     *
     * @param kind     RULE or GROUP
     * @param tenantId tenant
     * @param subject  rule or group id
     * @param actor    who withdraws (recorded in the audit trail by the caller)
     * @return the revision
     */
    public Revision withdraw(Kind kind, UUID tenantId, UUID subject, String actor) {
        return jdbc.write(c -> {
            lockSubject(c, kind, tenantId, subject);
            Revision open = requireOpen(c, kind, subject, "SUBMITTED", "APPROVED", "REJECTED");
            update(c, "UPDATE dai_re_revision SET state = 'DRAFT', submitted_by = NULL, submitted_at = NULL,"
                    + " reviewed_by = NULL, reviewed_at = NULL, review_comment = NULL WHERE id = ?", open.id());
            return revision(c, open.id());
        });
    }

    /**
     * SUBMITTED → APPROVED by someone other than the submitter.
     *
     * @param kind     RULE or GROUP
     * @param tenantId tenant
     * @param subject  rule or group id
     * @param actor    reviewer
     * @param comment  optional comment
     * @return the revision
     */
    public Revision approve(Kind kind, UUID tenantId, UUID subject, String actor, @Nullable String comment) {
        return review(kind, tenantId, subject, actor, comment, "APPROVED");
    }

    /**
     * SUBMITTED → REJECTED by someone other than the submitter; a comment is required.
     *
     * @param kind     RULE or GROUP
     * @param tenantId tenant
     * @param subject  rule or group id
     * @param actor    reviewer
     * @param comment  why it is rejected
     * @return the revision
     */
    public Revision reject(Kind kind, UUID tenantId, UUID subject, String actor, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new Invalid("comment_required", "comment: a rejection needs a reason");
        }
        return review(kind, tenantId, subject, actor, comment, "REJECTED");
    }

    private Revision review(Kind kind, UUID tenantId, UUID subject, String actor, @Nullable String comment,
                            String newState) {
        return jdbc.write(c -> {
            lockSubject(c, kind, tenantId, subject);
            Revision open = requireOpen(c, kind, subject, "SUBMITTED");
            if (actor.equals(open.submittedBy())) {
                throw new Conflict("four_eyes", "the submitter cannot review their own revision", List.of());
            }
            update(c, "UPDATE dai_re_revision SET state = ?, reviewed_by = ?, reviewed_at = now(), review_comment = ? WHERE id = ?",
                    newState, actor, comment, open.id());
            return revision(c, open.id());
        });
    }

    /**
     * Publishes the open revision: APPROVED → PUBLISHED (or DRAFT → PUBLISHED when review is not required). The live
     * rule/group row and its parameter links change in the same transaction; the previous published revision becomes
     * SUPERSEDED. The engine picks the change up at its next change-marker poll.
     *
     * @param kind     RULE or GROUP
     * @param tenantId tenant
     * @param subject  rule or group id
     * @param actor    publisher
     * @return the published revision
     */
    public Revision publish(Kind kind, UUID tenantId, UUID subject, String actor) {
        return jdbc.write(c -> {
            lockSubject(c, kind, tenantId, subject);
            Revision open = requireOpen(c, kind, subject, requireReview ? new String[]{"APPROVED"}
                    : new String[]{"APPROVED", "DRAFT"});
            checkContent(c, tenantId, kind, subject, open, true);
            update(c, "UPDATE dai_re_revision SET state = 'SUPERSEDED' WHERE kind = ? AND subject_id = ? AND state = 'PUBLISHED'",
                    kind.name(), subject);
            update(c, "UPDATE dai_re_revision SET state = 'PUBLISHED', published_by = ?, published_at = now() WHERE id = ?",
                    actor, open.id());
            if (kind == Kind.RULE) {
                applyRule(c, subject, open);
            } else {
                applyGroup(c, subject, open);
            }
            return revision(c, open.id());
        });
    }

    /**
     * Restores an older published revision as a new DRAFT (it still has to be submitted/approved/published like any
     * change when review is required).
     *
     * @param kind       RULE or GROUP
     * @param tenantId   tenant
     * @param subject    rule or group id
     * @param revisionNo the PUBLISHED or SUPERSEDED revision to restore
     * @param note       why
     * @param actor      who
     * @return the new draft revision
     */
    public Revision rollback(Kind kind, UUID tenantId, UUID subject, int revisionNo, @Nullable String note, String actor) {
        return jdbc.write(c -> {
            lockSubject(c, kind, tenantId, subject);
            if (open(c, kind, subject) != null) {
                throw new Conflict("revision_open", "finish or withdraw the open revision first", List.of());
            }
            Revision target = queryOne(c, "SELECT " + REV_COLS + " FROM dai_re_revision WHERE kind = ? AND subject_id = ?"
                    + " AND revision_no = ?", RuleLifecycle::map, kind.name(), subject, revisionNo);
            if (target == null) {
                throw new NotFound("revision " + revisionNo);
            }
            if (!target.state().equals("PUBLISHED") && !target.state().equals("SUPERSEDED")) {
                throw new Conflict("not_restorable", "only published revisions can be restored", List.of());
            }
            int next = queryOne(c, "SELECT max(revision_no) + 1 FROM dai_re_revision WHERE kind = ? AND subject_id = ?",
                    rs -> rs.getInt(1), kind.name(), subject);
            return insertRevision(c, tenantId, kind, subject, next, target.content(),
                    note != null ? note : "restore revision " + revisionNo, target.id(), actor);
        });
    }

    /**
     * Takes a rule or group out of service. A rule used by an active group cannot be retired.
     *
     * @param kind     RULE or GROUP
     * @param tenantId tenant
     * @param subject  rule or group id
     */
    public void retire(Kind kind, UUID tenantId, UUID subject) {
        jdbc.write(c -> {
            lockSubject(c, kind, tenantId, subject);
            if (kind == Kind.RULE) {
                List<String> groups = query(c, "SELECT g.code FROM dai_re_rule_group_rule m JOIN dai_re_rule_group g"
                        + " ON g.id = m.group_id WHERE m.rule_id = ? AND m.enabled AND g.status = 'ACTIVE' ORDER BY g.code",
                        rs -> rs.getString(1), subject);
                if (!groups.isEmpty()) {
                    throw new Conflict("rule_in_use", "the rule is a member of active groups", groups);
                }
                update(c, "UPDATE dai_re_rule SET status = 'RETIRED' WHERE id = ?", subject);
            } else {
                update(c, "UPDATE dai_re_rule_group SET status = 'RETIRED' WHERE id = ?", subject);
            }
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ reads

    /**
     * The revision history of a rule or group, newest first.
     *
     * @param kind     RULE or GROUP
     * @param tenantId tenant
     * @param subject  rule or group id
     * @return revisions
     */
    public List<Revision> revisions(Kind kind, UUID tenantId, UUID subject) {
        return jdbc.read(c -> {
            lockless(c, kind, tenantId, subject);
            return query(c, "SELECT " + REV_COLS + " FROM dai_re_revision WHERE kind = ? AND subject_id = ?"
                    + " ORDER BY revision_no DESC", RuleLifecycle::map, kind.name(), subject);
        });
    }

    /**
     * Rules of a tenant.
     *
     * @param tenantId   tenant
     * @param moduleCode module filter, or {@code null}
     * @param status     status filter (DRAFT, ACTIVE, RETIRED), or {@code null}
     * @return rules ordered by module and code
     */
    public List<RuleSummary> listRules(UUID tenantId, @Nullable String moduleCode, @Nullable String status) {
        return jdbc.read(c -> query(c,
                "SELECT r.id, r.organization_id, m.code, r.code, r.name, r.status, r.cel_expression, o.revision_no, o.state"
                        + " FROM dai_re_rule r JOIN dai_re_module m ON m.id = r.module_id"
                        + " LEFT JOIN dai_re_revision o ON o.kind = 'RULE' AND o.subject_id = r.id AND o.state IN " + OPEN
                        + " WHERE r.tenant_id = ? AND (?::text IS NULL OR m.code = ?) AND (?::text IS NULL OR r.status = ?)"
                        + " ORDER BY m.code, r.code",
                rs -> new RuleSummary(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), (Integer) rs.getObject(8),
                        rs.getString(9)), tenantId, moduleCode, moduleCode, status, status));
    }

    /**
     * Groups of a tenant.
     *
     * @param tenantId   tenant
     * @param moduleCode module filter, or {@code null}
     * @param status     status filter, or {@code null}
     * @return groups ordered by module and code
     */
    public List<GroupSummary> listGroups(UUID tenantId, @Nullable String moduleCode, @Nullable String status) {
        return jdbc.read(c -> query(c,
                "SELECT g.id, g.organization_id, m.code, g.code, g.name, g.status, g.evaluation_policy, o.revision_no, o.state"
                        + " FROM dai_re_rule_group g JOIN dai_re_module m ON m.id = g.module_id"
                        + " LEFT JOIN dai_re_revision o ON o.kind = 'GROUP' AND o.subject_id = g.id AND o.state IN " + OPEN
                        + " WHERE g.tenant_id = ? AND (?::text IS NULL OR m.code = ?) AND (?::text IS NULL OR g.status = ?)"
                        + " ORDER BY m.code, g.code",
                rs -> new GroupSummary(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), (Integer) rs.getObject(8),
                        rs.getString(9)), tenantId, moduleCode, moduleCode, status, status));
    }

    /**
     * One rule with its open and published revisions.
     *
     * @param tenantId tenant
     * @param ruleId   rule
     * @return the detail
     */
    public Detail<RuleSummary> rule(UUID tenantId, UUID ruleId) {
        RuleSummary summary = listRules(tenantId, null, null).stream().filter(r -> r.id().equals(ruleId)).findFirst()
                .orElseThrow(() -> new NotFound("rule"));
        return detail(Kind.RULE, tenantId, ruleId, summary);
    }

    /**
     * One group with its open and published revisions.
     *
     * @param tenantId tenant
     * @param groupId  group
     * @return the detail
     */
    public Detail<GroupSummary> group(UUID tenantId, UUID groupId) {
        GroupSummary summary = listGroups(tenantId, null, null).stream().filter(g -> g.id().equals(groupId)).findFirst()
                .orElseThrow(() -> new NotFound("group"));
        return detail(Kind.GROUP, tenantId, groupId, summary);
    }

    /**
     * A summary plus the revisions an editor needs.
     *
     * @param summary   list row
     * @param open      the revision in flight, or {@code null}
     * @param published the live revision, or {@code null} for a never-published subject
     * @param <S>       summary type
     */
    public record Detail<S>(S summary, @Nullable Revision open, @Nullable Revision published) {
    }

    private <S> Detail<S> detail(Kind kind, UUID tenantId, UUID subject, S summary) {
        return jdbc.read(c -> new Detail<>(summary, open(c, kind, subject),
                queryOne(c, "SELECT " + REV_COLS + " FROM dai_re_revision WHERE kind = ? AND subject_id = ? AND state = 'PUBLISHED'",
                        RuleLifecycle::map, kind.name(), subject)));
    }

    // ------------------------------------------------------------------------------------------------ validation

    private void checkContent(Connection c, UUID tenantId, Kind kind, UUID subject, Revision rev, boolean forPublish)
            throws SQLException {
        try {
            if (kind == Kind.RULE) {
                checkRule(c, tenantId, JSON.treeToValue(rev.content(), RuleContent.class), forPublish);
            } else {
                UUID moduleId = queryOne(c, "SELECT module_id FROM dai_re_rule_group WHERE id = ?",
                        rs -> rs.getObject(1, UUID.class), subject);
                checkGroup(c, tenantId, Objects.requireNonNull(moduleId), JSON.treeToValue(rev.content(), GroupContent.class),
                        forPublish);
            }
        } catch (tools.jackson.core.JacksonException e) {
            throw new Invalid("invalid_content", "content: " + e.getOriginalMessage());
        }
    }

    private List<Parameter> checkRule(Connection c, UUID tenantId, RuleContent content, boolean forPublish)
            throws SQLException {
        List<String> problems = new ArrayList<>();
        text(problems, "name", content.name(), 200);
        List<Parameter> referenced = List.of();
        if (content.expression() == null || content.expression().isBlank()) {
            problems.add("expression: required");
        } else {
            try {
                referenced = library.get().compileBoolean(content.expression()).referenced();
            } catch (RuleCompilationException e) {
                problems.add("expression: " + e.getMessage());
            }
        }
        bundlesExist(c, tenantId, problems, content.trueMessageBundleId(), content.falseMessageBundleId());
        if (content.trueAction() == null || content.falseAction() == null) {
            problems.add("trueAction/falseAction: required");
        }
        if (!problems.isEmpty()) {
            throw new Invalid("invalid_rule", problems);
        }
        return referenced;
    }

    private void checkGroup(Connection c, UUID tenantId, UUID moduleId, GroupContent content, boolean forPublish)
            throws SQLException {
        List<String> problems = new ArrayList<>();
        text(problems, "name", content.name(), 200);
        if (content.policy() == null || content.matchOn() == null || content.onError() == null
                || content.compositeTrueAction() == null || content.compositeFalseAction() == null) {
            problems.add("policy/matchOn/onError/composite actions: required");
        } else if (content.policy() != EvaluationPolicy.COMPOSITE
                && (content.compositeTrueBundleId() != null || content.compositeFalseBundleId() != null)) {
            problems.add("compositeTrueBundleId/compositeFalseBundleId: only for the COMPOSITE policy");
        }
        bundlesExist(c, tenantId, problems, content.compositeTrueBundleId(), content.compositeFalseBundleId());
        if (forPublish && content.members().stream().noneMatch(GroupContent.Member::enabled)) {
            problems.add("members: a group needs at least one enabled rule");
        }
        Set<UUID> ruleIds = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        for (GroupContent.Member m : content.members()) {
            if (m.sequence() < 0) {
                problems.add("members: sequence must be >= 0");
            }
            if (!ruleIds.add(m.ruleId())) {
                problems.add("members: rule " + m.ruleId() + " listed twice");
            }
            if (!sequences.add(m.sequence())) {
                problems.add("members: sequence " + m.sequence() + " used twice");
            }
        }
        for (UUID ruleId : ruleIds) {
            String[] row = queryOne(c, "SELECT status, module_id::text FROM dai_re_rule WHERE id = ? AND tenant_id = ?",
                    rs -> new String[]{rs.getString(1), rs.getString(2)}, ruleId, tenantId);
            if (row == null) {
                problems.add("members: rule " + ruleId + " does not exist");
            } else {
                if (!row[1].equals(moduleId.toString())) {
                    problems.add("members: rule " + ruleId + " belongs to another module");
                }
                if (forPublish && !row[0].equals("ACTIVE")) {
                    problems.add("members: rule " + ruleId + " is " + row[0] + "; publish it first");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new Invalid("invalid_group", problems);
        }
    }

    private static void text(List<String> problems, String field, @Nullable String value, int max) {
        if (value == null || value.isBlank()) {
            problems.add(field + ": required");
        } else if (value.length() > max) {
            problems.add(field + ": longer than " + max + " characters");
        }
    }

    private static void bundlesExist(Connection c, UUID tenantId, List<String> problems, @Nullable UUID... bundles)
            throws SQLException {
        for (UUID id : bundles) {
            if (id != null && queryOne(c, "SELECT 1 FROM dai_re_sys_bundle WHERE id = ? AND (tenant_id IS NULL OR tenant_id = ?)",
                    rs -> 1, id, tenantId) == null) {
                problems.add("message bundle " + id + " does not exist");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ apply

    private void applyRule(Connection c, UUID ruleId, Revision rev) throws SQLException {
        RuleContent content = readContent(rev, RuleContent.class);
        update(c, "UPDATE dai_re_rule SET name = ?, description = ?, cel_expression = ?, true_message_bundle_id = ?,"
                        + " false_message_bundle_id = ?, true_action = ?, false_action = ?, status = 'ACTIVE',"
                        + " published_revision_id = ? WHERE id = ?",
                content.name(), content.description(), content.expression(), content.trueMessageBundleId(),
                content.falseMessageBundleId(), content.trueAction().name(), content.falseAction().name(), rev.id(), ruleId);
        update(c, "DELETE FROM dai_re_rule_parameter WHERE rule_id = ?", ruleId);
        try {
            CompiledExpression compiled = library.get().compileBoolean(content.expression());
            for (Parameter p : compiled.referenced()) {
                update(c, "INSERT INTO dai_re_rule_parameter (rule_id, attribute_id) VALUES (?, ?)", ruleId, p.attributeId());
            }
        } catch (RuleCompilationException e) {
            throw new Invalid("invalid_rule", "expression: " + e.getMessage());
        }
    }

    private void applyGroup(Connection c, UUID groupId, Revision rev) throws SQLException {
        GroupContent content = readContent(rev, GroupContent.class);
        update(c, "UPDATE dai_re_rule_group SET name = ?, description = ?, evaluation_policy = ?, match_on = ?,"
                        + " composite_true_bundle_id = ?, composite_false_bundle_id = ?, composite_true_action = ?,"
                        + " composite_false_action = ?, on_error = ?, status = 'ACTIVE', published_revision_id = ? WHERE id = ?",
                content.name(), content.description(), content.policy().name(), content.matchOn().name(),
                content.compositeTrueBundleId(), content.compositeFalseBundleId(), content.compositeTrueAction().name(),
                content.compositeFalseAction().name(), content.onError().name(), rev.id(), groupId);
        update(c, "DELETE FROM dai_re_rule_group_rule WHERE group_id = ?", groupId);
        for (GroupContent.Member m : content.members()) {
            update(c, "INSERT INTO dai_re_rule_group_rule (group_id, rule_id, sequence, enabled) VALUES (?, ?, ?, ?)",
                    groupId, m.ruleId(), m.sequence(), m.enabled());
        }
    }

    private static <T> T readContent(Revision rev, Class<T> type) {
        try {
            return JSON.treeToValue(rev.content(), type);
        } catch (tools.jackson.core.JacksonException e) {
            throw new Invalid("invalid_content", "content: " + e.getOriginalMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    private static void requireCode(String code) {
        if (code == null || !code.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) {
            throw new Invalid("invalid_code", "code: letters, digits, '.', '_' or '-', 1..128 characters");
        }
    }

    private static UUID moduleId(Connection c, String moduleCode) throws SQLException {
        UUID id = queryOne(c, "SELECT id FROM dai_re_module WHERE code = ? AND active", rs -> rs.getObject(1, UUID.class),
                moduleCode);
        if (id == null) {
            throw new Invalid("unknown_module", "module: " + moduleCode + " does not exist or is inactive");
        }
        return id;
    }

    private static AdminException duplicate(SQLException e, String message) {
        if ("23505".equals(e.getSQLState())) {
            return new Conflict("duplicate_code", message, List.of());
        }
        throw new StoreFailure(e.getSQLState(), e.getMessage(), e);
    }

    /** Locks the subject row and returns its module id; another tenant's subject is "not found". */
    private static UUID lockSubject(Connection c, Kind kind, UUID tenantId, UUID subject) throws SQLException {
        String table = kind == Kind.RULE ? "dai_re_rule" : "dai_re_rule_group";
        UUID moduleId = queryOne(c, "SELECT module_id FROM " + table + " WHERE id = ? AND tenant_id = ? FOR UPDATE",
                rs -> rs.getObject(1, UUID.class), subject, tenantId);
        if (moduleId == null) {
            throw new NotFound(kind == Kind.RULE ? "rule" : "group");
        }
        return moduleId;
    }

    private static void lockless(Connection c, Kind kind, UUID tenantId, UUID subject) throws SQLException {
        String table = kind == Kind.RULE ? "dai_re_rule" : "dai_re_rule_group";
        if (queryOne(c, "SELECT 1 FROM " + table + " WHERE id = ? AND tenant_id = ?", rs -> 1, subject, tenantId) == null) {
            throw new NotFound(kind == Kind.RULE ? "rule" : "group");
        }
    }

    private static @Nullable Revision open(Connection c, Kind kind, UUID subject) throws SQLException {
        return queryOne(c, "SELECT " + REV_COLS + " FROM dai_re_revision WHERE kind = ? AND subject_id = ? AND state IN " + OPEN,
                RuleLifecycle::map, kind.name(), subject);
    }

    private static Revision requireOpen(Connection c, Kind kind, UUID subject, String... states) throws SQLException {
        Revision open = open(c, kind, subject);
        if (open == null) {
            throw new Conflict("no_open_revision", "there is no revision in progress; edit first", List.of());
        }
        if (!List.of(states).contains(open.state())) {
            throw new Conflict("invalid_state", "revision " + open.revisionNo() + " is " + open.state()
                    + "; this step needs " + String.join(" or ", states), List.of());
        }
        return open;
    }

    private static Revision revision(Connection c, UUID id) throws SQLException {
        return Objects.requireNonNull(queryOne(c, "SELECT " + REV_COLS + " FROM dai_re_revision WHERE id = ?",
                RuleLifecycle::map, id));
    }

    private static Revision insertRevision(Connection c, UUID tenantId, Kind kind, UUID subject, int no, Object content,
                                           @Nullable String note, @Nullable UUID rollbackOf, String actor)
            throws SQLException {
        UUID id = Ids.newId();
        String json = content instanceof JsonNode node ? node.toString() : JSON.writeValueAsString(content);
        update(c, "INSERT INTO dai_re_revision (id, tenant_id, kind, subject_id, revision_no, content, change_note,"
                + " rollback_of, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                id, tenantId, kind.name(), subject, no, json, note, rollbackOf, actor);
        return revision(c, id);
    }

    private static Revision map(ResultSet rs) throws SQLException {
        return new Revision(rs.getObject(1, UUID.class), Kind.valueOf(rs.getString(2)), rs.getObject(3, UUID.class),
                rs.getInt(4), rs.getString(5), JSON.readTree(rs.getString(6)), rs.getString(7),
                rs.getObject(8, UUID.class), rs.getString(9), rs.getTimestamp(10).toInstant(), rs.getString(11),
                rs.getString(12), rs.getString(13), rs.getString(14),
                rs.getTimestamp(15) == null ? null : rs.getTimestamp(15).toInstant());
    }
}
