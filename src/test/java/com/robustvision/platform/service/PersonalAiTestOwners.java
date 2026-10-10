package com.robustvision.platform.service;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.repository.UserRepository;
import java.util.Optional;
import static org.mockito.Mockito.*;
/** Mock owners only for transport unit tests; integration tests use real user rows. */
final class PersonalAiTestOwners {
 static UserRepository active(){var rows=mock(UserRepository.class);var user=mock(UserEntity.class);when(user.hasActiveAccess(any())).thenReturn(true);when(rows.findLockedById(anyLong())).thenReturn(Optional.of(user));return rows;}
}
