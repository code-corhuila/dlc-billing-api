package com.dilucca.billing.api;

import com.dilucca.billing.infrastructure.security.IamSessionValidationClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

final class IamSessionEligibilityFilter extends OncePerRequestFilter {
    private final IamSessionValidationClient iam;
    IamSessionEligibilityFilter(IamSessionValidationClient iam) { this.iam = iam; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            boolean active;
            try {
                active = iam.isActive(jwt.getToken().getTokenValue());
            } catch (IllegalStateException unavailable) {
                deny(request, response, 503, "SERVICE_UNAVAILABLE");
                return;
            }
            if (!active) {
                response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                deny(request, response, 401, "UNAUTHORIZED");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletRequest request, HttpServletResponse response, int status,
            String error) throws IOException {
        SecurityContextHolder.clearContext();
        String trace = request.getHeader("X-Correlation-Id");
        if (trace == null || !trace.matches("[A-Za-z0-9._:-]{8,128}")) trace = UUID.randomUUID().toString();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Correlation-Id", trace);
        response.getWriter().write("{\"error\":\"" + error
                + "\",\"message\":\"Authentication could not be confirmed.\",\"traceId\":\"" + trace + "\"}");
    }
}
