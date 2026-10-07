package com.robustvision.platform.service;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.PersonalAiDtos;
import com.robustvision.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeAnswerServiceTest {
    @Test void changedSourcesCannotBeSentAndForeignUserCannotConsumeApproval(){
        var search=mock(KnowledgeSearchService.class);var ai=mock(PersonalAiService.class);var current=mock(CurrentUserService.class);
        var owner=mock(UserEntity.class);when(owner.getId()).thenReturn(1L);when(current.requireCurrent()).thenReturn(owner);
        var source=new KnowledgeSearchService.Source("one","NOTE","Own note","private evidence","/app/notes/one/edit",Instant.EPOCH);
        when(search.source("NOTE","one")).thenReturn(source);
        when(ai.preview(any())).thenReturn(new PersonalAiDtos.PreviewView("token",Instant.now().plusSeconds(60),AiProvider.QWEN,"model","endpoint","answer","context","system",100));
        var service=new KnowledgeAnswerService(search,ai,current);
        service.preview(new KnowledgeAnswerService.Question(AiProvider.QWEN,"Question?",List.of(new KnowledgeAnswerService.Ref("NOTE","one"))));
        when(owner.getId()).thenReturn(2L);
        assertThatThrownBy(()->service.execute(new PersonalAiDtos.ExecuteRequest("token",true))).isInstanceOf(BusinessException.class);
        when(owner.getId()).thenReturn(1L);when(search.source("NOTE","one")).thenReturn(new KnowledgeSearchService.Source("one","NOTE","Own note","changed","/app/notes/one/edit",Instant.now()));
        assertThatThrownBy(()->service.execute(new PersonalAiDtos.ExecuteRequest("token",true))).isInstanceOf(BusinessException.class);
        verify(ai,never()).executeKnowledge(any());
    }
    @Test void revokedGroupAccessIsCheckedAgainBeforeExecution(){
        var search=mock(KnowledgeSearchService.class);var ai=mock(PersonalAiService.class);var current=mock(CurrentUserService.class);
        var owner=mock(UserEntity.class);when(owner.getId()).thenReturn(1L);when(current.requireCurrent()).thenReturn(owner);
        when(search.source("GROUP","one")).thenReturn(new KnowledgeSearchService.Source("one","GROUP","Group","data","/group",Instant.EPOCH));
        when(ai.preview(any())).thenReturn(new PersonalAiDtos.PreviewView("token",Instant.now().plusSeconds(60),AiProvider.QWEN,"model","endpoint","answer","context","system",100));
        var service=new KnowledgeAnswerService(search,ai,current);service.preview(new KnowledgeAnswerService.Question(AiProvider.QWEN,"Q",List.of(new KnowledgeAnswerService.Ref("GROUP","one"))));
        when(search.source("GROUP","one")).thenThrow(new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,"SOURCE_UNAVAILABLE","Unavailable"));
        assertThatThrownBy(()->service.execute(new PersonalAiDtos.ExecuteRequest("token",true))).isInstanceOf(BusinessException.class);
        verify(ai,never()).executeKnowledge(any());
    }
    @Test void unicodeMetricsCountCharactersAndNotUtf16Units(){assertThat(BenchmarkService.distance("😀字","😀词")).isEqualTo(1);assertThat(BenchmarkService.distance("abc","abXYc")).isEqualTo(2);}
}
