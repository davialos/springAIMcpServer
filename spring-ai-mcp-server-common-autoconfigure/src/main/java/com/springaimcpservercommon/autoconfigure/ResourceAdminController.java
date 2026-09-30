package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.environment.Capability;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.config.ConfigStore;
import com.springaimcpservercommon.persistence.config.DraftContent;
import com.springaimcpservercommon.persistence.config.PublishResult;
import com.springaimcpservercommon.persistence.config.ResourceKind;
import com.springaimcpservercommon.persistence.config.ResourceView;
import com.springaimcpservercommon.persistence.config.ReviewDecision;
import com.springaimcpservercommon.persistence.config.ReviewOutcome;
import com.springaimcpservercommon.persistence.config.RevisionView;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemCode;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resource lifecycle admin API (LLD-08 §2, LLD-09): create a resource with its first draft, edit drafts, submit
 * for review, review, publish, suspend/resume, deprecate and retire.
 *
 * <p>Conventions (LLD-08): RFC 9457 problems (validation errors list the failing fields), an {@code ETag}
 * (row version) on every revision and {@code If-Match} on edit and submit (428 when missing, 412 when stale),
 * paged lists, and an audit event after every committed change.
 *
 * <p>Authorization per kind: authoring needs the kind's author permission, publishing and retiring need its
 * publish permission, reviewing needs {@link Permission#REVIEW_APPROVE}; kinds without a dedicated permission
 * ({@code ROW_POLICY}, {@code POLICY_OVERLAY}, {@code MCP_SERVER}) need {@link Permission#WORKSPACE_ADMIN}.
 * Reading needs {@link Permission#CATALOG_READ}. Authoring calls need the {@link Capability#AUTHORING}
 * capability and publishing calls {@link Capability#CONFIG_CHANGES_UI}; both are off in production unless
 * overridden (LLD-12 §2.2). Suspend and resume stay available in every environment because they are
 * operational safety actions.
 *
 * <p>Separation of duties: the store refuses a review by the revision's author (403). Every request is scoped
 * to the workspace in the path; a resource of another workspace answers 404.
 *
 * <p>Not a {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/workspaces/{workspaceId}/resources")
public final class ResourceAdminController {

    static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,62}[a-z0-9]");
    static final int MAX_SUMMARY = 500;
    static final int MAX_REASON = 500;
    static final int MAX_COMMENT = 1000;

    /**
     * Create request.
     *
     * @param kind          ENDPOINT, QUERY, AGENT, TOOL_BINDING, ROW_POLICY, POLICY_OVERLAY or MCP_SERVER
     * @param slug          slug unique per workspace and kind, 3..64 lowercase characters
     * @param specJson      spec JSON object (revision 1), at most 256000 characters, no credentials
     * @param changeSummary optional human summary, up to 500
     */
    public record CreateResourceRequest(@Nullable String kind, @Nullable String slug, @Nullable String specJson,
                                        @Nullable String changeSummary) {}

    /**
     * New draft on top of an existing resource.
     *
     * @param specJson          new spec JSON object
     * @param changeSummary     optional summary
     * @param basedOnRevisionId revision this draft derives from; {@code null} for a fresh draft
     */
    public record CreateDraftRequest(@Nullable String specJson, @Nullable String changeSummary,
                                     @Nullable UUID basedOnRevisionId) {}

    /**
     * Edit of a draft in place.
     *
     * @param specJson      new spec JSON object
     * @param changeSummary optional summary
     */
    public record UpdateDraftRequest(@Nullable String specJson, @Nullable String changeSummary) {}

    /**
     * Submit request.
     *
     * @param riskScore optional risk score 0..100 computed by the UI or GitOps tooling
     */
    public record SubmitRequest(@Nullable Integer riskScore) {}

    /**
     * Review decision comment.
     *
     * @param comment mandatory for reject and request-changes, optional for approve (max 1000)
     */
    public record ReviewRequest(@Nullable String comment) {}

    /**
     * Reason for a lifecycle change.
     *
     * @param reason audit reason (mandatory for suspend; max 500)
     */
    public record ReasonRequest(@Nullable String reason) {}

    /**
     * A page of resources.
     *
     * @param items   resources ordered by kind and slug
     * @param limit   page size
     * @param offset  page offset
     * @param hasMore whether another page exists
     */
    public record ResourcePage(List<ResourceView> items, int limit, int offset, boolean hasMore) {}

    /**
     * A page of revisions.
     *
     * @param items   revisions, newest first
     * @param limit   page size
     * @param offset  page offset
     * @param hasMore whether another page exists
     */
    public record RevisionPage(List<RevisionView> items, int limit, int offset, boolean hasMore) {}

    /**
     * A resource with its live revision.
     *
     * @param resource     resource metadata
     * @param liveRevision the PUBLISHED or DEPRECATED revision, or {@code null} if none
     */
    public record ResourceDetail(ResourceView resource, @Nullable RevisionView liveRevision) {}

    private final ConfigStore configStore;
    private final AdminAudit audit;
    private final AdminApi api;
    private final int requiredApprovals;

    private final Runnable generationPublished;

    ResourceAdminController(ConfigStore configStore, AdminAudit audit, AdminApi api, int requiredApprovals) {
        this(configStore, audit, api, requiredApprovals, () -> { });
    }

    /**
     * @param generationPublished called after a new generation was published by this node, so its snapshot caches
     *                            load it at once instead of at the next poll (other nodes: within the poll interval)
     */
    ResourceAdminController(ConfigStore configStore, AdminAudit audit, AdminApi api, int requiredApprovals,
                            Runnable generationPublished) {
        this.generationPublished = Objects.requireNonNull(generationPublished, "generationPublished");
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
        if (requiredApprovals < 1) {
            throw new IllegalArgumentException("requiredApprovals must be >= 1");
        }
        this.requiredApprovals = requiredApprovals;
    }

    // ─── Reads ───────────────────────────────────────────────────────────────

    /**
     * Lists the resources of the workspace.
     *
     * @param workspaceId workspace
     * @param kind        optional kind filter (case-insensitive)
     * @param limit       page size (1..200, default 50)
     * @param offset      page offset
     * @param request     current request
     * @return 200 with a page; 400 for an unknown kind
     */
    @GetMapping
    public ResponseEntity<?> listResources(@PathVariable UUID workspaceId,
                                           @RequestParam(required = false) @Nullable String kind,
                                           @RequestParam(required = false) @Nullable Integer limit,
                                           @RequestParam(required = false) @Nullable Integer offset,
                                           HttpServletRequest request) {
        var gate = readGate(workspaceId, request);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        ResourceKind filter = null;
        if (kind != null && !kind.isBlank()) {
            filter = kind(errors, "kind", kind);
        }
        var page = AdminApi.page(limit, offset);
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        ResourceKind wanted = filter;
        List<ResourceView> all = configStore.resources(workspaceId).stream()
                .filter(r -> wanted == null || r.kind() == wanted).toList();
        int from = Math.min(page.offset(), all.size());
        int to = Math.min(from + page.limit(), all.size());
        return ResponseEntity.ok(new ResourcePage(all.subList(from, to), page.limit(), page.offset(), to < all.size()));
    }

    /**
     * Returns a resource with its live revision.
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param request     current request
     * @return 200; 404 when unknown or in another workspace
     */
    @GetMapping("/{resourceId}")
    public ResponseEntity<?> getResource(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                         HttpServletRequest request) {
        var gate = readGate(workspaceId, request);
        if (!gate.open()) {
            return gate.denied();
        }
        ResourceView resource = resource(workspaceId, resourceId);
        if (resource == null) {
            return notFound("Resource", request);
        }
        return ResponseEntity.ok(new ResourceDetail(resource, configStore.liveRevision(resourceId).orElse(null)));
    }

    /**
     * Lists the revisions of a resource, newest first.
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param limit       page size (1..200, default 50)
     * @param offset      page offset
     * @param request     current request
     * @return 200 with a page
     */
    @GetMapping("/{resourceId}/revisions")
    public ResponseEntity<?> listRevisions(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                           @RequestParam(required = false) @Nullable Integer limit,
                                           @RequestParam(required = false) @Nullable Integer offset,
                                           HttpServletRequest request) {
        var gate = readGate(workspaceId, request);
        if (!gate.open()) {
            return gate.denied();
        }
        if (resource(workspaceId, resourceId) == null) {
            return notFound("Resource", request);
        }
        var page = AdminApi.page(limit, offset);
        List<RevisionView> all = configStore.revisions(resourceId);
        int from = Math.min(page.offset(), all.size());
        int to = Math.min(from + page.limit(), all.size());
        return ResponseEntity.ok(new RevisionPage(all.subList(from, to), page.limit(), page.offset(), to < all.size()));
    }

    /**
     * Returns one revision with an {@code ETag} (row version) for later {@code If-Match} calls.
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param revisionId  revision
     * @param request     current request
     * @return 200 with the revision; 404 when it is not a revision of this resource
     */
    @GetMapping("/{resourceId}/revisions/{revisionId}")
    public ResponseEntity<?> getRevision(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                         @PathVariable UUID revisionId, HttpServletRequest request) {
        var gate = readGate(workspaceId, request);
        if (!gate.open()) {
            return gate.denied();
        }
        if (resource(workspaceId, resourceId) == null) {
            return notFound("Resource", request);
        }
        RevisionView revision = revision(resourceId, revisionId);
        return revision == null ? notFound("Revision", request) : etag(HttpStatus.OK, revision);
    }

    // ─── Authoring ───────────────────────────────────────────────────────────

    /**
     * Creates a resource with its first DRAFT revision.
     *
     * @param workspaceId workspace
     * @param body        kind, slug and spec
     * @param request     current request
     * @return 201 with the draft; 400 with field errors; 409 when the slug is taken
     */
    @PostMapping
    public ResponseEntity<?> createResource(@PathVariable UUID workspaceId, @RequestBody CreateResourceRequest body,
                                            HttpServletRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        ResourceKind kind = kind(errors, "kind", body.kind());
        var gate = kind == null ? api.authenticated(request)
                : api.gate(request, authorPermission(kind), workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        ResponseEntity<String> disabled = api.capabilityDenied(Capability.AUTHORING, request);
        if (disabled != null) {
            return disabled;
        }
        String slug = AdminApi.text(errors, "slug", body.slug(), true, 64);
        if (slug != null && !SLUG.matcher(slug).matches()) {
            errors.add(new FieldViolation("slug", "must match [a-z][a-z0-9-]{1,62}[a-z0-9]"));
        }
        String spec = ResourceSpecs.validate(body.specJson(), "specJson", errors);
        String summary = AdminApi.text(errors, "changeSummary", body.changeSummary(), false, MAX_SUMMARY);
        if (!errors.isEmpty() || kind == null) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        RevisionView draft = configStore.createResource(workspaceId, kind, Objects.requireNonNull(slug),
                new DraftContent(Objects.requireNonNull(spec), 1, summary, null), caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "RESOURCE_CREATED", workspaceId, "resource",
                draft.resourceId().toString(), null, null, Map.of("kind", kind.name(), "slug", slug));
        return etag(HttpStatus.CREATED, draft);
    }

    /**
     * Creates a new DRAFT revision for an existing resource.
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param body        spec and optional base revision
     * @param request     current request
     * @return 201 with the draft; 400/404/409 problems
     */
    @PostMapping("/{resourceId}/revisions")
    public ResponseEntity<?> createDraft(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                         @RequestBody CreateDraftRequest body, HttpServletRequest request) {
        ResourceView resource = resource(workspaceId, resourceId);
        if (resource == null) {
            var authenticated = api.authenticated(request);
            return authenticated.open() ? notFound("Resource", request) : authenticated.denied();
        }
        var gate = api.gate(request, authorPermission(resource.kind()), workspaceId);
        if (!gate.open()) {
            return gate.denied();
        }
        ResponseEntity<String> disabled = api.capabilityDenied(Capability.AUTHORING, request);
        if (disabled != null) {
            return disabled;
        }
        List<FieldViolation> errors = new ArrayList<>();
        String spec = ResourceSpecs.validate(body.specJson(), "specJson", errors);
        String summary = AdminApi.text(errors, "changeSummary", body.changeSummary(), false, MAX_SUMMARY);
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        RevisionView draft = configStore.createDraft(resourceId, body.basedOnRevisionId(),
                new DraftContent(Objects.requireNonNull(spec), 1, summary, null), caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "REVISION_DRAFT_CREATED", workspaceId,
                "revision", draft.id().toString(), null, null, Map.of("resourceId", resourceId.toString()));
        return etag(HttpStatus.CREATED, draft);
    }

    /**
     * Edits a DRAFT in place. Requires {@code If-Match} with the row version last read.
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param revisionId  draft revision
     * @param ifMatch     row version
     * @param body        new spec and summary
     * @param request     current request
     * @return 200 with the draft; 412 when stale; 428 when {@code If-Match} is missing; 409 when not a draft
     */
    @PutMapping("/{resourceId}/revisions/{revisionId}")
    public ResponseEntity<?> updateDraft(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                         @PathVariable UUID revisionId,
                                         @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
                                         @RequestBody UpdateDraftRequest body, HttpServletRequest request) {
        return withResource(workspaceId, resourceId, request, resource -> {
            var gate = api.gate(request, authorPermission(resource.kind()), workspaceId);
            if (!gate.open()) {
                return gate.denied();
            }
            ResponseEntity<String> disabled = api.capabilityDenied(Capability.AUTHORING, request);
            if (disabled != null) {
                return disabled;
            }
            Long version = AdminApi.ifMatch(ifMatch);
            if (version == null) {
                return preconditionRequired(request);
            }
            if (revision(resourceId, revisionId) == null) {
                return notFound("Revision", request);
            }
            List<FieldViolation> errors = new ArrayList<>();
            String spec = ResourceSpecs.validate(body.specJson(), "specJson", errors);
            String summary = AdminApi.text(errors, "changeSummary", body.changeSummary(), false, MAX_SUMMARY);
            if (!errors.isEmpty()) {
                return AdminApi.validation(request, errors);
            }
            DaiPrincipal caller = gate.caller();
            RevisionView updated = configStore.updateDraft(revisionId, version, caller.principalId(),
                    new DraftContent(Objects.requireNonNull(spec), 1, summary, null));
            audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "REVISION_DRAFT_UPDATED", workspaceId,
                    "revision", revisionId.toString(), null, null, Map.of("resourceId", resourceId.toString()));
            return etag(HttpStatus.OK, updated);
        });
    }

    /**
     * Submits a draft for review; its content becomes immutable. Requires {@code If-Match}.
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param revisionId  draft revision
     * @param ifMatch     row version
     * @param body        optional risk score
     * @param request     current request
     * @return 200 with the revision in IN_REVIEW
     */
    @PostMapping("/{resourceId:[^:]+}/revisions/{revisionId:[^:]+}:submit")
    public ResponseEntity<?> submit(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                    @PathVariable UUID revisionId,
                                    @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
                                    @RequestBody(required = false) @Nullable SubmitRequest body,
                                    HttpServletRequest request) {
        return withResource(workspaceId, resourceId, request, resource -> {
            var gate = api.gate(request, authorPermission(resource.kind()), workspaceId);
            if (!gate.open()) {
                return gate.denied();
            }
            ResponseEntity<String> disabled = api.capabilityDenied(Capability.AUTHORING, request);
            if (disabled != null) {
                return disabled;
            }
            Long version = AdminApi.ifMatch(ifMatch);
            if (version == null) {
                return preconditionRequired(request);
            }
            Integer risk = body == null ? null : body.riskScore();
            if (risk != null && (risk < 0 || risk > 100)) {
                return AdminApi.validation(request, List.of(new FieldViolation("riskScore", "must be between 0 and 100")));
            }
            if (revision(resourceId, revisionId) == null) {
                return notFound("Revision", request);
            }
            RevisionView submitted = configStore.submit(revisionId, version, risk);
            audit.record(gate.caller(), AuditCategory.ADMIN, AuditPlane.CONTROL, "REVISION_SUBMITTED", workspaceId,
                    "revision", revisionId.toString(), null, null, Map.of("resourceId", resourceId.toString()));
            return etag(HttpStatus.OK, submitted);
        });
    }

    // ─── Review ──────────────────────────────────────────────────────────────

    /**
     * Approves a revision in review. The revision becomes APPROVED once the required number of distinct
     * reviewers approved; the author cannot review their own revision (403).
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param revisionId  revision in review
     * @param body        optional comment
     * @param request     current request
     * @return 200 with the review outcome
     */
    @PostMapping("/{resourceId:[^:]+}/revisions/{revisionId:[^:]+}:approve")
    public ResponseEntity<?> approve(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                     @PathVariable UUID revisionId,
                                     @RequestBody(required = false) @Nullable ReviewRequest body,
                                     HttpServletRequest request) {
        return review(workspaceId, resourceId, revisionId, body, request, ReviewDecision.APPROVED, "REVISION_APPROVED");
    }

    /**
     * Rejects a revision in review (comment mandatory).
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param revisionId  revision in review
     * @param body        comment
     * @param request     current request
     * @return 200 with the review outcome
     */
    @PostMapping("/{resourceId:[^:]+}/revisions/{revisionId:[^:]+}:reject")
    public ResponseEntity<?> reject(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                    @PathVariable UUID revisionId, @RequestBody @Nullable ReviewRequest body,
                                    HttpServletRequest request) {
        return review(workspaceId, resourceId, revisionId, body, request, ReviewDecision.REJECTED, "REVISION_REJECTED");
    }

    /**
     * Sends a revision back to the author (comment mandatory).
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param revisionId  revision in review
     * @param body        comment
     * @param request     current request
     * @return 200 with the review outcome
     */
    @PostMapping("/{resourceId:[^:]+}/revisions/{revisionId:[^:]+}:request-changes")
    public ResponseEntity<?> requestChanges(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                            @PathVariable UUID revisionId,
                                            @RequestBody @Nullable ReviewRequest body, HttpServletRequest request) {
        return review(workspaceId, resourceId, revisionId, body, request, ReviewDecision.CHANGES_REQUESTED,
                "REVISION_CHANGES_REQUESTED");
    }

    private ResponseEntity<?> review(UUID workspaceId, UUID resourceId, UUID revisionId, @Nullable ReviewRequest body,
                                     HttpServletRequest request, ReviewDecision decision, String action) {
        return withResource(workspaceId, resourceId, request, resource -> {
            var gate = api.gate(request, Permission.REVIEW_APPROVE, workspaceId);
            if (!gate.open()) {
                return gate.denied();
            }
            ResponseEntity<String> disabled = api.capabilityDenied(Capability.CONFIG_CHANGES_UI, request);
            if (disabled != null) {
                return disabled;
            }
            List<FieldViolation> errors = new ArrayList<>();
            String comment = AdminApi.text(errors, "comment", body == null ? null : body.comment(),
                    decision != ReviewDecision.APPROVED, MAX_COMMENT);
            if (!errors.isEmpty()) {
                return AdminApi.validation(request, errors);
            }
            if (revision(resourceId, revisionId) == null) {
                return notFound("Revision", request);
            }
            DaiPrincipal caller = gate.caller();
            ReviewOutcome outcome = configStore.review(revisionId, caller.principalId(), decision, comment,
                    requiredApprovals);
            audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, action, workspaceId, "revision",
                    revisionId.toString(), null, comment,
                    Map.of("resourceId", resourceId.toString(), "approvals", outcome.approvals(),
                            "state", outcome.revision().state().name()));
            return etag(HttpStatus.OK, outcome.revision());
        });
    }

    // ─── Publish and operate ─────────────────────────────────────────────────

    /**
     * Publishes an APPROVED revision, creating a new snapshot generation (LLD-09 §2.4). A publish that
     * would leave a live revision depending on a resource that is not live answers 409 with the findings.
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param revisionId  revision (must be APPROVED)
     * @param body        optional audit reason
     * @param request     current request
     * @return 200 with the publish result
     */
    @PostMapping("/{resourceId:[^:]+}/revisions/{revisionId:[^:]+}:publish")
    public ResponseEntity<?> publishRevision(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                             @PathVariable UUID revisionId,
                                             @RequestBody(required = false) @Nullable ReasonRequest body,
                                             HttpServletRequest request) {
        return withResource(workspaceId, resourceId, request, resource -> {
            var gate = api.gate(request, publishPermission(resource.kind()), workspaceId);
            if (!gate.open()) {
                return gate.denied();
            }
            ResponseEntity<String> disabled = api.capabilityDenied(Capability.CONFIG_CHANGES_UI, request);
            if (disabled != null) {
                return disabled;
            }
            List<FieldViolation> errors = new ArrayList<>();
            String reason = AdminApi.text(errors, "reason", body == null ? null : body.reason(), false, MAX_REASON);
            if (!errors.isEmpty()) {
                return AdminApi.validation(request, errors);
            }
            if (revision(resourceId, revisionId) == null) {
                return notFound("Revision", request);
            }
            DaiPrincipal caller = gate.caller();
            PublishResult result = configStore.publish(revisionId, caller.principalId(), reason);
            recordGeneration(caller, "REVISION_PUBLISHED", workspaceId, "revision", revisionId, reason, result);
            generationPublished.run();
            return ResponseEntity.ok(result);
        });
    }

    /**
     * Suspends a published resource, removing it from the live set (LLD-09 §2.6). Available in every
     * environment.
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param body        reason (mandatory)
     * @param request     current request
     * @return 200 with the publish result
     */
    @PostMapping("/{resourceId:[^:]+}:suspend")
    public ResponseEntity<?> suspend(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                     @RequestBody @Nullable ReasonRequest body, HttpServletRequest request) {
        return withResource(workspaceId, resourceId, request, resource -> {
            var gate = api.gate(request, publishPermission(resource.kind()), workspaceId);
            if (!gate.open()) {
                return gate.denied();
            }
            List<FieldViolation> errors = new ArrayList<>();
            String reason = AdminApi.text(errors, "reason", body == null ? null : body.reason(), true, MAX_REASON);
            if (!errors.isEmpty()) {
                return AdminApi.validation(request, errors);
            }
            DaiPrincipal caller = gate.caller();
            PublishResult result = configStore.suspend(resourceId, Objects.requireNonNull(reason),
                    caller.principalId());
            recordGeneration(caller, "RESOURCE_SUSPENDED", workspaceId, "resource", resourceId, reason, result);
            generationPublished.run();
            return ResponseEntity.ok(result);
        });
    }

    /**
     * Resumes a suspended resource, restoring it to the live set. Available in every environment.
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param body        optional reason
     * @param request     current request
     * @return 200 with the publish result
     */
    @PostMapping("/{resourceId:[^:]+}:resume")
    public ResponseEntity<?> resume(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                    @RequestBody(required = false) @Nullable ReasonRequest body,
                                    HttpServletRequest request) {
        return withResource(workspaceId, resourceId, request, resource -> {
            var gate = api.gate(request, publishPermission(resource.kind()), workspaceId);
            if (!gate.open()) {
                return gate.denied();
            }
            List<FieldViolation> errors = new ArrayList<>();
            String reason = AdminApi.text(errors, "reason", body == null ? null : body.reason(), false, MAX_REASON);
            if (!errors.isEmpty()) {
                return AdminApi.validation(request, errors);
            }
            DaiPrincipal caller = gate.caller();
            PublishResult result = configStore.resume(resourceId, caller.principalId(), reason);
            recordGeneration(caller, "RESOURCE_RESUMED", workspaceId, "resource", resourceId, reason, result);
            generationPublished.run();
            return ResponseEntity.ok(result);
        });
    }

    /**
     * Marks the live revision DEPRECATED (still served, flagged for removal).
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param body        optional reason
     * @param request     current request
     * @return 200 with the publish result; 409 when there is no live revision
     */
    @PostMapping("/{resourceId:[^:]+}:deprecate")
    public ResponseEntity<?> deprecate(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                       @RequestBody(required = false) @Nullable ReasonRequest body,
                                       HttpServletRequest request) {
        return retireOrDeprecate(workspaceId, resourceId, body, request, false);
    }

    /**
     * Retires the resource permanently (removed from the live set, cannot be published again).
     *
     * @param workspaceId workspace
     * @param resourceId  resource
     * @param body        optional reason
     * @param request     current request
     * @return 200 with the publish result
     */
    @PostMapping("/{resourceId:[^:]+}:retire")
    public ResponseEntity<?> retire(@PathVariable UUID workspaceId, @PathVariable UUID resourceId,
                                    @RequestBody(required = false) @Nullable ReasonRequest body,
                                    HttpServletRequest request) {
        return retireOrDeprecate(workspaceId, resourceId, body, request, true);
    }

    private ResponseEntity<?> retireOrDeprecate(UUID workspaceId, UUID resourceId, @Nullable ReasonRequest body,
                                                HttpServletRequest request, boolean retire) {
        return withResource(workspaceId, resourceId, request, resource -> {
            var gate = api.gate(request, publishPermission(resource.kind()), workspaceId);
            if (!gate.open()) {
                return gate.denied();
            }
            ResponseEntity<String> disabled = api.capabilityDenied(Capability.CONFIG_CHANGES_UI, request);
            if (disabled != null) {
                return disabled;
            }
            List<FieldViolation> errors = new ArrayList<>();
            String reason = AdminApi.text(errors, "reason", body == null ? null : body.reason(), false, MAX_REASON);
            if (!errors.isEmpty()) {
                return AdminApi.validation(request, errors);
            }
            DaiPrincipal caller = gate.caller();
            PublishResult result = retire
                    ? configStore.retire(resourceId, caller.principalId(), reason)
                    : configStore.deprecate(resourceId, caller.principalId(), reason);
            recordGeneration(caller, retire ? "RESOURCE_RETIRED" : "RESOURCE_DEPRECATED", workspaceId, "resource",
                    resourceId, reason, result);
            generationPublished.run();
            return ResponseEntity.ok(result);
        });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Maps a kind to the permission that allows authoring it. */
    static Permission authorPermission(ResourceKind kind) {
        return switch (kind) {
            case ENDPOINT -> Permission.ENDPOINT_AUTHOR;
            case QUERY -> Permission.QUERY_AUTHOR;
            case AGENT -> Permission.AGENT_AUTHOR;
            case TOOL_BINDING -> Permission.TOOL_AUTHOR;
            case ROW_POLICY, POLICY_OVERLAY, MCP_SERVER -> Permission.WORKSPACE_ADMIN;
        };
    }

    /** Maps a kind to the permission that allows publishing, suspending and retiring it. */
    static Permission publishPermission(ResourceKind kind) {
        return switch (kind) {
            case ENDPOINT -> Permission.ENDPOINT_PUBLISH;
            case QUERY -> Permission.QUERY_PUBLISH;
            case AGENT -> Permission.AGENT_PUBLISH;
            case TOOL_BINDING -> Permission.TOOL_PUBLISH;
            case ROW_POLICY, POLICY_OVERLAY, MCP_SERVER -> Permission.WORKSPACE_ADMIN;
        };
    }

    private AdminApi.Gate readGate(UUID workspaceId, HttpServletRequest request) {
        return api.gateAny(request, workspaceId, Permission.CATALOG_READ, Permission.WORKSPACE_ADMIN,
                Permission.AUDIT_READ);
    }

    private @Nullable ResourceView resource(UUID workspaceId, UUID resourceId) {
        Optional<ResourceView> found = configStore.findResource(resourceId);
        return found.filter(r -> r.workspaceId().equals(workspaceId)).orElse(null);
    }

    private @Nullable RevisionView revision(UUID resourceId, UUID revisionId) {
        return configStore.findRevision(revisionId).filter(r -> r.resourceId().equals(resourceId)).orElse(null);
    }

    /** Resolves the resource (404 otherwise, after authentication) and runs the handler with it. */
    private ResponseEntity<?> withResource(UUID workspaceId, UUID resourceId, HttpServletRequest request,
                                           java.util.function.Function<ResourceView, ResponseEntity<?>> handler) {
        var authenticated = api.authenticated(request);
        if (!authenticated.open()) {
            return authenticated.denied();
        }
        ResourceView resource = resource(workspaceId, resourceId);
        return resource == null ? notFound("Resource", request) : handler.apply(resource);
    }

    private void recordGeneration(DaiPrincipal caller, String action, UUID workspaceId, String resourceType, UUID id,
                                  @Nullable String reason, PublishResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("generation", result.generation());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, action, workspaceId, resourceType,
                id.toString(), null, reason, details);
    }

    private static @Nullable ResourceKind kind(List<FieldViolation> errors, String field, @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            errors.add(new FieldViolation(field, "is required"));
            return null;
        }
        try {
            return ResourceKind.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new FieldViolation(field, "must be ENDPOINT, QUERY, AGENT, TOOL_BINDING, ROW_POLICY, "
                    + "POLICY_OVERLAY or MCP_SERVER"));
            return null;
        }
    }

    private static ResponseEntity<String> notFound(String what, HttpServletRequest request) {
        return AdminApi.problem(ProblemCode.NOT_FOUND, what + " not found", null, request);
    }

    private static ResponseEntity<String> preconditionRequired(HttpServletRequest request) {
        return AdminApi.problem(ProblemCode.PRECONDITION_REQUIRED, "If-Match required",
                "Send the row version you last read in If-Match.", request);
    }

    private static ResponseEntity<RevisionView> etag(HttpStatus status, RevisionView revision) {
        return ResponseEntity.status(status).eTag("\"" + revision.rowVersion() + "\"").body(revision);
    }
}
