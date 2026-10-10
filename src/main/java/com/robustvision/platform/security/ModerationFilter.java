package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.*;
import com.robustvision.platform.repository.UserRepository;
import com.robustvision.platform.service.ModerationGuard;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class ModerationFilter extends OncePerRequestFilter {
    private final ModerationGuard guard;
    private final UserRepository users;
    private final ObjectMapper json;
    public ModerationFilter(ModerationGuard guard,UserRepository users,ObjectMapper json){this.guard=guard;this.users=users;this.json=json;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        try {
            if(auth!=null && auth.isAuthenticated() && !auth.getName().equals("anonymousUser") && req.getRequestURI().startsWith("/api/v1/"))
                users.findByUsername(auth.getName()).ifPresent(u->guard.request(u.getId(),req.getRequestURI(),req.getMethod()));
        } catch(BusinessException e) {
            res.setStatus(e.getStatus().value());res.setContentType("application/json;charset=UTF-8");
            json.writeValue(res.getOutputStream(),ApiResponse.failure(e.getCode(),e.getMessage()));return;
        }
        chain.doFilter(req,res);
    }
}
