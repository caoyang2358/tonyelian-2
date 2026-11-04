package cn.copper.phase2.sensor;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.sensor.SensorModels.ParameterDetail;
import cn.copper.phase2.sensor.SensorModels.ParameterWriteRequest;
import cn.copper.phase2.sensor.SensorModels.RuleSnapshot;
import cn.copper.phase2.sensor.SensorRepository.SensorRow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * S1-P07 新增参数定义。
 *
 * <p>写三张表，同一事务：{@code dim_sensor_parameter}（一期共享的参数身份）、
 * {@code p2_sensor_parameter_profile}（二期配置版本，首版 1）、
 * {@code p2_alert_rule_version}（仅当请求带 rule，首版规则快照）。</p>
 *
 * <p>三条自我约束：</p>
 * <ul>
 *   <li>不写 {@code data_source}：参数有没有真的接到采集，取决于采集映射建立，不取决于一次表单提交。</li>
 *   <li>不写一期维表的四个旧阈值列：规则以版本快照为唯一真相，两处同时写必然出现一处变旧。</li>
 *   <li>规则边界不越权推断：只校验"下限不高于上限"与触发模式对持续秒数的要求，
 *       不替现场判断正常区间该比告警区间窄还是宽。</li>
 * </ul>
 */
@Service
public class SensorParameterWriteService {
    private static final String OBJECT_TYPE = "SENSOR_PARAMETER";

    private static final int CODE_MAX = 64;
    private static final int NAME_MAX = 100;
    private static final int UNIT_MAX = 32;
    private static final int REASON_MAX = 1000;
    private static final int BOUND_MAX = 32;

    /** 与 {@code ck_parameter_type} 一致。 */
    private static final List<String> DATA_TYPES = List.of("DECIMAL", "INTEGER", "BOOLEAN", "STRING");

    /** 小数位上限：库里是 INT，不设上限会写出无法正常格式化的精度。 */
    private static final int DECIMAL_PLACES_MAX = 10;

    /** 与测点编码同一条规则：编码要能进 URL、CSV 与报表列名。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

    /**
     * 可空字段同样要求显式出现：传 null 表示不设该项，漏传即校验失败。
     *
     * <p>{@code reason} 不在其中：契约对新增只要求"可传"，不传就是没有说明，
     * 与 {@code unitCode} 这类"必须表态设不设"的业务字段不是一回事。</p>
     */
    private static final List<String> REQUIRED = List.of(
            "requestId", "operatorName", "parameterCode", "name", "dataType",
            "unitCode", "decimalPlaces", "samplingIntervalSeconds", "processId", "enabled", "rule");

    /**
     * 规则对象的必填字段。
     *
     * <p>{@code unitCode} 不在其中：规则单位缺省继承参数单位，不属于"清空"语义，
     * 强行要求出现只会让调用方多传一个重复值。</p>
     */
    private static final List<String> RULE_REQUIRED = List.of(
            "normalLower", "normalUpper", "alarmLower", "alarmUpper",
            "triggerMode", "durationSeconds", "enabled");

    private final SensorRepository repository;
    private final ChangeLogRepository changeLog;
    private final IdempotencyService idempotency;

    public SensorParameterWriteService(SensorRepository repository, ChangeLogRepository changeLog,
                                       IdempotencyService idempotency) {
        this.repository = repository;
        this.changeLog = changeLog;
        this.idempotency = idempotency;
    }

    /** 新增参数；同一 requestId 重试只产生一条参数与一版规则。 */
    public ParameterDetail create(String sensorId, JsonNode body) {
        long id = SensorService.requiredId(sensorId, "sensorId", "测点");
        ParameterWriteRequest request = parse(body);
        return idempotency.execute(request.requestId(), "SENSOR_PARAMETER_CREATE:" + id, request,
                ParameterDetail.class, () -> doCreate(id, request));
    }

