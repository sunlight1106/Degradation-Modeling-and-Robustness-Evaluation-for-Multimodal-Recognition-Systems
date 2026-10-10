package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.FileAssetEntity;
import com.robustvision.platform.domain.FileScanStatus;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.repository.FileAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileContentSecurityTest {
    private final FileAssetRepository repository = mock(FileAssetRepository.class);
    private final CurrentUserService users = mock(CurrentUserService.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final AntivirusService antivirus = mock(AntivirusService.class);
    private final UserEntity owner = mock(UserEntity.class);
    private FileService files;
    @BeforeEach void setUp() {
        files = new FileService(repository, users, storage, antivirus, new ContentInspectionService(),
                mock(MediaProcessingService.class), "/tmp/content-security-legacy-not-read", 20 * 1024 * 1024);
        when(users.requireCurrent()).thenReturn(owner);
        when(owner.getId()).thenReturn(123L);
        org.springframework.test.util.ReflectionTestUtils.setField(files,"moderation",mock(ModerationGuard.class));
    }
    @Test void unsafeAttachmentNeverReachesAntivirusStorageOrDatabase() {
        var attachment = new MockMultipartFile("file", "private-name.txt", "text/plain", ContentInspectionServiceTest.bytes("<script>example</script>"));
        assertThatThrownBy(() -> files.storeMessageAttachment(attachment, owner)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("FILE_ACTIVE_CONTENT_BLOCKED"));
        verifyNoInteractions(antivirus, storage, repository);
    }
    @Test void mediaEndpointAlsoRejectsDisguisedActiveFilesBeforePersistence() {
        var upload = new MockMultipartFile("file", "image.png", "image/png", ContentInspectionServiceTest.bytes("MZ harmless synthetic file"));
        assertThatThrownBy(() -> files.upload(upload)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(antivirus, storage, repository);
    }
    @Test void requiredScannerFailurePreventsStorageAndPersistence() {
        when(antivirus.scan(any())).thenThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "VIRUS_SCANNER_UNAVAILABLE", "Unavailable"));
        var attachment = new MockMultipartFile("file", "notes.md", "text/markdown", ContentInspectionServiceTest.bytes("# Safe notes"));
        assertThatThrownBy(() -> files.storeMessageAttachment(attachment, owner)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage, repository);
    }
    @Test void optionalSkippedScannerIsNeverStoredAsClean() {
        when(antivirus.scan(any())).thenReturn(new AntivirusService.ScanResult(FileScanStatus.SKIPPED, "ClamAV unavailable"));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var attachment = new MockMultipartFile("file", "notes.md", "text/markdown", ContentInspectionServiceTest.bytes("# Code\n```js\nalert('example')\n```"));
        FileAssetEntity result = files.storeMessageAttachment(attachment, owner);
        assertThat(result.getScanStatus()).isEqualTo(FileScanStatus.SKIPPED);
        verify(storage).put(any(), any(), eq("text/plain"));
    }
}
