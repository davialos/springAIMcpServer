package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.Constraints;
import com.springaimcpservercommon.loadtest.model.EntityTable;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import com.springaimcpservercommon.loadtest.model.Schema;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the endpoints Spring Data REST exports for repository interfaces: {@code GET/POST /{base}/{collection}}
 * and {@code GET/PUT/PATCH/DELETE /{base}/{collection}/{id}}. The collection path is
 * {@code @RepositoryRestResource(path)} or Spring Data REST's default (uncapitalised English plural of the entity
 * name). Repositories with {@code exported = false} or {@code @NoRepositoryBean} are skipped. Bodies are the
 * entity's REST representation: associations are URIs of the target resource.
 */
final class DataRestScanner {

    private static final Set<String> REPOSITORY_BASES = Set.of("Repository", "CrudRepository",
            "ListCrudRepository", "PagingAndSortingRepository", "ListPagingAndSortingRepository", "JpaRepository",
            "MongoRepository", "QuerydslPredicateExecutor");

    private final SourceTrees trees;
    private final TypeMapper mapper;
    private final List<EntityTable> entities;
    private final ProjectSettings settings;

    DataRestScanner(SourceTrees trees, TypeMapper mapper, List<EntityTable> entities, ProjectSettings settings) {
        this.trees = trees;
        this.mapper = mapper;
        this.entities = entities;
        this.settings = settings;
    }

    /** An exported repository: the entity it manages and its collection path. */
    private record Exported(String repository, String entity, String path) {
    }

    List<ApiEndpoint> scan() {
        List<Exported> exported = new ArrayList<>();
        for (SourceTrees.TypeDecl d : trees.types()) {
            ClassTree ct = d.tree();
            if (ct.getKind() != Tree.Kind.INTERFACE || SourceTrees.has(ct.getModifiers(), "NoRepositoryBean")) {
                continue;
            }
            String entity = entityOf(ct, new HashSet<>());
            if (entity == null || trees.type(entity).isEmpty() || !exported(ct)) {
                continue;
            }
            String path = SourceTrees.annotation(ct.getModifiers(), "RepositoryRestResource", "RestResource")
                    .flatMap(a -> trees.string(a, "path")).filter(p -> !p.isBlank())
                    .orElse(defaultPath(entity));
            exported.add(new Exported(d.simpleName(), entity, path));
        }
        String base = settings.dataRestBasePath() == null ? "" : settings.dataRestBasePath();
        Map<String, String> links = new LinkedHashMap<>();
        for (Exported e : exported) {
            links.put(e.entity(), SpringSourceScanner.joinPath(base, e.path()) + "/");
        }
        List<ApiEndpoint> out = new ArrayList<>();
        for (Exported e : exported) {
            String collection = SpringSourceScanner.joinPath(base, e.path());
            String item = collection + "/{id}";
            Schema body = mapper.dataRestSchema(e.entity(), links);
            List<ApiParam> idParam = List.of(new ApiParam("id", ParamLocation.PATH, true, mapper.idSchema(e.entity()),
                    null));
            List<ApiParam> paging = List.of(
                    new ApiParam("page", ParamLocation.QUERY, false, integer(0, null), "0"),
                    new ApiParam("size", ParamLocation.QUERY, false, integer(1, 100), "20"));
            String noun = e.entity();
            String resource = entities.stream().anyMatch(t -> t.entityName().equals(noun)) ? noun : null;
            List<String> tags = List.of(e.repository());
            out.add(endpoint("list" + plural(noun), HttpMethod.GET, collection, paging, null, resource, tags));
            out.add(endpoint("create" + noun, HttpMethod.POST, collection, List.of(), body, resource, tags));
            out.add(endpoint("get" + noun, HttpMethod.GET, item, idParam, null, resource, tags));
            out.add(endpoint("update" + noun, HttpMethod.PUT, item, idParam, body, resource, tags));
            out.add(endpoint("patch" + noun, HttpMethod.PATCH, item, idParam, body, resource, tags));
            out.add(endpoint("delete" + noun, HttpMethod.DELETE, item, idParam, null, resource, tags));
        }
        return out;
    }

    private static ApiEndpoint endpoint(String id, HttpMethod method, String path, List<ApiParam> params,
                                        @Nullable Schema body, @Nullable String resource, List<String> tags) {
        return new ApiEndpoint(id, method, path, "Spring Data REST", tags, params, body, resource,
                Set.of("source", "data-rest"));
    }

    private boolean exported(ClassTree ct) {
        for (String name : List.of("RepositoryRestResource", "RestResource")) {
            Optional<AnnotationTree> a = SourceTrees.annotation(ct.getModifiers(), name);
            if (a.isPresent() && trees.string(a.get(), "exported").map("false"::equals).orElse(false)) {
                return false;
            }
        }
        return true;
    }

    /** The managed entity: first type argument of a repository base interface, through the project's own bases. */
    private @Nullable String entityOf(ClassTree ct, Set<String> seen) {
        if (!seen.add(ct.getSimpleName().toString())) {
            return null;
        }
        for (Tree sup : ct.getImplementsClause()) {
            String raw = TypeMapper.simpleName(sup);
            if (sup instanceof ParameterizedTypeTree pt && !pt.getTypeArguments().isEmpty()) {
                String first = TypeMapper.simpleName(pt.getTypeArguments().getFirst());
                if (REPOSITORY_BASES.contains(raw)) {
                    return first;
                }
                Optional<SourceTrees.TypeDecl> own = trees.type(raw);
                if (own.isPresent() && entityOf(own.get().tree(), seen) != null) {
                    return first; // BaseRepository<T, ID> extends JpaRepository<T, ID>
                }
            }
        }
        return null;
    }

    /** Spring Data REST's default collection path: {@code Company} → {@code companies}. */
    static String defaultPath(String entity) {
        String p = plural(entity);
        return Character.toLowerCase(p.charAt(0)) + p.substring(1);
    }

    static String plural(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.endsWith("y") && word.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch")
                || lower.endsWith("sh")) {
            return word + "es";
        }
        return word + "s";
    }

    private static ScalarSchema integer(long min, @Nullable Integer max) {
        return ScalarSchema.of(ScalarType.INTEGER, "int32").withConstraints(new Constraints(null, null,
                BigDecimal.valueOf(min), max == null ? null : BigDecimal.valueOf(max), null, null));
    }
}
