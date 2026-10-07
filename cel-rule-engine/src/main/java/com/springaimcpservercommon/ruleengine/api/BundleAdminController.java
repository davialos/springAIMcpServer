package com.springaimcpservercommon.ruleengine.api;

import com.springaimcpservercommon.ruleengine.repo.BundleRepository;
import com.springaimcpservercommon.ruleengine.repo.BundleRepository.Bundle;
import com.springaimcpservercommon.ruleengine.repo.BundleRepository.BundleText;
import com.springaimcpservercommon.ruleengine.repo.CatalogRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Message bundles: one message, one text per language ({@code {customer.age}} placeholders are filled in). */
@RestController
@RequestMapping("/api/v1/admin/bundles")
public class BundleAdminController {

    /** A new bundle with its texts. */
    public record BundleRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{2,100}") String code,
                                @Nullable String description, @Nullable Map<String, String> texts) { }

    /** One language's text. */
    public record TextRequest(@NotBlank String text) { }

    /** A bundle with its texts. */
    public record BundleView(Bundle bundle, List<BundleText> texts) { }

    private final BundleRepository bundles;
    private final CatalogRepository catalog;

    public BundleAdminController(BundleRepository bundles, CatalogRepository catalog) {
        this.bundles = bundles;
        this.catalog = catalog;
    }

    @GetMapping
    public List<Bundle> list() {
        return bundles.bundles();
    }

    @GetMapping("/{code}")
    public BundleView get(@PathVariable String code) {
        Bundle b = bundles.bundle(code).orElseThrow(() -> RuleEngineException.notFound("bundle " + code));
        return new BundleView(b, bundles.texts(b.id()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BundleView create(@Valid @RequestBody BundleRequest r) {
        Bundle b = bundles.createBundle(r.code(), r.description());
        if (r.texts() != null) {
            r.texts().forEach((language, text) -> setText(b.id(), language, text));
        }
        return new BundleView(b, bundles.texts(b.id()));
    }

    @PutMapping("/{code}/texts/{language}")
    public BundleView setText(@PathVariable String code, @PathVariable String language,
                              @Valid @RequestBody TextRequest r) {
        Bundle b = bundles.bundle(code).orElseThrow(() -> RuleEngineException.notFound("bundle " + code));
        setText(b.id(), language, r.text());
        return new BundleView(b, bundles.texts(b.id()));
    }

    private void setText(long bundleId, String language, String text) {
        if (!catalog.languageExists(language)) {
            throw RuleEngineException.badRequest("unknown language " + language);
        }
        bundles.setText(bundleId, language, text);
    }
}
