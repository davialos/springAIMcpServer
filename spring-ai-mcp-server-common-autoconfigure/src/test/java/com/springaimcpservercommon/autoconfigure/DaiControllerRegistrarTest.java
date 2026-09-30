package com.springaimcpservercommon.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Library controllers are plain {@code @Bean}s with a type-level {@code @RequestMapping} and no {@code @Controller}
 * (the library never adds component stereotypes). Spring MVC 7 maps only {@code @Controller} types, so without
 * {@link DaiControllerRegistrar} every admin and data-plane API answered 404 in a real host.
 */
class DaiControllerRegistrarTest {

    /** Shaped like the library's controllers. */
    @RequestMapping("/dynamic-ai/admin/api/v1/probe")
    static final class ProbeController {
        @GetMapping
        public ResponseEntity<?> list() {
            return ResponseEntity.ok(Map.of("ok", true));
        }

        @PostMapping("/{id:[^:]+}:reset")
        public ResponseEntity<?> reset(@PathVariable String id, @RequestBody Map<String, Object> body) {
            return ResponseEntity.ok(Map.of("id", id, "reason", body.get("reason")));
        }
    }

    /** A host controller: mapped by Spring itself, proves the MVC setup works. */
    @org.springframework.stereotype.Controller
    @RequestMapping("/host")
    static final class HostController {
        @GetMapping
        public ResponseEntity<?> ping() {
            return ResponseEntity.ok(Map.of("host", true));
        }
    }

    @EnableWebMvc
    static final class Mvc {
    }

    private static MockMvc mvc(boolean withRegistrar) {
        GenericWebApplicationContext context = new GenericWebApplicationContext(new MockServletContext());
        org.springframework.context.annotation.AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
        context.registerBean(Mvc.class);
        context.registerBean(HostController.class, HostController::new);
        context.registerBean(ProbeController.class, ProbeController::new);
        if (withRegistrar) {
            context.registerBean(DaiControllerRegistrar.class, () -> new DaiControllerRegistrar(
                    context.getBeanProvider(org.springframework.web.servlet.mvc.method.annotation
                            .RequestMappingHandlerMapping.class), context));
        }
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void withoutTheRegistrarALibraryControllerIsNotMappedWhileAHostControllerIs() throws Exception {
        MockMvc mvc = mvc(false);
        mvc.perform(get("/host")).andExpect(status().isOk());
        mvc.perform(get("/dynamic-ai/admin/api/v1/probe")).andExpect(status().isNotFound());
    }

    @Test
    void theRegistrarMapsLibraryControllersIntoTheHostsHandlerMapping() throws Exception {
        MockMvc mvc = mvc(true);

        mvc.perform(get("/host")).andExpect(status().isOk());
        String listed = mvc.perform(get("/dynamic-ai/admin/api/v1/probe"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(listed).contains("\"ok\":true");
        String reset = mvc.perform(post("/dynamic-ai/admin/api/v1/probe/openai:reset")
                        .contentType("application/json").content("{\"reason\":\"recovered\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(reset).contains("\"id\":\"openai\"", "\"reason\":\"recovered\"");
    }
}
