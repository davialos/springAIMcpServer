package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.knowledge.KnowledgeStore;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.persistence.config.PublishedResource;
import com.springaimcpservercommon.persistence.config.ResourceKind;
import com.springaimcpservercommon.persistence.config.ResourceStatus;
import com.springaimcpservercommon.persistence.config.RevisionState;
import com.springaimcpservercommon.query.validation.QueryValidationException;
import com.springaimcpservercommon.query.validation.QueryValidator;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The default {@link ResourceSpecChecker} (OQ-41): parses the spec the way the runtime will and checks it against the
 * live catalog.
 *
 * <ul>
 *   <li>{@code QUERY}: parsed, then the catalog allow-list ({@link QueryValidator#validateAtPublish}): every path
 *       exposed, enabled and not sensitive, sort/page/parameter rules.</li>
 *   <li>{@code AGENT}: parsed into an agent definition (limits, memory, model, knowledge references), and every
 *       knowledge pack it names must exist.</li>
 *   <li>{@code TOOL_BINDING}: parsed into a tool binding (source, constraints, write mode); a {@code criteria}
 *       binding's entity allow-list must name entities the catalog exposes to AI.</li>
 * </ul>
 * Other kinds are accepted as they are.
 */
final class CatalogSpecChecker implements ResourceSpecChecker {

    private static final QueryValidator QUERIES = new QueryValidator();

    private final ObjectProvider<MetadataRegistry> registry;
    private final ObjectProvider<KnowledgeStore> knowledge;

    CatalogSpecChecker(ObjectProvider<MetadataRegistry> registry, ObjectProvider<KnowledgeStore> knowledge) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    @Override
    public List<String> violations(ResourceKind kind, String specJson) {
        List<String> violations = new ArrayList<>();
        PublishedResource resource = new PublishedResource(Ids.newId(), Ids.newId(), kind, "spec-check",
                ResourceStatus.ACTIVE, null, Ids.newId(), 1, RevisionState.DRAFT, specJson, 1, "sha256:none", null);
        try {
            switch (kind) {
                case QUERY -> checkQuery(resource, violations);
                case AGENT -> checkAgent(resource, violations);
                case TOOL_BINDING -> checkBinding(resource, violations);
                default -> { }
            }
        } catch (QueryValidationException e) {
            violations.addAll(e.violations());
        } catch (RuntimeException e) {
            violations.add("the " + kind.name().toLowerCase(java.util.Locale.ROOT) + " spec is invalid: "
                    + summary(e));
        }
        return violations;
    }

    private void checkQuery(PublishedResource resource, List<String> violations) {
        var definition = DaiPersistenceAutoConfiguration.parseQuery(resource);
        MetadataRegistry live = registry.getIfAvailable();
        if (live != null) {
            QUERIES.validateAtPublish(definition, live.current(), null);
        }
    }

    private void checkBinding(PublishedResource resource, List<String> violations) {
        var binding = ToolBindingSpecs.parse(resource);
        MetadataRegistry live = registry.getIfAvailable();
        if (live == null || !(binding.source() instanceof com.springaimcpservercommon.ai.tool.ToolSource.CriteriaSource
                criteria)) {
            return;
        }
        List<String> exposed = new ArrayList<>();
        java.util.Set<String> known = new java.util.HashSet<>();
        for (var entity : live.current().entities().values()) {
            if (entity.enabled()) {
                exposed.add(entity.name());
                known.add(entity.name().toLowerCase(java.util.Locale.ROOT));
                known.add(entity.descriptor().javaType().toLowerCase(java.util.Locale.ROOT));
                known.add(entity.ref().toString().toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (String name : criteria.entities()) {
            if (!known.contains(name.trim().toLowerCase(java.util.Locale.ROOT))) {
                violations.add("source.entities: '" + name + "' is not an entity exposed to AI (available: "
                        + String.join(", ", exposed.stream().sorted().toList()) + ")");
            }
        }
    }

    private void checkAgent(PublishedResource resource, List<String> violations) {
        var definition = DaiPersistenceAutoConfiguration.parseAgent(resource);
        KnowledgeStore store = knowledge.getIfAvailable();
        for (var ref : definition.knowledge()) {
            if (store != null && !store.packs().contains(ref.pack())) {
                violations.add("knowledge pack '" + ref.pack() + "' does not exist (available: "
                        + String.join(", ", store.packs()) + ")");
            }
        }
    }

    private static @Nullable String summary(RuntimeException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName()
                : message.length() > 300 ? message.substring(0, 300) : message;
    }
}
