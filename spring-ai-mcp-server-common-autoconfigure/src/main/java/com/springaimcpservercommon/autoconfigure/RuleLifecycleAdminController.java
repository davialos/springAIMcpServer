package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ruleengine.admin.ExpressionTester;
import com.springaimcpservercommon.ruleengine.admin.GroupContent;
import com.springaimcpservercommon.ruleengine.admin.Revision;
import com.springaimcpservercommon.ruleengine.admin.Revision.Kind;
import com.springaimcpservercommon.ruleengine.admin.RuleContent;
import com.springaimcpservercommon.ruleengine.admin.RuleLifecycle;
import com.springaimcpservercommon.security.permission.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Rule and rule-group authoring API (LLD-18, OQ-65/66). In this API the <b>workspace is the rule engine's tenant</b>:
 * everything is addressed under {@code /workspaces/{workspaceId}/rule-engine} and the caller needs the permission in
 * that workspace. Another workspace's id is a 404.
 *
 * <p>Lifecycle (per rule and per group): {@code PUT …/draft} (or create) → {@code submit} → {@code approve} by a
 * different person → {@code publish}; {@code reject} sends it back; {@code withdraw} reopens a submitted revision;
 * {@code rollback} restores an older revision as a new draft; {@code retire} takes it out of service. Writes need
 * {@link Permission#RULES_AUTHOR} (draft, submit, withdraw) or {@link Permission#RULES_PUBLISH} (approve, reject,
 * publish, rollback, retire) <em>and</em> the AUTHORING capability of the environment (off in PROD unless the audited
 * production override is on). Every change is audited.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiRuleEngineAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/workspaces/{workspaceId}/rule-engine")
public final class RuleLifecycleAdminController {

    /**
     * Create a rule.
     *
     * @param organizationId organization, or {@code null} = whole workspace
     * @param moduleCode     module
     * @param code           rule code
     * @param content        first draft
     */
    public record CreateRule(@Nullable UUID organizationId, String moduleCode, String code, RuleContent content) {}

    /**
     * Create a rule group.
     *
     * @param organizationId organization, or {@code null} = whole workspace
     * @param moduleCode     module
     * @param code           group code
     * @param content        first draft
     */
    public record CreateGroup(@Nullable UUID organizationId, String moduleCode, String code, GroupContent content) {}

    /**
     * Save a rule draft.
     *
     * @param content new definition
     * @param note    why it changes
     */
    public record SaveRuleDraft(RuleContent content, @Nullable String note) {}

    /**
     * Save a group draft.
     *
     * @param content new definition
     * @param note    why it changes
     */
    public record SaveGroupDraft(GroupContent content, @Nullable String note) {}

    /**
     * A reviewer's comment.
     *
     * @param comment comment (required for reject)
     */
    public record Review(@Nullable String comment) {}

    /**
     * Restore an older revision.
     *
     * @param revisionNo revision to restore
     * @param note       why
     */
    public record Rollback(int revisionNo, @Nullable String note) {}

    /**
     * An expression to check.
     *
     * @param expression CEL text
     */
    public record ExpressionRequest(String expression) {}

    /**
     * An expression to run on sample values.
     *
     * @param expression CEL text
     * @param facts      sample values, flat or nested
     */
    public record ExpressionTestRequest(String expression, @Nullable Map<String, Object> facts) {}

    private final RuleLifecycle lifecycle;
    private final ExpressionTester tester;
    private final RuleAdminSupport support;

