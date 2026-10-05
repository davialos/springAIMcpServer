package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.Channel;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.Property;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.Schema;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the project's non-request/response endpoints: WebSocket handlers ({@code addHandler}, {@code @ServerEndpoint}),
 * STOMP ({@code registerStompEndpoints}, {@code @MessageMapping}, {@code @SendTo}), Server-Sent Events (the
 * {@code sse}-tagged endpoints of the catalog) and Kafka ({@code @KafkaListener}, {@code KafkaTemplate.send}). Sample
 * messages are built from the payload types of the handlers.
 */
public final class ChannelScanner {

    private static final Pattern ADD_HANDLER = Pattern.compile(
            "addHandler\\s*\\(\\s*[^,()]+(?:\\([^)]*\\))?\\s*,\\s*\"([^\"]+)\"");
    private static final Pattern SERVER_ENDPOINT = Pattern.compile("@ServerEndpoint\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern ADD_ENDPOINT = Pattern.compile("addEndpoint\\s*\\(([^)]*)\\)");
    private static final Pattern APP_PREFIX = Pattern.compile("setApplicationDestinationPrefixes\\s*\\(([^)]*)\\)");
    private static final Pattern BROKER = Pattern.compile("enable(?:Simple|StompBroker)(?:Broker|Relay)\\s*\\(([^)]*)\\)");
    private static final Pattern SEND_TO = Pattern.compile("(?:@SendTo(?:User)?\\s*\\(\\s*(?:value\\s*=\\s*)?\\{?\\s*|convertAndSend\\s*\\(\\s*)\"([^\"]+)\"");
    private static final Pattern KAFKA_SEND = Pattern.compile("(?:kafkaTemplate|template|kafka)\\w*\\s*\\.\\s*send\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Set<String> NOT_PAYLOADS = Set.of("Principal", "Message", "SimpMessageHeaderAccessor",
            "MessageHeaders", "Headers", "StompHeaderAccessor", "WebSocketSession", "Session", "Acknowledgment",
            "ConsumerRecord", "Consumer", "String");

    private final Consumer<String> log;

    /**
     * Creates a scanner.
     *
     * @param log receives a note per channel kind found
     */
    public ChannelScanner(Consumer<String> log) {
        this.log = log;
    }

