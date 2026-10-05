package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.CreateRule;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.GroupRequest;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.GroupView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.RuleRef;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.RuleView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.TriggerSpec;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.Written;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.cel.CompiledExpression;
import com.springaimcpservercommon.ruleengine.cel.RuleCompilationException;
import com.springaimcpservercommon.ruleengine.model.Action;
import com.springaimcpservercommon.ruleengine.model.EvaluationPolicy;
import com.springaimcpservercommon.ruleengine.model.MatchOn;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import com.springaimcpservercommon.ruleengine.model.TriggerType;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The write side: creates and changes rules, rule groups and their trigger points on behalf of the caller. It enforces
 * what the database cannot say alone:
 * <ul>
 *   <li><b>Scope.</b> Items belong to the caller's organization, or to the whole tenant when an administrator (or a
 *       tenant-wide user) asks for it. A user changes only items of their own organization; an administrator any visible
 *       item. A tenant-wide group may only contain tenant-wide rules, so no organization's private rule runs for others.</li>
 *   <li><b>Valid CEL at save time.</b> A rule that does not compile against the parameter library is refused with the
 *       compiler's message (which names parameters, never values); the parameters a rule reads are recorded for impact
 *       analysis.</li>
 *   <li><b>Consistency.</b> One transaction per request holds the rows, their message bundles and the audit entry;
 *       replacing a group is optimistic ({@code expectedRowVersion}).</li>
 * </ul>
 * The engine's cache is refreshed after commit, so the writer sees its change at once; other nodes follow the change
 * markers within one poll interval.
 */