    RuleLifecycleAdminController(RuleLifecycle lifecycle, ExpressionTester tester, RuleAdminSupport support) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.tester = Objects.requireNonNull(tester, "tester");
        this.support = Objects.requireNonNull(support, "support");
    }

    // ---- rules --------------------------------------------------------------------------------------------------

    /**
     * Lists rules.
     *
     * @param workspaceId workspace (tenant)
     * @param module      module filter
     * @param status      status filter (DRAFT, ACTIVE, RETIRED)
     * @param request     current request
     * @return 200 with the rules
     */
    @GetMapping("/rules")
    public ResponseEntity<?> listRules(@PathVariable UUID workspaceId, @RequestParam(required = false) @Nullable String module,
                                       @RequestParam(required = false) @Nullable String status, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(lifecycle.listRules(workspaceId, module, status)) : gate.denied();
    }

    /**
     * Creates a rule as a draft.
     *
     * @param workspaceId workspace (tenant)
     * @param body        the rule
     * @param request     current request
     * @return 201 with revision 1
     */
    @PostMapping("/rules")
    public ResponseEntity<?> createRule(@PathVariable UUID workspaceId, @RequestBody CreateRule body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Revision r = lifecycle.createRule(workspaceId, body.organizationId(), body.moduleCode(), body.code(), body.content(),
                RuleAdminSupport.actor(gate));
        support.audit(gate, "RULE_CREATED", workspaceId, "rule", r.subjectId().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(r);
    }

    /**
     * One rule with its open and published revisions.
     *
     * @param workspaceId workspace (tenant)
     * @param id          rule
     * @param request     current request
     * @return 200 with the detail
     */
    @GetMapping("/rules/{id}")
    public ResponseEntity<?> rule(@PathVariable UUID workspaceId, @PathVariable UUID id, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(lifecycle.rule(workspaceId, id)) : gate.denied();
    }

    /**
     * Saves a rule draft (creates the next revision when none is open).
     *
     * @param workspaceId workspace (tenant)
     * @param id          rule
     * @param body        new definition and note
     * @param request     current request
     * @return 200 with the draft revision
     */
    @PutMapping("/rules/{id}/draft")
    public ResponseEntity<?> saveRuleDraft(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                           @RequestBody SaveRuleDraft body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Revision r = lifecycle.editRule(workspaceId, id, body.content(), body.note(), RuleAdminSupport.actor(gate));
        support.audit(gate, "RULE_DRAFT_SAVED", workspaceId, "rule", id.toString(), body.note(),
                details(r));
        return ResponseEntity.ok(r);
    }

    // ---- groups -------------------------------------------------------------------------------------------------

    /**
     * Lists rule groups.
     *
     * @param workspaceId workspace (tenant)
     * @param module      module filter
     * @param status      status filter
     * @param request     current request
     * @return 200 with the groups
     */
    @GetMapping("/groups")
    public ResponseEntity<?> listGroups(@PathVariable UUID workspaceId, @RequestParam(required = false) @Nullable String module,
                                        @RequestParam(required = false) @Nullable String status, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(lifecycle.listGroups(workspaceId, module, status)) : gate.denied();
    }

    /**
     * Creates a group as a draft.
     *
     * @param workspaceId workspace (tenant)
     * @param body        the group
     * @param request     current request
     * @return 201 with revision 1
     */
    @PostMapping("/groups")
    public ResponseEntity<?> createGroup(@PathVariable UUID workspaceId, @RequestBody CreateGroup body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Revision r = lifecycle.createGroup(workspaceId, body.organizationId(), body.moduleCode(), body.code(), body.content(),
                RuleAdminSupport.actor(gate));
        support.audit(gate, "GROUP_CREATED", workspaceId, "rule_group", r.subjectId().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(r);
    }

    /**
     * One group with its open and published revisions.
     *
     * @param workspaceId workspace (tenant)
     * @param id          group
     * @param request     current request
     * @return 200 with the detail
     */
    @GetMapping("/groups/{id}")
    public ResponseEntity<?> group(@PathVariable UUID workspaceId, @PathVariable UUID id, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(lifecycle.group(workspaceId, id)) : gate.denied();
    }

    /**
     * Saves a group draft.
     *
     * @param workspaceId workspace (tenant)
     * @param id          group
     * @param body        new definition and note
     * @param request     current request
     * @return 200 with the draft revision
     */
    @PutMapping("/groups/{id}/draft")
    public ResponseEntity<?> saveGroupDraft(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                            @RequestBody SaveGroupDraft body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Revision r = lifecycle.editGroup(workspaceId, id, body.content(), body.note(), RuleAdminSupport.actor(gate));
        support.audit(gate, "GROUP_DRAFT_SAVED", workspaceId, "rule_group", id.toString(), body.note(), details(r));
        return ResponseEntity.ok(r);
    }

    // ---- lifecycle transitions (rules and groups) ---------------------------------------------------------------

    /**
     * Submits the draft for review.
     *
     * @param workspaceId workspace (tenant)
     * @param kind        {@code rules} or {@code groups}
     * @param id          subject
     * @param request     current request
     * @return 200 with the revision
     */
    @PostMapping("/{kind:rules|groups}/{id}/submit")
    public ResponseEntity<?> submit(@PathVariable UUID workspaceId, @PathVariable String kind, @PathVariable UUID id,
                                    HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Revision r = lifecycle.submit(kind(kind), workspaceId, id, RuleAdminSupport.actor(gate));
        support.audit(gate, action(kind, "SUBMITTED"), workspaceId, resource(kind), id.toString(), null, details(r));
        return ResponseEntity.ok(r);
    }

    /**
     * Withdraws a submitted, approved or rejected revision back to a draft.
     *
     * @param workspaceId workspace (tenant)
     * @param kind        {@code rules} or {@code groups}
     * @param id          subject
     * @param request     current request
     * @return 200 with the revision
     */
    @PostMapping("/{kind:rules|groups}/{id}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable UUID workspaceId, @PathVariable String kind, @PathVariable UUID id,
                                      HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_AUTHOR, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Revision r = lifecycle.withdraw(kind(kind), workspaceId, id, RuleAdminSupport.actor(gate));
        support.audit(gate, action(kind, "WITHDRAWN"), workspaceId, resource(kind), id.toString(), null, details(r));
        return ResponseEntity.ok(r);
    }

    /**
     * Approves a submitted revision (not by its submitter).
     *
     * @param workspaceId workspace (tenant)
     * @param kind        {@code rules} or {@code groups}
     * @param id          subject
     * @param body        optional comment
     * @param request     current request
     * @return 200 with the revision; 409 {@code four_eyes} for the submitter
     */
    @PostMapping("/{kind:rules|groups}/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable UUID workspaceId, @PathVariable String kind, @PathVariable UUID id,
                                     @RequestBody(required = false) @Nullable Review body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_PUBLISH, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        String comment = body == null ? null : body.comment();
        Revision r = lifecycle.approve(kind(kind), workspaceId, id, RuleAdminSupport.actor(gate), comment);
        support.audit(gate, action(kind, "APPROVED"), workspaceId, resource(kind), id.toString(), comment, details(r));
        return ResponseEntity.ok(r);
    }

    /**
     * Rejects a submitted revision (not by its submitter); a comment is required.
     *
     * @param workspaceId workspace (tenant)
     * @param kind        {@code rules} or {@code groups}
     * @param id          subject
     * @param body        the reason
     * @param request     current request
     * @return 200 with the revision
     */
    @PostMapping("/{kind:rules|groups}/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable UUID workspaceId, @PathVariable String kind, @PathVariable UUID id,
                                    @RequestBody Review body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_PUBLISH, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Revision r = lifecycle.reject(kind(kind), workspaceId, id, RuleAdminSupport.actor(gate), body.comment() == null ? "" : body.comment());
        support.audit(gate, action(kind, "REJECTED"), workspaceId, resource(kind), id.toString(), body.comment(), details(r));
        return ResponseEntity.ok(r);
    }

    /**
     * Publishes the approved revision; the engine picks it up at its next change-marker poll on every node.
     *
     * @param workspaceId workspace (tenant)
     * @param kind        {@code rules} or {@code groups}
     * @param id          subject
     * @param request     current request
     * @return 200 with the published revision
     */
    @PostMapping("/{kind:rules|groups}/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable UUID workspaceId, @PathVariable String kind, @PathVariable UUID id,
                                     HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_PUBLISH, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Revision r = lifecycle.publish(kind(kind), workspaceId, id, RuleAdminSupport.actor(gate));
        support.audit(gate, action(kind, "PUBLISHED"), workspaceId, resource(kind), id.toString(), null, details(r));
        return ResponseEntity.ok(r);
    }

    /**
     * Restores an older published revision as a new draft.
     *
     * @param workspaceId workspace (tenant)
     * @param kind        {@code rules} or {@code groups}
     * @param id          subject
     * @param body        revision number and note
     * @param request     current request
     * @return 201 with the new draft
     */
    @PostMapping("/{kind:rules|groups}/{id}/rollback")
    public ResponseEntity<?> rollback(@PathVariable UUID workspaceId, @PathVariable String kind, @PathVariable UUID id,
                                      @RequestBody Rollback body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_PUBLISH, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        Revision r = lifecycle.rollback(kind(kind), workspaceId, id, body.revisionNo(), body.note(), RuleAdminSupport.actor(gate));
        support.audit(gate, action(kind, "ROLLED_BACK"), workspaceId, resource(kind), id.toString(), body.note(),
                Map.of("restored", body.revisionNo(), "newRevision", r.revisionNo()));
        return ResponseEntity.status(HttpStatus.CREATED).body(r);
    }

    /**
     * Retires a rule or group (a rule in an active group is refused with {@code rule_in_use}).
     *
     * @param workspaceId workspace (tenant)
     * @param kind        {@code rules} or {@code groups}
     * @param id          subject
     * @param request     current request
     * @return 204
     */
    @PostMapping("/{kind:rules|groups}/{id}/retire")
    public ResponseEntity<?> retire(@PathVariable UUID workspaceId, @PathVariable String kind, @PathVariable UUID id,
                                    HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_PUBLISH, workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        lifecycle.retire(kind(kind), workspaceId, id);
        support.audit(gate, action(kind, "RETIRED"), workspaceId, resource(kind), id.toString());
        return ResponseEntity.noContent().build();
    }

    /**
     * The revision history, newest first.
     *
     * @param workspaceId workspace (tenant)
     * @param kind        {@code rules} or {@code groups}
     * @param id          subject
     * @param request     current request
     * @return 200 with the revisions
     */
    @GetMapping("/{kind:rules|groups}/{id}/revisions")
    public ResponseEntity<?> revisions(@PathVariable UUID workspaceId, @PathVariable String kind, @PathVariable UUID id,
                                       HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(lifecycle.revisions(kind(kind), workspaceId, id)) : gate.denied();
    }

    // ---- expression editor helpers ------------------------------------------------------------------------------

    /**
     * Checks an expression against the parameter library.
     *
     * @param workspaceId workspace (tenant)
     * @param body        the expression
     * @param request     current request
     * @return 200 with validity, issues and the parameters read
     */
    @PostMapping("/expressions/validate")
    public ResponseEntity<?> validate(@PathVariable UUID workspaceId, @RequestBody ExpressionRequest body, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_READ, workspaceId);
        return gate.open() ? ResponseEntity.ok(tester.validate(body.expression())) : gate.denied();
    }

    /**
     * Runs an expression on sample values (nothing is saved or logged).
     *
     * @param workspaceId workspace (tenant)
     * @param body        the expression and sample values
     * @param request     current request
     * @return 200 with TRUE, FALSE or an error code
     */
    @PostMapping("/expressions/test")
    public ResponseEntity<?> test(@PathVariable UUID workspaceId, @RequestBody ExpressionTestRequest body, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_AUTHOR, workspaceId);
        return gate.open() ? ResponseEntity.ok(tester.test(body.expression(), body.facts() == null ? Map.of() : body.facts()))
                : gate.denied();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static Kind kind(String path) {
        return path.equals("rules") ? Kind.RULE : Kind.GROUP;
    }

    private static String action(String path, String verb) {
        return (path.equals("rules") ? "RULE_" : "GROUP_") + verb;
    }

    private static String resource(String path) {
        return path.equals("rules") ? "rule" : "rule_group";
    }

    private static Map<String, Object> details(Revision r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("revision", r.revisionNo());
        d.put("state", r.state());
        return d;
    }
}
