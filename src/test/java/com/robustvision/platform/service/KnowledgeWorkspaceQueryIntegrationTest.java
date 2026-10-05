package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.ApiDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DataJpaTest(showSql = false, properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@Import({KnowledgeService.class, WorkspaceService.class})
class KnowledgeWorkspaceQueryIntegrationTest {
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired KnowledgeService knowledgeService;
    @Autowired WorkspaceService workspaceService;
    @MockBean CurrentUserService currentUserService;

    private UserEntity current;
    private UserEntity other;
    private Statistics statistics;
    private int nextUser;
    private int nextTopic;

    @BeforeEach
    void setUp() {
        current = user("current");
        other = user("other");
        when(currentUserService.requireCurrent()).thenReturn(current);
        when(currentUserService.isSuperAdmin(current)).thenReturn(false);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void topicCountsUseTwoQueriesAndPreserveOrderingAndEmptyTopics() {
        List<KnowledgeTopicEntity> visible = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            KnowledgeTopicEntity topic = topic(i % 2 == 0 ? null : current, i % 3 == 0 ? "zoology" : "biology", i % 4);
            visible.add(topic);
            for (int j = 0; j < i % 3; j++) entry(topic, "card " + j, topic.getOwner(), j);
        }
        KnowledgeTopicEntity hidden = topic(other, "biology", 0);
        entry(hidden, "hidden", other, 0);
        clearPersistenceContextAndStatistics();

        List<ApiDtos.KnowledgeTopicView> result = knowledgeService.listTopics();

        visible.sort(Comparator.comparing(KnowledgeTopicEntity::getDomain)
                .thenComparingInt(KnowledgeTopicEntity::getSortOrder).thenComparing(KnowledgeTopicEntity::getId));
        assertThat(result).extracting(ApiDtos.KnowledgeTopicView::id)
                .containsExactlyElementsOf(visible.stream().map(KnowledgeTopicEntity::getId).toList());
        assertThat(result).extracting(ApiDtos.KnowledgeTopicView::entryCount).contains(0L, 1L, 2L);
        assertThat(result.stream().mapToLong(ApiDtos.KnowledgeTopicView::entryCount).sum()).isEqualTo(24);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void allCardsUseTwoQueriesRegardlessOfTopicAndCardCount() {
        List<KnowledgeTopicEntity> topics = new ArrayList<>();
        for (int i = 0; i < 16; i++) topics.add(topic(i % 2 == 0 ? null : current, "biology", 15 - i));
        List<KnowledgeEntryEntity> cards = new ArrayList<>();
        for (KnowledgeTopicEntity topic : topics) {
            for (int j = 0; j < 4; j++) {
                KnowledgeEntryEntity card = entry(topic, "visible " + j, topic.getOwner(), j % 2);
                cards.add(card);
            }
        }
        KnowledgeEntryEntity first = cards.get(0);
        NoteEntity note = note(current);
        reference(note, first.getId(), NoteReferenceType.ENTRY, 0);
        reference(note(current), first.getId(), NoteReferenceType.ENTRY, 0);
        reference(note(other), first.getId(), NoteReferenceType.ENTRY, 0);
        reference(note, first.getId(), NoteReferenceType.FILE, 2);
        entry(topic(other, "hidden", 0), "hidden", other, 0);
        clearPersistenceContextAndStatistics();

        List<ApiDtos.KnowledgeEntryView> result = knowledgeService.listEntries(null, null);

        cards.sort(Comparator.comparingInt((KnowledgeEntryEntity e) -> e.getTopic().getOwner() == null ? 0 : 1)
                .thenComparingInt(e -> e.getTopic().getSortOrder()).thenComparing(e -> e.getTopic().getId())
                .thenComparingInt(KnowledgeEntryEntity::getSortOrder).thenComparing(KnowledgeEntryEntity::getCreatedAt)
                .thenComparing(KnowledgeEntryEntity::getId));
        assertThat(result).extracting(ApiDtos.KnowledgeEntryView::id)
                .containsExactlyElementsOf(cards.stream().map(KnowledgeEntryEntity::getId).toList());
        assertThat(result.stream().filter(e -> e.id().equals(first.getId())).findFirst().orElseThrow().noteReferences()).isEqualTo(3);
        assertThat(result.stream().filter(e -> !e.id().equals(first.getId()))).allMatch(e -> e.noteReferences() == 0);
        assertThat(result).allSatisfy(e -> assertThat(e.tags()).containsExactly("tag", "other"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void topicFilteredCardsUseThreeQueriesAndRejectInaccessibleTopics() {
        KnowledgeTopicEntity own = topic(current, "science", 0);
        KnowledgeEntryEntity card = entry(own, "mine", current, 0);
        KnowledgeTopicEntity hidden = topic(other, "science", 0);
        clearPersistenceContextAndStatistics();

        assertThat(knowledgeService.listEntries(own.getId(), "  "))
                .extracting(ApiDtos.KnowledgeEntryView::id).containsExactly(card.getId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThatThrownBy(() -> knowledgeService.listEntries(hidden.getId(), null))
                .isInstanceOf(BusinessException.class).hasMessage("知识主题不存在");
        assertThatThrownBy(() -> knowledgeService.listEntries(Long.MAX_VALUE, null))
                .isInstanceOf(BusinessException.class).hasMessage("知识主题不存在");
    }

    @Test
    void searchFiltersInDatabasePreservingEntryOwnershipAndDeterministicTies() {
        KnowledgeTopicEntity own = topic(current, "science", 0);
        KnowledgeTopicEntity builtin = topic(null, "science", 0);
        KnowledgeEntryEntity first = entry(own, "needle first", current, 0);
        KnowledgeEntryEntity second = entry(own, "needle second", current, 0);
        entry(own, "needle private", other, 0);
        KnowledgeEntryEntity publicCard = entry(builtin, "needle builtin", null, 0);
        Instant tie = Instant.parse("2026-01-01T00:00:00Z");
        first.setUpdatedAt(tie);
        second.setUpdatedAt(tie);
        reference(note(current), second.getId(), NoteReferenceType.ENTRY, 0);
        clearPersistenceContextAndStatistics();

        List<ApiDtos.KnowledgeEntryView> filtered = knowledgeService.listEntries(own.getId(), "  needle  ");

        assertThat(filtered).extracting(ApiDtos.KnowledgeEntryView::id)
                .containsExactlyElementsOf(List.of(first.getId(), second.getId()).stream().sorted().toList());
        assertThat(filtered.stream().filter(e -> e.id().equals(second.getId())).findFirst().orElseThrow().noteReferences()).isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        clearPersistenceContextAndStatistics();
        assertThat(knowledgeService.listEntries(null, "needle")).extracting(ApiDtos.KnowledgeEntryView::id)
                .containsExactlyInAnyOrder(first.getId(), second.getId(), publicCard.getId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void topicListingAndSearchRequireBothTopicAndEntryVisibility() {
        KnowledgeTopicEntity ownTopic = topic(current, "science", 0);
        KnowledgeTopicEntity hiddenTopic = topic(other, "science", 0);
        KnowledgeEntryEntity otherOwnedInVisibleTopic = entry(ownTopic, "needle other-owned", other, 0);
        KnowledgeEntryEntity builtinInHiddenTopic = entry(hiddenTopic, "needle builtin", null, 0);
        clearPersistenceContextAndStatistics();

        // Legacy mismatched ownership must not turn a visible topic into a private-card leak.
        assertThat(knowledgeService.listEntries(null, null)).isEmpty();
        clearPersistenceContextAndStatistics();
        assertThat(knowledgeService.listEntries(null, "needle")).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        clearPersistenceContextAndStatistics();
        assertThat(knowledgeService.listEntries(Long.MAX_VALUE, "needle")).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void publicKnowledgeIsReadableButCannotBeChangedByOrdinaryUsers() {
        KnowledgeTopicEntity shared = topic(null, "science", 0);
        KnowledgeEntryEntity card = entry(shared, "Shared card", null, 0);
        assertThat(knowledgeService.entry(card.getId()).title()).isEqualTo("Shared card");
        assertThatThrownBy(() -> knowledgeService.deleteEntry(card.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅管理员");
        when(currentUserService.isSuperAdmin(current)).thenReturn(true);
        knowledgeService.deleteEntry(card.getId());
        assertThat(entityManager.find(KnowledgeEntryEntity.class, card.getId())).isNull();
    }

    @Test
    void emptyListsDoNotRunEmptyInQueries() {
        clearPersistenceContextAndStatistics();
        assertThat(knowledgeService.listTopics()).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();
        assertThat(knowledgeService.listEntries(null, null)).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();
        assertThat(knowledgeService.listEntries(null, "missing")).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();
        assertThat(workspaceService.list()).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void nextTopicOrderUsesMaximumWithoutLoadingAllTopics() {
        topic(current, "science", -20);
        topic(current, "science", -10);
        topic(other, "science", 900);
        clearPersistenceContextAndStatistics();

        ApiDtos.KnowledgeTopicView created = knowledgeService.createTopic(
                new ApiDtos.CreateKnowledgeTopicRequest("science", "created", null, null));

        assertThat(created.sortOrder()).isZero();
        assertThat(created.entryCount()).isZero();
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void memberWorkspaceListUsesTwoQueriesAndPreservesMembershipOrderAndPermissions() {
        List<WorkspaceEntity> visible = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            UserEntity owner = user("owner" + i);
            WorkspaceEntity workspace = workspace(owner, "workspace" + i);
            visible.add(workspace);
            member(workspace, current, WorkspaceMemberRole.VIEWER, Set.of("CONTENT_READ"));
            member(workspace, owner, WorkspaceMemberRole.OWNER, WorkspaceService.ALL);
            member(workspace, user("member" + i), WorkspaceMemberRole.MEMBER, Set.of());
        }
        workspace(current, "owner-without-membership");
        member(workspace(other, "hidden"), other, WorkspaceMemberRole.OWNER, WorkspaceService.ALL);
        clearPersistenceContextAndStatistics();

        List<ApiDtos.WorkspaceView> result = workspaceService.list();

        visible.sort(Comparator.comparing(WorkspaceEntity::getId).reversed());
        assertThat(result).extracting(ApiDtos.WorkspaceView::id)
                .containsExactlyElementsOf(visible.stream().map(WorkspaceEntity::getId).toList());
        assertThat(result).allSatisfy(view -> {
            assertThat(view.currentRole()).isEqualTo(WorkspaceMemberRole.VIEWER);
            assertThat(view.currentPermissions()).containsExactly("CONTENT_READ");
            assertThat(view.members()).singleElement().satisfies(member -> {
                assertThat(member.userId()).isEqualTo(current.getId());
                assertThat(member.permissions()).containsExactly("CONTENT_READ");
            });
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void adminWorkspaceListIncludesEmptyWorkspacesAndUsesOwnRoleWithAllPermissions() {
        when(currentUserService.isSuperAdmin(current)).thenReturn(true);
        WorkspaceEntity empty = workspace(other, "empty");
        WorkspaceEntity joined = workspace(other, "joined");
        member(joined, current, WorkspaceMemberRole.VIEWER, Set.of("CONTENT_READ"));
        clearPersistenceContextAndStatistics();

        List<ApiDtos.WorkspaceView> result = workspaceService.list();

        assertThat(result).extracting(ApiDtos.WorkspaceView::id).containsExactly(empty.getId(), joined.getId());
        assertThat(result.get(0).currentRole()).isEqualTo(WorkspaceMemberRole.ADMIN);
        assertThat(result.get(0).members()).isEmpty();
        assertThat(result.get(1).currentRole()).isEqualTo(WorkspaceMemberRole.VIEWER);
        assertThat(result).allSatisfy(view -> assertThat(view.currentPermissions()).isEqualTo(WorkspaceService.ALL));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void eachWorkspaceListingObservesChangedPermissionsAndRevokedMembership() {
        WorkspaceEntity workspace = workspace(other, "workspace");
        WorkspaceMemberEntity own = member(workspace, current, WorkspaceMemberRole.MEMBER, Set.of("CONTENT_READ", "CONTENT_WRITE"));
        clearPersistenceContextAndStatistics();
        assertThat(workspaceService.list().get(0).currentPermissions()).contains("CONTENT_WRITE");

        entityManager.find(WorkspaceMemberEntity.class, own.getId()).update(WorkspaceMemberRole.VIEWER, Set.of("CONTENT_READ"));
        clearPersistenceContextAndStatistics();
        ApiDtos.WorkspaceView updated = workspaceService.list().get(0);
        assertThat(updated.currentRole()).isEqualTo(WorkspaceMemberRole.VIEWER);
        assertThat(updated.currentPermissions()).containsExactly("CONTENT_READ");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);

        entityManager.remove(entityManager.find(WorkspaceMemberEntity.class, own.getId()));
        clearPersistenceContextAndStatistics();
        assertThat(workspaceService.list()).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    private UserEntity user(String name) {
        String unique = name + nextUser++;
        RoleEntity role = new RoleEntity("role-" + unique, "Role", null, Set.of("knowledge:read", "knowledge:write"));
        entityManager.persist(role);
        UserEntity user = new UserEntity(unique, "not-a-real-password-hash", name, unique + "@test.local", role);
        entityManager.persist(user);
        return user;
    }

    private KnowledgeTopicEntity topic(UserEntity owner, String domain, int order) {
        KnowledgeTopicEntity topic = new KnowledgeTopicEntity(domain, "Topic " + nextTopic++, null, owner == null, owner, order);
        entityManager.persist(topic);
        return topic;
    }

    private KnowledgeEntryEntity entry(KnowledgeTopicEntity topic, String title, UserEntity owner, int order) {
        KnowledgeEntryEntity entry = new KnowledgeEntryEntity(topic, title, "summary", "body", "tag, tag, ,other", owner == null, owner, order);
        ReflectionTestUtils.setField(entry, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        entityManager.persist(entry);
        return entry;
    }

    private NoteEntity note(UserEntity owner) {
        NoteEntity note = new NoteEntity(owner, "note", "body", null, NoteStatus.DRAFT);
        entityManager.persist(note);
        return note;
    }

    private void reference(NoteEntity note, String id, NoteReferenceType type, int order) {
        entityManager.persist(new NoteReferenceEntity(note, type, id, null, order));
    }

    private WorkspaceEntity workspace(UserEntity owner, String slug) {
        WorkspaceEntity workspace = new WorkspaceEntity(slug, slug, "#000000", owner);
        entityManager.persist(workspace);
        return workspace;
    }

    private WorkspaceMemberEntity member(WorkspaceEntity workspace, UserEntity user, WorkspaceMemberRole role, Set<String> permissions) {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity(workspace, user, role, permissions);
        ReflectionTestUtils.setField(member, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        entityManager.persist(member);
        return member;
    }

    private void clearPersistenceContextAndStatistics() {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
    }
}