    private ParameterDetail doCreate(long sensorId, ParameterWriteRequest request) {
        // 锁父行：确认测点存在，同时让"参数编码唯一"的检查与插入之间不被并发插队。
        SensorRow sensor = repository.lockRow(sensorId)
                .orElseThrow(() -> BusinessException.notFound("测点 " + sensorId + " 不存在。"));

        repository.findParameterIdByCode(sensorId, request.parameterCode()).ifPresent(existing -> {
            throw duplicateParameterCode(sensor.sensorCode(), request.parameterCode());
        });
        assertReferences(request);

        long parameterId;
        try {
            parameterId = repository.insertParameter(sensorId, request,
                    repository.nextDisplayOrder(sensorId));
        } catch (DuplicateKeyException race) {
            // uk_sensor_parameter 兜底：并发新增同一编码时给同一种说法，而不是 500。
            throw duplicateParameterCode(sensor.sensorCode(), request.parameterCode());
        }
        repository.insertParameterProfile(parameterId, 1L);

        String ruleVersionId = request.rule() == null ? null
                : String.valueOf(repository.insertRuleVersion(parameterId, inheritUnit(request),
                        request.reason(), request.operatorName()));

        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(parameterId), "CREATE",
                null, snapshot(request, sensor.sensorCode()), request.reason(), request.operatorName(),
                0, 1, request.requestId()));

        return repository.parameterDetail(parameterId)
                .orElseThrow(() -> new IllegalStateException("新增参数后未能读回参数 " + parameterId + "。"));
    }

    /** 规则没单独给单位时，沿用参数定义上的单位，避免同一参数出现两个量纲。 */
    private static RuleSnapshot inheritUnit(ParameterWriteRequest request) {
        RuleSnapshot rule = request.rule();
        if (rule.unitCode() != null || request.unitCode() == null) {
            return rule;
        }
        return new RuleSnapshot(rule.normalLower(), rule.normalUpper(),
                rule.alarmLower(), rule.alarmUpper(), request.unitCode(),
                rule.triggerMode(), rule.durationSeconds(), rule.enabled());
    }

    /** 单位和工序引用必须真实存在；不存在时按字段错误返回，不让外键异常冒成 500。 */
    private void assertReferences(ParameterWriteRequest request) {
        if (request.unitCode() != null && !repository.unitExists(request.unitCode())) {
            throw BusinessException.invalid("unitCode", "单位 " + request.unitCode() + " 不存在。");
        }
        if (request.processId() != null && !repository.processExists(request.processId())) {
            throw BusinessException.invalid("processId", "工序 " + request.processId() + " 不存在。");
        }
    }

    private static BusinessException duplicateParameterCode(String sensorCode, String code) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("parameterCode", "该测点下参数编码已存在。");
        return new BusinessException(ErrorCode.DUPLICATE_CODE,
                "测点 " + sensorCode + " 下参数编码 " + code + " 已被占用。",
                Map.of("fields", fields));
    }

    // ---------------------------------------------------------------- 解析

    private static ParameterWriteRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw BusinessException.invalid("body", "请求体必须是 JSON 对象。");
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        for (String field : REQUIRED) {
            if (!body.has(field)) {
                missing.put(field, "该字段必须出现；可空字段请显式传 null。");
            }
        }
        if (!missing.isEmpty()) {
            throw BusinessException.invalid(missing);
        }

        Map<String, Object> errors = new LinkedHashMap<>();
        String requestId = text(body.get("requestId"));
        String operatorName = text(body.get("operatorName"));
        String reason = text(body.get("reason"));
        String parameterCode = text(body.get("parameterCode"));
        String name = text(body.get("name"));
        String dataType = text(body.get("dataType"));
        String unitCode = text(body.get("unitCode"));
        Long decimalPlaces = number(body.get("decimalPlaces"));
        Long samplingInterval = number(body.get("samplingIntervalSeconds"));
        Long processId = number(body.get("processId"));
        Boolean enabled = bool(body.get("enabled"));

        if (requestId == null) {
            errors.put("requestId", "请求编号为必填项。");
        }
        if (operatorName == null) {
            errors.put("operatorName", "操作人为必填项。");
        }
        if (reason != null && reason.length() > REASON_MAX) {
            errors.put("reason", "说明不得超过 " + REASON_MAX + " 个字符。");
        }
        if (parameterCode == null) {
            errors.put("parameterCode", "参数编码不得为空。");
        } else if (parameterCode.length() > CODE_MAX) {
            errors.put("parameterCode", "参数编码不得超过 " + CODE_MAX + " 个字符。");
        } else if (!CODE_PATTERN.matcher(parameterCode).matches()) {
            errors.put("parameterCode", "参数编码只能包含字母、数字、点、横线与下划线，且以字母或数字开头。");
        }
        if (name == null) {
            errors.put("name", "参数名称不得为空。");
        } else if (name.length() > NAME_MAX) {
            errors.put("name", "参数名称不得超过 " + NAME_MAX + " 个字符。");
        }
        if (dataType == null) {
            errors.put("dataType", "数据类型不得为空。");
        } else if (!DATA_TYPES.contains(dataType)) {
            errors.put("dataType", "数据类型只能是 " + String.join("、", DATA_TYPES) + "。");
        }
        if (unitCode != null && unitCode.length() > UNIT_MAX) {
            errors.put("unitCode", "单位编码不得超过 " + UNIT_MAX + " 个字符。");
        }
        if (decimalPlaces != null && (decimalPlaces < 0 || decimalPlaces > DECIMAL_PLACES_MAX)) {
            errors.put("decimalPlaces",
                    "小数位必须是 0 到 " + DECIMAL_PLACES_MAX + " 之间的整数。");
        }
        if (samplingInterval != null && samplingInterval <= 0) {
            errors.put("samplingIntervalSeconds", "采样周期必须是正整数，或显式传 null 表示不设周期。");
        }
        if (processId != null && processId <= 0) {
            errors.put("processId", "工序 ID 必须是正整数，或显式传 null 表示不归属工序。");
        }
        if (enabled == null) {
            errors.put("enabled", "必须明确给出启用状态。");
        }

        RuleSnapshot rule = parseRule(body.get("rule"), unitCode, errors);

        if (!errors.isEmpty()) {
            throw BusinessException.invalid(errors);
        }
        return new ParameterWriteRequest(requestId, operatorName, reason,
                parameterCode, name, dataType, unitCode,
                decimalPlaces == null ? null : decimalPlaces.intValue(),
                samplingInterval == null ? null : samplingInterval.intValue(),
                processId, enabled, rule);
    }

    /**
     * 解析规则对象。
     *
     * <p>触发模式与持续秒数的关系按库上 CHECK 提前拦住：{@code IMMEDIATE} 不允许带持续秒数，
     * {@code SUSTAINED} 且规则启用时必须有正整数秒数。让 400 说清楚，比让数据库回一个
     * CHECK 约束名更有用。</p>
     */
    private static RuleSnapshot parseRule(JsonNode node, String parameterUnit, Map<String, Object> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            errors.put("rule", "规则必须是 JSON 对象，或显式传 null 表示不配规则。");
            return null;
        }
        for (String field : RULE_REQUIRED) {
            if (!node.has(field)) {
                errors.put("rule." + field, "该字段必须出现；可空字段请显式传 null。");
            }
        }

        String normalLower = bound(node.get("normalLower"), "rule.normalLower", errors);
        String normalUpper = bound(node.get("normalUpper"), "rule.normalUpper", errors);
        String alarmLower = bound(node.get("alarmLower"), "rule.alarmLower", errors);
        String alarmUpper = bound(node.get("alarmUpper"), "rule.alarmUpper", errors);
        String ruleUnit = text(node.get("unitCode"));
        String triggerMode = text(node.get("triggerMode"));
        Long duration = number(node.get("durationSeconds"));
        Boolean enabled = bool(node.get("enabled"));

        if (ruleUnit != null && ruleUnit.length() > UNIT_MAX) {
            errors.put("rule.unitCode", "规则单位编码不得超过 " + UNIT_MAX + " 个字符。");
        }
        if (triggerMode != null && !SensorModels.TRIGGER_MODES.contains(triggerMode)) {
            errors.put("rule.triggerMode",
                    "触发模式只能是 " + String.join("、", SensorModels.TRIGGER_MODES) + "。");
        }
        if (enabled == null) {
            errors.put("rule.enabled", "必须明确给出规则的启用状态。");
        }

        assertOrder(normalLower, normalUpper, "rule.normalLower", "rule.normalUpper", errors);
        assertOrder(alarmLower, alarmUpper, "rule.alarmLower", "rule.alarmUpper", errors);

        if ("IMMEDIATE".equals(triggerMode) && duration != null) {
            errors.put("rule.durationSeconds", "立即触发模式不带持续秒数，请显式传 null。");
        }
        if ("SUSTAINED".equals(triggerMode) && Boolean.TRUE.equals(enabled)) {
            if (duration == null) {
                errors.put("rule.durationSeconds", "持续触发模式需要正整数持续秒数。");
            } else if (duration <= 0) {
                errors.put("rule.durationSeconds", "持续秒数必须是正整数。");
            }
        } else if (duration != null && duration <= 0) {
            errors.put("rule.durationSeconds", "持续秒数必须是正整数，或显式传 null。");
        }

        return new RuleSnapshot(normalLower, normalUpper, alarmLower, alarmUpper,
                ruleUnit == null ? parameterUnit : ruleUnit,
                triggerMode, duration == null ? null : duration.intValue(), Boolean.TRUE.equals(enabled));
    }

    /** 边界只接受十进制字符串或 null；空串按"没填"处理但要求调用方显式写 null。 */
    private static String bound(JsonNode node, String field, Map<String, Object> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            errors.put(field, "边界值必须用十进制字符串表示，或显式传 null 表示不设限。");
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            errors.put(field, "边界值不得为空字符串；不设限请传 null。");
            return null;
        }
        if (value.length() > BOUND_MAX) {
            errors.put(field, "边界值不得超过 " + BOUND_MAX + " 个字符。");
            return null;
        }
        try {
            new BigDecimal(value);
        } catch (NumberFormatException notNumber) {
            errors.put(field, "边界值必须是十进制数字。");
            return null;
        }
        return value;
    }

    private static void assertOrder(String lower, String upper, String lowerField, String upperField,
                                    Map<String, Object> errors) {
        if (lower == null || upper == null) {
            return;
        }
        if (new BigDecimal(lower).compareTo(new BigDecimal(upper)) > 0) {
            errors.put(lowerField, "下限不得高于上限。");
        }
    }

    private static Map<String, Object> snapshot(ParameterWriteRequest request, String sensorCode) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sensorCode", sensorCode);
        snapshot.put("parameterCode", request.parameterCode());
        snapshot.put("name", request.name());
        snapshot.put("dataType", request.dataType());
        snapshot.put("unitCode", request.unitCode());
        snapshot.put("decimalPlaces", request.decimalPlaces());
        snapshot.put("samplingIntervalSeconds", request.samplingIntervalSeconds());
        snapshot.put("processId", request.processId() == null ? null : String.valueOf(request.processId()));
        snapshot.put("enabled", request.enabled());
        snapshot.put("rule", request.rule());
        return snapshot;
    }

    /** 文本统一去两端空白；空串按缺失处理，不以空串冒充"已填写"。 */
    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static Long number(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean bool(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            String value = node.asText().trim().toLowerCase(java.util.Locale.ROOT);
            if ("true".equals(value) || "false".equals(value)) {
                return Boolean.parseBoolean(value);
            }
        }
        return null;
    }
}
