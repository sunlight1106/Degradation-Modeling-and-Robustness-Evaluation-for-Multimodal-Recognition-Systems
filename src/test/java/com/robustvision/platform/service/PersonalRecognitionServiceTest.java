package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalRecognitionServiceTest {
    CurrentUserService current=mock(CurrentUserService.class);
    FileAssetRepository files=mock(FileAssetRepository.class);
    FileService fileService=mock(FileService.class);
    PersonalAiSettingRepository settings=mock(PersonalAiSettingRepository.class);
    PersonalRecognitionResultRepository results=mock(PersonalRecognitionResultRepository.class);
    PersonalAiUsageRepository usage=mock(PersonalAiUsageRepository.class);
    SecretEncryptionService encryption=mock(SecretEncryptionService.class);
    PersonalAiEndpointPolicy endpoints=new PersonalAiEndpointPolicy("");
    PersonalAiTransport transport=mock(PersonalAiTransport.class);
    PersonalAiSettingEntity setting;
    UserEntity a,b;
    FileAssetEntity file;
    String fileId=UUID.randomUUID().toString();
    @BeforeEach void setup() {
        var role=new RoleEntity("ADMIN","Admin",null,Set.of());
        a=new UserEntity("a","fixture","A","a@example.invalid",role);ReflectionTestUtils.setField(a,"id",1L);
        b=new UserEntity("b","fixture","B","b@example.invalid",role);ReflectionTestUtils.setField(b,"id",2L);
        when(current.requireCurrent()).thenReturn(a);
        setting=new PersonalAiSettingEntity(1L,AiProvider.OPENAI);setting.update("vision-fixture","https://api.openai.com/v1","encrypted-fixture",true);
        when(settings.findByOwnerIdAndProvider(1L,AiProvider.OPENAI)).thenReturn(Optional.of(setting));
        file=new FileAssetEntity("receipt.png","fixture","image/png",3,"a".repeat(64),"fixture",a,FileSource.UPLOAD,FileScanStatus.CLEAN,"fixture");
        ReflectionTestUtils.setField(file,"id",fileId);
        when(files.existsByIdAndOwnerId(fileId,1L)).thenReturn(true);when(files.findById(fileId)).thenReturn(Optional.of(file));
        when(fileService.readBytes(file)).thenReturn(new byte[]{1,2,3});
        when(transport.prepareVision(any(),anyString(),anyString(),anyString(),anyString(),any(),anyString()))
                .thenReturn(new PersonalAiTransport.Payload("https://api.openai.com/v1/chat/completions","{\"synthetic\":true}"));
        when(encryption.decrypt("encrypted-fixture")).thenReturn("synthetic-own-key");
        when(transport.execute(any(),any(),eq("synthetic-own-key"))).thenReturn(new PersonalAiTransport.Completion("商户：示例，金额未知",10,5));
        when(results.save(any())).thenAnswer(invocation->invocation.getArgument(0));
    }
    private PersonalRecognitionService service(boolean enabled){return new PersonalRecognitionService(current,files,fileService,settings,results,usage,encryption,endpoints,transport,new PersonalAiRateLimiter(),enabled);}
    @Test void approvedOwnImageUsesOnlyOwnKeyPersistsResultAndCannotReplay() {
        var service=service(true);var preview=service.preview(AiProvider.OPENAI,fileId,TaskType.RECEIPT);
        assertThat(preview.fileId()).isEqualTo(fileId);assertThat(preview.outboundBytes()).isPositive();
        verify(transport,never()).execute(any(),any(),anyString());
        var result=service.execute(preview.previewToken(),true);
        assertThat(result.result()).contains("金额未知");assertThat(result.inputTokens()).isEqualTo(10);
        verify(results).save(argThat(r->r.getOwnerId().equals(1L)&&r.getFileId().equals(fileId)));
        assertThatThrownBy(()->service.execute(preview.previewToken(),true)).isInstanceOf(BusinessException.class);
        verify(transport,times(1)).execute(any(),any(),eq("synthetic-own-key"));
    }
    @Test void adminCannotReadUseOtherOwnersFileKeyOrPreview() {
        var service=service(true);var preview=service.preview(AiProvider.OPENAI,fileId,TaskType.LICENSE_PLATE);
        when(current.requireCurrent()).thenReturn(b);
        assertThatThrownBy(()->service.execute(preview.previewToken(),true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.preview(AiProvider.OPENAI,fileId,TaskType.RECEIPT)).isInstanceOf(BusinessException.class);
        verify(transport,never()).execute(any(),any(),anyString());
    }
    @Test void simultaneousSameOwnerPreviewIsRejectedBeforeLoadingAnotherImage() throws Exception {
        var service=service(true);
        var started=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(fileService.readBytes(file)).thenAnswer(call->{started.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException();return new byte[]{1,2,3};});
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->service.preview(AiProvider.OPENAI,fileId,TaskType.RECEIPT));
            assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.preview(AiProvider.OPENAI,fileId,TaskType.RECEIPT))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("繁忙");
            verify(fileService,times(1)).readBytes(file);
            release.countDown(); assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void missingConsentDisabledDeploymentAndChangedSettingsFailClosed() {
        var disabled=service(false);var preview=disabled.preview(AiProvider.OPENAI,fileId,TaskType.RECEIPT);
        assertThatThrownBy(()->disabled.execute(preview.previewToken(),true)).isInstanceOf(BusinessException.class).hasMessageContaining("尚未启用");
        var service=service(true);var approved=service.preview(AiProvider.OPENAI,fileId,TaskType.RECEIPT);
        assertThatThrownBy(()->service.execute(approved.previewToken(),false)).isInstanceOf(BusinessException.class);
        ReflectionTestUtils.setField(setting,"revision",1L);
        assertThatThrownBy(()->service.execute(approved.previewToken(),true)).isInstanceOf(BusinessException.class).hasMessageContaining("已更改");
        verify(transport,never()).execute(any(),any(),anyString());
    }
    @Test void failedRecognitionPersistsReportedUsageWithoutStoringUnusableOutput() {
        when(transport.execute(any(),any(),anyString())).thenThrow(new PersonalAiUpstreamFailure(80L,1600L));
        var service=service(true);var preview=service.preview(AiProvider.OPENAI,fileId,TaskType.RECEIPT);
        assertThatThrownBy(()->service.execute(preview.previewToken(),true)).isInstanceOf(BusinessException.class);
        verify(usage).save(argThat(row -> row.getStatus().equals("FAILED") && Long.valueOf(80).equals(row.getInputTokens())
                && Long.valueOf(1600).equals(row.getOutputTokens())));
        verify(results,never()).save(any());
    }
    @Test void ownershipRecheckedAtExecuteAndUnscannedOrUnverifiedVisionRejected() {
        var service=service(true);var preview=service.preview(AiProvider.OPENAI,fileId,TaskType.RECEIPT);
        when(files.existsByIdAndOwnerId(fileId,1L)).thenReturn(false);
        assertThatThrownBy(()->service.execute(preview.previewToken(),true)).isInstanceOf(BusinessException.class);
        when(files.existsByIdAndOwnerId(fileId,1L)).thenReturn(true);
        ReflectionTestUtils.setField(file,"scanStatus",FileScanStatus.SKIPPED);
        assertThatThrownBy(()->service.preview(AiProvider.OPENAI,fileId,TaskType.RECEIPT)).isInstanceOf(BusinessException.class).hasMessageContaining("病毒扫描");
        assertThatThrownBy(()->service.preview(AiProvider.DEEPSEEK,fileId,TaskType.RECEIPT)).isInstanceOf(BusinessException.class).hasMessageContaining("尚未验证");
        assertThatThrownBy(()->service.preview(AiProvider.OPENAI,fileId,TaskType.VIDEO_ANALYSIS)).isInstanceOf(BusinessException.class);
        verify(transport,never()).execute(any(),any(),anyString());
    }
}
