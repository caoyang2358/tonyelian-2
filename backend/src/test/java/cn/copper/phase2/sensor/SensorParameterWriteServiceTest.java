package cn.copper.phase2.sensor;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.sensor.SensorModels.ParameterDetail;
import cn.copper.phase2.sensor.SensorModels.RuleSnapshot;
import cn.copper.phase2.sensor.SensorRepository.SensorRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1-P07 新增参数的写入契约。
 *
 * <p>重点是几条"看起来能通、实际会写坏数据"的路径：同测点编码重复必须在插入前拦下；
 * 上下界颠倒、立即触发带持续秒数这类组合不能留给数据库 CHECK 报 500；
 * 不配规则时不许凭空造一版空规则；规则单位缺省继承参数单位，避免一个参数两个量纲。</p>
 */
class SensorParameterWriteServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long SENSOR_ID = 11L;
    private static final long PARAMETER_ID = 501L;
    private static final long RULE_VERSION_ID = 9001L;

    private static final SensorRow SENSOR = new SensorRow(
            SENSOR_ID, "TEMP-011", "炉壳温度", 1L, "温度", true, null,
            "东侧炉壳", "监控炉壳温升", 1);

    private final SensorRepository repository = mock(SensorRepository.class);
    private final ChangeLogRepository changeLog = mock(ChangeLogRepository.class);

    @SuppressWarnings("unchecked")
    private SensorParameterWriteService service() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(4)).get());
        return new SensorParameterWriteService(repository, changeLog, idempotency);
    }

    private void givenSensor() {
        when(repository.lockRow(SENSOR_ID)).thenReturn(Optional.of(SENSOR));
        when(repository.findParameterIdByCode(eq(SENSOR_ID), anyString())).thenReturn(Optional.empty());
        when(repository.nextDisplayOrder(SENSOR_ID)).thenReturn(0);
        when(repository.insertParameter(eq(SENSOR_ID), any(), anyInt())).thenReturn(PARAMETER_ID);
        when(repository.unitExists("kPa")).thenReturn(true);
        when(repository.insertRuleVersion(eq(PARAMETER_ID), any(), any(), anyString())).thenReturn(RULE_VERSION_ID);
        when(repository.parameterDetail(PARAMETER_ID)).thenReturn(Optional.of(detail(null)));
    }

    private static ParameterDetail detail(RuleSnapshot rule) {
        return new ParameterDetail(String.valueOf(PARAMETER_ID), String.valueOf(SENSOR_ID),
                "PRESSURE", "压力", "DECIMAL", "kPa", 2, 60, null, true, rule,
                rule == null ? null : String.valueOf(RULE_VERSION_ID), 1);
    }

    private static JsonNode body(String code, String dataType, String rule) throws Exception {
        return JSON.readTree("""
                {"requestId":"aa110000-0000-4000-8000-0000000000c1","operatorName":"设备管理员",
                 "reason":"补录参数","parameterCode":"%s","name":"压力","dataType":"%s",
                 "unitCode":"kPa","decimalPlaces":2,"samplingIntervalSeconds":60,
                 "processId":null,"enabled":true,"rule":%s}
                """.formatted(code, dataType, rule));
    }

    private static final String VALID_RULE = """
            {"normalLower":"0","normalUpper":"100","alarmLower":"-10","alarmUpper":"150",
             "triggerMode":"SUSTAINED","durationSeconds":30,"enabled":true}
            """;

    // ---------------------------------------------------------------- 正常路径

    @Test
    void createWritesParameterProfileAndRuleSnapshot() throws Exception {
        givenSensor();

        service().create("11", body("PRESSURE", "DECIMAL", VALID_RULE));

        verify(repository).insertParameter(eq(SENSOR_ID), any(), eq(0));
        verify(repository).insertParameterProfile(PARAMETER_ID, 1L);
        ArgumentCaptor<RuleSnapshot> rule = ArgumentCaptor.forClass(RuleSnapshot.class);
        verify(repository).insertRuleVersion(eq(PARAMETER_ID), rule.capture(), eq("补录参数"), eq("设备管理员"));
        assertEquals("0", rule.getValue().normalLower());
        assertEquals("SUSTAINED", rule.getValue().triggerMode());
        assertEquals(30, rule.getValue().durationSeconds());
    }

    @Test
    void createWithoutRuleDoesNotInventAnEmptyRuleVersion() throws Exception {
        givenSensor();

        service().create("11", body("PRESSURE", "DECIMAL", "null"));

        verify(repository, never()).insertRuleVersion(anyLong(), any(), any(), anyString());
        verify(repository).insertParameterProfile(PARAMETER_ID, 1L);
    }

    @Test
    void ruleInheritsParameterUnitWhenRuleUnitIsAbsent() throws Exception {
        givenSensor();

        service().create("11", body("PRESSURE", "DECIMAL", VALID_RULE));

        ArgumentCaptor<RuleSnapshot> rule = ArgumentCaptor.forClass(RuleSnapshot.class);
        verify(repository).insertRuleVersion(eq(PARAMETER_ID), rule.capture(), any(), anyString());
        assertEquals("kPa", rule.getValue().unitCode());
    }

    @Test
    void writeIsRecordedWithParameterAsObject() throws Exception {
        givenSensor();

        service().create("11", body("PRESSURE", "DECIMAL", "null"));

        ArgumentCaptor<ChangeLogWrite> write = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(write.capture());
        assertEquals("SENSOR_PARAMETER", write.getValue().objectType());
        assertEquals(String.valueOf(PARAMETER_ID), write.getValue().objectId());
        assertEquals("CREATE", write.getValue().action());
        assertNull(write.getValue().before());
        assertEquals(0, write.getValue().versionBefore());
        assertEquals(1, write.getValue().versionAfter());
    }

    // ---------------------------------------------------------------- 冲突与校验

    @Test
    void duplicateCodeInSameSensorIsRejectedBeforeInsert() throws Exception {
        givenSensor();
        when(repository.findParameterIdByCode(SENSOR_ID, "PRESSURE")).thenReturn(Optional.of(77L));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create("11", body("PRESSURE", "DECIMAL", "null")));

        assertEquals(ErrorCode.DUPLICATE_CODE, error.errorCode());
        verify(repository, never()).insertParameter(anyLong(), any(), anyInt());
    }

    @Test
    void unknownSensorIsNotFound() {
        when(repository.lockRow(SENSOR_ID)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create("11", body("PRESSURE", "DECIMAL", "null")));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
        verify(repository, never()).insertParameter(anyLong(), any(), anyInt());
    }

    @Test
    void unsupportedDataTypeIsRejected() {
        givenSensor();

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create("11", body("PRESSURE", "FLOAT", "null")));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(fields(error).containsKey("dataType"));
    }

    @Test
    void reversedNormalBoundsAreRejected() {
        givenSensor();
        String rule = """
                {"normalLower":"100","normalUpper":"0","alarmLower":null,"alarmUpper":null,
                 "triggerMode":"SUSTAINED","durationSeconds":30,"enabled":true}
                """;

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create("11", body("PRESSURE", "DECIMAL", rule)));

        assertTrue(fields(error).containsKey("rule.normalLower"));
        verify(repository, never()).insertRuleVersion(anyLong(), any(), any(), anyString());
    }

    @Test
    void immediateTriggerWithDurationIsRejected() {
        givenSensor();
        String rule = """
                {"normalLower":null,"normalUpper":null,"alarmLower":null,"alarmUpper":null,
                 "triggerMode":"IMMEDIATE","durationSeconds":30,"enabled":true}
                """;

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create("11", body("PRESSURE", "DECIMAL", rule)));

        assertTrue(fields(error).containsKey("rule.durationSeconds"));
    }

    @Test
    void sustainedEnabledRuleRequiresDuration() {
        givenSensor();
        String rule = """
                {"normalLower":null,"normalUpper":null,"alarmLower":null,"alarmUpper":null,
                 "triggerMode":"SUSTAINED","durationSeconds":null,"enabled":true}
                """;

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create("11", body("PRESSURE", "DECIMAL", rule)));

        assertTrue(fields(error).containsKey("rule.durationSeconds"));
    }

    @Test
    void unknownUnitIsRejected() {
        givenSensor();
        when(repository.unitExists("kPa")).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create("11", body("PRESSURE", "DECIMAL", "null")));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(fields(error).containsKey("unitCode"));
    }

    @Test
    void missingFieldIsRejectedRatherThanTreatedAsNull() throws Exception {
        givenSensor();
        JsonNode incomplete = JSON.readTree("""
                {"requestId":"aa110000-0000-4000-8000-0000000000c2","operatorName":"设备管理员",
                 "parameterCode":"PRESSURE","name":"压力","dataType":"DECIMAL","enabled":true}
                """);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create("11", incomplete));

        assertTrue(fields(error).containsKey("rule"));
        assertTrue(fields(error).containsKey("unitCode"));
    }

    @Test
    void nonPositiveSamplingIntervalIsRejected() throws Exception {
        givenSensor();
        JsonNode body = JSON.readTree("""
                {"requestId":"aa110000-0000-4000-8000-0000000000c3","operatorName":"设备管理员",
                 "reason":null,"parameterCode":"PRESSURE","name":"压力","dataType":"DECIMAL",
                 "unitCode":null,"decimalPlaces":null,"samplingIntervalSeconds":0,
                 "processId":null,"enabled":true,"rule":null}
                """);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().create("11", body));

        assertTrue(fields(error).containsKey("samplingIntervalSeconds"));
        verify(repository, never()).insertParameter(anyLong(), any(), anyInt());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(BusinessException error) {
        Object fields = error.details().get("fields");
        assertTrue(fields instanceof Map, "校验错误应带 fields 明细");
        return (Map<String, Object>) fields;
    }
}
