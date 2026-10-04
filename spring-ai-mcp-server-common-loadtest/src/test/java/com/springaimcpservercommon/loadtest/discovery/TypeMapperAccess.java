package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ObjectSchema;

import java.nio.file.Path;
import java.util.Optional;

/** Test access to the package-private mapper: the request schema of one class of a project. */
final class TypeMapperAccess {

    private TypeMapperAccess() {
    }

    static Optional<ObjectSchema> entitySchema(Path project, String className) {
        SourceTrees trees = SourceTrees.parse(ProjectFiles.javaSources(project));
        return trees.type(className).map(d -> new TypeMapper(trees, false).objectSchema(d.tree(), null));
    }
}
