package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.lint.SecretScanner;
import com.springaimcpservercommon.persistence.support.CanonicalJson;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Validation of a resource spec (the JSON document of a draft revision) at the HTTP boundary, so authors get
 * field errors when they save instead of a failure when they publish.
 *
 * <p>Checks: present, at most {@value #MAX_CHARS} characters, a well-formed JSON object within the canonical
 * JSON depth limit, and free of credentials (a spec is stored, shown to reviewers and can reach a model).
 * Per-kind shape validation is done where the spec is consumed (publish and snapshot load); see OQ-41.
 * Error messages name the problem only and never echo spec content.
 */
@NullMarked
final class ResourceSpecs {

    static final int MAX_CHARS = 256_000;

    private static final SecretScanner SECRETS = new SecretScanner();

    private ResourceSpecs() {
    }

    /**
     * Validates a spec.
     *
     * @param specJson raw spec text from the request
     * @param field    field name for violations, normally {@code specJson}
     * @param errors   collector for violations
     * @return the spec when valid, otherwise {@code null} (violations added)
     */
    static @Nullable String validate(@Nullable String specJson, String field, List<FieldViolation> errors) {
        if (specJson == null || specJson.isBlank()) {
            errors.add(new FieldViolation(field, "is required"));
            return null;
        }
        if (specJson.length() > MAX_CHARS) {
            errors.add(new FieldViolation(field, "must be at most " + MAX_CHARS + " characters"));
            return null;
        }
        try {
            CanonicalJson.canonicalizeObject(specJson);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldViolation(field, "must be a well-formed JSON object"));
            return null;
        }
        Optional<String> secret = SECRETS.findSecret(specJson);
        if (secret.isPresent()) {
            errors.add(new FieldViolation(field, "appears to contain a credential (" + secret.get()
                    + "); keep secrets in the host's secret store"));
            return null;
        }
        return specJson;
    }
}
