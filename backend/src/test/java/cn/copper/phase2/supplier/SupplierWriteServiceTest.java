package cn.copper.phase2.supplier;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.supplier.SupplierModels.SupplierDetail;
import cn.copper.phase2.supplier.SupplierRepository.SupplierRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * S1-S03 / S1-S04 的写入契约。
 *
 * <p>重点在几个容易写错的地方：可空字段必须显式出现、设备集合去重、
 * 空数组必须真的解除关联而不会被当成"没传"、版本不符时不能落库。</p>
 */
class SupplierWriteServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final SupplierDetail DETAIL = new SupplierDetail("7", "华东机电", "张工",
            "021-0000", null, List.of("1"), 1, OffsetDateTime.parse("2026-11-04T10:00:00+08:00"));

    private static SupplierRow row(long version) {
        return new SupplierRow(7L, "华东机电", "张工", "021-0000", null, version,
                OffsetDateTime.parse("2026-11-04T09:00:00+08:00"));
    }

    @SuppressWarnings("unchecked")
    private static IdempotencyService passThrough() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(4)).get());
        return idempotency;
    }

    private static JsonNode body(String name, String equipmentIds, boolean withVersion) throws Exception {
        String versionField = withVersion ? "\"expectedVersion\":1," : "";
        return JSON.readTree("""
                {"requestId":"3f2a1b00-0000-4000-8000-000000000077","operatorName":"设备管理员",
                 %s"name":%s,"contactName":null,"phone":null,"remark":null,"equipmentIds":%s}
                """.formatted(versionField, name, equipmentIds));
    }

    private static SupplierWriteService service(SupplierRepository repository,
                                                ChangeLogRepository changeLog) {
        SupplierService reader = mock(SupplierService.class);
        when(reader.detail(anyLong())).thenReturn(DETAIL);
        return new SupplierWriteService(repository, changeLog, passThrough(), reader);
    }

    @Test
    void rejectsMissingOptionalFieldInsteadOfTreatingItAsCleared() throws Exception {
        JsonNode body = JSON.readTree("""
                {"requestId":"3f2a1b00-0000-4000-8000-000000000078","operatorName":"设备管理员",
                 "name":"华东机电","equipmentIds":[]}
                """);
        SupplierRepository repository = mock(SupplierRepository.class);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, mock(ChangeLogRepository.class)).create(body));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        Map<?, ?> fields = (Map<?, ?>) error.details().get("fields");
        assertTrue(fields.containsKey("contactName"), fields.toString());
        verify(repository, never()).insert(anyString(), any(), any(), any());
    }

    @Test
    void rejectsBlankName() throws Exception {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service(mock(SupplierRepository.class), mock(ChangeLogRepository.class))
                        .create(body("\"   \"", "[]", false)));

        Map<?, ?> fields = (Map<?, ?>) error.details().get("fields");
        assertTrue(fields.containsKey("name"), fields.toString());
    }

    @Test
    void rejectsNonArrayEquipmentIds() throws Exception {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service(mock(SupplierRepository.class), mock(ChangeLogRepository.class))
                        .create(body("\"华东机电\"", "\"1\"", false)));

        Map<?, ?> fields = (Map<?, ?>) error.details().get("fields");
        assertTrue(fields.containsKey("equipmentIds"), fields.toString());
    }

    @Test
    void deDuplicatesEquipmentIdsAndKeepsFirstOccurrenceOrder() throws Exception {
        SupplierRepository repository = mock(SupplierRepository.class);
        when(repository.usableEquipmentIds(List.of(2L, 1L))).thenReturn(List.of(2L, 1L));
        when(repository.insert("华东机电", null, null, null)).thenReturn(7L);

        service(repository, mock(ChangeLogRepository.class))
                .create(body("\"华东机电\"", "[\"2\",\"1\",\"2\"]", false));

        verify(repository).replaceAssociations(7L, List.of(2L, 1L));
    }

    @Test
    void rejectsEquipmentThatDoesNotExistOrIsNotProduction() throws Exception {
        SupplierRepository repository = mock(SupplierRepository.class);
        when(repository.usableEquipmentIds(List.of(2L, 999L))).thenReturn(List.of(2L));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, mock(ChangeLogRepository.class))
                        .create(body("\"华东机电\"", "[\"2\",\"999\"]", false)));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        // 具体文案放在 details.fields 里，与设备写入接口的错误结构保持一致。
        Map<?, ?> fields = (Map<?, ?>) error.details().get("fields");
        assertTrue(fields.get("equipmentIds").toString().contains("999"), fields.toString());
        verify(repository, never()).insert(anyString(), any(), any(), any());
    }

    @Test
    void updateRejectsStaleVersionBeforeWritingAnything() throws Exception {
        SupplierRepository repository = mock(SupplierRepository.class);
        when(repository.lockRow(7L)).thenReturn(Optional.of(row(3)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, mock(ChangeLogRepository.class))
                        .update("7", body("\"华东机电\"", "[\"1\"]", true)));

        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
        assertEquals(1L, error.details().get("expectedVersion"));
        assertEquals(3L, error.details().get("currentVersion"));
        verify(repository, never()).update(anyLong(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void updateWithEmptyArrayUnbindsEveryEquipmentAndKeepsAuditTrail() throws Exception {
        SupplierRepository repository = mock(SupplierRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        when(repository.lockRow(7L)).thenReturn(Optional.of(row(1)));
        when(repository.equipmentIds(7L)).thenReturn(List.of(1L));
        when(repository.update(eq(7L), eq("华东机电"), any(), any(), any(), eq(1L))).thenReturn(1);

        service(repository, changeLog).update("7", body("\"华东机电\"", "[]", true));

        verify(repository).replaceAssociations(7L, List.of());

        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        ChangeLogWrite entry = captor.getValue();
        assertEquals("SUPPLIER", entry.objectType());
        assertEquals("UPDATE", entry.action());
        assertEquals(1, entry.versionBefore());
        assertEquals(2, entry.versionAfter());
        assertTrue(entry.before().toString().contains("[1]"), entry.before().toString());
        assertTrue(entry.after().toString().contains("[]"), entry.after().toString());
    }

    @Test
    void createWritesAuditEntryWithVersionZeroToOne() throws Exception {
        SupplierRepository repository = mock(SupplierRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        when(repository.usableEquipmentIds(List.of(1L))).thenReturn(List.of(1L));
        when(repository.insert("华东机电", null, null, null)).thenReturn(7L);

        service(repository, changeLog).create(body("\"华东机电\"", "[\"1\"]", false));

        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        assertEquals("CREATE", captor.getValue().action());
        assertEquals(0, captor.getValue().versionBefore());
        assertEquals(1, captor.getValue().versionAfter());
    }
}
