package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.EntityTable;
import com.springaimcpservercommon.loadtest.model.Names;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the project's JPA entities and their table/column mapping, so request fields can be bound to real rows.
 * Applies Spring Boot's default physical naming (snake case) where no explicit name is given.
 */
final class JpaEntityScanner {

    private final SourceTrees trees;

    JpaEntityScanner(SourceTrees trees) {
        this.trees = trees;
    }

    List<EntityTable> scan() {
        List<EntityTable> out = new ArrayList<>();
        for (SourceTrees.TypeDecl decl : trees.types()) {
            ClassTree ct = decl.tree();
            if (SourceTrees.has(ct.getModifiers(), "Entity")) {
                out.add(entity(ct));
            }
        }
        return out;
    }

    private EntityTable entity(ClassTree ct) {
        String name = ct.getSimpleName().toString();
        Optional<AnnotationTree> table = SourceTrees.annotation(ct.getModifiers(), "Table");
        String tableName = table.flatMap(a -> trees.string(a, "name")).filter(s -> !s.isBlank())
                .orElseGet(() -> SourceTrees.annotation(ct.getModifiers(), "Entity")
                        .flatMap(a -> trees.string(a, "name")).filter(s -> !s.isBlank())
                        .map(Names::snakeCase)
                        .orElse(Names.snakeCase(name)));
        String schema = table.flatMap(a -> trees.string(a, "schema")).filter(s -> !s.isBlank()).orElse(null);
        boolean confidentialEntity = SourceTrees.annotation(ct.getModifiers(), "AiContext")
                .flatMap(a -> trees.string(a, "classification"))
                .map(c -> c.equals("CONFIDENTIAL") || c.equals("RESTRICTED"))
                .orElse(false);

        String idField = null;
        String idColumn = null;
        Map<String, String> columns = new LinkedHashMap<>();
        Map<String, String> refs = new LinkedHashMap<>();
        Map<String, String> joins = new LinkedHashMap<>();
        Set<String> sensitive = new HashSet<>();
        TypeMapper helper = new TypeMapper(trees, false);
        for (VariableTree f : fields(ct, new HashSet<>())) {
            var mods = f.getModifiers();
            if (mods.getFlags().contains(Modifier.STATIC) || mods.getFlags().contains(Modifier.TRANSIENT)
                    || SourceTrees.has(mods, "Transient", "OneToMany", "ManyToMany", "ElementCollection")) {
                continue;
            }
            String field = f.getName().toString();
            if (SourceTrees.has(mods, "ManyToOne", "OneToOne")) {
                refs.put(field, TypeMapper.simpleName(f.getType()));
                joins.put(field, SourceTrees.annotation(mods, "JoinColumn").flatMap(a -> trees.string(a, "name"))
                        .filter(s -> !s.isBlank()).orElse(Names.snakeCase(field) + "_id"));
                continue;
            }
            String column = SourceTrees.annotation(mods, "Column").flatMap(a -> trees.string(a, "name"))
                    .filter(s -> !s.isBlank()).orElse(Names.snakeCase(field));
            columns.put(field, column);
            boolean isId = SourceTrees.has(mods, "Id");
            if (isId && idField == null) {
                idField = field;
                idColumn = column;
            }
            if (helper.sensitive(f, field) || (confidentialEntity && !isId)) {
                sensitive.add(field);
            }
        }
        return new EntityTable(name, schema, tableName, idField, idColumn, columns, refs, joins, sensitive);
    }

    /** Fields of an entity and of its parsed {@code @MappedSuperclass} ancestors. */
    private List<VariableTree> fields(ClassTree ct, Set<String> seen) {
        List<VariableTree> out = new ArrayList<>();
        if (!seen.add(ct.getSimpleName().toString())) {
            return out;
        }
        Tree ext = ct.getExtendsClause();
        if (ext != null) {
            trees.type(TypeMapper.simpleName(ext)).ifPresent(sup -> out.addAll(fields(sup.tree(), seen)));
        }
        for (Tree m : ct.getMembers()) {
            if (m instanceof VariableTree v) {
                out.add(v);
            }
        }
        return out;
    }
}
