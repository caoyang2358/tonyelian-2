package cn.copper.phase2.sensor;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.sensor.SensorModels.SensorDetail;
import cn.copper.phase2.sensor.SensorRepository.SensorRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1-P03 / S1-P04 的写入契约。
 *
 * <p>重点在几处容易被当成"小事"的地方：新建测点不得写采集来源（否则页面会声称已对接）、
 * 有采集记录时改编码必须被拒（否则历史数据变孤儿）、版本不符时两张表一个字都不能写、
 * 停用只能改标志位而不能碰历史。</p>
 */
class SensorWriteServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final SensorDetail DETAIL = new SensorDetail(
            "11", "TEMP-011", "炉壳温度", "1", "SMELT-FUR-01", "1#熔炼炉",
            "1", "车间1 · 铜杆生产", "温度", null, "东侧炉壳", "监控炉壳温升",
            true, 0, false, SensorModels.MONITORING_UNKNOWN,
            SensorModels.CONFIGURATION_PENDING, 1, List.of(),
            new SensorModels.DeletionState(true, List.of()));

    private final SensorRepository repository = mock(SensorRepository.class);
    private final ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
    private final SensorService reader = mock(SensorService.class);

    @SuppressWarnings("unchecked")
    private SensorWriteService service() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(4)).get());
        when(reader.detail(anyString())).thenReturn(DETAIL);
        return new SensorWriteService(repository, changeLog, idempotency, reader);
    }

    private static JsonNode createBody(String code, boolean withSensorType) throws Exception {
        return JSON.readTree("""
                {"requestId":"7c1f2a00-0000-4000-8000-0000000000a1","operatorName":"设备管理员",
                 "sensorCode":"%s","name":"炉壳温度","equipmentId":"1",
                 "sensorType":%s,"location":"东侧炉壳","purpose":"监控炉壳温升","enabled":true}
                """.formatted(code, withSensorType ? "\"温度\"" : "null"));
    }

    private static JsonNode updateBody(String code, long expectedVersion, boolean enabled,
                                       String reason) throws Exception {
        return JSON.readTree("""
                {"requestId":"7c1f2a00-0000-4000-8000-0000000000b2","operatorName":"设备管理员",
                 "expectedVersion":%d,"sensorCode":"%s","name":"炉壳温度","equipmentId":"1",
                 "sensorType":"温度","location":"东侧炉壳","purpose":"监控炉壳温升",
                 "enabled":%s,"reason":%s}
                """.formatted(expectedVersion, code, enabled, reason == null ? "null" : "\"" + reason + "\""));
    }

    private static SensorRow row(long version, String code) {
        return new SensorRow(11L, code, "炉壳温度", 1L, "温度", true, null,
                "东侧炉壳", "监控炉壳温升", version);
    }

    // ---------------------------------------------------------------- P03

    @Test
    void createWritesBothTablesAndLeavesDataSourceEmpty() throws Exception {
        when(repository.equipmentUsable(1L)).thenReturn(true);
        when(repository.findIdByCode("TEMP-011")).thenReturn(Optional.empty());
        when(repository.insertSensor("TEMP-011", "炉壳温度", 1L, "温度", true)).thenReturn(11L);

        SensorDetail detail = service().create(createBody("TEMP-011", true));

        assertEquals("11", detail.sensorId());
        verify(repository).insertSensor("TEMP-011", "炉壳温度", 1L, "温度", true);
        // 首存版本必须是 1，而不是默认值；否则详情里的版本口径会与新建结果不一致。
        verify(repository).insertProfile(11L, "东侧炉壳", "监控炉壳温升", 1L);
    }

    @Test
    void createRejectsDuplicateCode() throws Exception {
        when(repository.equipmentUsable(1L)).thenReturn(true);
        when(repository.findIdByCode("TEMP-001")).thenReturn(Optional.of(5L));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create(createBody("TEMP-001", true)));

        assertEquals(ErrorCode.DUPLICATE_CODE, error.errorCode());
        verify(repository, never()).insertSensor(anyString(), anyString(), anyLong(), any(), anyBoolean());
    }

    @Test
    void createRejectsNonProductionEquipment() throws Exception {
        when(repository.equipmentUsable(1L)).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create(createBody("TEMP-011", true)));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(String.valueOf(error.details()).contains("equipmentId"), error.details().toString());
    }

    @Test
    void createRejectsCodeWithIllegalCharacters() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create(createBody("炉壳 温度", true)));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(String.valueOf(error.details()).contains("sensorCode"), error.details().toString());
        verify(repository, never()).insertSensor(anyString(), anyString(), anyLong(), any(), anyBoolean());
    }

    @Test
    void createRequiresNullableFieldsToBePresent() throws Exception {
        JsonNode body = JSON.readTree("""
                {"requestId":"7c1f2a00-0000-4000-8000-0000000000a3","operatorName":"设备管理员",
                 "sensorCode":"TEMP-011","name":"炉壳温度","equipmentId":"1","enabled":true}
                """);

        BusinessException error = assertThrows(BusinessException.class, () -> service().create(body));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        String details = String.valueOf(error.details());
        assertTrue(details.contains("location") && details.contains("purpose"), details);
    }

    @Test
    void createLogsChangeWithVersionsZeroToOne() throws Exception {
        when(repository.equipmentUsable(1L)).thenReturn(true);
        when(repository.findIdByCode("TEMP-011")).thenReturn(Optional.empty());
        when(repository.insertSensor(anyString(), anyString(), anyLong(), any(), anyBoolean())).thenReturn(11L);

        service().create(createBody("TEMP-011", true));

        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        ChangeLogWrite entry = captor.getValue();
        assertEquals("SENSOR", entry.objectType());
        assertEquals("CREATE", entry.action());
        assertEquals(0, entry.versionBefore());
        assertEquals(1, entry.versionAfter());
    }

    // ---------------------------------------------------------------- P04

    @Test
    void updateRejectsStaleVersionBeforeWritingAnything() throws Exception {
        when(repository.lockRow(11L)).thenReturn(Optional.of(row(3L, "TEMP-011")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().update("11", updateBody("TEMP-011", 2L, true, null)));

        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
        verify(repository, never()).updateSensor(anyLong(), anyString(), anyString(), anyLong(), any(), anyBoolean());
        verify(repository, never()).updateProfile(anyLong(), any(), any(), anyLong());
    }

    @Test
    void updateRefusesCodeChangeWhenHistoryExists() throws Exception {
        when(repository.lockRow(11L)).thenReturn(Optional.of(row(1L, "TEMP-011")));
        when(repository.hasHistory("TEMP-011")).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().update("11", updateBody("TEMP-012", 1L, true, null)));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(String.valueOf(error.details()).contains("sensorCode"), error.details().toString());
        verify(repository, never()).updateSensor(anyLong(), anyString(), anyString(), anyLong(), any(), anyBoolean());
    }

    @Test
    void updateAllowsCodeChangeWhenNoHistoryAndCodeIsFree() throws Exception {
        when(repository.lockRow(11L)).thenReturn(Optional.of(row(1L, "TEMP-011")));
        when(repository.hasHistory("TEMP-011")).thenReturn(false);
        when(repository.findIdByCode("TEMP-012")).thenReturn(Optional.empty());
        when(repository.equipmentUsable(1L)).thenReturn(true);
        when(repository.updateProfile(11L, "东侧炉壳", "监控炉壳温升", 1L)).thenReturn(1);

        service().update("11", updateBody("TEMP-012", 1L, true, "编码与现场标牌对齐"));

        verify(repository).updateSensor(11L, "TEMP-012", "炉壳温度", 1L, "温度", true);
        verify(repository).updateProfile(11L, "东侧炉壳", "监控炉壳温升", 1L);
    }

    @Test
    void updateCreatesProfileRowForSeedSensorWithoutOne() throws Exception {
        when(repository.lockRow(11L)).thenReturn(Optional.of(row(0L, "TEMP-011")));
        when(repository.equipmentUsable(1L)).thenReturn(true);

        service().update("11", updateBody("TEMP-011", 0L, true, null));

        // 一期种子测点没有扩展档案：这次更新补一行，版本从 1 起算。
        verify(repository).insertProfile(11L, "东侧炉壳", "监控炉壳温升", 1L);
        verify(repository, never()).updateProfile(anyLong(), any(), any(), anyLong());
    }

    @Test
    void disablingKeepsHistoryAndOnlyFlipsTheFlag() throws Exception {
        when(repository.lockRow(11L)).thenReturn(Optional.of(row(2L, "TEMP-011")));
        when(repository.equipmentUsable(1L)).thenReturn(true);
        when(repository.updateProfile(11L, "东侧炉壳", "监控炉壳温升", 2L)).thenReturn(1);

        service().update("11", updateBody("TEMP-011", 2L, false, "工位改造暂停采集"));

        verify(repository).updateSensor(11L, "TEMP-011", "炉壳温度", 1L, "温度", false);
        // 停用不得删除原始数据、事件或工单：删除是独立的 P05 接口，停用这条路径不许碰。
        verify(repository, never()).deleteSensor(anyLong());
        verify(repository, never()).deleteParameters(anyLong());
        verify(repository, never()).deleteRuleVersions(anyLong());
        verify(repository, never()).deleteProfile(anyLong());
    }

    @Test
    void updateReportsVersionConflictWhenProfileRowMovesBetweenReadAndWrite() throws Exception {
        when(repository.lockRow(11L)).thenReturn(Optional.of(row(1L, "TEMP-011")));
        when(repository.equipmentUsable(1L)).thenReturn(true);
        when(repository.updateProfile(eq(11L), any(), any(), eq(1L))).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().update("11", updateBody("TEMP-011", 1L, true, null)));

        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
    }

    @Test
    void updateRecordsBeforeAndAfterSnapshots() throws Exception {
        when(repository.lockRow(11L)).thenReturn(Optional.of(row(1L, "TEMP-011")));
        when(repository.equipmentUsable(1L)).thenReturn(true);
        when(repository.updateProfile(11L, "东侧炉壳", "监控炉壳温升", 1L)).thenReturn(1);

        service().update("11", updateBody("TEMP-011", 1L, true, "复核归属"));

        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        ChangeLogWrite entry = captor.getValue();
        assertEquals("SENSOR", entry.objectType());
        assertEquals("UPDATE", entry.action());
        assertEquals("复核归属", entry.reason());
        assertEquals(1, entry.versionBefore());
        assertEquals(2, entry.versionAfter());
        assertTrue(String.valueOf(entry.before()).contains("TEMP-011"), String.valueOf(entry.before()));
    }
}
