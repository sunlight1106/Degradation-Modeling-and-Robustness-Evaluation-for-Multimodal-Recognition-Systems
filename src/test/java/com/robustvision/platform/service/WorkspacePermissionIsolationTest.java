package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.ApiDtos;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@DataJpaTest(showSql = false)
@Import(WorkspaceService.class)
class WorkspacePermissionIsolationTest {
    @Autowired EntityManager em;
    @Autowired WorkspaceService service;
    @MockBean CurrentUserService current;
    @Test void viewerOnlySeesOwnMembershipAndDelegatedWriterCannotEscalate() {
        RoleEntity role = new RoleEntity("WORKSPACE_TEST", "Test", null, Set.of()); em.persist(role);
        UserEntity owner = new UserEntity("workspace-owner", "fixture", "Owner", "wo@example.invalid", role); em.persist(owner);
        UserEntity viewer = new UserEntity("workspace-viewer", "fixture", "Viewer", "wv@example.invalid", role); em.persist(viewer);
        WorkspaceEntity workspace = new WorkspaceEntity("Private", "private-test", "#123456", owner); em.persist(workspace);
        em.persist(new WorkspaceMemberEntity(workspace, owner, WorkspaceMemberRole.OWNER, WorkspaceService.ALL));
        WorkspaceMemberEntity membership = new WorkspaceMemberEntity(workspace, viewer, WorkspaceMemberRole.VIEWER, Set.of("CONTENT_READ")); em.persist(membership);
        when(current.requireCurrent()).thenReturn(viewer);
        assertThat(service.list()).singleElement().satisfies(view -> assertThat(view.members()).extracting(ApiDtos.WorkspaceMemberView::userId).containsExactly(viewer.getId()));
        assertThat(service.detail(workspace.getId()).members()).extracting(ApiDtos.WorkspaceMemberView::userId).containsExactly(viewer.getId());
        membership.update(WorkspaceMemberRole.MEMBER, Set.of("CONTENT_READ", "MEMBERS_WRITE")); em.flush();
        assertThatThrownBy(() -> service.upsertMember(workspace.getId(), new ApiDtos.WorkspaceMemberRequest(
                viewer.getId(), WorkspaceMemberRole.ADMIN, Set.of("SETTINGS_WRITE", "MEMBERS_WRITE"))))
                .isInstanceOf(BusinessException.class).hasMessage("工作空间权限不足");
        when(current.requireCurrent()).thenReturn(owner);
        assertThat(service.detail(workspace.getId()).members()).hasSize(2);
    }
}
