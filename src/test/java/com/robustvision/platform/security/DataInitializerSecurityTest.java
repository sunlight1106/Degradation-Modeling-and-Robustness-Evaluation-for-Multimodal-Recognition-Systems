package com.robustvision.platform.security;

import com.robustvision.platform.config.DataInitializer;
import com.robustvision.platform.domain.RoleEntity;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.repository.ModelDefinitionRepository;
import com.robustvision.platform.repository.RoleRepository;
import com.robustvision.platform.repository.UserRepository;
import com.robustvision.platform.service.UserSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataInitializerSecurityTest {
    @Test void explicitBootstrapPasswordResetsRevokeSessionsAndKeepCustomRolePermissions() {
        Fixture fixture = new Fixture(); fixture.initializer(true).run();
        verify(fixture.sessions).revokeAll(1L); verify(fixture.sessions).revokeAll(2L);
        assertThat(fixture.researcher.getPermissions()).containsExactly("notes:read");
        assertThat(fixture.viewer.getPermissions()).containsExactly("role:read");
    }
    @Test void ordinaryStartupDoesNotResetPasswordsOrRevokeExistingSessions() {
        Fixture fixture = new Fixture(); fixture.initializer(false).run();
        verifyNoInteractions(fixture.sessions); verifyNoInteractions(fixture.passwords);
        assertThat(fixture.researcher.getPermissions()).containsExactly("notes:read");
        assertThat(fixture.viewer.getPermissions()).containsExactly("role:read");
    }
    private static class Fixture {
        final RoleRepository roles = mock(RoleRepository.class);
        final UserRepository users = mock(UserRepository.class);
        final ModelDefinitionRepository models = mock(ModelDefinitionRepository.class);
        final PasswordEncoder passwords = mock(PasswordEncoder.class);
        final UserSessionService sessions = mock(UserSessionService.class);
        final RoleEntity admin = new RoleEntity("ADMIN", "Admin", "", Permissions.allCodes());
        final RoleEntity researcher = new RoleEntity("RESEARCHER", "Researcher", "", Set.of("notes:read"));
        final RoleEntity viewer = new RoleEntity("VIEWER", "Viewer", "", Set.of("role:read"));
        Fixture() {
            when(roles.findByCode("ADMIN")).thenReturn(Optional.of(admin));
            when(roles.findByCode("RESEARCHER")).thenReturn(Optional.of(researcher));
            when(roles.findByCode("VIEWER")).thenReturn(Optional.of(viewer));
            when(models.existsByCodeAndVersion(anyString(), anyString())).thenReturn(true);
            when(users.existsByUsername(anyString())).thenReturn(true);
            UserEntity adminUser = new UserEntity("admin-fixture", "synthetic-old-hash", "Admin", "admin@example.test", admin);
            UserEntity demoUser = new UserEntity("demo-fixture", "synthetic-old-hash", "Demo", "demo@example.test", researcher);
            ReflectionTestUtils.setField(adminUser, "id", 1L); ReflectionTestUtils.setField(demoUser, "id", 2L);
            when(users.findByUsername("admin-fixture")).thenReturn(Optional.of(adminUser));
            when(users.findByUsername("demo-fixture")).thenReturn(Optional.of(demoUser));
            when(passwords.encode(anyString())).thenReturn("synthetic-new-hash");
        }
        DataInitializer initializer(boolean reset) {
            return new DataInitializer(roles, users, models, passwords, sessions,
                    "admin-fixture", "SyntheticAdmin123!", "admin@example.test", reset,
                    "demo-fixture", "SyntheticDemo123!", "demo@example.test", reset);
        }
    }
}
