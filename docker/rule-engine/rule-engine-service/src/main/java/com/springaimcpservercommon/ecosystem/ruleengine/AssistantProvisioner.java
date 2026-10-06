package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.AssistantInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes the AI assistant available to a tenant the first time one of its users opens it, through the library's own admin
 * API and lifecycle (ADR-0027), not by writing its tables:
 * <ol>
 *   <li>a <b>workspace</b> for the tenant (its {@code tenant_id} is the token's tenant), so conversations and grants
 *       belong to one tenant;</li>
 *   <li>the read-only <b>tool bindings</b> over {@link AssistantTools} and the <b>agent</b> that uses them, each created
 *       by the author identity, approved by the other one and published (separation of duties applies to the service too);</li>
 *   <li>for the signed-in <b>user</b>: workspace membership and the grants {@code agent:invoke} and {@code tool:invoke}
 *       (default deny: nobody can chat before this, and only authenticated users of the tenant ever get here).</li>
 * </ol>
 * The user's own bearer token is used to let the library register them; everything else uses tokens the service signs
 * for its two provisioning identities ({@link ProvisioningTokens}). Provisioning failures are reported to the caller as
 * "assistant not available" and logged without tokens: the rest of the console keeps working (fail the feature, not the
 * host). All steps are idempotent, so a replica that finds a half-provisioned tenant completes it.
 */
@Service
class AssistantProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantProvisioner.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ADMIN = "/dynamic-ai/admin/api/v1";

    /** What the agent is told. Short and strict: it can read the setup and check CEL, nothing else. */
    static final String SYSTEM_PROMPT = """
            You are the rule assistant of a CEL business-rule console. You help the signed-in user understand and author \
            rules and rule groups. Use your tools to look things up: never guess what a rule, group or parameter is. \
            You can list and explain rules, list and explain rule groups, list the parameter library and check whether a \
            CEL boolean expression compiles. You cannot save, change, activate or evaluate anything: when the user wants a \
            change, give them the CEL expression and the steps to make it in the console. CEL rules read parameters as \
            object.attribute and the types are strict (use double(x) to compare an int with a double). Answer briefly. \
            Never ask for or repeat input values; talk about rules, not about customers.""";

    private record ToolSpec(String toolName, String method, String slug, String summary) {
    }

    private static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("list_rules", "listRules", "ra-list-rules", "List and search rules"),
            new ToolSpec("get_rule", "getRule", "ra-get-rule", "Show one rule"),
            new ToolSpec("list_rule_groups", "listRuleGroups", "ra-list-rule-groups", "List and search rule groups"),
            new ToolSpec("get_rule_group", "getRuleGroup", "ra-get-rule-group", "Show one rule group"),
            new ToolSpec("list_library_parameters", "listLibraryParameters", "ra-list-parameters", "List the parameter library"),
            new ToolSpec("check_cel_expression", "checkCelExpression", "ra-check-cel", "Check a CEL expression"));

    private record Resp(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final AssistantProperties props;
    private final ProvisioningTokens tokens;
    private final Environment env;
    private final Map<String, ChatModel> models;
    // HTTP/1.1 only: the JDK client's default h2c upgrade attempt is not needed for calls to ourselves
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final Set<UUID> tenantsReady = ConcurrentHashMap.newKeySet();
    private final Set<String> usersReady = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Object> tenantLocks = new ConcurrentHashMap<>();

    AssistantProvisioner(AssistantProperties props, ProvisioningTokens tokens, Environment env, Map<String, ChatModel> models) {
        this.props = props;
        this.tokens = tokens;
        this.env = env;
        this.models = models;
    }

    /**
     * The state of the assistant for the caller, provisioning what is missing.
     *
     * @param caller        the signed-in user
     * @param authorization the user's own {@code Authorization} header (used once to register them with the library)
     * @return the slug to chat with, or why the assistant is not available
     */
    AssistantInfo prepare(Caller caller, String authorization) {
        if (!props.enabled()) {
            return new AssistantInfo(false, null, null, "The assistant is switched off.");
        }
        if (!hasModel(props.provider())) {
            return new AssistantInfo(false, null, props.provider(),
                    "No chat model named '" + props.provider() + "' is configured on this service.");
        }
        String slug = slug(caller.tenantId());
        try {
            Object lock = tenantLocks.computeIfAbsent(caller.tenantId(), t -> new Object());
            synchronized (lock) {
                if (!tenantsReady.contains(caller.tenantId())) {
                    provisionTenant(caller, slug);
                    tenantsReady.add(caller.tenantId());
                }
                String userKey = caller.tenantId() + ":" + caller.userId();
                if (!usersReady.contains(userKey)) {
                    provisionUser(caller, slug, authorization);
                    usersReady.add(userKey);
                }
            }
            return new AssistantInfo(true, slug, props.provider(), null);
        } catch (RuntimeException e) {
            LOG.warn("The assistant could not be provisioned for a tenant: {}", e.getMessage());
            return new AssistantInfo(false, null, props.provider(), "The assistant is not available right now.");
        }
    }

    /**
     * The workspace and agent slug of a tenant: derived from its id, so replicas agree without coordination. A hash, not a
     * prefix of the id: time-ordered (UUIDv7) tenant ids created close together share their first digits.
     */
    static String slug(UUID tenantId) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(tenantId.toString().getBytes(StandardCharsets.UTF_8));
            return "rule-assistant-" + java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory on every JVM
        }
    }

    private boolean hasModel(String provider) {
        String wanted = normalize(provider);
        return models.keySet().stream().anyMatch(n -> normalize(n.replaceAll("(?i)chatmodel$", "")).equals(wanted));
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ── the tenant ──────────────────────────────────────────────────────────────────────────────────────

    private void provisionTenant(Caller caller, String slug) {
        String author = "Bearer " + tokens.mint(ProvisioningTokens.AUTHOR);
        String approver = "Bearer " + tokens.mint(ProvisioningTokens.APPROVER);
        UUID authorId = principalId(author);
        UUID approverId = principalId(approver);

        UUID workspace = workspace(author, slug, caller);
        String members = ADMIN + "/workspaces/" + workspace + "/members";
        require(call("POST", members, author, Map.of("principalId", authorId, "role", "AUTHOR"), null), 409, "add the author");
        require(call("POST", members, author, Map.of("principalId", approverId, "role", "APPROVER"), null), 409, "add the approver");

        Map<String, UUID> existing = resources(author, workspace);
        List<Map<String, Object>> toolRefs = new ArrayList<>();
        for (ToolSpec t : TOOLS) {
            UUID id = existing.get(t.slug());
            if (id == null) {
                String ref = operationRef(author, t);
                String spec = json(Map.of("toolName", t.toolName(), "source", Map.of("kind", "operation", "ref", ref),
                        "mcpExposed", false));
                id = publish(workspace, "TOOL_BINDING", t.slug(), spec, t.summary(), author, approver);
            }
            toolRefs.add(Map.of("bindingId", id.toString(), "revision", liveRevision(author, workspace, id)));
        }
        if (!existing.containsKey("rule-assistant")) {
            String spec = json(Map.of("displayName", "Rule assistant", "systemPrompt", SYSTEM_PROMPT,
                    "model", Map.of("providerId", props.provider(), "modelName", props.modelName(), "temperature", 0.2),
                    "tools", toolRefs, "memory", Map.of("strategy", "WINDOW", "windowSize", 20),
                    "guardrails", Map.of("maxInputChars", 2000),
                    "limits", Map.of("maxToolCallsPerTurn", 6, "maxTokensPerTurn", 4096, "turnTimeoutSeconds", 60,
                            "maxTurnsPerConversation", 100)));
            publish(workspace, "AGENT", slug, spec, "Rule assistant", author, approver);
        }
        LOG.info("The rule assistant is provisioned for a tenant (workspace {}).", slug);
    }

    private UUID workspace(String author, String slug, Caller caller) {
        UUID found = findWorkspace(author, slug);
        if (found != null) {
            return found;
        }
        Resp created = call("POST", ADMIN + "/workspaces", author,
                Map.of("slug", slug, "name", "Rule assistant — " + caller.tenantName(), "tenantId", caller.tenantId().toString()), null);
        if (created.ok()) {
            return UUID.fromString(created.body().get("id").asString());
        }
        found = findWorkspace(author, slug); // another replica was faster
        if (found == null) {
            throw new IllegalStateException("cannot create the workspace (" + created.status() + ")");
        }
        return found;
    }

    private @Nullable UUID findWorkspace(String author, String slug) {
        Resp list = call("GET", ADMIN + "/workspaces", author, null, null);
        require(list, -1, "list workspaces");
        for (JsonNode w : list.body()) {
            if (slug.equals(w.path("slug").asString())) {
                return UUID.fromString(w.get("id").asString());
            }
        }
        return null;
    }

    /** slug → resource id of the live (ACTIVE) resources of the workspace. */
    private Map<String, UUID> resources(String author, UUID workspace) {
        Resp list = call("GET", ADMIN + "/workspaces/" + workspace + "/resources?limit=200", author, null, null);
        require(list, -1, "list resources");
        Map<String, UUID> out = new LinkedHashMap<>();
        for (JsonNode r : list.body().path("items")) {
            if ("ACTIVE".equals(r.path("status").asString())) {
                out.put(r.path("slug").asString(), UUID.fromString(r.get("id").asString()));
            }
        }
        return out;
    }

    private String operationRef(String author, ToolSpec tool) {
        Resp list = call("GET", ADMIN + "/catalog/operations?limit=100&q=" + tool.method(), author, null, null);
        require(list, -1, "browse the catalog");
        JsonNode items = list.body().isArray() ? list.body() : list.body().path("items");
        for (JsonNode o : items) {
            if (tool.toolName().equals(o.path("toolName").asString())) {
                return o.get("ref").asString();
            }
        }
        throw new IllegalStateException("the catalog scan did not find the tool " + tool.toolName());
    }

    private int liveRevision(String author, UUID workspace, UUID resource) {
        Resp r = call("GET", ADMIN + "/workspaces/" + workspace + "/resources/" + resource, author, null, null);
        require(r, -1, "read a resource");
        JsonNode live = r.body().path("liveRevision");
        if (live.isMissingNode() || live.isNull()) {
            throw new IllegalStateException("resource " + resource + " has no live revision");
        }
        return live.get("revisionNo").asInt();
    }

    /** create → submit (author) → approve (approver) → publish (author); returns the resource id. */
    private UUID publish(UUID workspace, String kind, String slug, String specJson, String summary, String author, String approver) {
        String base = ADMIN + "/workspaces/" + workspace + "/resources";
        Resp created = call("POST", base, author,
                Map.of("kind", kind, "slug", slug, "specJson", specJson, "changeSummary", summary), null);
        require(created, -1, "create " + kind + " " + slug);
        UUID resource = UUID.fromString(created.body().get("resourceId").asString());
        String revision = base + "/" + resource + "/revisions/" + created.body().get("id").asString();
        long version = created.body().path("rowVersion").asLong();
        Resp submitted = call("POST", revision + ":submit", author, Map.of(), version);
        require(submitted, -1, "submit " + slug);
        Resp approved = call("POST", revision + ":approve", approver, Map.of("comment", "provisioned by the service"),
                submitted.body().path("rowVersion").asLong());
        require(approved, -1, "approve " + slug);
        require(call("POST", revision + ":publish", author, Map.of("reason", "rule assistant"),
                approved.body().path("rowVersion").asLong()), -1, "publish " + slug);
        return resource;
    }

    // ── the user ────────────────────────────────────────────────────────────────────────────────────────

    private void provisionUser(Caller caller, String slug, String authorization) {
        String author = "Bearer " + tokens.mint(ProvisioningTokens.AUTHOR);
        UUID workspace = findWorkspace(author, slug);
        if (workspace == null) {
            throw new IllegalStateException("the tenant's workspace disappeared");
        }
        UUID principal = principalId(authorization); // the library registers the user from their own token
        require(call("POST", ADMIN + "/workspaces/" + workspace + "/members", author,
                Map.of("principalId", principal, "role", "CONSUMER"), null), 409, "add the user");
        for (String permission : List.of("agent:invoke", "tool:invoke")) {
            require(call("POST", ADMIN + "/workspaces/" + workspace + "/grants", author,
                    Map.of("principalId", principal, "permission", permission, "targetType", "WORKSPACE"), null), 409,
                    "grant " + permission);
        }
    }

    private UUID principalId(String authorization) {
        Resp me = call("GET", ADMIN + "/me", authorization, null, null);
        require(me, -1, "register a principal");
        return UUID.fromString(me.body().path("principal").path("principalId").asString());
    }

    // ── HTTP to this very service ───────────────────────────────────────────────────────────────────────

    private Resp call(String method, String path, String authorization, @Nullable Object body, @Nullable Long rowVersion) {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port", env.getProperty("server.port", "8092"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", authorization).header("Accept", "application/json");
        if (rowVersion != null) {
            b.header("If-Match", "\"" + rowVersion + "\"");
        }
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        try {
            HttpResponse<String> r = http.send(b.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = r.body() == null || r.body().isBlank() ? JSON.nullNode() : JSON.readTree(r.body());
            return new Resp(r.statusCode(), parsed);
        } catch (IOException e) {
            throw new IllegalStateException("the admin API is not reachable (" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** Fails unless the call succeeded; {@code tolerated} (e.g. 409 "already there") is also accepted. */
    private static void require(Resp r, int tolerated, String what) {
        if (!r.ok() && r.status() != tolerated) {
            throw new IllegalStateException("could not " + what + " (HTTP " + r.status() + ")");
        }
    }

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }
}
