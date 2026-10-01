package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.tool.ArgConstraint;
import com.springaimcpservercommon.ai.tool.ProposalService;
import com.springaimcpservercommon.ai.tool.ResultPolicy;
import com.springaimcpservercommon.ai.tool.ToolBinding;
import com.springaimcpservercommon.ai.tool.ToolSource;
import com.springaimcpservercommon.ai.tool.WriteMode;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.persistence.config.PublishedResource;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Parses the spec JSON of a published {@code TOOL_BINDING} resource into a {@link ToolBinding} (LLD-07 §2).
 *
 * <pre>{@code
 * {
 *   "toolName": "find_orders",                       // ^[a-z][a-z0-9_]{2,63}$
 *   "description": "…",                              // optional override of the catalog description
 *   "source": {"kind": "operation", "ref": "op:com.acme.OrderService#find(java.lang.String)"}
 *          | {"kind": "query", "ref": "<query resource uuid>"}
 *          | {"kind": "agent", "ref": "<agent resource uuid>"}
 *          | {"kind": "mcp", "serverId": "<uuid>", "remoteTool": "name"}
 *          | {"kind": "criteria", "tool": "describe" | "validate" | "execute",   // model-built queries, LLD-05 §12
 *             "entities": ["Order", "entity:com.acme.Customer"],               // optional allow-list; default all
 *             "maxRows": 50},                                                  // optional, default 50
 *                                                     // toolName defaults to describe_data_model,
 *                                                     // check_data_query or run_data_query
 *   "writeMode": "EXECUTE" | "PROPOSE",              // default EXECUTE (a read tool)
 *   "change": "create" | "update" | "delete",        // what a PROPOSE tool does; default update, delete needs an approver
 *   "entityIdArgument": "orderId",                   // the argument holding the changed record's id (update/delete)
 *   "argConstraints": {"customerId": {"kind": "principalAttr", "attr": "customerId"},
 *                      "status": {"kind": "literal", "value": "OPEN"},
 *                      "limit": {"kind": "range", "min": 1, "max": 50}},
 *   "returnDirect": false, "timeoutSeconds": 30, "maxCallsPerTurn": 5,
 *   "result": {"maxChars": 0, "maskSensitive": true},
 *   "mcpExposed": false                              // default false: not offered over MCP
 * }
 * }</pre>
 * A PROPOSE tool must be backed by an operation: a proposal names the host operation to run once a person confirms
 * it (queries only read, agents and remote MCP tools have no write path of ours). Anything invalid throws {@link IllegalArgumentException}; the snapshot cache skips that binding with a warning, so
 * one bad binding never takes the others down (LLD-12).
 */
@NullMarked
final class ToolBindingSpecs {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ToolBindingSpecs() {
    }

    static ToolBinding parse(PublishedResource resource) {
        Object root;
        try {
            root = MAPPER.readerFor(Object.class).readValue(resource.specJson());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("tool binding spec is not valid JSON");
        }
        Map<String, Object> spec = object(root, "spec");
        // criteria tools have well-known default names; every other binding names its tool
        boolean criteriaSource = spec.get("source") instanceof Map<?, ?> src && src.get("kind") instanceof String k
                && k.equalsIgnoreCase("criteria");
        String toolName = spec.get("toolName") == null && criteriaSource ? null
                : string(spec.get("toolName"), "toolName");
        ToolSource source = source(object(spec.get("source"), "source"));
        if (toolName == null) {
            toolName = CriteriaToolCallback.defaultToolName(((ToolSource.CriteriaSource) source).tool());
        }
        WriteMode mode = spec.get("writeMode") == null ? WriteMode.EXECUTE
                : parseEnum(WriteMode.class, spec.get("writeMode"), "writeMode");
        Object description = spec.get("description");
        Map<String, Object> result = spec.get("result") == null ? Map.of() : object(spec.get("result"), "result");
        if (mode == WriteMode.PROPOSE && !(source instanceof ToolSource.OperationSource)) {
            throw new IllegalArgumentException("writeMode PROPOSE needs an operation source");
        }
        return new ToolBinding(resource.resourceId(), resource.revisionNo(), resource.workspaceId(), toolName,
                source,
                description == null ? null : string(description, "description"),
                constraints(spec.get("argConstraints")), mode,
                bool(spec.get("returnDirect"), false),
                Duration.ofSeconds(bounded(spec.get("timeoutSeconds"), 30, 1, 600, "timeoutSeconds")),
                (int) bounded(spec.get("maxCallsPerTurn"), 5, 1, 50, "maxCallsPerTurn"),
                new ResultPolicy((int) bounded(result.get("maxChars"), 0, 0, 10_000_000, "result.maxChars"),
                        bool(result.get("maskSensitive"), true)),
                bool(spec.get("mcpExposed"), false),
                spec.get("change") == null ? null : parseEnum(ProposalService.Change.class, spec.get("change"), "change"),
                spec.get("entityIdArgument") == null ? null : string(spec.get("entityIdArgument"), "entityIdArgument"));
    }

