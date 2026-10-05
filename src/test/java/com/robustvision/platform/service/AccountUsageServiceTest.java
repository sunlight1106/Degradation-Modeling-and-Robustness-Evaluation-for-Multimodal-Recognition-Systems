package com.robustvision.platform.service;
import com.robustvision.platform.domain.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
@DataJpaTest(showSql=false)
@Import(AccountUsageService.class)
class AccountUsageServiceTest {
    @Autowired EntityManager em;
    @Autowired AccountUsageService usage;
    @MockBean CurrentUserService current;
    @Test void allTimeUsageCountsAreOwnerScopedAndUnknownTokensStayVisible() {
        var role=new RoleEntity("USAGE","User",null,Set.of());em.persist(role);
        var a=new UserEntity("usage-a","fixture","A","ua@example.invalid",role);em.persist(a);
        var b=new UserEntity("usage-b","fixture","B","ub@example.invalid",role);em.persist(b);
        em.persist(new PersonalAiUsageEntity(a.getId(),AiProvider.OPENAI,"m","tidy","SUCCEEDED",10,4,null));
        em.persist(new PersonalAiUsageEntity(a.getId(),AiProvider.OPENAI,"m","tidy","FAILED",(Long)null,(Long)null,"UPSTREAM"));
        em.persist(new PersonalAiUsageEntity(b.getId(),AiProvider.OPENAI,"m","tidy","SUCCEEDED",999,888,null));
        em.persist(new NoteEntity(a,"private","body",null,NoteStatus.DRAFT));em.flush();
        when(current.requireCurrent()).thenReturn(a);
        var summary=usage.summary();assertThat(summary.ai().total()).isEqualTo(2);
        assertThat(summary.ai().succeeded()).isEqualTo(1);assertThat(summary.ai().failed()).isEqualTo(1);
        assertThat(summary.ai().knownInputTokens()).isEqualTo(10);assertThat(summary.ai().knownOutputTokens()).isEqualTo(4);
        assertThat(summary.ai().unknownUsageCalls()).isEqualTo(1);assertThat(summary.noteCount()).isEqualTo(1);
        assertThat(summary.experiments().total()).isZero();assertThat(summary.files().bytes()).isZero();
    }
}
