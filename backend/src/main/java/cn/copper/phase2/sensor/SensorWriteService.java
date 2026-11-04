package cn.copper.phase2.sensor;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.sensor.SensorModels.SensorDetail;
import cn.copper.phase2.sensor.SensorModels.SensorWriteRequest;
import cn.copper.phase2.sensor.SensorRepository.SensorRow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * S1-P03 / S1-P04 测点写入。
 *
 * <p>与设备、供应商共用同一套并发契约：幂等 requestId、乐观锁 expectedVersion、
 * 事务内校验、变更留痕。测点身份写一期 {@code dim_sensor}，扩展资料写
 * {@code p2_sensor_profile}，两张表在同一个事务里落库。</p>
 *
 * <p>三条自我约束：</p>
 * <ul>
 *   <li>不写 {@code data_source}。新建测点一律"待配置"，页面不得声称模拟器或 PLC 已对接。</li>
 *   <li>已有原始采集记录时不允许改编码——原始表按 {@code sensor_code} 关联，一改就断链，
 *       历史数据会变成查不到的孤儿。停用另建测点是唯一安全做法。</li>
 *   <li>停用不删除任何历史：{@code enabled=false} 只影响后续采集与页面状态。</li>
 * </ul>
 */
@Service
public class SensorWriteService {
    private static final String OBJECT_TYPE = "SENSOR";

    private static final int CODE_MAX = 128;
    private static final int NAME_MAX = 100;
    private static final int TYPE_MAX = 50;
    private static final int TEXT_MAX = 255;
    private static final int REASON_MAX = 1000;

    /** 编码要能进 URL、CSV 与报表列名，因此限定为字母数字开头、只含点横线下划线。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

    /** 可空字段同样要求显式出现：传 null 表示清空，漏传即校验失败。 */
    private static final List<String> CREATE_REQUIRED = List.of(
            "requestId", "operatorName", "sensorCode", "name", "equipmentId",
            "sensorType", "location", "purpose", "enabled");

    private static final List<String> UPDATE_REQUIRED = List.of(
            "requestId", "operatorName", "expectedVersion", "sensorCode", "name", "equipmentId",
            "sensorType", "location", "purpose", "enabled");

    private final SensorRepository repository;
    private final ChangeLogRepository changeLog;
    private final IdempotencyService idempotency;
    private final SensorService reader;

    public SensorWriteService(SensorRepository repository, ChangeLogRepository changeLog,
                              IdempotencyService idempotency, SensorService reader) {
        this.repository = repository;
        this.changeLog = changeLog;
        this.idempotency = idempotency;
        this.reader = reader;
    }

    /** S1-P03 新增测点身份与扩展位置资料。 */
    public SensorDetail create(JsonNode body) {
        SensorWriteRequest request = parse(body, false);
        return idempotency.execute(request.requestId(), "SENSOR_CREATE", request,
                SensorDetail.class, () -> doCreate(request));
    }

    /** S1-P04 修改测点资料与启停用。 */
    public SensorDetail update(String sensorId, JsonNode body) {
        long id = SensorService.requiredId(sensorId, "sensorId", "测点");
        SensorWriteRequest request = parse(body, true);
        return idempotency.execute(request.requestId(), "SENSOR_UPDATE:" + id, request,
                SensorDetail.class, () -> doUpdate(id, request));
    }

