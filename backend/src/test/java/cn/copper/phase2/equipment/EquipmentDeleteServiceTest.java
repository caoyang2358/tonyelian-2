package cn.copper.phase2.equipment;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.equipment.EquipmentDeleteModels.EquipmentDeleteResult;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentProfile;
import cn.copper.phase2.equipment.EquipmentRepository.EquipmentRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EquipmentDeleteServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static EquipmentProfile profile() {
        return new EquipmentProfile("OTHER", null, null, null, null, null,
                null, null, null, null, "IN_USE", List.of());
    }

    private static EquipmentRow row(long version) {
        return new EquipmentRow("16", "EQUIP-DEMO-001", "示例生产设备", "1", "车间1 · 铜杆生产",
                null, true, profile(), version);
    }

    @SuppressWarnings("unchecked")
    private static IdempotencyService passThrough() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(4)).get());
        return idempotency;
    }

    private static JsonNode body(String confirmed) throws Exception {
        return JSON.readTree("""
                {"requestId":"d1e2f3a4-0000-4000-8000-000000000099","operatorName":"设备管理员",
                 "expectedVersion":2,"reason":"误建测试设备","confirmed":%s}
                """.formatted(confirmed));
    }

    private static EquipmentDeleteService service(EquipmentRepository repository,
                                                  ChangeLogRepository changeLog,
                                                  EquipmentReferenceChecker checker) {
        return new EquipmentDeleteService(repository, changeLog, passThrough(), checker);
    }

    @Test
    void deletesWhenNoReferenceExistsAndKeepsChangeLog() throws Exception {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        EquipmentReferenceChecker checker = mock(EquipmentReferenceChecker.class);
        when(repository.lockDetail(16L)).thenReturn(Optional.of(row(2L)));
        when(checker.blockers(16L)).thenReturn(List.of());
        when(repository.deleteEquipment(16L)).thenReturn(1);

        EquipmentDeleteResult result = service(repository, changeLog, checker).delete("16", body("true"));

        assertEquals("16", result.id());
        assertTrue(result.deleted());
        verify(repository).deleteProfile(16L);
        verify(repository).deleteEquipment(16L);

        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        ChangeLogWrite write = captor.getValue();
        assertEquals("DELETE", write.action());
        assertEquals("16", write.objectId());
        assertNotNull(write.before(), "删除留痕必须保留原身份快照");
        assertNull(write.after(), "删除后 after 必须为 null");
        assertEquals(2, write.versionBefore());
        assertNull(write.versionAfter());
        assertEquals("误建测试设备", write.reason());
    }

    @Test
    void refusesDeletionWhenBusinessReferenceExists() throws Exception {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        EquipmentReferenceChecker checker = mock(EquipmentReferenceChecker.class);
        when(repository.lockDetail(16L)).thenReturn(Optional.of(row(2L)));
        when(checker.blockers(16L)).thenReturn(List.of(
                new EquipmentModels.DeletionBlocker("SENSOR", 1L, "该设备已关联 1 条测点。")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, changeLog, checker).delete("16", body("true")));

        assertEquals(ErrorCode.RELATED_DATA_EXISTS, error.errorCode());
        assertNotNull(error.details().get("blockers"));
        verify(repository, never()).deleteProfile(anyLong());
        verify(repository, never()).deleteEquipment(anyLong());
        verifyNoInteractions(changeLog);
    }

    @Test
    void refusesDeletionWithoutSecondConfirmation() throws Exception {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        EquipmentReferenceChecker checker = mock(EquipmentReferenceChecker.class);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, changeLog, checker).delete("16", body("false")));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(((java.util.Map<?, ?>) error.details().get("fields")).containsKey("confirmed"));
        verifyNoInteractions(repository, changeLog);
    }

    @Test
    void reportsVersionConflictBeforeCheckingReferences() throws Exception {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        EquipmentReferenceChecker checker = mock(EquipmentReferenceChecker.class);
        when(repository.lockDetail(16L)).thenReturn(Optional.of(row(5L)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, changeLog, checker).delete("16", body("true")));

        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
        assertEquals(5L, error.details().get("currentVersion"));
        verifyNoInteractions(checker);
        verify(repository, never()).deleteEquipment(anyLong());
    }

    @Test
    void unknownEquipmentIsNotFound() throws Exception {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        EquipmentReferenceChecker checker = mock(EquipmentReferenceChecker.class);
        when(repository.lockDetail(16L)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, changeLog, checker).delete("16", body("true")));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
        verify(repository, never()).deleteEquipment(anyLong());
    }

    @Test
    void blankReasonIsRejected() throws Exception {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        EquipmentReferenceChecker checker = mock(EquipmentReferenceChecker.class);
        JsonNode payload = JSON.readTree("""
                {"requestId":"d1e2f3a4-0000-4000-8000-000000000098","operatorName":"设备管理员",
                 "expectedVersion":2,"reason":"   ","confirmed":true}
                """);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, mock(ChangeLogRepository.class), checker).delete("16", payload));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(((java.util.Map<?, ?>) error.details().get("fields")).containsKey("reason"));
        verifyNoInteractions(repository);
    }
}
