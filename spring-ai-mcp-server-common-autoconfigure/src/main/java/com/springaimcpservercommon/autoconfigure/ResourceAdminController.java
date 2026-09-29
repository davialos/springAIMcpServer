package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.config.ConfigLifecycleException;
import com.springaimcpservercommon.persistence.config.ConfigStore;
import com.springaimcpservercommon.persistence.config.DraftContent;
import com.springaimcpservercommon.persistence.config.PublishResult;
import com.springaimcpservercommon.persistence.config.ResourceKind;
import com.springaimcpservercommon.persistence.config.ResourceView;
import com.springaimcpservercommon.persistence.config.RevisionView;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resource lifecycle endpoints for the admin control plane (LLD-08 §2, LLD-09).
 *
 * <p>Endpoints under {@code /dynamic-ai/admin/api/v1/workspaces/{workspaceId}/resources}:
 * <ul>
 *   <li>{@code GET  /resources?kind=KIND}                    — list resources in workspace</li>
 *   <li>{@code POST /resources}                              — create resource + first draft</li>
 *   <li>{@code GET  /resources/{id}}                        — get resource + live revision</li>
 *   <li>{@code GET  /resources/{id}/revisions}              — list all revisions</li>
 *   <li>{@code POST /resources/{id}/revisions}              — create next draft</li>
 *   <li>{@code POST /resources/{id}/revisions/{revId}:publish} — publish approved revision</li>
 *   <li>{@code POST /resources/{id}:suspend}                — suspend (unpublish) resource</li>
 *   <li>{@code POST /resources/{id}:resume}                 — resume suspended resource</li>
 * </ul>
 *
 * <p>Not a {@code @Component} — registered as a bean by {@link DaiAdminAutoConfiguration}.
 * Spring MVC detects it via the class-level {@link RequestMapping}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/workspaces/{workspaceId}/resources")
public final class ResourceAdminController {

    private static final Logger log = LoggerFactory.getLogger(ResourceAdminController.class);

    /**
     * Request body for creating a resource together with its first draft revision.
     *
     * @param kind        resource kind (matches {@link ResourceKind} name, case-insensitive)
     * @param slug        slug unique per workspace and kind
     * @param specJson    spec JSON object (revision 1 content)
     * @param changeSummary optional human summary
     */
    public record CreateResourceRequest(
            String kind,
            String slug,
            String specJson,
            @Nullable String changeSummary) {}

    /**
     * Request body for creating a new draft revision of an existing resource.
     *
     * @param specJson         new spec JSON object
     * @param changeSummary    optional human summary of the change
     * @param basedOnRevisionId revision this draft derives from; {@code null} for a fresh draft
     */
    public record CreateDraftRequest(
            String specJson,
            @Nullable String changeSummary,
            @Nullable UUID basedOnRevisionId) {}

    /**
     * Request body for publishing a revision.
     *
     * @param reason optional audit reason
     */
    public record PublishRequest(@Nullable String reason) {}

    /**
     * Request body for suspending a resource.
     *
     * @param reason human-readable reason (required by {@link ConfigStore#suspend})
     */
    public record SuspendRequest(String reason) {}

    /**
     * Request body for resuming a suspended resource.
     *
     * @param reason optional audit reason
     */
    public record ResumeRequest(@Nullable String reason) {}

    /**
     * Response combining a resource with its live revision.
     *
     * @param resource      resource metadata
     * @param liveRevision  the PUBLISHED or DEPRECATED revision, or {@code null} if none
     */
    public record ResourceDetail(
            ResourceView resource,
            @Nullable RevisionView liveRevision) {}

    private final ConfigStore configStore;

