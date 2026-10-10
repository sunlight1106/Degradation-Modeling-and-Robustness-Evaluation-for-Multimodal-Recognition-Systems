package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.domain.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.UserRepository;
import com.robustvision.platform.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserService userService;
    private final UserSessionService sessions;
    private final PasswordEncoder passwordEncoder;
    @org.springframework.beans.factory.annotation.Autowired private AccountSecurityService accountSecurity;

    public AuthService(AuthenticationManager authenticationManager, JwtService jwtService,
                       UserRepository userRepository, UserService userService, UserSessionService sessions, PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.userService = userService;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ApiDtos.LoginResponse login(ApiDtos.LoginRequest request) { return login(request, null); }

    @Transactional(noRollbackFor = BusinessException.class)
    public ApiDtos.LoginResponse login(ApiDtos.LoginRequest request, String userAgent) {
        try {
            if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误");
            }
            // Lock before loading authentication data so a concurrent credential
            // reset cannot create a fresh session using the previous password.
            var locked = userRepository.findLockedByUsername(request.username().trim());
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username().trim(), request.password()));
            UserDetails principal = (UserDetails) authentication.getPrincipal();
            UserEntity user = locked.orElseThrow(() ->
                    new BusinessException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误"));
            if (user.getStatus() != UserStatus.ACTIVE || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误");
            }
            accountSecurity.checkLogin(user, request.otp());
            Instant issuedAt = Instant.now();
            var session = sessions.create(user, issuedAt, jwtService.expiresAt(issuedAt), userAgent);
            return new ApiDtos.LoginResponse(
                    jwtService.createToken(principal, session.getId(), issuedAt), "Bearer", jwtService.expiresAt(issuedAt), userService.toView(user));
        } catch (AuthenticationException exception) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误");
        }
    }
}
