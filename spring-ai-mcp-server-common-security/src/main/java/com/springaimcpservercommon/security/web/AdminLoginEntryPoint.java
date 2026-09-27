package com.springaimcpservercommon.security.web;

import com.springaimcpservercommon.security.internal.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Entry point of the admin chain. The admin plane never runs its own login: it relies on the session the host's
 * login (oauth2Login, form, LDAP, SAML …) established. Browser navigations ({@code Accept: text/html}) are redirected
 * to the host's login URL; API calls of the admin UI get a 401 problem.
 */
public final class AdminLoginEntryPoint implements AuthenticationEntryPoint {

    private final @Nullable String loginUrl;

    /**
     * Creates the entry point.
     *
     * @param loginUrl host login URL (context-relative, e.g. {@code /oauth2/authorization/entra} or {@code /login}), or
     *                 {@code null} to always answer 401
     */
    public AdminLoginEntryPoint(@Nullable String loginUrl) {
        if (loginUrl != null && (!loginUrl.startsWith("/") || loginUrl.startsWith("//") || loginUrl.contains("\\"))) {
            throw new IllegalArgumentException("admin login URL must be a context-relative path");
        }
        this.loginUrl = loginUrl;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String accept = request.getHeader("Accept");
        boolean browserNavigation = "GET".equals(request.getMethod()) && accept != null && accept.contains("text/html");
        if (loginUrl != null && browserNavigation) {
            response.sendRedirect(request.getContextPath() + loginUrl);
            return;
        }
        ProblemWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated", "Authentication required");
    }
}