@Service
class AuthoringService {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern TRIGGER_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");
    private static final Set<String> RULE_STATUS_ON_CREATE = Set.of("DRAFT", "ACTIVE");
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "RETIRED");
    private static final int MAX_NAME = 200;
    private static final int MAX_TEXT = 1000;
    private static final int MAX_RULES_PER_GROUP = 200;

    private final JdbcClient jdbc;
    private final RuleCatalogCache cache;
    private final CatalogRepository catalog;
    private final AuditLog audit;

    AuthoringService(JdbcClient jdbc, RuleCatalogCache cache, CatalogRepository catalog, AuditLog audit) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.catalog = catalog;
        this.audit = audit;
    }

    // ── rules ──────────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    Written<RuleView> createRule(Caller c, CreateRule req) {
        String code = code(req.code(), "code");
        String name = name(req.name());
        String expression = expression(req.expression());
        String status = req.status() == null ? "ACTIVE" : upper(req.status());
        if (!RULE_STATUS_ON_CREATE.contains(status)) {
            throw ApiProblem.bad("invalid_status", "a new rule is created as DRAFT or ACTIVE");
        }
        Action trueAction = action(req.trueAction(), Action.ALLOW, "trueAction");
        Action falseAction = action(req.falseAction(), Action.BLOCK, "falseAction");
        UUID organization = organizationFor(c, req.scope());
        UUID moduleId = moduleId(req.moduleCode());
        CompiledExpression compiled = compile(c, expression);

        UUID id = Ids.newId();
        UUID trueBundle = createBundle(c, "r-" + id + "-true", "Rule " + code + ": message when true",
                req.trueMessage());
        UUID falseBundle = createBundle(c, "r-" + id + "-false", "Rule " + code + ": message when false",
                req.falseMessage());
        jdbc.sql("""
                INSERT INTO dai_re_rule (id, tenant_id, organization_id, module_id, code, name, description,
                                         cel_expression, status, true_message_bundle_id, false_message_bundle_id,
                                         true_action, false_action)
                VALUES (:id, :t, :org, :module, :code, :name, :description, :expression, :status, :tb, :fb, :ta, :fa)
                """).param("id", id).param("t", c.tenantId()).param("org", organization).param("module", moduleId)
                .param("code", code).param("name", name).param("description", blankToNull(req.description()))
                .param("expression", expression).param("status", status).param("tb", trueBundle)
                .param("fb", falseBundle).param("ta", trueAction.name()).param("fa", falseAction.name()).update();
        for (Parameter p : compiled.referenced()) {
            jdbc.sql("INSERT INTO dai_re_rule_parameter (rule_id, attribute_id) VALUES (:r, :a) ON CONFLICT DO NOTHING")
                    .param("r", id).param("a", p.attributeId()).update();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("module", req.moduleCode());
        details.put("status", status);
        details.put("scope", CatalogRepository.scopeLabel(organization));
        details.put("parameters", compiled.referenced().stream().map(Parameter::celName).sorted().toList());
        audit.record(c, organization, "RULE_CREATED", "RULE", id, code, "Created rule " + code, details);
        refreshAfterCommit();

        List<String> warnings = new ArrayList<>();
        if (!"ACTIVE".equals(status)) {
            warnings.add("The rule is " + status + " and will not run until it is ACTIVE.");
        }
        return new Written<>(catalog.rule(c, id), warnings);
    }

    @Transactional
    RuleView setRuleStatus(Caller c, UUID id, String requested) {
        String status = status(requested);
        RuleView rule = catalog.rule(c, id);
        UUID organization = ruleOrganization(c, id);
        requireCanModify(c, organization, "rule");
        jdbc.sql("UPDATE dai_re_rule SET status = :s, row_version = row_version + 1 WHERE id = :id AND tenant_id = :t")
                .param("s", status).param("id", id).param("t", c.tenantId()).update();
        audit.record(c, organization, "RULE_STATUS_CHANGED", "RULE", id, rule.code(),
                "Rule " + rule.code() + ": " + rule.status() + " → " + status, Map.of("from", rule.status(), "to", status));
        refreshAfterCommit();
        return catalog.rule(c, id);
    }

    /** A nullable id read from a row that exists: wrapped, because a mapper may not return {@code null} itself. */
    private record Ref(@Nullable UUID id) {
    }

    private @Nullable UUID ruleOrganization(Caller c, UUID id) {
        return jdbc.sql("SELECT organization_id FROM dai_re_rule WHERE id = :id AND tenant_id = :t")
                .param("id", id).param("t", c.tenantId())
                .query((rs, n) -> new Ref(rs.getObject(1, UUID.class))).single().id();
    }

    private @Nullable UUID groupOrganization(Caller c, UUID id, String lock) {
        return jdbc.sql("SELECT organization_id FROM dai_re_rule_group WHERE id = :id AND tenant_id = :t" + lock)
                .param("id", id).param("t", c.tenantId())
                .query((rs, n) -> new Ref(rs.getObject(1, UUID.class))).single().id();
    }

    // ── rule groups ────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    Written<GroupView> createGroup(Caller c, GroupRequest req) {
        Checked g = check(req);
        UUID organization = organizationFor(c, req.scope());
        UUID moduleId = moduleId(req.moduleCode());
        String status = req.status() == null ? "ACTIVE" : upper(req.status());
        if (!RULE_STATUS_ON_CREATE.contains(status)) {
            throw ApiProblem.bad("invalid_status", "a new group is created as DRAFT or ACTIVE");
        }
        List<Member> members = members(c, moduleId, organization, req.rules());
        UUID id = Ids.newId();
        UUID trueBundle = createBundle(c, "g-" + id + "-true", "Group " + g.code + ": message when all rules are true",
                req.compositeTrueMessage());
        UUID falseBundle = createBundle(c, "g-" + id + "-false", "Group " + g.code + ": message when a rule fails",
                req.compositeFalseMessage());
        jdbc.sql("""
                INSERT INTO dai_re_rule_group (id, tenant_id, organization_id, module_id, code, name, description,
                                               status, evaluation_policy, match_on, composite_true_bundle_id,
                                               composite_false_bundle_id, composite_true_action,
                                               composite_false_action, on_error)
                VALUES (:id, :t, :org, :module, :code, :name, :description, :status, :policy, :matchOn, :tb, :fb,
                        :ta, :fa, :onError)
                """).param("id", id).param("t", c.tenantId()).param("org", organization).param("module", moduleId)
                .param("code", g.code).param("name", g.name).param("description", blankToNull(req.description()))
                .param("status", status).param("policy", g.policy.name()).param("matchOn", g.matchOn.name())
                .param("tb", trueBundle).param("fb", falseBundle).param("ta", g.trueAction.name())
                .param("fa", g.falseAction.name()).param("onError", g.onError.name()).update();
        insertMembers(id, members);
        List<UUID> triggerIds = insertTriggers(c, id, moduleId, organization, req.triggers());
        audit.record(c, organization, "RULE_GROUP_CREATED", "RULE_GROUP", id, g.code, "Created rule group " + g.code,
                groupDetails(req.moduleCode(), g, status, organization, members.size(), triggerIds.size()));
        refreshAfterCommit();
        return new Written<>(catalog.group(c, id), groupWarnings(status, members, g));
    }

    @Transactional
    Written<GroupView> replaceGroup(Caller c, UUID id, GroupRequest req) {
        Checked g = check(req);
        if (req.expectedRowVersion() == null) {
            throw ApiProblem.bad("expected_row_version_required", "expectedRowVersion is required to replace a group");
        }
        GroupView current = catalog.group(c, id);
        UUID organization = groupOrganization(c, id, " FOR UPDATE");
        requireCanModify(c, organization, "rule group");
        if (!current.moduleCode().equals(req.moduleCode()) || !current.code().equals(g.code)) {
            throw ApiProblem.invalid("immutable_identity", "the module and code of a group cannot be changed");
        }
        if (current.rowVersion() != req.expectedRowVersion()) {
            throw ApiProblem.conflict("stale_version", "the group changed since you loaded it; reload and retry");
        }
        UUID moduleId = moduleId(req.moduleCode());
        String status = req.status() == null ? current.status() : status(req.status());
        List<Member> members = members(c, moduleId, organization, req.rules());
        UUID trueBundle = replaceBundle(c, bundleOf(id, "composite_true_bundle_id"), "g-" + id + "-true",
                "Group " + g.code + ": message when all rules are true", req.compositeTrueMessage());
        UUID falseBundle = replaceBundle(c, bundleOf(id, "composite_false_bundle_id"), "g-" + id + "-false",
                "Group " + g.code + ": message when a rule fails", req.compositeFalseMessage());
        jdbc.sql("""
                UPDATE dai_re_rule_group
                   SET name = :name, description = :description, status = :status, evaluation_policy = :policy,
                       match_on = :matchOn, composite_true_bundle_id = :tb, composite_false_bundle_id = :fb,
                       composite_true_action = :ta, composite_false_action = :fa, on_error = :onError,
                       row_version = row_version + 1
                 WHERE id = :id AND tenant_id = :t
                """).param("name", g.name).param("description", blankToNull(req.description()))
                .param("status", status).param("policy", g.policy.name()).param("matchOn", g.matchOn.name())
                .param("tb", trueBundle).param("fb", falseBundle).param("ta", g.trueAction.name())
                .param("fa", g.falseAction.name()).param("onError", g.onError.name()).param("id", id)
                .param("t", c.tenantId()).update();
        jdbc.sql("DELETE FROM dai_re_rule_group_rule WHERE group_id = :id").param("id", id).update();
        insertMembers(id, members);
        int triggers = current.triggers().size();
        if (req.triggers() != null) {
            jdbc.sql("DELETE FROM dai_re_trigger_point WHERE rule_group_id = :id AND tenant_id = :t")
                    .param("id", id).param("t", c.tenantId()).update();
            triggers = insertTriggers(c, id, moduleId, organization, req.triggers()).size();
        }
        audit.record(c, organization, "RULE_GROUP_UPDATED", "RULE_GROUP", id, g.code, "Updated rule group " + g.code,
                groupDetails(req.moduleCode(), g, status, organization, members.size(), triggers));
        refreshAfterCommit();
        return new Written<>(catalog.group(c, id), groupWarnings(status, members, g));
    }

    @Transactional
    GroupView setGroupStatus(Caller c, UUID id, String requested) {
        String status = status(requested);
        GroupView group = catalog.group(c, id);
        UUID organization = groupOrganization(c, id, "");
        requireCanModify(c, organization, "rule group");
        jdbc.sql("UPDATE dai_re_rule_group SET status = :s, row_version = row_version + 1 "
                        + "WHERE id = :id AND tenant_id = :t").param("s", status).param("id", id)
                .param("t", c.tenantId()).update();
        audit.record(c, organization, "RULE_GROUP_STATUS_CHANGED", "RULE_GROUP", id, group.code(),
                "Rule group " + group.code() + ": " + group.status() + " → " + status,
                Map.of("from", group.status(), "to", status));
        refreshAfterCommit();
        return catalog.group(c, id);
    }

    /** The validated scalar fields of a group request. */
    private record Checked(String code, String name, EvaluationPolicy policy, MatchOn matchOn, Action onError,
                           Action trueAction, Action falseAction) {
    }

    private Checked check(GroupRequest req) {
        String code = code(req.code(), "code");
        String name = name(req.name());
        EvaluationPolicy policy = enumValue(EvaluationPolicy.class, req.policy(), null, "policy");
        MatchOn matchOn = enumValue(MatchOn.class, req.matchOn(), MatchOn.TRUE, "matchOn");
        boolean hasComposite = !(req.compositeTrueMessage() == null || req.compositeTrueMessage().isEmpty())
                || !(req.compositeFalseMessage() == null || req.compositeFalseMessage().isEmpty());
        if (policy != EvaluationPolicy.COMPOSITE && hasComposite) {
            throw ApiProblem.invalid("composite_message_not_allowed",
                    "group messages are only used by the COMPOSITE policy");
        }
        return new Checked(code, name, policy, matchOn, action(req.onError(), Action.BLOCK, "onError"),
                action(req.compositeTrueAction(), Action.ALLOW, "compositeTrueAction"),
                action(req.compositeFalseAction(), Action.BLOCK, "compositeFalseAction"));
    }

    private record Member(UUID ruleId, String ruleCode, String status, int sequence, boolean enabled) {
    }

    private List<Member> members(Caller c, UUID moduleId, @Nullable UUID groupOrganization,
                                 @Nullable List<RuleRef> refs) {
        List<Member> out = new ArrayList<>();
        if (refs == null) {
            return out;
        }
        if (refs.size() > MAX_RULES_PER_GROUP) {
            throw ApiProblem.invalid("too_many_rules", "a group holds at most " + MAX_RULES_PER_GROUP + " rules");
        }
        Set<String> codes = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        int next = 10;
        for (RuleRef ref : refs) {
            String ruleCode = code(ref.ruleCode(), "ruleCode");
            if (!codes.add(ruleCode)) {
                throw ApiProblem.invalid("duplicate_rule", "rule " + ruleCode + " is listed twice");
            }
            int sequence = ref.sequence() == null ? next : ref.sequence();
            if (sequence < 0) {
                throw ApiProblem.bad("invalid_sequence", "sequence must not be negative");
            }
            if (!sequences.add(sequence)) {
                throw ApiProblem.invalid("duplicate_sequence", "two rules have sequence " + sequence);
            }
            next = Math.max(next, sequence) + 10;
            Map<String, Object> p = new HashMap<>();
            p.put("t", c.tenantId());
            p.put("m", moduleId);
            p.put("code", ruleCode);
            p.put("gorg", groupOrganization);
            // a tenant-wide group may only use tenant-wide rules; an organization group may add its own
            List<Member> found = jdbc.sql("""
                    SELECT id, status FROM dai_re_rule
                    WHERE tenant_id = :t AND module_id = :m AND code = :code
                      AND (organization_id IS NULL OR organization_id = CAST(:gorg AS uuid))
                    ORDER BY organization_id NULLS LAST LIMIT 1
                    """).params(p).query((rs, n) -> new Member(rs.getObject("id", UUID.class), ruleCode,
                    rs.getString("status"), sequence, ref.enabled() == null || ref.enabled())).list();
            if (found.isEmpty()) {
                throw ApiProblem.invalid("unknown_rule", "rule " + ruleCode + " does not exist in this module"
                        + (groupOrganization == null ? " or is not shared with the whole tenant" : ""));
            }
            out.add(found.getFirst());
        }
        return out;
    }

    private void insertMembers(UUID groupId, List<Member> members) {
        for (Member m : members) {
            jdbc.sql("INSERT INTO dai_re_rule_group_rule (group_id, rule_id, sequence, enabled) "
                            + "VALUES (:g, :r, :s, :e)").param("g", groupId).param("r", m.ruleId())
                    .param("s", m.sequence()).param("e", m.enabled()).update();
        }
    }

    private List<UUID> insertTriggers(Caller c, UUID groupId, UUID moduleId, @Nullable UUID organization,
                                      @Nullable List<TriggerSpec> specs) {
        List<UUID> ids = new ArrayList<>();
        if (specs == null) {
            return ids;
        }
        int next = 0;
        for (TriggerSpec t : specs) {
            TriggerType type = enumValue(TriggerType.class, t.type(), null, "trigger type");
            if ((type == TriggerType.FORM_FIELD) != (t.fieldCode() != null && !t.fieldCode().isBlank())) {
                throw ApiProblem.invalid("invalid_trigger", "fieldCode is required for FORM_FIELD triggers and "
                        + "not allowed for FORM_ACTION triggers");
            }
            for (String value : new String[]{t.application(), t.formCode(), t.actionCode()}) {
                if (value == null || !TRIGGER_CODE.matcher(value).matches()) {
                    throw ApiProblem.bad("invalid_trigger", "trigger application, form and action codes use letters, "
                            + "digits, '.', '_' and '-' (max 64)");
                }
            }
            if (t.fieldCode() != null && !t.fieldCode().isBlank() && !TRIGGER_CODE.matcher(t.fieldCode()).matches()) {
                throw ApiProblem.bad("invalid_trigger", "the trigger field code uses letters, digits, '.', '_', '-'");
            }
            UUID id = Ids.newId();
            jdbc.sql("""
                    INSERT INTO dai_re_trigger_point (id, tenant_id, organization_id, application, module_id,
                                                      trigger_type, form_code, action_code, field_code, rule_group_id,
                                                      sequence)
                    VALUES (:id, :t, :org, :app, :module, :type, :form, :action, :field, :group, :seq)
                    """).param("id", id).param("t", c.tenantId()).param("org", organization)
                    .param("app", t.application()).param("module", moduleId).param("type", type.name())
                    .param("form", t.formCode()).param("action", t.actionCode())
                    .param("field", type == TriggerType.FORM_FIELD ? t.fieldCode() : null).param("group", groupId)
                    .param("seq", t.sequence() == null ? next : t.sequence()).update();
            next += 10;
            ids.add(id);
        }
        return ids;
    }

    private Map<String, Object> groupDetails(String module, Checked g, String status, @Nullable UUID organization,
                                             int rules, int triggers) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("module", module);
        d.put("policy", g.policy.name());
        d.put("status", status);
        d.put("scope", CatalogRepository.scopeLabel(organization));
        d.put("rules", rules);
        d.put("triggers", triggers);
        return d;
    }

    private List<String> groupWarnings(String status, List<Member> members, Checked g) {
        List<String> warnings = new ArrayList<>();
        if (!"ACTIVE".equals(status)) {
            warnings.add("The group is " + status + ": the engine ignores it until it is ACTIVE.");
        }
        if (members.isEmpty()) {
            warnings.add("The group has no rules, so it always allows.");
        }
        for (Member m : members) {
            if (!"ACTIVE".equals(m.status())) {
                warnings.add("Rule " + m.ruleCode() + " is " + m.status() + " and will not run.");
            }
        }
        if (g.policy == EvaluationPolicy.COMPOSITE && members.stream().noneMatch(Member::enabled)) {
            warnings.add("A COMPOSITE group with no enabled rule never fails.");
        }
        return warnings;
    }

    // ── shared helpers ─────────────────────────────────────────────────────────────────────────────────

    private CompiledExpression compile(Caller c, String expression) {
        try {
            return cache.catalog(c.tenantId()).library().compileBoolean(expression);
        } catch (RuleCompilationException e) {
            throw ApiProblem.invalid("invalid_expression", e.getMessage());
        }
    }

    private @Nullable UUID organizationFor(Caller c, @Nullable String scope) {
        String s = scope == null || scope.isBlank() ? (c.organizationId() != null ? "ORGANIZATION" : "TENANT")
                : upper(scope);
        return switch (s) {
            case "TENANT" -> {
                if (c.organizationId() != null && !c.admin()) {
                    throw ApiProblem.forbidden("scope_forbidden",
                            "only an administrator can create items shared with the whole tenant");
                }
                yield null;
            }
            case "ORGANIZATION" -> {
                if (c.organizationId() == null) {
                    throw ApiProblem.invalid("no_organization", "you are not assigned to an organization; use scope TENANT");
                }
                yield c.organizationId();
            }
            default -> throw ApiProblem.bad("invalid_scope", "scope is TENANT or ORGANIZATION");
        };
    }

    private void requireCanModify(Caller c, @Nullable UUID itemOrganization, String what) {
        if (!(c.admin() || Objects.equals(itemOrganization, c.organizationId()))) {
            throw ApiProblem.forbidden("not_yours", "this " + what + " belongs to "
                    + (itemOrganization == null ? "the whole tenant" : "another organization")
                    + "; only an administrator can change it");
        }
    }

    private UUID moduleId(String code) {
        List<UUID> ids = jdbc.sql("SELECT id FROM dai_re_module WHERE code = :c AND active").param("c", code)
                .query((rs, n) -> rs.getObject(1, UUID.class)).list();
        if (ids.isEmpty()) {
            throw ApiProblem.invalid("unknown_module", "module " + code + " does not exist");
        }
        return ids.getFirst();
    }

    private @Nullable UUID bundleOf(UUID groupId, String column) {
        return jdbc.sql("SELECT " + column + " FROM dai_re_rule_group WHERE id = :id").param("id", groupId)
                .query((rs, n) -> new Ref(rs.getObject(1, UUID.class))).single().id();
    }

    private @Nullable UUID createBundle(Caller c, String code, String description, @Nullable Map<String, String> texts) {
        if (texts == null || texts.isEmpty()) {
            return null;
        }
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO dai_re_sys_bundle (id, tenant_id, code, description) VALUES (:id, :t, :code, :d)")
                .param("id", id).param("t", c.tenantId()).param("code", code).param("d", description).update();
        writeTexts(id, texts);
        return id;
    }

    /** Replaces the texts of an existing bundle (or creates one); an empty map removes the message. */
    private @Nullable UUID replaceBundle(Caller c, @Nullable UUID existing, String code, String description,
                                         @Nullable Map<String, String> texts) {
        if (texts == null || texts.isEmpty()) {
            return null; // the group no longer references it; the orphaned bundle is harmless and swept by retention
        }
        if (existing == null) {
            return createBundle(c, code, description, texts);
        }
        jdbc.sql("DELETE FROM dai_re_sys_bundle_message WHERE bundle_id = :id").param("id", existing).update();
        writeTexts(existing, texts);
        return existing;
    }

    private void writeTexts(UUID bundleId, Map<String, String> texts) {
        for (Map.Entry<String, String> e : new LinkedHashMap<>(texts).entrySet()) {
            String language = e.getKey() == null ? "" : e.getKey().strip();
            String text = e.getValue() == null ? "" : e.getValue().strip();
            if (!LANGUAGE.matcher(language).matches()) {
                throw ApiProblem.bad("invalid_language", "language tags look like en, hi, th or pt-BR");
            }
            if (text.isEmpty() || text.length() > MAX_TEXT) {
                throw ApiProblem.bad("invalid_message", "each message text needs 1 to " + MAX_TEXT + " characters");
            }
            jdbc.sql("INSERT INTO dai_re_sys_bundle_message (bundle_id, language, message_text) VALUES (:b, :l, :m) "
                            + "ON CONFLICT (bundle_id, language) DO UPDATE SET message_text = EXCLUDED.message_text")
                    .param("b", bundleId).param("l", language).param("m", text).update();
        }
    }

    private void refreshAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.invalidate();
                }
            });
        } else {
            cache.invalidate();
        }
    }

    private static String code(@Nullable String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw ApiProblem.bad("invalid_" + field, field + " uses letters, digits, '.', '_' and '-' (1-64 characters)");
        }
        return value;
    }

    private static String name(@Nullable String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > MAX_NAME) {
            throw ApiProblem.bad("invalid_name", "name needs 1 to " + MAX_NAME + " characters");
        }
        return v;
    }

    private static String expression(@Nullable String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            throw ApiProblem.bad("invalid_expression", "the CEL expression is required");
        }
        return v;
    }

    private static String status(@Nullable String value) {
        String v = value == null ? "" : upper(value);
        if (!STATUSES.contains(v)) {
            throw ApiProblem.bad("invalid_status", "status is DRAFT, ACTIVE or RETIRED");
        }
        return v;
    }

    private static Action action(@Nullable String value, Action fallback, String field) {
        return enumValue(Action.class, value, fallback, field);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, @Nullable String value, @Nullable E fallback,
                                                   String field) {
        if (value == null || value.isBlank()) {
            if (fallback == null) {
                throw ApiProblem.bad("invalid_" + field.replace(' ', '_'), field + " is required");
            }
            return fallback;
        }
        try {
            return Enum.valueOf(type, upper(value));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.bad("invalid_" + field.replace(' ', '_'), field + " must be one of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    private static String upper(String value) {
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
