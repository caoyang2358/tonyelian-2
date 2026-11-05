package cn.copper.phase2.supplier;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.supplier.SupplierModels.SupplierDetail;
import cn.copper.phase2.supplier.SupplierModels.SupplierWriteRequest;
import cn.copper.phase2.supplier.SupplierRepository.SupplierRow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S1-S03 / S1-S04 供应商写入。
 *
 * <p>与设备写入共用同一套并发契约：幂等 requestId、乐观锁 expectedVersion、事务内校验、
 * 变更留痕。供应商资料与设备关联在同一个事务里落库，不存在"改了资料但关联没换"的中间态。</p>
 *
 * <p>{@code equipmentIds} 按整体替换语义处理：传入什么就是最终关联集合，
 * 空数组表示解除该供应商的全部设备关联。这是契约明确要求的行为，不做"空数组等于不修改"的猜测。</p>
 */
@Service
public class SupplierWriteService {
    private static final String OBJECT_TYPE = "SUPPLIER";
    private static final int NAME_MAX = 150;
    private static final int CONTACT_MAX = 100;
    private static final int PHONE_MAX = 50;
    private static final int REMARK_MAX = 2000;
    private static final int REASON_MAX = 1000;

    /** 可空字段同样要求显式出现：传 null 表示清空，漏传即校验失败，避免"漏传被当成清空"。 */
    private static final List<String> CREATE_REQUIRED = List.of(
            "requestId", "operatorName", "name", "contactName", "phone", "remark", "equipmentIds");

    private static final List<String> UPDATE_REQUIRED = List.of(
            "requestId", "operatorName", "expectedVersion", "name", "contactName", "phone",
            "remark", "equipmentIds");

    private final SupplierRepository repository;
    private final ChangeLogRepository changeLog;
    private final IdempotencyService idempotency;
    private final SupplierService reader;

    public SupplierWriteService(SupplierRepository repository, ChangeLogRepository changeLog,
                                IdempotencyService idempotency, SupplierService reader) {
        this.repository = repository;
        this.changeLog = changeLog;
        this.idempotency = idempotency;
        this.reader = reader;
    }

    /** S1-S03 新增供应商并关联设备。名称允许重复——同一名称在现实里可能对应不同主体。 */
    public SupplierDetail create(JsonNode body) {
        SupplierWriteRequest request = parse(body, false);
        return idempotency.execute(request.requestId(), "SUPPLIER_CREATE", request,
                SupplierDetail.class, () -> doCreate(request));
    }

    /** S1-S04 整体更新供应商资料与设备关联。 */
    public SupplierDetail update(String supplierId, JsonNode body) {
        long id = SupplierService.parseSupplierId(supplierId);
        SupplierWriteRequest request = parse(body, true);
        return idempotency.execute(request.requestId(), "SUPPLIER_UPDATE:" + id, request,
                SupplierDetail.class, () -> doUpdate(id, request));
    }

