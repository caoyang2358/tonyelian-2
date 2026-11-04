package cn.copper.phase2.attachment;

import cn.copper.phase2.attachment.AttachmentModels.AttachmentRow;
import cn.copper.phase2.attachment.AttachmentModels.AttachmentSummary;
import cn.copper.phase2.attachment.AttachmentRepository.AttachmentQuery;
import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.PageResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1-F01 / F02 / F04 的读取契约。
 *
 * <p>重点：归属类型和文件类别只接受契约枚举（非法值不能当成"全部"），
 * 版本与内容地址必须由服务端给，元信息在但文件丢了要单独报 404。</p>
 */
class AttachmentServiceTest {

    private static AttachmentRow row() {
        return new AttachmentRow(12L, "EQUIPMENT", "3", "MANUAL", "设备手册.pdf",
                "application/pdf", 2048L, "2026/11/aaaaaaaa-1111-4111-8111-111111111111",
                2L, OffsetDateTime.parse("2026-11-04T10:00:00+08:00"));
    }

    private static AttachmentRepository repository() {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        when(repository.search(any())).thenReturn(List.of(row()));
        when(repository.count(any())).thenReturn(1L);
        when(repository.findRow(12L)).thenReturn(Optional.of(row()));
        return repository;
    }

    @Test
    void searchNormalizesOwnerTypeAndKeepsKindOptional() {
        AttachmentRepository repository = repository();
        AttachmentService service = new AttachmentService(repository, mock(AttachmentStorage.class));

        PageResult<AttachmentSummary> page = service.search("equipment", "3", null, null, null);

        ArgumentCaptor<AttachmentQuery> captor = ArgumentCaptor.forClass(AttachmentQuery.class);
        verify(repository).search(captor.capture());
        AttachmentQuery query = captor.getValue();
        assertEquals("EQUIPMENT", query.ownerType());
        assertEquals(3L, query.ownerId());
        assertNull(query.fileKind());
        assertEquals(1, query.page());
        assertEquals(20, query.pageSize());

        AttachmentSummary item = page.items().get(0);
        assertEquals("12", item.attachmentId());
        assertEquals(2, item.version());
        assertEquals(2048L, item.size());
        // 受控地址由服务端拼，前端不应该自己拼 /api/v1 前缀。
        assertEquals("/api/v1/attachments/12/content", item.contentPath());
        assertEquals("/api/v1/attachments/12/content?download=true", item.downloadPath());
    }

    @Test
    void ownerTypeIsRequired() {
        AttachmentService service = new AttachmentService(mock(AttachmentRepository.class),
                mock(AttachmentStorage.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.search(null, "3", null, null, null));

        assertTrue(fields(error).containsKey("ownerType"));
    }

    @Test
    void unknownOwnerTypeIsRejected() {
        AttachmentService service = new AttachmentService(mock(AttachmentRepository.class),
                mock(AttachmentStorage.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.search("WORKSHOP", "3", null, null, null));

        assertEquals("VALIDATION_ERROR", error.errorCode().name());
        assertTrue(fields(error).get("ownerType").contains("EQUIPMENT"));
    }

    @Test
    void unknownFileKindIsRejected() {
        AttachmentService service = new AttachmentService(mock(AttachmentRepository.class),
                mock(AttachmentStorage.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.search("EQUIPMENT", "3", "VIDEO", null, null));

        assertTrue(fields(error).containsKey("fileKind"));
    }

    @Test
    void pageSizeIsBounded() {
        AttachmentService service = new AttachmentService(mock(AttachmentRepository.class),
                mock(AttachmentStorage.class));

        assertThrows(BusinessException.class, () -> service.search("EQUIPMENT", "3", null, 1, 0));
        assertThrows(BusinessException.class, () -> service.search("EQUIPMENT", "3", null, 1, 101));
        assertThrows(BusinessException.class, () -> service.search("EQUIPMENT", "3", null, 0, 20));
    }

    @Test
    void detailReportsMissingAttachment() {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        when(repository.findRow(99L)).thenReturn(Optional.empty());
        AttachmentService service = new AttachmentService(repository, mock(AttachmentStorage.class));

        BusinessException error = assertThrows(BusinessException.class, () -> service.detail("99"));

        assertEquals("NOT_FOUND", error.errorCode().name());
    }

    @Test
    void contentReportsLostFileSeparately() throws IOException {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        when(storage.locate(any())).thenReturn(Path.of("lost.pdf"));
        when(storage.sizeOf(any())).thenThrow(new IOException("gone"));
        AttachmentService service = new AttachmentService(repository(), storage);

        BusinessException error = assertThrows(BusinessException.class, () -> service.content("12"));

        assertEquals("NOT_FOUND", error.errorCode().name());
        assertTrue(error.getMessage().contains("丢失"));
    }

    @Test
    void contentReturnsRowPathAndSize() throws IOException {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        when(storage.locate(any())).thenReturn(Path.of("ok.pdf"));
        when(storage.sizeOf(any())).thenReturn(2048L);
        AttachmentService service = new AttachmentService(repository(), storage);

        AttachmentService.AttachmentContent content = service.content("12");

        assertEquals(2048L, content.size());
        assertEquals("application/pdf", content.row().mediaType());
        verify(storage, never()).persist(any());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> fields(BusinessException error) {
        Object nested = error.details().get("fields");
        return nested instanceof java.util.Map<?, ?> map
                ? (java.util.Map<String, String>) map
                : java.util.Map.of();
    }
}
