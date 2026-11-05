package cn.copper.phase2.attachment;

import cn.copper.phase2.attachment.AttachmentModels.AttachmentDetail;
import cn.copper.phase2.attachment.AttachmentModels.AttachmentRow;
import cn.copper.phase2.attachment.AttachmentStorage.FileFacts;
import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1-F03 / F05 的写入契约。
 *
 * <p>四件事必须成立：业务对象不存在时不能落盘；替换时版本不符一个字都不能写；
 * 替换成功后旧文件要在提交之后才删；类别不能借替换改名换姓。</p>
 */
class AttachmentWriteServiceTest {

    private static final AttachmentDetail DETAIL = new AttachmentDetail("12", "EQUIPMENT", "3",
            "MANUAL", "手册.pdf", "application/pdf", 2048L, 1L,
            OffsetDateTime.parse("2026-11-04T10:00:00+08:00"),
            "/api/v1/attachments/12/content", "/api/v1/attachments/12/content?download=true");

    private static final FileFacts FACTS =
            new AttachmentStorage.FileFacts("手册.pdf", "application/pdf", 2048L, "a".repeat(64));

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "手册.pdf", "application/pdf", new byte[]{1, 2, 3});
    }

    private static AttachmentRow row(long version) {
        return new AttachmentRow(12L, "EQUIPMENT", "3", "MANUAL", "旧手册.pdf",
                "application/pdf", 1024L, "2026/11/old", version,
                OffsetDateTime.parse("2026-11-04T09:00:00+08:00"));
    }

    @SuppressWarnings("unchecked")
    private static IdempotencyService passThrough() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(4)).get());
        return idempotency;
    }

    private static AttachmentWriteService service(AttachmentRepository repository,
                                                  AttachmentStorage storage,
                                                  ChangeLogRepository changeLog) {
        AttachmentService reader = mock(AttachmentService.class);
        when(reader.detail(12L)).thenReturn(DETAIL);
        return new AttachmentWriteService(repository, storage, changeLog, passThrough(), reader);
    }

    @Test
    void uploadRequiresFileKind() {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        AttachmentWriteService service = service(mock(AttachmentRepository.class), storage,
                mock(ChangeLogRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.upload("rid-1", "设备管理员", "EQUIPMENT", "3", null, file()));

        assertTrue(fields(error).containsKey("fileKind"));
        verify(storage, never()).inspect(any(), any());
    }

    /** 归属对象不存在时必须 404，并且不能先在磁盘上留一份文件。 */
    @Test
    void uploadRejectsMissingOwnerBeforeWriting() {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        when(storage.inspect(any(), eq("MANUAL"))).thenReturn(FACTS);
        AttachmentRepository repository = mock(AttachmentRepository.class);
        when(repository.ownerExists("EQUIPMENT", 3L)).thenReturn(false);
        AttachmentWriteService service = service(repository, storage, mock(ChangeLogRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.upload("rid-2", "设备管理员", "EQUIPMENT", "3", "MANUAL", file()));

        assertEquals("NOT_FOUND", error.errorCode().name());
        assertTrue(error.getMessage().contains("设备"));
        verify(storage, never()).persist(any());
        verify(repository, never()).insert(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyLong(), anyString());
    }

    @Test
    void uploadInsertsRowAndWritesCreateLog() {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        when(storage.inspect(any(), eq("MANUAL"))).thenReturn(FACTS);
        when(storage.persist(any())).thenReturn("2026/11/new");
        AttachmentRepository repository = mock(AttachmentRepository.class);
        when(repository.ownerExists("EQUIPMENT", 3L)).thenReturn(true);
        when(repository.insert("EQUIPMENT", 3L, "MANUAL", "手册.pdf", "application/pdf", 2048L,
                "2026/11/new")).thenReturn(12L);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        AttachmentWriteService service = service(repository, storage, changeLog);

        AttachmentDetail result = service.upload("rid-3", "设备管理员", "EQUIPMENT", "3", "manual", file());

        assertEquals("12", result.attachmentId());
        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        assertEquals("CREATE", captor.getValue().action());
        assertEquals("ATTACHMENT", captor.getValue().objectType());
        assertEquals(0, captor.getValue().versionBefore());
        assertEquals(1, captor.getValue().versionAfter());
        assertTrue(captor.getValue().after().toString().contains("手册.pdf"));
    }

    @Test
    void replaceRequiresReason() {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        AttachmentWriteService service = service(mock(AttachmentRepository.class), storage,
                mock(ChangeLogRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replace("12", "rid-4", "设备管理员", "1", "  ", file()));

        assertTrue(fields(error).containsKey("reason"));
        verify(storage, never()).persist(any());
    }

    @Test
    void replaceStopsOnVersionConflict() {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        when(storage.inspectAnyKind(any())).thenReturn(FACTS);
        AttachmentRepository repository = mock(AttachmentRepository.class);
        when(repository.lockRow(12L)).thenReturn(Optional.of(row(3L)));
        AttachmentWriteService service = service(repository, storage, mock(ChangeLogRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replace("12", "rid-5", "设备管理员", "1", "页面更新", file()));

        assertEquals("VERSION_CONFLICT", error.errorCode().name());
        verify(storage, never()).persist(any());
        verify(repository, never()).updateContent(anyLong(), anyString(), anyString(), anyLong(),
                anyString(), anyLong());
    }

    /** 替换不允许把照片位换成文档：类别沿用原附件，类型校验也沿用原类别。 */
    @Test
    void replaceKeepsOriginalFileKindAndRejectsMismatch() {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        when(storage.inspectAnyKind(any())).thenReturn(FACTS);
        AttachmentRepository repository = mock(AttachmentRepository.class);
        AttachmentRow image = new AttachmentRow(12L, "EQUIPMENT", "3", "IMAGE", "图.png",
                "image/png", 100L, "2026/11/img", 2L, null);
        when(repository.lockRow(12L)).thenReturn(Optional.of(image));
        AttachmentWriteService service = service(repository, storage, mock(ChangeLogRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replace("12", "rid-6", "设备管理员", "2", "换一张", file()));

        assertEquals("VALIDATION_ERROR", error.errorCode().name());
        assertTrue(fields(error).get("file").contains("IMAGE"));
        verify(storage, never()).persist(any());
    }

    @Test
    void replaceUpdatesContentThenDeletesOldFileAfterCommit() {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        when(storage.inspectAnyKind(any())).thenReturn(FACTS);
        when(storage.persist(any())).thenReturn("2026/11/new");
        AttachmentRepository repository = mock(AttachmentRepository.class);
        when(repository.lockRow(12L)).thenReturn(Optional.of(row(2L)));
        when(repository.updateContent(12L, "手册.pdf", "application/pdf", 2048L, "2026/11/new", 2L))
                .thenReturn(1);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        AttachmentWriteService service = service(repository, storage, changeLog);

        AttachmentDetail result = service.replace("12", "rid-7", "设备管理员", "2", "厂家补发新版",
                file());

        assertEquals("12", result.attachmentId());
        verify(repository).updateContent(12L, "手册.pdf", "application/pdf", 2048L, "2026/11/new", 2L);
        verify(storage).deleteAfterCommit("2026/11/old");

        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        assertEquals("UPDATE", captor.getValue().action());
        assertEquals("厂家补发新版", captor.getValue().reason());
        assertEquals(2, captor.getValue().versionBefore());
        assertEquals(3, captor.getValue().versionAfter());
        assertTrue(captor.getValue().before().toString().contains("旧手册.pdf"));
    }

    @Test
    void replaceReportsMissingAttachment() {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        when(storage.inspectAnyKind(any())).thenReturn(FACTS);
        AttachmentRepository repository = mock(AttachmentRepository.class);
        when(repository.lockRow(12L)).thenReturn(Optional.empty());
        AttachmentWriteService service = service(repository, storage, mock(ChangeLogRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replace("12", "rid-8", "设备管理员", "1", "替换", file()));

        assertEquals("NOT_FOUND", error.errorCode().name());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> fields(BusinessException error) {
        Object nested = error.details().get("fields");
        return nested instanceof java.util.Map<?, ?> map
                ? (java.util.Map<String, String>) map
                : java.util.Map.of();
    }
}
