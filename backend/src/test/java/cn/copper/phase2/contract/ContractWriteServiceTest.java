package cn.copper.phase2.contract;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.contract.ContractModels.ContractDetail;
import cn.copper.phase2.contract.ContractRepository.ContractRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1-C03 / S1-C04 的写入契约。
 *
 * <p>重点在几处容易被当成"小事"的地方：起止日期只填一个必须被允许（契约要求待补日期保留 null）、
 * 两个都填才校验先后、覆盖设备空数组必须真的解除关联、版本不符时一个字都不能写。</p>
 */
class ContractWriteServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ContractDetail DETAIL = new ContractDetail("3", "华东维保服务中心", "HT-2026-001",
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), null,
            List.of("1"), List.of(), "ACTIVE", 57, 1,
            OffsetDateTime.parse("2026-11-04T10:00:00+08:00"));

    private static ContractRow row(long version) {
        return new ContractRow(3L, "华东维保服务中心", "HT-2026-001",
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), null, version,
                OffsetDateTime.parse("2026-11-04T09:00:00+08:00"));
    }

    @SuppressWarnings("unchecked")
    private static IdempotencyService passThrough() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(4)).get());
        return idempotency;
    }

    private static JsonNode body(String startDate, String endDate, String equipmentIds,
                                 boolean withVersion) throws Exception {
        String versionField = withVersion ? "\"expectedVersion\":1," : "";
        return JSON.readTree("""
                {"requestId":"3f2a1b00-0000-4000-8000-0000000000c1","operatorName":"设备管理员",
                 %s"serviceOrganization":"华东维保服务中心","contractNumber":"HT-2026-001",
                 "startDate":%s,"endDate":%s,"remark":null,"equipmentIds":%s}
                """.formatted(versionField, startDate, endDate, equipmentIds));
    }

    /** 字段级校验错误统一放在 details.fields 下，这里解一层便于断言。 */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> fields(BusinessException error) {
        Object nested = error.details().get("fields");
        return nested instanceof java.util.Map<?, ?> map
                ? (java.util.Map<String, Object>) map
                : java.util.Map.of();
    }

    private static ContractWriteService service(ContractRepository repository,
                                                ChangeLogRepository changeLog) {
        ContractService reader = mock(ContractService.class);
        when(reader.detail(anyLong())).thenReturn(DETAIL);
        return new ContractWriteService(repository, changeLog, passThrough(), reader);
    }

    @Test
    void rejectsMissingOptionalFieldInsteadOfTreatingItAsCleared() throws Exception {
        ContractRepository repository = mock(ContractRepository.class);
        ContractWriteService service = service(repository, mock(ChangeLogRepository.class));
        JsonNode body = JSON.readTree("""
                {"requestId":"r-1","operatorName":"设备管理员",
                 "serviceOrganization":"华东维保服务中心","contractNumber":null,
                 "startDate":null,"endDate":null,"equipmentIds":[]}
                """);

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(body));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(fields(error).containsKey("remark"), String.valueOf(fields(error)));
        verify(repository, never()).insert(anyString(), any(), any(), any(), any());
    }

    @Test
    void rejectsEndDateEarlierThanStartDate() throws Exception {
        ContractRepository repository = mock(ContractRepository.class);
        ContractWriteService service = service(repository, mock(ChangeLogRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(body("\"2026-12-31\"", "\"2026-01-01\"", "[]", false)));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(fields(error).containsKey("endDate"), String.valueOf(fields(error)));
    }

    @Test
    void rejectsMalformedDate() throws Exception {
        ContractRepository repository = mock(ContractRepository.class);
        ContractWriteService service = service(repository, mock(ChangeLogRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(body("\"2026/01/01\"", "null", "[]", false)));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(fields(error).containsKey("startDate"), String.valueOf(fields(error)));
    }

    /** 契约要求"待补日期保留 null"：只填开始日期不是错误，结束日期就存 null。 */
    @Test
    void allowsOnlyOneDateAndKeepsTheOtherNull() throws Exception {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.insert(anyString(), any(), any(), any(), any())).thenReturn(3L);
        ContractWriteService service = service(repository, mock(ChangeLogRepository.class));

        service.create(body("\"2026-01-01\"", "null", "[]", false));

        verify(repository).insert(eq("华东维保服务中心"), eq("HT-2026-001"),
                eq(LocalDate.parse("2026-01-01")), isNull(), isNull());
    }

    @Test
    void deduplicatesEquipmentIdsKeepingFirstOccurrenceOrder() throws Exception {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.insert(anyString(), any(), any(), any(), any())).thenReturn(3L);
        when(repository.usableEquipmentIds(any())).thenReturn(List.of(1L, 2L));
        ContractWriteService service = service(repository, mock(ChangeLogRepository.class));

        service.create(body("null", "null", "[\"2\",\"1\",\"2\"]", false));

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceEquipment(eq(3L), captor.capture());
        assertEquals(List.of(2L, 1L), captor.getValue());
    }

    /** 空数组是"解除全部覆盖关系"，必须真的清空关联表，不能被当成"没传所以不动"。 */
    @Test
    void emptyArrayClearsAllEquipmentLinks() throws Exception {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.lockRow(3L)).thenReturn(Optional.of(row(1)));
        when(repository.update(anyLong(), anyString(), any(), any(), any(), any(), anyLong()))
                .thenReturn(1);
        ContractWriteService service = service(repository, mock(ChangeLogRepository.class));

        service.update("3", body("null", "null", "[]", true));

        verify(repository).replaceEquipment(eq(3L), eq(List.of()));
    }

    @Test
    void rejectsVersionConflictWithoutWritingAnything() throws Exception {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.lockRow(3L)).thenReturn(Optional.of(row(5)));
        ContractWriteService service = service(repository, mock(ChangeLogRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update("3", body("null", "null", "[]", true)));

        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
        verify(repository, never()).update(anyLong(), anyString(), any(), any(), any(), any(), anyLong());
        verify(repository, never()).replaceEquipment(anyLong(), any());
    }

    @Test
    void rejectsUnknownEquipmentBeforeTouchingTheDatabase() throws Exception {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.usableEquipmentIds(any())).thenReturn(List.of(1L));
        ContractWriteService service = service(repository, mock(ChangeLogRepository.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(body("null", "null", "[\"1\",\"99999\"]", false)));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(fields(error).containsKey("equipmentIds"), String.valueOf(fields(error)));
        assertTrue(String.valueOf(fields(error).get("equipmentIds")).contains("99999"));
        verify(repository, never()).insert(anyString(), any(), any(), any(), any());
    }

    /** 新建时留痕的 before 为空、after 是完整快照，版本从 0 到 1。 */
    @Test
    void createWritesChangeLogWithFullSnapshot() throws Exception {
        ContractRepository repository = mock(ContractRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        when(repository.insert(anyString(), any(), any(), any(), any())).thenReturn(3L);
        when(repository.usableEquipmentIds(any())).thenReturn(List.of(1L));
        ContractWriteService service = service(repository, changeLog);

        service.create(body("\"2026-01-01\"", "\"2026-12-31\"", "[\"1\"]", false));

        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        ChangeLogWrite write = captor.getValue();
        assertEquals("MAINTENANCE_CONTRACT", write.objectType());
        assertEquals("CREATE", write.action());
        assertNull(write.before());
        assertEquals(0, write.versionBefore());
        assertEquals(1, write.versionAfter());
    }
}
