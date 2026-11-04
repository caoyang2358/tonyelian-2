package cn.copper.phase2.equipment;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.equipment.EquipmentDeleteModels.EquipmentDeleteRequest;
import cn.copper.phase2.equipment.EquipmentDeleteModels.EquipmentDeleteResult;
import cn.copper.phase2.equipment.EquipmentModels.DeletionBlocker;
import cn.copper.phase2.equipment.EquipmentRepository.EquipmentRow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S1-E05 误建删除。
 *
 * <p>与更正共用同一套并发与留痕契约：幂等 requestId、乐观锁 expectedVersion、事务内校验。
 * 删除前在同一事务里重新核对全部业务引用，任何一类仍有数据就返回 409 及 blockers 并保留档案，
 * 前端传来的"无关联"声明不作依据。</p>
 *
 * <p>删除只清理设备共享身份与其自有扩展档案；{@code p2_change_log} 的审计留痕保留，
 * 因此删除后仍可用 E06 按历史身份追溯。不级联删除任何业务数据。</p>
 */
@Service
public class EquipmentDeleteService {
    private static final String OBJECT_TYPE = "EQUIPMENT";

    /** 公共字段 U，另加二次确认。 */
    private static final List<String> REQUIRED = List.of(
            "requestId", "operatorName", "expectedVersion", "reason", "confirmed");

    private static final int REASON_MAX = 1000;

    private final EquipmentRepository repository;
    private final ChangeLogRepository changeLog;
    private final IdempotencyService idempotency;
    private final EquipmentReferenceChecker references;

    public EquipmentDeleteService(EquipmentRepository repository, ChangeLogRepository changeLog,
                                  IdempotencyService idempotency, EquipmentReferenceChecker references) {
        this.repository = repository;
        this.changeLog = changeLog;
        this.idempotency = idempotency;
        this.references = references;
    }

    public EquipmentDeleteResult delete(String equipmentId, JsonNode body) {
        long id = EquipmentService.parseEquipmentId(equipmentId);
        EquipmentDeleteRequest request = parse(body);
        return idempotency.execute(request.requestId(), "EQUIPMENT_DELETE:" + id, request,
                EquipmentDeleteResult.class, () -> doDelete(id, request));
    }

    private EquipmentDeleteResult doDelete(long equipmentId, EquipmentDeleteRequest request) {
        // 锁定聚合行：版本比对、关联检查与删除之间不允许被其他事务插队。
        EquipmentRow current = repository.lockDetail(equipmentId)
                .orElseThrow(() -> BusinessException.notFound("设备不存在或已被删除。"));

        long expected = request.expectedVersion();
        if (current.version() != expected) {
            throw BusinessException.versionConflict(expected, current.version());
        }

        List<DeletionBlocker> blockers = references.blockers(equipmentId);
        if (!blockers.isEmpty()) {
            throw BusinessException.relatedData(
                    "该设备仍有关联业务数据，不能删除；如需停用请改为调整管理状态。", blockers);
        }

        // 自有扩展先清理，再删共享身份；留痕不动，审计依据得以保留。
        repository.deleteProfile(equipmentId);
        if (repository.deleteEquipment(equipmentId) == 0) {
            throw BusinessException.notFound("设备不存在或已被删除。");
        }

        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(equipmentId), "DELETE",
                EquipmentWriteService.snapshot(current), null, request.reason(),
                request.operatorName(), toVersion(current.version()), null, request.requestId()));

        return new EquipmentDeleteResult(String.valueOf(equipmentId), true);
    }

    /**
     * 严格解析删除请求：五个字段必须出现；confirmed 必须为 true。
     * 与写接口一致，可空字段也要求显式出现，避免"漏传被当成默认值"。
     */
    private static EquipmentDeleteRequest parse(JsonNode body) {
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
        return new EquipmentDeleteRequest(requestId, operatorName, expectedVersion, reason, true);
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
}
