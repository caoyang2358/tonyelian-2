package cn.copper.phase2.sensor;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.sensor.SensorModels.ParameterDetail;
import cn.copper.phase2.sensor.SensorModels.RuleSnapshot;
import cn.copper.phase2.sensor.SensorRepository.CurrentRule;
import cn.copper.phase2.sensor.SensorRepository.ParameterRow;
import cn.copper.phase2.sensor.SensorRepository.SensorRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
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
import static org.mockito.Mockito.inOrder;
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
    private final AlertEventTerminator alertEvents = mock(AlertEventTerminator.class);

    @SuppressWarnings("unchecked")
    private SensorParameterWriteService service() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(4)).get());
        return new SensorParameterWriteService(repository, changeLog, idempotency, alertEvents);
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

    // ---------------------------------------------------------------- P08 配置更新

    /** 当前生效的规则，与 VALID_RULE 定稿后一致（单位继承参数单位 kPa）。 */
    private static final RuleSnapshot CURRENT_RULE = new RuleSnapshot(
            "0", "100", "-10", "150", "kPa", "SUSTAINED", 30, true);

    private void givenParameter(long version, RuleSnapshot rule) {
        when(repository.lockParameter(PARAMETER_ID)).thenReturn(Optional.of(new ParameterRow(
                PARAMETER_ID, SENSOR_ID, "TEMP-011", "PRESSURE", "压力", "DECIMAL",
                "kPa", 2, 60, null, true, version)));
        when(repository.currentRule(PARAMETER_ID)).thenReturn(rule == null
                ? Optional.empty()
                : Optional.of(new CurrentRule(RULE_VERSION_ID, 1L, rule)));
        when(repository.unitExists(anyString())).thenReturn(true);
        when(repository.updateParameterVersion(eq(PARAMETER_ID), anyLong())).thenReturn(1);
        when(repository.insertRuleVersion(eq(PARAMETER_ID), anyLong(), any(), any(), any(),
                anyString(), any())).thenReturn(RULE_VERSION_ID + 1);
    }

    private static JsonNode configBody(long expectedVersion, String name, String unitCode,
                                       String rule) throws Exception {
        return JSON.readTree("""
                {"requestId":"aa110000-0000-4000-8000-0000000000d1","operatorName":"设备管理员",
                 "expectedVersion":%d,"reason":"调整阈值","name":"%s","unitCode":"%s",
                 "decimalPlaces":2,"samplingIntervalSeconds":60,"processId":null,
                 "enabled":true,"rule":%s}
                """.formatted(expectedVersion, name, unitCode, rule));
    }

    @Test
    void configUpdateWithUnchangedRuleOnlyAdvancesConfigurationVersion() throws Exception {
        givenParameter(1, CURRENT_RULE);

        var result = service().updateConfiguration("501",
                configBody(1, "压力（更正名称）", "kPa", VALID_RULE));

        // 判断依据没变：不许产出"看起来改过"的新规则版本，但配置版本要推进。
        verify(repository, never()).insertRuleVersion(anyLong(), anyLong(), any(), any(), any(), anyString());
        assertEquals(2, result.version());
        assertEquals(String.valueOf(RULE_VERSION_ID), result.previousRuleVersionId());
        assertEquals(String.valueOf(RULE_VERSION_ID), result.ruleVersionId());
        assertTrue(result.effectNote().contains("未变化"), result.effectNote());
    }

    @Test
    void trailingZerosInStoredBoundsAreNotTreatedAsAChange() throws Exception {
        // 库里是 DECIMAL(20,6)，读回常带尾随零；按字符串比会每次保存都多出版本。
        givenParameter(1, new RuleSnapshot("0.000000", "100.000000", "-10.000000", "150.000000",
                "kPa", "SUSTAINED", 30, true));

        service().updateConfiguration("501", configBody(1, "压力", "kPa", VALID_RULE));

        verify(repository, never()).insertRuleVersion(anyLong(), anyLong(), any(), any(), any(), anyString());
    }

    @Test
    void changedThresholdAppendsNewRuleVersionPointingAtThePreviousOne() throws Exception {
        givenParameter(3, CURRENT_RULE);
        String changed = """
                {"normalLower":"0","normalUpper":"120","alarmLower":"-10","alarmUpper":"150",
                 "triggerMode":"SUSTAINED","durationSeconds":30,"enabled":true}
                """;

        var result = service().updateConfiguration("501", configBody(3, "压力", "kPa", changed));

        ArgumentCaptor<RuleSnapshot> rule = ArgumentCaptor.forClass(RuleSnapshot.class);
        verify(repository).insertRuleVersion(eq(PARAMETER_ID), eq(2L), eq(RULE_VERSION_ID),
                rule.capture(), eq("调整阈值"), eq("设备管理员"), any());
        assertEquals("120", rule.getValue().normalUpper());
        assertEquals(4, result.version());
        assertEquals(String.valueOf(RULE_VERSION_ID), result.previousRuleVersionId());
        assertEquals(String.valueOf(RULE_VERSION_ID + 1), result.ruleVersionId());
        assertEquals(List.of(), result.affectedEventIds());
        // 规则变了但没有活动事件：说明里要写"没有需要结束的事件"，
        // 不能让人以为事件那段漏做了（和"判断依据没变所以没碰事件"是两件事）。
        assertTrue(result.effectNote().contains("没有需要结束的活动事件"), result.effectNote());
    }

    @Test
    void changedRuleEndsActiveEventAndReportsTheEventId() throws Exception {
        givenParameter(3, CURRENT_RULE);
        when(alertEvents.terminateOnRuleChange(eq(PARAMETER_ID), eq(RULE_VERSION_ID + 1), any()))
                .thenReturn(List.of("7"));
        String changed = """
                {"normalLower":"0","normalUpper":"120","alarmLower":"-10","alarmUpper":"150",
                 "triggerMode":"SUSTAINED","durationSeconds":30,"enabled":true}
                """;

        var result = service().updateConfiguration("501", configBody(3, "压力", "kPa", changed));

        assertEquals(List.of("7"), result.affectedEventIds());
        assertTrue(result.effectNote().contains("RULE_CHANGED"), result.effectNote());
        assertTrue(result.effectNote().contains("#7"), result.effectNote());
        // "不算恢复"这句要留在响应里：现场看到事件结束、recovered_at 却是空，得有个说法。
        assertTrue(result.effectNote().contains("不算恢复"), result.effectNote());
    }

    @Test
    void ruleSnapshotIsWrittenBeforeEventsAreEnded() throws Exception {
        givenParameter(3, CURRENT_RULE);
        String changed = """
                {"normalLower":"0","normalUpper":"120","alarmLower":"-10","alarmUpper":"150",
                 "triggerMode":"SUSTAINED","durationSeconds":30,"enabled":true}
                """;

        service().updateConfiguration("501", configBody(3, "压力", "kPa", changed));

        // 事件结束要指向新版本；先结束再插规则版本，就会出现"事件挂在一版还不存在的规则上"。
        var order = inOrder(repository, alertEvents);
        order.verify(repository).insertRuleVersion(eq(PARAMETER_ID), eq(2L), eq(RULE_VERSION_ID),
                any(), anyString(), anyString(), any());
        order.verify(alertEvents).terminateOnRuleChange(eq(PARAMETER_ID), eq(RULE_VERSION_ID + 1), any());
    }

    @Test
    void unchangedRuleNeverTouchesEvents() throws Exception {
        givenParameter(1, CURRENT_RULE);

        var result = service().updateConfiguration("501",
                configBody(1, "压力（更正名称）", "kPa", VALID_RULE));

        // 只改名称不该结束任何事件：判断依据没变，活动事件还得继续按原规则挂着。
        verify(alertEvents, never()).terminateOnRuleChange(anyLong(), anyLong(), any());
        assertEquals(List.of(), result.affectedEventIds());
    }

    @Test
    void firstRuleOnASeedParameterStartsTheVersionChain() throws Exception {
        givenParameter(0, null);
        when(repository.insertRuleVersion(eq(PARAMETER_ID), anyLong(), any(), any(), any(),
                anyString(), any())).thenReturn(77L);

        var result = service().updateConfiguration("501", configBody(0, "压力", "kPa", VALID_RULE));

        verify(repository).insertParameterProfile(PARAMETER_ID, 1L);
        verify(repository).insertRuleVersion(eq(PARAMETER_ID), eq(1L), eq(null),
                any(), eq("调整阈值"), eq("设备管理员"), any());
        assertNull(result.previousRuleVersionId());
        assertEquals("77", result.ruleVersionId());
    }

    @Test
    void staleExpectedVersionIsRejectedBeforeAnyWrite() throws Exception {
        givenParameter(3, CURRENT_RULE);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().updateConfiguration("501", configBody(2, "压力", "kPa", VALID_RULE)));

        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
        assertEquals(3L, error.details().get("currentVersion"));
        verify(repository, never()).updateParameterConfig(anyLong(), any());
        verify(repository, never()).updateParameterVersion(anyLong(), anyLong());
    }

    @Test
    void ruleCannotBeDroppedThroughConfigurationUpdate() throws Exception {
        givenParameter(1, CURRENT_RULE);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().updateConfiguration("501", configBody(1, "压力", "kPa", "null")));

        // 规则快照只能追加、不能覆盖，接受 null 会让页面显示一份实际已不生效的规则。
        assertTrue(fields(error).containsKey("rule"));
        verify(repository, never()).updateParameterConfig(anyLong(), any());
    }

    @Test
    void changingParameterUnitChangesTheInheritedRuleUnitAndStartsANewVersion() throws Exception {
        givenParameter(1, CURRENT_RULE);

        // 参数单位 kPa → Pa，规则没单独给单位，于是规则单位随之变为 Pa：量纲变了就是判断依据变了。
        service().updateConfiguration("501", configBody(1, "压力", "Pa", VALID_RULE));

        ArgumentCaptor<RuleSnapshot> rule = ArgumentCaptor.forClass(RuleSnapshot.class);
        verify(repository).insertRuleVersion(eq(PARAMETER_ID), eq(2L), eq(RULE_VERSION_ID),
                rule.capture(), any(), anyString(), any());
        assertEquals("Pa", rule.getValue().unitCode());
    }

    @Test
    void unknownParameterIsNotFound() throws Exception {
        when(repository.lockParameter(PARAMETER_ID)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().updateConfiguration("501", configBody(1, "压力", "kPa", VALID_RULE)));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }
}