    private static ToolSource source(Map<String, Object> source) {
        String kind = string(source.get("kind"), "source.kind").toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "operation" -> new ToolSource.OperationSource(
                    CatalogElementRef.parse(string(source.get("ref"), "source.ref")));
            case "query" -> new ToolSource.QuerySource(uuid(source.get("ref"), "source.ref"));
            case "agent" -> new ToolSource.AgentSource(uuid(source.get("ref"), "source.ref"));
            case "mcp" -> new ToolSource.McpSource(uuid(source.get("serverId"), "source.serverId"),
                    string(source.get("remoteTool"), "source.remoteTool"));
            case "criteria" -> new ToolSource.CriteriaSource(
                    parseEnum(ToolSource.CriteriaTool.class, source.get("tool"), "source.tool"),
                    strings(source.get("entities"), "source.entities"),
                    (int) bounded(source.get("maxRows"), 50, 1, 1000, "source.maxRows"));
            default -> throw new IllegalArgumentException("unknown source kind");
        };
    }

    private static Map<String, ArgConstraint> constraints(@Nullable Object raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, ArgConstraint> constraints = new LinkedHashMap<>();
        object(raw, "argConstraints").forEach((name, value) -> {
            Map<String, Object> c = object(value, "argConstraints." + name);
            String kind = string(c.get("kind"), "argConstraints." + name + ".kind").toLowerCase(Locale.ROOT);
            constraints.put(name, switch (kind) {
                case "principalattr" -> ArgConstraint.principalAttr(string(c.get("attr"), "attr"));
                case "literal" -> ArgConstraint.literal(c.get("value"));
                case "range" -> ArgConstraint.range(number(c.get("min"), "min"), number(c.get("max"), "max"));
                default -> throw new IllegalArgumentException("unknown constraint kind");
            });
        });
        return constraints;
    }

    // ── value helpers (messages name the field, never the value) ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(@Nullable Object value, String field) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(field + " must be an object");
    }

    private static String string(@Nullable Object value, String field) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException(field + " must be a non-empty string");
    }

    private static java.util.Set<String> strings(@Nullable Object value, String field) {
        if (value == null) {
            return java.util.Set.of();
        }
        if (!(value instanceof java.util.List<?> list)) {
            throw new IllegalArgumentException(field + " must be a list of names");
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Object item : list) {
            out.add(string(item, field));
        }
        return out;
    }

    private static UUID uuid(@Nullable Object value, String field) {
        try {
            return UUID.fromString(string(value, field));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
    }

    private static Number number(@Nullable Object value, String field) {
        if (value instanceof Number n) {
            return n;
        }
        throw new IllegalArgumentException(field + " must be a number");
    }

    private static boolean bool(@Nullable Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("expected a boolean");
    }

    private static long bounded(@Nullable Object value, long fallback, long min, long max, String field) {
        if (value == null) {
            return fallback;
        }
        long v = number(value, field).longValue();
        if (v < min || v > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return v;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, @Nullable Object value, String field) {
        try {
            return Enum.valueOf(type, string(value, field).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " has an unknown value");
        }
    }
}