    /**
     * @param configStore configuration lifecycle store
     */
    public ResourceAdminController(ConfigStore configStore) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
    }

    /**
     * Lists all resources in the workspace, optionally filtered by kind.
     *
     * @param workspaceId workspace
     * @param kind        optional kind filter (case-insensitive {@link ResourceKind} name)
     * @return resource list
     */
    @GetMapping
    public ResponseEntity<List<ResourceView>> listResources(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) @Nullable String kind) {
        List<ResourceView> all = configStore.resources(workspaceId);
        if (kind != null && !kind.isBlank()) {
            ResourceKind parsed;
            try {
                parsed = ResourceKind.valueOf(kind.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
            all = all.stream().filter(r -> r.kind() == parsed).toList();
        }
        return ResponseEntity.ok(all);
    }

    /**
     * Creates a resource together with its first DRAFT revision.
     *
     * @param workspaceId workspace
     * @param request     create request
     * @return 201 Created with the first draft revision, 400 for bad input
     */
    @PostMapping
    public ResponseEntity<RevisionView> createResource(
            @PathVariable UUID workspaceId,
            @RequestBody CreateResourceRequest request) {
        ResourceKind kind;
        try {
            kind = ResourceKind.valueOf(request.kind().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (request.slug() == null || request.slug().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.specJson() == null || request.specJson().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        DraftContent content = new DraftContent(request.specJson(), 1, request.changeSummary(), null);
        UUID authorId = currentPrincipalId();
        try {
            RevisionView revision = configStore.createResource(workspaceId, kind, request.slug(), content, authorId);
            return ResponseEntity.status(HttpStatus.CREATED).body(revision);
        } catch (IllegalArgumentException e) {
            log.debug("createResource rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (ConfigLifecycleException e) {
            log.debug("createResource lifecycle error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * Returns a resource with its live revision (if any).
     *
     * @param workspaceId workspace (used for scope validation)
     * @param resourceId  resource id
     * @return 200 with detail, 404 if not found
     */
    @GetMapping("/{resourceId}")
    public ResponseEntity<ResourceDetail> getResource(
            @PathVariable UUID workspaceId,
            @PathVariable UUID resourceId) {
        Optional<ResourceView> resource = configStore.findResource(resourceId);
        if (resource.isEmpty() || !resource.get().workspaceId().equals(workspaceId)) {
            return ResponseEntity.notFound().build();
        }
        Optional<RevisionView> live = configStore.liveRevision(resourceId);
        return ResponseEntity.ok(new ResourceDetail(resource.get(), live.orElse(null)));
    }

    /**
     * Lists all revisions of a resource (newest first).
     *
     * @param workspaceId workspace (scope check)
     * @param resourceId  resource id
     * @return 200 with revision list, 404 if resource not found in this workspace
     */
    @GetMapping("/{resourceId}/revisions")
    public ResponseEntity<List<RevisionView>> listRevisions(
            @PathVariable UUID workspaceId,
            @PathVariable UUID resourceId) {
        Optional<ResourceView> resource = configStore.findResource(resourceId);
        if (resource.isEmpty() || !resource.get().workspaceId().equals(workspaceId)) {
            return ResponseEntity.notFound().build();
        }
        List<RevisionView> revisions = configStore.revisions(resourceId);
        return ResponseEntity.ok(revisions);
    }

    /**
     * Creates a new DRAFT revision for an existing resource.
     *
     * @param workspaceId workspace (scope check)
     * @param resourceId  resource id
     * @param request     draft content
     * @return 201 Created with the new draft, 404/400/409 on errors
     */
    @PostMapping("/{resourceId}/revisions")
    public ResponseEntity<RevisionView> createDraft(
            @PathVariable UUID workspaceId,
            @PathVariable UUID resourceId,
            @RequestBody CreateDraftRequest request) {
        Optional<ResourceView> resource = configStore.findResource(resourceId);
        if (resource.isEmpty() || !resource.get().workspaceId().equals(workspaceId)) {
            return ResponseEntity.notFound().build();
        }
        if (request.specJson() == null || request.specJson().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        DraftContent content = new DraftContent(request.specJson(), 1, request.changeSummary(), null);
        UUID authorId = currentPrincipalId();
        try {
            RevisionView draft = configStore.createDraft(resourceId, request.basedOnRevisionId(), content, authorId);
            return ResponseEntity.status(HttpStatus.CREATED).body(draft);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.debug("createDraft rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (ConfigLifecycleException e) {
            log.debug("createDraft lifecycle error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * Publishes an APPROVED revision, creating a new snapshot generation (LLD-09 §2.4).
     *
     * @param workspaceId workspace (scope check)
     * @param resourceId  resource id
     * @param revisionId  revision to publish (must be in APPROVED state)
     * @param request     optional publish reason
     * @return 200 with publish result, 404/409 on errors
     */
    @PostMapping("/{resourceId:[^:]+}/revisions/{revisionId:[^:]+}:publish")
    public ResponseEntity<PublishResult> publishRevision(
            @PathVariable UUID workspaceId,
            @PathVariable UUID resourceId,
            @PathVariable UUID revisionId,
            @RequestBody(required = false) @Nullable PublishRequest request) {
        Optional<ResourceView> resource = configStore.findResource(resourceId);
        if (resource.isEmpty() || !resource.get().workspaceId().equals(workspaceId)) {
            return ResponseEntity.notFound().build();
        }
        UUID by = currentPrincipalId();
        String reason = request != null ? request.reason() : null;
        try {
            PublishResult result = configStore.publish(revisionId, by, reason);
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (ConfigLifecycleException e) {
            log.debug("publish lifecycle error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * Suspends a PUBLISHED resource, removing it from the live set (LLD-09 §2.6).
     *
     * @param workspaceId workspace (scope check)
     * @param resourceId  resource to suspend
     * @param request     suspend reason (required)
     * @return 200 with publish result, 404/400/409 on errors
     */
    @PostMapping("/{resourceId:[^:]+}:suspend")
    public ResponseEntity<PublishResult> suspend(
            @PathVariable UUID workspaceId,
            @PathVariable UUID resourceId,
            @RequestBody SuspendRequest request) {
        Optional<ResourceView> resource = configStore.findResource(resourceId);
        if (resource.isEmpty() || !resource.get().workspaceId().equals(workspaceId)) {
            return ResponseEntity.notFound().build();
        }
        if (request.reason() == null || request.reason().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        UUID by = currentPrincipalId();
        try {
            PublishResult result = configStore.suspend(resourceId, request.reason(), by);
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (ConfigLifecycleException e) {
            log.debug("suspend lifecycle error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * Resumes a SUSPENDED resource, restoring it to the live set.
     *
     * @param workspaceId workspace (scope check)
     * @param resourceId  resource to resume
     * @param request     optional resume reason
     * @return 200 with publish result, 404/409 on errors
     */
    @PostMapping("/{resourceId:[^:]+}:resume")
    public ResponseEntity<PublishResult> resume(
            @PathVariable UUID workspaceId,
            @PathVariable UUID resourceId,
            @RequestBody(required = false) @Nullable ResumeRequest request) {
        Optional<ResourceView> resource = configStore.findResource(resourceId);
        if (resource.isEmpty() || !resource.get().workspaceId().equals(workspaceId)) {
            return ResponseEntity.notFound().build();
        }
        UUID by = currentPrincipalId();
        String reason = request != null ? request.reason() : null;
        try {
            PublishResult result = configStore.resume(resourceId, by, reason);
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (ConfigLifecycleException e) {
            log.debug("resume lifecycle error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * Extracts the current principal's UUID from the Spring Security context.
     * If the principal name is a valid UUID, returns it directly; otherwise derives a stable UUID from the name.
     */
    private static UUID currentPrincipalId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return UUID.nameUUIDFromBytes(new byte[0]);
        }
        String name = auth.getName();
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        }
    }
}