    /**
     * Scans a project and adds the SSE endpoints of its catalog.
     *
     * @param projectDir project root, or {@code null} when only a catalog exists
     * @param settings   its Spring settings (placeholders in topic names)
     * @param endpoints  the discovered endpoints (those tagged {@code sse} become SSE channels)
     * @return the channels, possibly none
     */
    public List<Channel> scan(@Nullable Path projectDir, ProjectSettings settings, List<ApiEndpoint> endpoints) {
        List<Channel> out = new ArrayList<>();
        for (ApiEndpoint e : endpoints) {
            if (e.tags().contains("sse") && e.method().name().equals("GET")) {
                out.add(new Channel(Names.jsIdentifier("sse_" + e.id()), Channel.Kind.SSE,
                        e.path().replaceAll("\\{[^}]+}", "1"), List.of(), List.of(), null, e.tags().getFirst()));
            }
        }
        if (projectDir != null) {
            out.addAll(sources(projectDir, settings));
        }
        for (Channel.Kind kind : Channel.Kind.values()) {
            long n = out.stream().filter(c -> c.kind() == kind).count();
            if (n > 0) {
                log.accept("channels: " + n + " " + kind.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }

    private List<Channel> sources(Path projectDir, ProjectSettings settings) {
        List<Path> files = ProjectFiles.javaSources(projectDir);
        StringBuilder text = new StringBuilder();
        for (Path f : files) {
            try {
                text.append(Files.readString(f)).append('\n');
            } catch (IOException e) {
                log.accept("channels: cannot read " + f + ": " + e.getMessage());
            }
        }
        String all = text.toString().replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*//.*$", " ");
        if (!all.contains("WebSocket") && !all.contains("ServerEndpoint") && !all.contains("MessageMapping")
                && !all.contains("KafkaListener") && !all.contains("KafkaTemplate")) {
            return List.of();
        }
        SourceTrees trees = SourceTrees.parse(files);
        TypeMapper mapper = new TypeMapper(trees, settings.snakeCaseJson());
        List<Channel> out = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();

        // plain WebSocket
        for (Matcher m = ADD_HANDLER.matcher(all); m.find(); ) {
            out.add(ws("ws_" + slug(m.group(1)), m.group(1), "WebSocketConfigurer", used));
        }
        for (Matcher m = SERVER_ENDPOINT.matcher(all); m.find(); ) {
            out.add(ws("ws_" + slug(m.group(1)), m.group(1), "@ServerEndpoint", used));
        }

        // STOMP
        if (all.contains("registerStompEndpoints") || all.contains("@MessageMapping")) {
            List<String> endpoints = new ArrayList<>();
            for (Matcher m = ADD_ENDPOINT.matcher(all); m.find(); ) {
                endpoints.addAll(strings(m.group(1)));
            }
            String prefix = "/app";
            Matcher pm = APP_PREFIX.matcher(all);
            if (pm.find() && !strings(pm.group(1)).isEmpty()) {
                prefix = strings(pm.group(1)).getFirst();
            }
            Map<String, JsonNode> mappings = messageMappings(trees, mapper, prefix);
            Set<String> subscribe = new LinkedHashSet<>();
            for (Matcher m = SEND_TO.matcher(all); m.find(); ) {
                subscribe.add(m.group(1));
            }
            if (!endpoints.isEmpty() || !mappings.isEmpty()) {
                if (endpoints.isEmpty()) {
                    endpoints.add("/ws"); // @MessageMapping without a visible registration: Spring's usual name
                }
                for (String endpoint : endpoints) {
                    out.add(new Channel(unique("stomp_" + slug(endpoint), used), Channel.Kind.STOMP, endpoint,
                            List.copyOf(mappings.keySet()), List.copyOf(subscribe),
                            mappings.values().stream().filter(v -> v != null).findFirst().orElse(null), "STOMP"));
                }
            }
        }

        // Kafka
        Map<String, JsonNode> topics = new LinkedHashMap<>();
        for (SourceTrees.TypeDecl decl : trees.types()) {
            for (Tree member : decl.tree().getMembers()) {
                if (member instanceof MethodTree method) {
                    Optional<AnnotationTree> listener = SourceTrees.annotation(method.getModifiers(), "KafkaListener");
                    if (listener.isPresent()) {
                        JsonNode sample = payloadSample(method, mapper);
                        for (String topic : trees.strings(listener.get(), "topics", "value")) {
                            topics.putIfAbsent(settings.resolvePlaceholders(topic), sample);
                        }
                    }
                }
            }
        }
        for (Matcher m = KAFKA_SEND.matcher(all); m.find(); ) {
            topics.putIfAbsent(settings.resolvePlaceholders(m.group(1)), null);
        }
        for (Map.Entry<String, JsonNode> t : topics.entrySet()) {
            out.add(new Channel(unique("kafka_" + slug(t.getKey()), used), Channel.Kind.KAFKA, null,
                    List.of(t.getKey()), List.of(), t.getValue(),
                    settings.properties().getOrDefault("spring.kafka.bootstrap-servers", "localhost:9092")));
        }
        return out;
    }

    private static Channel ws(String id, String path, String source, Set<String> used) {
        return new Channel(unique(id, used), Channel.Kind.WS, path, List.of(), List.of(), null, source);
    }

    private static String unique(String id, Set<String> used) {
        String base = Names.jsIdentifier(id);
        String name = base;
        for (int i = 2; !used.add(name); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    private static String slug(String s) {
        String slug = s.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "root" : slug;
    }

    private static List<String> strings(String args) {
        List<String> out = new ArrayList<>();
        Matcher m = STRING.matcher(args);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** {@code @MessageMapping("/chat")} methods → application destination ({@code /app/chat}) and a sample payload. */
    private Map<String, JsonNode> messageMappings(SourceTrees trees, TypeMapper mapper, String prefix) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (SourceTrees.TypeDecl decl : trees.types()) {
            ClassTree ct = decl.tree();
            String classPrefix = SourceTrees.annotation(ct.getModifiers(), "MessageMapping")
                    .flatMap(a -> trees.string(a, "value")).orElse("");
            for (Tree member : ct.getMembers()) {
                if (member instanceof MethodTree method) {
                    Optional<AnnotationTree> mapping = SourceTrees.annotation(method.getModifiers(), "MessageMapping");
                    if (mapping.isPresent()) {
                        for (String dest : trees.strings(mapping.get(), "value")) {
                            if (dest.contains("{")) {
                                dest = dest.replaceAll("\\{[^}]+}", "1");
                            }
                            out.put((prefix + "/" + classPrefix + "/" + dest).replaceAll("/+", "/"),
                                    payloadSample(method, mapper));
                        }
                    }
                }
            }
        }
        return out;
    }

    private @Nullable JsonNode payloadSample(MethodTree method, TypeMapper mapper) {
        for (VariableTree p : method.getParameters()) {
            String type = TypeMapper.simpleName(p.getType());
            if (NOT_PAYLOADS.contains(type)
                    || SourceTrees.has(p.getModifiers(), "DestinationVariable", "Header", "Headers")) {
                continue;
            }
            return sample(mapper.map(p.getType()), mapper.schemas(), 0);
        }
        return null;
    }

    /** A minimal valid-looking JSON value for a schema: strings "sample", numbers 1, enums their first value. */
    static JsonNode sample(Schema schema, Map<String, ObjectSchema> named, int depth) {
        var f = Documents.json();
        if (schema instanceof RefSchema(String name) && named.get(name) != null) {
            return sample(named.get(name), named, depth);
        }
        if (schema instanceof ScalarSchema s) {
            if (!s.enumValues().isEmpty()) {
                return f.getNodeFactory().stringNode(s.enumValues().getFirst());
            }
            return switch (s.type()) {
                case INTEGER -> f.getNodeFactory().numberNode(1);
                case NUMBER -> f.getNodeFactory().numberNode(1.5);
                case BOOLEAN -> f.getNodeFactory().booleanNode(true);
                default -> f.getNodeFactory().stringNode(s.format() != null && s.format().equals("date-time")
                        ? "2026-01-01T00:00:00Z" : s.format() != null && s.format().equals("date") ? "2026-01-01"
                        : s.format() != null && s.format().equals("uuid") ? "00000000-0000-4000-8000-000000000001"
                        : "sample");
            };
        }
        if (schema instanceof ArraySchema a) {
            ArrayNode arr = f.createArrayNode();
            if (depth < 3) {
                arr.add(sample(a.items(), named, depth + 1));
            }
            return arr;
        }
        ObjectNode o = f.createObjectNode();
        if (schema instanceof ObjectSchema os && depth < 3) {
            for (Map.Entry<String, Property> e : os.properties().entrySet()) {
                o.set(e.getKey(), sample(e.getValue().schema(), named, depth + 1));
            }
        }
        return o;
    }
}
