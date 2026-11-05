package cn.copper.phase2.sensor;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.sensor.SensorDeleteService.SensorDeleteRequest;
import cn.copper.phase2.sensor.SensorDeleteService.SensorDeleteResult;
import cn.copper.phase2.sensor.SensorModels.DeletionBlocker;
import cn.copper.phase2.sensor.SensorRepository.SensorRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1-P05 删除测点的契约。
 *
 * <p>重点是"删得掉"和"删不该删的"两侧边界：六类关联逐一验证会挡住删除且不执行任何清理；
 * 无关联时按外键依赖顺序级联，先删引用方再删被引用方；留痕保留被级联掉的参数编码，
 * 让事后还能回答"当初删掉的测点带着哪些参数"。</p>
 */
class SensorDeleteServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long SENSOR_ID = 77L;
    private static final String SENSOR_CODE = "TEMP-077";

    private static final SensorRow SENSOR = new SensorRow(
            SENSOR_ID, SENSOR_CODE, "炉壳温度", 1L, "温度", true, null,
            "东侧炉壳", "监控炉壳温升", 3);

    private final SensorRepository repository = mock(SensorRepository.class);
    private final ChangeLogRepository changeLog = mock(ChangeLogRepository.class);

    @SuppressWarnings("unchecked")
    private SensorDeleteService service() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(4)).get());
        return new SensorDeleteService(repository, changeLog, idempotency,
                new SensorReferenceChecker(repository));
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static JsonNode body(String requestId, Object expectedVersion, boolean confirmed) {
        return parse("{\"requestId\":\"" + requestId + "\","
                + "\"operatorName\":\"设备管理员\","
                + "\"expectedVersion\":" + expectedVersion + ","
                + "\"reason\":\"误建测点，编码填错\","
                + "\"confirmed\":" + confirmed + "}");
    }

    private static JsonNode body() {
        return body("req-delete-1", 3, true);
    }

    /** 默认：存在且无任何关联，参数编码留一份快照。 */
    private void givenCleanSensor() {
        when(repository.lockRow(SENSOR_ID)).thenReturn(Optional.of(SENSOR));
        when(repository.countCollection(SENSOR_CODE)).thenReturn(0L);
        when(repository.countAlertEvents(SENSOR_ID)).thenReturn(0L);
        when(repository.countMonitor(SENSOR_ID)).thenReturn(0L);
        when(repository.countEnergy(SENSOR_ID)).thenReturn(0L);
        when(repository.countChildSensors(SENSOR_ID)).thenReturn(0L);
        when(repository.countParametersWithRuleHistory(SENSOR_ID)).thenReturn(0L);
        when(repository.parameterCodes(SENSOR_ID)).thenReturn(List.of("PRESSURE", "TEMP"));
        when(repository.countNetworkNodes(SENSOR_ID)).thenReturn(2L);
        when(repository.deleteSensor(SENSOR_ID)).thenReturn(1);
    }

    // ---------------------------------------------------------------- 正常路径

    @Test
    void deletesSensorAndCascadesOwnedData() {
        givenCleanSensor();

        SensorDeleteResult result = service().delete(String.valueOf(SENSOR_ID), body());

        assertEquals(String.valueOf(SENSOR_ID), result.sensorId());
        assertTrue(result.deleted());
    }

    @Test
    void cascadeFollowsForeignKeyOrder() {
        givenCleanSensor();

        service().delete(String.valueOf(SENSOR_ID), body());

        // 引用方先删，被引用方后删：顺序反了会直接撞外键。
        InOrder order = inOrder(repository);
        order.verify(repository).deleteNetworkNodes(SENSOR_ID);
        order.verify(repository).deleteRuleVersions(SENSOR_ID);
        order.verify(repository).deleteParameterProfiles(SENSOR_ID);
        order.verify(repository).deleteParameters(SENSOR_ID);
        order.verify(repository).deleteProfile(SENSOR_ID);
        order.verify(repository).deleteSensor(SENSOR_ID);
    }

    @Test
    void keepsParameterCodesAndNodeCountInSnapshot() {
        givenCleanSensor();

        service().delete(String.valueOf(SENSOR_ID), body());

        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        ChangeLogWrite log = captor.getValue();

        assertEquals("SENSOR", log.objectType());
        assertEquals("DELETE", log.action());
        // 参数行随后被清理，快照是唯一还记得它们的地方。
        @SuppressWarnings("unchecked")
        Map<String, Object> before = (Map<String, Object>) log.before();
        assertEquals(List.of("PRESSURE", "TEMP"), before.get("parameterCodes"));
        assertEquals(2L, before.get("networkNodes"));
        assertEquals(SENSOR_CODE, before.get("sensorCode"));
        assertNull(log.after());
        assertEquals(3, log.versionBefore());
        assertNull(log.versionAfter());
    }

    @Test
    void usesOperationScopedBySensorId() {
        givenCleanSensor();
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> SensorDeleteResult.class.cast(
                        ((Supplier<?>) invocation.getArgument(4)).get()));

        new SensorDeleteService(repository, changeLog, idempotency,
                new SensorReferenceChecker(repository)).delete(String.valueOf(SENSOR_ID), body());

        // 幂等键必须带目标，否则同 requestId 打到别的测点会重放旧结果。
        verify(idempotency).execute(eq("req-delete-1"), eq("SENSOR_DELETE:77"),
                any(SensorDeleteRequest.class), eq(SensorDeleteResult.class), any());
    }

    // ---------------------------------------------------------------- 阻塞项

    @Test
    void blocksWhenCollectionDataExists() {
        givenCleanSensor();
        when(repository.countCollection(SENSOR_CODE)).thenReturn(1200L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID), body()));

        assertEquals(ErrorCode.RELATED_DATA_EXISTS, error.errorCode());
        assertEquals("COLLECTION_DATA", firstBlocker(error).type());
        assertEquals(1200L, firstBlocker(error).count());
        // 有采集记录时连编码都冻结，必须整体拒绝，不能"删一半"。
        verify(repository, never()).deleteSensor(anyLong());
        verify(changeLog, never()).insert(any());
    }

    @Test
    void blocksWhenAlertEventsExist() {
        givenCleanSensor();
        when(repository.countAlertEvents(SENSOR_ID)).thenReturn(4L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID), body()));

        assertEquals("ALERT_EVENT", firstBlocker(error).type());
        verify(repository, never()).deleteSensor(anyLong());
    }

    @Test
    void blocksWhenMonitorStatisticsExist() {
        givenCleanSensor();
        when(repository.countMonitor(SENSOR_ID)).thenReturn(30L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID), body()));

        assertEquals("MONITOR", firstBlocker(error).type());
        verify(repository, never()).deleteSensor(anyLong());
    }

    @Test
    void blocksWhenEnergyStatisticsExist() {
        givenCleanSensor();
        when(repository.countEnergy(SENSOR_ID)).thenReturn(96L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID), body()));

        assertEquals("ENERGY", firstBlocker(error).type());
        verify(repository, never()).deleteSensor(anyLong());
    }

    @Test
    void blocksWhenChildSensorsExist() {
        givenCleanSensor();
        when(repository.countChildSensors(SENSOR_ID)).thenReturn(2L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID), body()));

        assertEquals("CHILD_SENSOR", firstBlocker(error).type());
        verify(repository, never()).deleteSensor(anyLong());
    }

    @Test
    void blocksWhenRulesWereEdited() {
        givenCleanSensor();
        when(repository.countParametersWithRuleHistory(SENSOR_ID)).thenReturn(1L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID), body()));

        assertEquals("PARAMETER_HISTORY", firstBlocker(error).type());
        verify(repository, never()).deleteRuleVersions(anyLong());
    }

    @Test
    void reportsEveryBlockerAtOnce() {
        givenCleanSensor();
        when(repository.countCollection(SENSOR_CODE)).thenReturn(3L);
        when(repository.countMonitor(SENSOR_ID)).thenReturn(1L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID), body()));

        List<?> blockers = (List<?>) error.details().get("blockers");
        // 一次报全，避免用户改一条、重试一次、再冒下一条。
        assertEquals(2, blockers.size());
    }

    // ---------------------------------------------------------------- 校验与并发

    @Test
    void rejectsStaleVersion() {
        givenCleanSensor();

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID), body("req-delete-2", 2, true)));

        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
        assertEquals(3L, error.details().get("currentVersion"));
        verify(repository, never()).deleteSensor(anyLong());
    }

    @Test
    void rejectsWithoutConfirmation() {
        givenCleanSensor();

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID), body("req-delete-3", 3, false)));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) error.details().get("fields");
        assertTrue(fields.containsKey("confirmed"));
        verify(repository, never()).deleteSensor(anyLong());
    }

    @Test
    void rejectsMissingReason() {
        givenCleanSensor();

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID),
                        parse("{\"requestId\":\"r\",\"operatorName\":\"张三\","
                                + "\"expectedVersion\":3,\"confirmed\":true}")));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) error.details().get("fields");
        assertTrue(fields.containsKey("reason"));
    }

    @Test
    void missingSensorReportsNotFound() {
        when(repository.lockRow(SENSOR_ID)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete(String.valueOf(SENSOR_ID), body()));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void invalidIdIsRejectedBeforeTouchingDatabase() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service().delete("abc", body()));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        verify(repository, never()).lockRow(anyLong());
    }

    @Test
    void deletionStateIsDerivedFromBlockers() {
        SensorReferenceChecker checker = new SensorReferenceChecker(repository);
        when(repository.countCollection(SENSOR_CODE)).thenReturn(0L);
        when(repository.countParametersWithRuleHistory(SENSOR_ID)).thenReturn(0L);

        assertTrue(checker.blockers(SENSOR_ID, SENSOR_CODE).isEmpty());

        when(repository.countCollection(SENSOR_CODE)).thenReturn(5L);
        List<DeletionBlocker> blockers = checker.blockers(SENSOR_ID, SENSOR_CODE);
        assertFalse(blockers.isEmpty());
        assertTrue(blockers.get(0).message().contains(SENSOR_CODE));
    }

    private static DeletionBlocker firstBlocker(BusinessException error) {
        Object value = error.details().get("blockers");
        assertTrue(value instanceof List<?>, "blockers 必须是数组");
        List<?> list = (List<?>) value;
        assertFalse(list.isEmpty(), "至少应有一个阻塞项");
        return (DeletionBlocker) list.get(0);
    }
}
