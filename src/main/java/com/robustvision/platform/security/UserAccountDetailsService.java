package com.robustvision.platform.security;

import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.domain.UserStatus;
import com.robustvision.platform.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserAccountDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    public UserAccountDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserEntity account = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + account.getRole().getCode()));
        account.getRole().getPermissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .disabled(account.getStatus() != UserStatus.ACTIVE)
                .authorities(authorities)
                .build();
    }
}

