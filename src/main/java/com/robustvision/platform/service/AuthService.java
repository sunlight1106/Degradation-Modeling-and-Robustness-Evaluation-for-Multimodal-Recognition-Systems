package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.UserEntity;
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

    public AuthService(AuthenticationManager authenticationManager, JwtService jwtService,
                       UserRepository userRepository, UserService userService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    public ApiDtos.LoginResponse login(ApiDtos.LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username().trim(), request.password()));
            UserDetails principal = (UserDetails) authentication.getPrincipal();
            UserEntity user = userRepository.findByUsername(principal.getUsername())
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误"));
            Instant issuedAt = Instant.now();
            return new ApiDtos.LoginResponse(
                    jwtService.createToken(principal), "Bearer", jwtService.expiresAt(issuedAt), userService.toView(user));
        } catch (AuthenticationException exception) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误");
        }
    }
}
