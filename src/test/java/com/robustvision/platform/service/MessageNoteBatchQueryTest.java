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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DataJpaTest(showSql = false, properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@Import({MessageService.class, NoteService.class, NoteHistoryService.class, LiveUpdateService.class})
class MessageNoteBatchQueryTest {
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory factory;
    @Autowired MessageService messages;
    @Autowired NoteService notes;
    @MockBean CurrentUserService currentUser;
    @MockBean FileService files;
    @MockBean WorkspaceService workspaces;
    @MockBean NoteReferenceService references;
    private UserEntity current;
    private UserEntity other;
    private Statistics statistics;
    private int sequence;

    @BeforeEach
    void setup() {
        current = user();
        other = user();
        when(currentUser.requireCurrent()).thenReturn(current);
        when(currentUser.isSuperAdmin(current)).thenReturn(false);
        statistics = factory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void inboxUsesThreeQueriesAndPreservesRecipientsAttachmentsReadFlagsAndOrder() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            MessageEntity message = message(user(), "inbox " + i, i);
            expected.add(0, message.getId());
            recipient(message, other, true);
            recipient(message, current, i % 2 == 0);
            attachment(message, "first.txt");
            attachment(message, "second.txt");
        }
        recipient(message(other, "hidden", 1000), other, true);
        clear();

        List<ApiDtos.MessageView> result = messages.inbox();

        assertThat(result).extracting(ApiDtos.MessageView::id).containsExactlyElementsOf(expected);
        for (int i = 0; i < result.size(); i++) {
            ApiDtos.MessageView view = result.get(i);
            assertThat(view.read()).isEqualTo((29 - i) % 2 == 0);
            assertThat(view.recipients()).extracting(ApiDtos.UserDirectoryView::id).containsExactly(other.getId(), current.getId());
            assertThat(view.attachments()).extracting(ApiDtos.MessageAttachmentView::fileName).containsExactly("first.txt", "second.txt");
        }
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void sentUsesThreeQueriesAndSenderReadsIncludingSelfAddressedMessages() {
        MessageEntity earlier = message(current, "earlier", 1);
        recipient(earlier, other, false);
        MessageEntity latest = message(current, "latest", 2);
        recipient(latest, current, false);
        recipient(message(other, "not sent", 3), current, false);
        clear();

        List<ApiDtos.MessageView> result = messages.sent();

        assertThat(result).extracting(ApiDtos.MessageView::id).containsExactly(latest.getId(), earlier.getId());
        assertThat(result).allMatch(ApiDtos.MessageView::read);
        assertThat(result).allSatisfy(view -> assertThat(view.attachments()).isEmpty());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void listsAreEmptyWithoutChildQueriesAndAdminDoesNotBroadenInbox() {
        recipient(message(other, "hidden", 0), other, false);
        when(currentUser.isSuperAdmin(current)).thenReturn(true);
        clear();
        assertThat(messages.inbox()).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();
        assertThat(messages.sent()).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void messagesBeyondBatchBoundaryRemainCompleteAndOrdered() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            MessageEntity message = message(current, "message " + i, i);
            expected.add(0, message.getId());
            recipient(message, other, false);
        }
        clear();
        assertThat(messages.sent()).extracting(ApiDtos.MessageView::id).containsExactlyElementsOf(expected);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void detailMarksOnlyCurrentRecipientReadAndStillRejectsUnrelatedUsers() {
        MessageEntity message = message(other, "visible", 0);
        MessageRecipientEntity own = recipient(message, current, false);
        MessageRecipientEntity another = recipient(message, other, false);
        MessageEntity hidden = message(other, "hidden", 1);
        recipient(hidden, other, false);
        clear();
        assertThat(messages.detail(message.getId()).read()).isTrue();
        clear();
        assertThat(messages.inbox().get(0).read()).isTrue();
        assertThat(em.find(MessageRecipientEntity.class, own.getId()).getReadAt()).isNotNull();
        assertThat(em.find(MessageRecipientEntity.class, another.getId()).getReadAt()).isNull();
        assertThatThrownBy(() -> messages.detail(hidden.getId())).isInstanceOf(BusinessException.class).hasMessage("无权查看这封站内信");
    }

    @Test
    void notesCountAllShareStatesInTwoQueriesAndKeepOwnershipAndSearchStatus() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            NoteEntity note = new NoteEntity(current, "needle " + i, "# Body", "one,two", i % 2 == 0 ? NoteStatus.ACTIVE : NoteStatus.DRAFT);
            note.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
            em.persist(note);
            expected.add(0, note.getId());
            for (int j = 0; j < i % 3; j++) {
                NoteShareEntity share = new NoteShareEntity(note, current, UUID.randomUUID().toString().replace("-", ""), null, Instant.EPOCH);
                if (j == 1) share.revoke(Instant.EPOCH);
                em.persist(share);
            }
        }
        em.persist(new NoteEntity(other, "needle hidden", "body", null, NoteStatus.ACTIVE));
        clear();
        List<ApiDtos.NoteSummaryView> result = notes.list(null, null);
        assertThat(result).extracting(ApiDtos.NoteSummaryView::id).containsExactlyElementsOf(expected);
        assertThat(result.stream().mapToInt(ApiDtos.NoteSummaryView::shareCount).sum()).isEqualTo(24);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        clear();
        assertThat(notes.list("ACTIVE", " needle ")).hasSize(12).allMatch(note -> note.status().code().equals("ACTIVE"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    private UserEntity user() {
        String name = "u" + sequence++;
        RoleEntity role = new RoleEntity("role" + name, name, null, Set.of("read"));
        em.persist(role);
        UserEntity user = new UserEntity(name, "test-hash", name, name + "@test.local", role);
        em.persist(user);
        return user;
    }

    private MessageEntity message(UserEntity sender, String subject, int time) {
        MessageEntity message = new MessageEntity(sender, subject, "body");
        ReflectionTestUtils.setField(message, "createdAt", Instant.parse("2026-01-01T00:00:00Z").plusSeconds(time));
        em.persist(message);
        return message;
    }

    private MessageRecipientEntity recipient(MessageEntity message, UserEntity user, boolean read) {
        MessageRecipientEntity recipient = new MessageRecipientEntity(message, user);
        if (read) recipient.markRead();
        em.persist(recipient);
        return recipient;
    }

    private void attachment(MessageEntity message, String name) {
        FileAssetEntity file = new FileAssetEntity(name, UUID.randomUUID().toString(), "text/plain", 32,
                "a".repeat(64), "/tmp/test", other, FileSource.MESSAGE_ATTACHMENT, FileScanStatus.CLEAN, "test");
        em.persist(file);
        em.persist(new MessageAttachmentEntity(message, file));
    }

    private void clear() {
        em.flush();
        em.clear();
        statistics.clear();
    }
}
