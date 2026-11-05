package cn.copper.phase2.sensor;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.sensor.SensorModels.DeletionBlocker;
import cn.copper.phase2.sensor.SensorRepository.SensorRow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S1-P05 误建测点删除。
 *
 * <p>与 E05 设备删除同构：幂等 requestId、乐观锁 expectedVersion、事务内复查关联。
 * 删除前在同一事务里重新核对全部关联，任何一类仍有数据就返回 409 及 blockers 并保留测点，
 * 前端传来的"无关联"声明不作依据。</p>
 *
 * <p>删除范围分两级，见 {@link SensorReferenceChecker}：阻塞类一律不动；随删类只清理
 * 依附于这个测点自身的数据——组网布局节点、参数定义与初始规则版本、扩展档案。
 * {@code p2_change_log} 的审计留痕保留，删除后仍可按历史身份追溯。</p>
 *
 * <p>{@code ods_sensor_data} 从不删除：原始采集是按 {@code sensor_code} 存的独立事实，
 * 有记录时该测点连删除都不允许，编码因此永久冻结。</p>
 */
@Service
public class SensorDeleteService {
    private static final String OBJECT_TYPE = "SENSOR";

    /** 公共字段 U，另加二次确认。 */
    private static final List<String> REQUIRED = List.of(
            "requestId", "operatorName", "expectedVersion", "reason", "confirmed");

    private static final int REASON_MAX = 1000;

    private final SensorRepository repository;
    private final ChangeLogRepository changeLog;
    private final IdempotencyService idempotency;
    private final SensorReferenceChecker references;

    public SensorDeleteService(SensorRepository repository, ChangeLogRepository changeLog,
                               IdempotencyService idempotency, SensorReferenceChecker references) {
        this.repository = repository;
        this.changeLog = changeLog;
        this.idempotency = idempotency;
        this.references = references;
    }

    public SensorDeleteResult delete(String sensorId, JsonNode body) {
        long id = SensorService.requiredId(sensorId, "sensorId", "测点");
        SensorDeleteRequest request = parse(body);
        return idempotency.execute(request.requestId(), "SENSOR_DELETE:" + id, request,
                SensorDeleteResult.class, () -> doDelete(id, request));
    }

    private SensorDeleteResult doDelete(long sensorId, SensorDeleteRequest request) {
        // 锁定聚合行：版本比对、关联检查与删除之间不允许被其他事务插队。
        SensorRow current = repository.lockRow(sensorId)
                .orElseThrow(() -> BusinessException.notFound("测点 " + sensorId + " 不存在或已被删除。"));

        long expected = request.expectedVersion();
        if (current.version() != expected) {
            throw BusinessException.versionConflict(expected, current.version());
        }

        List<DeletionBlocker> blockers = references.blockers(sensorId, current.sensorCode());
        if (!blockers.isEmpty()) {
            throw BusinessException.relatedData(
                    "该测点仍有采集记录或业务历史，不能删除；如需停用请改为调整启停状态。", blockers);
        }

        // 参数行马上会被清掉，先在快照里留下它们的编码。
        List<String> parameterCodes = repository.parameterCodes(sensorId);
        long networkNodes = repository.countNetworkNodes(sensorId);

        // 先删引用方再删被引用方，顺序由外键决定。
        repository.deleteNetworkNodes(sensorId);
        repository.deleteRuleVersions(sensorId);
        repository.deleteParameterProfiles(sensorId);
        repository.deleteParameters(sensorId);
        repository.deleteProfile(sensorId);
        try {
            if (repository.deleteSensor(sensorId) == 0) {
                throw BusinessException.notFound("测点 " + sensorId + " 不存在或已被删除。");
            }
        } catch (DataIntegrityViolationException race) {
            // 检查与删除之间有人并发插了引用行；外键是最后一道防线，翻译成同一种 409，
            // 让用户重试后看到具体的 blockers，而不是一个数据库约束名。
            throw BusinessException.relatedData(
                    "该测点刚刚产生新的关联数据，请刷新后重试。", List.of());
        }

        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(sensorId), "DELETE",
                snapshot(current, parameterCodes, networkNodes), null, request.reason(),
                request.operatorName(), toVersion(current.version()), null, request.requestId()));

        return new SensorDeleteResult(String.valueOf(sensorId), true);
    }

    private static Map<String, Object> snapshot(SensorRow row, List<String> parameterCodes,
                                                long networkNodes) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sensorCode", row.sensorCode());
        snapshot.put("name", row.name());
        snapshot.put("equipmentId", String.valueOf(row.equipmentId()));
        snapshot.put("sensorType", row.sensorType());
        snapshot.put("location", row.location());
        snapshot.put("purpose", row.purpose());
        snapshot.put("enabled", row.enabled());
        snapshot.put("parameterCodes", parameterCodes);
        snapshot.put("networkNodes", networkNodes);
        return snapshot;
    }

    /**
     * 严格解析删除请求：五个字段必须出现；confirmed 必须为 true。
     * 与写接口一致，可空字段也要求显式出现，避免"漏传被当成默认值"。
     */
    private static SensorDeleteRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw BusinessException.invalid("body", "请求体必须是 JSON 对象。");
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        for (String field : REQUIRED) {
            if (!body.has(field)) {
                missing.put(field, "该字段必须出现。");
            }
        }
        if (!missing.isEmpty()) {
            throw BusinessException.invalid(missing);
        }

        Map<String, Object> errors = new LinkedHashMap<>();
        String requestId = text(body.get("requestId"));
        String operatorName = text(body.get("operatorName"));
        String reason = text(body.get("reason"));
        Long expectedVersion = number(body.get("expectedVersion"));
        boolean confirmed = body.path("confirmed").asBoolean(false);

        if (requestId == null) {
            errors.put("requestId", "请求编号为必填项。");
        }
        if (operatorName == null) {
            errors.put("operatorName", "操作人为必填项。");
        }
        if (reason == null) {
            errors.put("reason", "删除原因不得为空。");
        } else if (reason.length() > REASON_MAX) {
            errors.put("reason", "删除原因不得超过 " + REASON_MAX + " 个字符。");
        }
        if (expectedVersion == null || expectedVersion < 0) {
            errors.put("expectedVersion", "删除时必须提供非负的当前版本号。");
        }
        if (!confirmed) {
            errors.put("confirmed", "删除需要二次确认，confirmed 必须为 true。");
        }
        if (!errors.isEmpty()) {
            throw BusinessException.invalid(errors);
        }
        return new SensorDeleteRequest(requestId, operatorName, expectedVersion, reason, true);
    }

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

    private static Integer toVersion(long version) {
        return version > Integer.MAX_VALUE ? null : Math.toIntExact(version);
    }

    /** 删除请求：五个字段的含义与设备删除完全一致。 */
    public record SensorDeleteRequest(String requestId, String operatorName, long expectedVersion,
                                      String reason, boolean confirmed) {
    }

    /** 删除结果；字段名按契约用 {@code sensorId}。 */
    public record SensorDeleteResult(String sensorId, boolean deleted) {
    }
}
