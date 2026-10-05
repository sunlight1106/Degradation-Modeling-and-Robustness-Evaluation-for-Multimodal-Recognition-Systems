package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@DataJpaTest(showSql = false)
@Import(NoteExperimentSourceService.class)
class NoteExperimentSourceTest {
    @Autowired EntityManager em;
    @Autowired NoteExperimentSourceService sources;
    @MockBean CurrentUserService current;
    @Test void listingInsertionAndAiContextStayOwnOnlyEvenForAnAdministrator() {
        RoleEntity role = new RoleEntity("ADMIN", "Admin", null, Set.of()); em.persist(role);
        UserEntity a = new UserEntity("source-a", "fixture", "A", "sa@example.invalid", role); em.persist(a);
        UserEntity b = new UserEntity("source-b", "fixture", "B", "sb@example.invalid", role); em.persist(b);
        when(current.requireCurrent()).thenReturn(a); when(current.isSuperAdmin(a)).thenReturn(true);
        InferenceTaskEntity own = task(a, "own.png");
        InferenceTaskEntity other = task(b, "PRIVATE_OTHER_FILENAME");
        assertThat(sources.list()).extracting(NoteExperimentSourceService.SourceView::taskId).containsExactly(own.getId());
        assertThat(sources.preview(List.of(own.getId())).markdown()).contains(own.getId(), "基线结果");
        assertThatThrownBy(() -> sources.preview(List.of(other.getId()))).isInstanceOf(BusinessException.class).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> sources.buildContext(List.of(other.getId()))).isInstanceOf(BusinessException.class).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> sources.buildContext(List.of("' OR 1=1 --"))).isInstanceOf(BusinessException.class);
        own.setStatus(InferenceStatus.FAILED);
        assertThatThrownBy(() -> sources.buildContext(List.of(own.getId()))).isInstanceOf(BusinessException.class).hasMessageContaining("已完成");
    }
    @Test void previewsBoundSelectionAndNeverSilentlyTruncate() {
        RoleEntity role = new RoleEntity("SOURCE_TEST", "Test", null, Set.of()); em.persist(role);
        UserEntity a = new UserEntity("source-limit", "fixture", "A", "sl@example.invalid", role); em.persist(a);
        when(current.requireCurrent()).thenReturn(a);
        InferenceTaskEntity task = task(a, "safe.png"); task.setBaselineResult("x".repeat(25000));
        assertThatThrownBy(() -> sources.buildContext(List.of(task.getId()))).isInstanceOf(BusinessException.class).hasMessageContaining("不会静默截断");
        assertThatThrownBy(() -> sources.buildContext(java.util.Collections.nCopies(21, task.getId()))).isInstanceOf(BusinessException.class);
    }
    private InferenceTaskEntity task(UserEntity user, String filename) {
        FileAssetEntity file = new FileAssetEntity(filename, UUID.randomUUID().toString(), "image/png", 3,
                "a".repeat(64), "fixture", user, FileSource.UPLOAD, FileScanStatus.CLEAN, "test"); em.persist(file);
        ModelDefinitionEntity model = new ModelDefinitionEntity("source-" + UUID.randomUUID(), "model", "1", ModelProvider.DEMO, TaskType.RECEIPT, "fixture"); em.persist(model);
        InferenceTaskEntity task = new InferenceTaskEntity(UUID.randomUUID().toString(), TaskType.RECEIPT, false, file, model, user);
        task.setStatus(InferenceStatus.COMPLETED); task.setBaselineResult("{\"text\":\"fixture\"}"); em.persist(task); return task;
    }
}
