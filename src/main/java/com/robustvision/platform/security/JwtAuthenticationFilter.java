package com.robustvision.platform.security;

import com.robustvision.platform.service.UserSessionService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserAccountDetailsService userDetailsService;
    private final UserSessionService sessions;

    public JwtAuthenticationFilter(JwtService jwtService, UserAccountDetailsService userDetailsService, UserSessionService sessions) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = authorization.substring(7);
            jwtService.validToken(token).ifPresent(claims -> {
                if (!sessions.isActive(claims.sessionId(), claims.subject())) return;
                try {
                    UserDetails details = userDetailsService.loadUserByUsername(claims.subject());
                    if (details.isEnabled()) {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
                        authentication.setDetails(new UserSessionService.SessionDetails(claims.sessionId()));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                } catch (UsernameNotFoundException ignored) {
                    // Deleted accounts never authenticate, even with a correctly signed token.
                }
            });
        }
        filterChain.doFilter(request, response);
    }
}