    private SensorDetail doCreate(SensorWriteRequest request) {
        assertEquipmentUsable(request.equipmentId());
        // 先查一次给出可读的 409；真正的唯一性仍由 uk_dim_sensor_code 兜底，
        // 并发插入时下面的 catch 会把数据库异常翻译成同一种说法。
        repository.findIdByCode(request.sensorCode()).ifPresent(existing -> {
            throw BusinessException.duplicateCode(request.sensorCode());
        });

        long sensorId;
        try {
            sensorId = repository.insertSensor(request.sensorCode(), request.name(),
                    request.equipmentId(), request.sensorType(), request.enabled());
        } catch (DuplicateKeyException race) {
            throw BusinessException.duplicateCode(request.sensorCode());
        }
        // 首存版本为 1：扩展档案存在即代表"已被维护过一次"，0 保留给一期种子测点。
        repository.insertProfile(sensorId, request.location(), request.purpose(), 1L);

        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(sensorId), "CREATE",
                null, snapshot(request), request.reason(), request.operatorName(),
                0, 1, request.requestId()));
        return reader.detail(String.valueOf(sensorId));
    }

    private SensorDetail doUpdate(long sensorId, SensorWriteRequest request) {
        SensorRow current = repository.lockRow(sensorId)
                .orElseThrow(() -> BusinessException.notFound("测点 " + sensorId + " 不存在。"));

        long expected = request.expectedVersion();
        if (current.version() != expected) {
            throw BusinessException.versionConflict(expected, current.version());
        }
        assertCodeChangeAllowed(current, request.sensorCode());
        assertEquipmentUsable(request.equipmentId());

        try {
            repository.updateSensor(sensorId, request.sensorCode(), request.name(),
                    request.equipmentId(), request.sensorType(), request.enabled());
        } catch (DuplicateKeyException race) {
            throw BusinessException.duplicateCode(request.sensorCode());
        }

        // 一期种子测点没有扩展档案行（版本 0）：这次更新就补一行，版本从 1 起算，
        // 与新建口径一致；已有档案则按版本条件推进。
        if (current.version() == 0) {
            repository.insertProfile(sensorId, request.location(), request.purpose(), 1L);
        } else if (repository.updateProfile(sensorId, request.location(), request.purpose(), expected) == 0) {
            throw BusinessException.versionConflict(expected, current.version());
        }

        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(sensorId), "UPDATE",
                snapshot(current), snapshot(request), request.reason(), request.operatorName(),
                toVersion(current.version()), toVersion(current.version() + 1), request.requestId()));
        return reader.detail(String.valueOf(sensorId));
    }

    /**
     * 编码变更的边界。
     *
     * <p>原始采集表只有 {@code sensor_code} 这一个连接键，改编码等于把历史数据剪断。
     * 因此有采集记录时直接拒绝，并给出可执行的替代路径（停用后另建），而不是悄悄放过。</p>
     */
    private void assertCodeChangeAllowed(SensorRow current, String newCode) {
        if (current.sensorCode().equals(newCode)) {
            return;
        }
        if (repository.hasHistory(current.sensorCode())) {
            throw BusinessException.invalid("sensorCode",
                    "测点 " + current.sensorCode() + " 已有原始采集记录，编码不能修改；"
                            + "请停用该测点后另建新测点承接后续采集。");
        }
        repository.findIdByCode(newCode).ifPresent(other -> {
            throw BusinessException.duplicateCode(newCode);
        });
    }

    private void assertEquipmentUsable(long equipmentId) {
        if (!repository.equipmentUsable(equipmentId)) {
            throw BusinessException.invalid("equipmentId",
                    "设备 " + equipmentId + " 不存在或不是生产设备。");
        }
    }

    // ---------------------------------------------------------------- 解析

    private static SensorWriteRequest parse(JsonNode body, boolean update) {
        if (body == null || !body.isObject()) {
            throw BusinessException.invalid("body", "请求体必须是 JSON 对象。");
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        for (String field : update ? UPDATE_REQUIRED : CREATE_REQUIRED) {
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
        String sensorCode = text(body.get("sensorCode"));
        String name = text(body.get("name"));
        String sensorType = text(body.get("sensorType"));
        String location = text(body.get("location"));
        String purpose = text(body.get("purpose"));
        String reason = text(body.get("reason"));
        Long equipmentId = number(body.get("equipmentId"));
        Long expectedVersion = number(body.get("expectedVersion"));
        Boolean enabled = bool(body.get("enabled"));

        if (requestId == null) {
            errors.put("requestId", "请求编号为必填项。");
        }
        if (operatorName == null) {
            errors.put("operatorName", "操作人为必填项。");
        }
        if (sensorCode == null) {
            errors.put("sensorCode", "测点编码不得为空。");
        } else if (sensorCode.length() > CODE_MAX) {
            errors.put("sensorCode", "测点编码不得超过 " + CODE_MAX + " 个字符。");
        } else if (!CODE_PATTERN.matcher(sensorCode).matches()) {
            errors.put("sensorCode", "测点编码只能包含字母、数字、点、横线与下划线，且以字母或数字开头。");
        }
        if (name == null) {
            errors.put("name", "测点名称不得为空。");
        } else if (name.length() > NAME_MAX) {
            errors.put("name", "测点名称不得超过 " + NAME_MAX + " 个字符。");
        }
        if (equipmentId == null || equipmentId <= 0) {
            errors.put("equipmentId", "必须提供正整数的归属设备 ID。");
        }
        checkLength(errors, "sensorType", sensorType, TYPE_MAX);
        checkLength(errors, "location", location, TEXT_MAX);
        checkLength(errors, "purpose", purpose, TEXT_MAX);
        checkLength(errors, "reason", reason, REASON_MAX);
        if (enabled == null) {
            errors.put("enabled", "必须明确给出启停用状态。");
        }
        if (update && (expectedVersion == null || expectedVersion < 0)) {
            errors.put("expectedVersion", "更新时必须提供非负的当前版本号。");
        }
        if (!errors.isEmpty()) {
            throw BusinessException.invalid(errors);
        }

        return new SensorWriteRequest(requestId, operatorName,
                update ? expectedVersion : null, reason, sensorCode, name, equipmentId,
                sensorType, location, purpose, enabled);
    }

    private static void checkLength(Map<String, Object> errors, String field, String value, int max) {
        if (value != null && value.length() > max) {
            errors.put(field, "不得超过 " + max + " 个字符。");
        }
    }

    private static Map<String, Object> snapshot(SensorWriteRequest request) {
        return snapshot(request.sensorCode(), request.name(), request.equipmentId(),
                request.sensorType(), request.location(), request.purpose(), request.enabled());
    }

    private static Map<String, Object> snapshot(SensorRow row) {
        return snapshot(row.sensorCode(), row.name(), row.equipmentId(),
                row.sensorType(), row.location(), row.purpose(), row.enabled());
    }

    private static Map<String, Object> snapshot(String sensorCode, String name, long equipmentId,
                                                String sensorType, String location,
                                                String purpose, boolean enabled) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sensorCode", sensorCode);
        snapshot.put("name", name);
        snapshot.put("equipmentId", String.valueOf(equipmentId));
        snapshot.put("sensorType", sensorType);
        snapshot.put("location", location);
        snapshot.put("purpose", purpose);
        snapshot.put("enabled", enabled);
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

    private static Integer toVersion(long version) {
        return version > Integer.MAX_VALUE ? null : Math.toIntExact(version);
    }
}