    private SupplierDetail doCreate(SupplierWriteRequest request) {
        // 事务内再校验一次：外键虽然会兜底，但那是 500；这里要给出可读的 400。
        assertEquipmentUsable(request.equipmentIds());
        long supplierId = repository.insert(request.name(), request.contactName(),
                request.phone(), request.remark());
        repository.replaceAssociations(supplierId, request.equipmentIds());
        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(supplierId), "CREATE",
                null, snapshot(request), request.reason(), request.operatorName(),
                0, 1, request.requestId()));
        return reader.detail(supplierId);
    }

    private SupplierDetail doUpdate(long supplierId, SupplierWriteRequest request) {
        // 锁定供应商行：版本比对、主表更新与关联替换之间不允许被其他事务插队。
        SupplierRow current = repository.lockRow(supplierId)
                .orElseThrow(() -> BusinessException.notFound("供应商不存在或已被删除。"));

        long expected = request.expectedVersion();
        if (current.version() != expected) {
            throw BusinessException.versionConflict(expected, current.version());
        }

        assertEquipmentUsable(request.equipmentIds());

        // 变更前关联集合必须在替换之前读出，否则留痕的 before 会变成"已替换后"的样子。
        List<Long> beforeIds = repository.equipmentIds(supplierId);

        if (repository.update(supplierId, request.name(), request.contactName(),
                request.phone(), request.remark(), expected) == 0) {
            throw BusinessException.versionConflict(expected, current.version());
        }
        repository.replaceAssociations(supplierId, request.equipmentIds());

        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(supplierId), "UPDATE",
                snapshot(current, beforeIds), snapshot(request), request.reason(),
                request.operatorName(), toVersion(current.version()),
                toVersion(current.version() + 1), request.requestId()));

        return reader.detail(supplierId);
    }

    /** 校验设备集合：只接受存在且为生产用途的设备，缺失项一次列全，便于页面逐条提示。 */
    private void assertEquipmentUsable(List<Long> equipmentIds) {
        if (equipmentIds.isEmpty()) {
            return;
        }
        Set<Long> usable = new HashSet<>(repository.usableEquipmentIds(equipmentIds));
        List<String> missing = equipmentIds.stream()
                .filter(id -> !usable.contains(id))
                .map(String::valueOf)
                .toList();
        if (!missing.isEmpty()) {
            throw BusinessException.invalid("equipmentIds",
                    "以下设备不存在或不是生产设备：" + String.join("、", missing));
        }
    }

    /**
     * 严格解析：必填字段必须出现；可空字段必须出现但可以传 null；
     * equipmentIds 必须是数组，元素为十进制字符串或整数，重复项按首次出现去重。
     */
    private static SupplierWriteRequest parse(JsonNode body, boolean update) {
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
        String name = text(body.get("name"));
        String contactName = text(body.get("contactName"));
        String phone = text(body.get("phone"));
        String remark = text(body.get("remark"));
        String reason = text(body.get("reason"));
        Long expectedVersion = number(body.get("expectedVersion"));

        if (requestId == null) {
            errors.put("requestId", "请求编号为必填项。");
        }
        if (operatorName == null) {
            errors.put("operatorName", "操作人为必填项。");
        }
        if (name == null) {
            errors.put("name", "供应商名称不得为空。");
        } else if (name.length() > NAME_MAX) {
            errors.put("name", "供应商名称不得超过 " + NAME_MAX + " 个字符。");
        }
        checkLength(errors, "contactName", contactName, CONTACT_MAX);
        checkLength(errors, "phone", phone, PHONE_MAX);
        checkLength(errors, "remark", remark, REMARK_MAX);
        if (reason != null && reason.length() > REASON_MAX) {
            errors.put("reason", "补充说明不得超过 " + REASON_MAX + " 个字符。");
        }
        if (update) {
            if (expectedVersion == null || expectedVersion < 0) {
                errors.put("expectedVersion", "更新时必须提供非负的当前版本号。");
            }
        }

        List<Long> equipmentIds = parseEquipmentIds(body.get("equipmentIds"), errors);
        if (!errors.isEmpty()) {
            throw BusinessException.invalid(errors);
        }
        return new SupplierWriteRequest(requestId, operatorName,
                update ? expectedVersion : null, reason, name, contactName, phone, remark, equipmentIds);
    }

    private static List<Long> parseEquipmentIds(JsonNode node, Map<String, Object> errors) {
        if (node == null || !node.isArray()) {
            errors.put("equipmentIds", "必须是数组；不关联设备时传空数组。");
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (JsonNode element : node) {
            Long id = number(element);
            if (id == null || id <= 0) {
                errors.put("equipmentIds", "设备 ID 必须是正整数字符串。");
                return List.of();
            }
            unique.add(id);
        }
        return List.copyOf(unique);
    }

    private static void checkLength(Map<String, Object> errors, String field,
                                    String value, int max) {
        if (value != null && value.length() > max) {
            errors.put(field, "不得超过 " + max + " 个字符。");
        }
    }

    private static Map<String, Object> snapshot(SupplierWriteRequest request) {
        return snapshot(request.name(), request.contactName(), request.phone(),
                request.remark(), request.equipmentIds());
    }

    private static Map<String, Object> snapshot(SupplierRow row, List<Long> equipmentIds) {
        return snapshot(row.name(), row.contactName(), row.phone(), row.remark(), equipmentIds);
    }

    private static Map<String, Object> snapshot(String name, String contactName, String phone,
                                                String remark, List<Long> equipmentIds) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", name);
        snapshot.put("contactName", contactName);
        snapshot.put("phone", phone);
        snapshot.put("remark", remark);
        snapshot.put("equipmentIds", equipmentIds.stream().map(String::valueOf).toList());
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

    private static Integer toVersion(long version) {
        return version > Integer.MAX_VALUE ? null : Math.toIntExact(version);
    }
}
