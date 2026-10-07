package com.springaimcpservercommon.ruleengine.api;

import com.springaimcpservercommon.ruleengine.domain.DataType;
import com.springaimcpservercommon.ruleengine.domain.Model.SysAttribute;
import com.springaimcpservercommon.ruleengine.domain.Model.SysObject;
import com.springaimcpservercommon.ruleengine.library.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.library.ParameterLibraryService;
import com.springaimcpservercommon.ruleengine.repo.CatalogRepository;
import com.springaimcpservercommon.ruleengine.repo.LibraryRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * The parameter library: sys objects and their attributes. Every object is a CEL variable and every attribute is
 * {@code object.attribute} in expressions. The application keeps the library cached; each change here refreshes it.
 */
@RestController
@RequestMapping("/api/v1/admin/library")
public class LibraryAdminController {

    /** A new sys object. */
    public record ObjectRequest(@NotBlank @Pattern(regexp = "[a-z][A-Za-z0-9_]*",
            message = "must start with a lower-case letter: it becomes a CEL variable") String code,
                                @NotBlank String name, @Nullable String description, @Nullable String module) { }

    /** Changes to a sys object. */
    public record ObjectUpdate(@NotBlank String name, @Nullable String description, boolean active) { }

    /** A new or changed attribute. */
    public record AttributeRequest(@NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*") String code,
                                   @NotBlank String name, DataType dataType, boolean required,
                                   @Nullable String description) { }

    private final LibraryRepository repository;
    private final ParameterLibraryService service;
    private final CatalogRepository catalog;

    public LibraryAdminController(LibraryRepository repository, ParameterLibraryService service,
                                  CatalogRepository catalog) {
        this.repository = repository;
        this.service = service;
        this.catalog = catalog;
    }

    /** The cached library, with the CEL names rules can use. */
    @GetMapping
    public Map<String, Object> library() {
        ParameterLibrary library = service.current();
        return Map.of("version", library.version(), "celNames", library.celNames(), "objects", library.objects().values());
    }

    /** Reloads the cache from the database (it also reloads itself when the library changes). */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        ParameterLibrary library = service.refresh();
        return Map.of("version", library.version(), "objects", library.objects().size(),
                "attributes", library.celNames().size());
    }

    @PostMapping("/objects")
    @ResponseStatus(HttpStatus.CREATED)
    public SysObject createObject(@Valid @RequestBody ObjectRequest r) {
        Long module = r.module() == null ? null : catalog.module(r.module())
                .orElseThrow(() -> RuleEngineException.notFound("module " + r.module())).id();
        SysObject object = repository.createObject(r.code(), r.name(), r.description(), module);
        service.refresh();
        return object;
    }

    @PutMapping("/objects/{code}")
    public SysObject updateObject(@PathVariable String code, @Valid @RequestBody ObjectUpdate r) {
        SysObject object = repository.object(code).orElseThrow(() -> RuleEngineException.notFound("object " + code));
        repository.updateObject(object.id(), r.name(), r.description(), r.active());
        service.refresh();
        return repository.object(code).orElse(object);
    }

    @PostMapping("/objects/{code}/attributes")
    @ResponseStatus(HttpStatus.CREATED)
    public SysAttribute upsertAttribute(@PathVariable String code, @Valid @RequestBody AttributeRequest r) {
        SysObject object = repository.object(code).orElseThrow(() -> RuleEngineException.notFound("object " + code));
        SysAttribute attribute = repository.upsertAttribute(object.id(), r.code(), r.name(), r.dataType(), r.required(),
                r.description());
        service.refresh();
        return attribute;
    }

    @DeleteMapping("/objects/{code}/attributes/{attribute}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttribute(@PathVariable String code, @PathVariable String attribute) {
        SysObject object = repository.object(code).orElseThrow(() -> RuleEngineException.notFound("object " + code));
        repository.deleteAttribute(object.id(), attribute);
        service.refresh();
    }

    @GetMapping("/objects")
    public List<SysObject> objects() {
        return List.copyOf(service.current().objects().values());
    }
}
