package com.springaimcpservercommon.core.scan;

import com.springaimcpservercommon.core.lint.TextLint;
import com.springaimcpservercommon.core.schema.JsonSchemaMapper;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Options of the bean scan ({@code dynamic.ai.agent.scan.*}).
 *
 * @param basePackages              only types under these packages are scanned (autoconfigure passes
 *                                  {@code AutoConfigurationPackages.get(beanFactory)} by default); empty ⇒ nothing
 *                                  is scanned (safe default)
 * @param excludedPackages          packages never scanned even if under a base package (default: the library's own)
 * @param strict                    {@code scan.strict}: unbounded list actions are excluded
 * @param outcomeActionThreshold    more actions than this on one entity ⇒ {@code CONSIDER_OUTCOME_ACTION} hint
 * @param schemaOptions             JSON-schema mapping options (depth, maxItems, sensitive names)
 * @param textLint                  description / secret lint
 * @param hostVersion               host application version recorded in the catalog, if known
 */
public record ScanOptions(List<String> basePackages, List<String> excludedPackages, boolean strict,
                          int outcomeActionThreshold, JsonSchemaMapper.Options schemaOptions, TextLint textLint,
                          @Nullable String hostVersion) {

    /** The library's own base package, never scanned. */
    public static final String LIBRARY_PACKAGE = "com.springaimcpservercommon";

    /** Validates components and copies collections. */
    public ScanOptions {
        basePackages = List.copyOf(basePackages);
        excludedPackages = List.copyOf(excludedPackages);
        Objects.requireNonNull(schemaOptions, "schemaOptions");
        Objects.requireNonNull(textLint, "textLint");
        if (outcomeActionThreshold < 1) {
            throw new IllegalArgumentException("outcomeActionThreshold must be >= 1");
        }
    }

    /**
     * Default options for the given base packages: library package excluded, non-strict, threshold 8.
     *
     * @param basePackages base packages
     * @return options
     */
    public static ScanOptions defaults(List<String> basePackages) {
        return new ScanOptions(basePackages, List.of(LIBRARY_PACKAGE), false, 8, JsonSchemaMapper.Options.defaults(),
                TextLint.defaults(), null);
    }

    /**
     * Copy with another strict flag.
     *
     * @param newStrict strict flag
     * @return the copy
     */
    public ScanOptions withStrict(boolean newStrict) {
        return new ScanOptions(basePackages, excludedPackages, newStrict, outcomeActionThreshold, schemaOptions,
                textLint, hostVersion);
    }

    /**
     * Copy with other schema options (e.g. confirmed sensitive names).
     *
     * @param newSchemaOptions schema options
     * @return the copy
     */
    public ScanOptions withSchemaOptions(JsonSchemaMapper.Options newSchemaOptions) {
        return new ScanOptions(basePackages, excludedPackages, strict, outcomeActionThreshold, newSchemaOptions,
                textLint, hostVersion);
    }

    /**
     * Whether a type name is in scope: under a base package and not under an excluded package.
     *
     * @param typeName fully qualified type name
     * @return {@code true} if in scope
     */
    public boolean inScope(String typeName) {
        return matchesAny(typeName, basePackages) && !matchesAny(typeName, excludedPackages);
    }

    private static boolean matchesAny(String typeName, List<String> packages) {
        for (String p : packages) {
            if (!p.isBlank() && typeName.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }
}
