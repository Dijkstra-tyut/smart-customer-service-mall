package com.supermarket.agent;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentInternalAuthFilterTest {
    @Test void serviceAndRunIdentityAreBothRequiredAndNoCredentialIsReturned() throws Exception {
        AgentStore store = mock(AgentStore.class);
        var filter = new AgentInternalAuthFilter(store, "service-secret");
        FilterChain chain = mock(FilterChain.class);
        var request = new MockHttpServletRequest("GET", "/internal/agent/context");
        request.setServletPath("/internal/agent/context");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(store, chain);
        request = new MockHttpServletRequest("GET", "/internal/agent/context");
        request.setServletPath("/internal/agent/context");
        request.addHeader("X-Agent-Service-Token", "service-secret");
        request.addHeader("X-Agent-Run-Token", "opaque-token");
        AgentRun run = new AgentRun("run", 11, 1, "running", 1, null);
        when(store.authenticate("opaque-token")).thenReturn(run);
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(request.getAttribute(AgentInternalAuthFilter.ATTRIBUTE)).isSameAs(run);
        verify(chain).doFilter(request, response);
        assertThat(response.getContentAsString()).doesNotContain("opaque-token", "service-secret");
    }

    @Test void unconfiguredServiceFailsClosed() throws Exception {
        var filter = new AgentInternalAuthFilter(mock(AgentStore.class), "");
        var request = new MockHttpServletRequest("GET", "/internal/agent/context");
        request.setServletPath("/internal/agent/context");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(chain);
    }
}
