package com.springaimcpservercommon.webmvc.endpoint;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EndpointSpanTest {

    private final List<Observation.Context> stopped = new ArrayList<>();
    private final ObservationRegistry registry = ObservationRegistry.create();

    EndpointSpanTest() {
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
    }

    private GenericDynamicHandler handler(DynamicEndpointRegistrar registrar) {
        return new GenericDynamicHandler(registrar, mock(GenericDynamicHandler.DaiPrincipalResolver.class),
                mock(com.springaimcpservercommon.security.authz.AuthorizationEngine.class),
                mock(GenericDynamicHandler.BackingExecutor.class),
                mock(GenericDynamicHandler.KillSwitchChecker.class),
                mock(GenericDynamicHandler.RateLimiter.class), registry);
    }

    @Test
    void everyRequestIsOneEndpointSpanWithMethodAndStatusClass() throws Exception {
        DynamicEndpointRegistrar registrar = mock(DynamicEndpointRegistrar.class);
        when(registrar.lookup(any(), any())).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(registrar).handleRequest(new MockHttpServletRequest("GET", "/dynamic-ai/api/nothing"), response);

        assertThat(stopped).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("dynamic.ai.agent.endpoint");
            assertThat(c.getContextualName()).isEqualTo("dai.endpoint");
            assertThat(c.getLowCardinalityKeyValue("dai.endpoint.method").getValue()).isEqualTo("GET");
            assertThat(c.getLowCardinalityKeyValue("dai.endpoint.status").getValue()).isEqualTo("4xx");
        });
    }

    @Test
    void anUnexpectedErrorMarksTheSpanAndStillStopsIt() {
        DynamicEndpointRegistrar registrar = mock(DynamicEndpointRegistrar.class);
        when(registrar.lookup(any(), any())).thenThrow(new IllegalStateException("boom"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler(registrar).handleRequest(
                new MockHttpServletRequest("POST", "/x"), new MockHttpServletResponse()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(stopped).singleElement().satisfies(c -> assertThat(c.getError()).isNotNull());
    }
}
