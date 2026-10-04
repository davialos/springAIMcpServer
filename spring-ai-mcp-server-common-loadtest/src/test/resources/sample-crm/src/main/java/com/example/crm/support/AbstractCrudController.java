package com.example.crm.support;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Generic CRUD endpoints inherited by entity controllers. */
public abstract class AbstractCrudController<T, ID> {

    @GetMapping
    public List<T> list(@RequestParam(defaultValue = "0") int page) {
        return List.of();
    }

    @GetMapping("/{id}")
    public T get(@PathVariable ID id) {
        return null;
    }

    @PostMapping
    public T create(@RequestBody T body) {
        return body;
    }

    @PutMapping("/{id}")
    public T update(@PathVariable ID id, @RequestBody T body) {
        return body;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable ID id) {
    }
}
