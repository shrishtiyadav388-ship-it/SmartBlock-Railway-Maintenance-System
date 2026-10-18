package com.smartblock.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class RoleAccessFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || path.equals("/api/login")
                || path.equals("/api/me") || path.equals("/api/logout")) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        String role = session == null ? null : (String) session.getAttribute("role");
        if (role == null) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"ok\":false,\"message\":\"Please log in first.\"}");
            return;
        }

        boolean department = "DEPARTMENT".equals(role);
        boolean manager = "CONTROL".equals(role);
        // Default-deny: every API path must explicitly match the user's role.
        boolean allowed = false;

        if (path.startsWith("/api/my-requests")) {
            allowed = department;
        } else if (path.equals("/api/tasks") && "POST".equalsIgnoreCase(request.getMethod())) {
            allowed = department;
        } else if (path.equals("/api/tasks") || path.equals("/api/counters")
                || path.equals("/api/requests") || path.startsWith("/api/control/requests")
                || path.startsWith("/api/control/tasks") || path.startsWith("/api/conflicts")
                || path.startsWith("/api/scheduling")
                || path.startsWith("/api/allocation") || path.startsWith("/api/plan")
                || path.startsWith("/api/reports") || path.equals("/api/control/completion-status")) {
            allowed = manager;
        }

        if (!allowed) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"ok\":false,\"message\":\"Your role cannot access this feature.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
