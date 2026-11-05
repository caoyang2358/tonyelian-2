package cn.copper.phase2.contract;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.contract.ContractModels.ContractDetail;
import cn.copper.phase2.contract.ContractModels.ContractWriteRequest;
import cn.copper.phase2.contract.ContractRepository.ContractRow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S1-C03 / S1-C04 合同写入。
 *
 * <p>与设备、供应商共用同一套并发契约：幂等 requestId、乐观锁 expectedVersion、
 * 事务内校验、变更留痕。合同主表与覆盖设备在同一个事务里落库。</p>
 *
 * <p>两处明确不做的写操作：一是不写财务结算，二是删改关联时不触碰 {@code p2_attachment}
 * 和维修单——契约要求"历史维修、合同附件引用不被连带删除"，所以更新覆盖设备只动关联表。</p>
 */
@Service
public class ContractWriteService {
    private static final String OBJECT_TYPE = "MAINTENANCE_CONTRACT";
    private static final int ORGANIZATION_MAX = 150;
    private static final int NUMBER_MAX = 100;
    private static final int REMARK_MAX = 2000;
    private static final int REASON_MAX = 1000;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 可空字段也要求显式出现：传 null 表示清空，漏传即校验失败，避免"漏传被当成清空"。 */
    private static final List<String> CREATE_REQUIRED = List.of(
            "requestId", "operatorName", "serviceOrganization", "contractNumber",
            "startDate", "endDate", "remark", "equipmentIds");

    private static final List<String> UPDATE_REQUIRED = List.of(
            "requestId", "operatorName", "expectedVersion", "serviceOrganization", "contractNumber",
            "startDate", "endDate", "remark", "equipmentIds");

    private final ContractRepository repository;
    private final ChangeLogRepository changeLog;
    private final IdempotencyService idempotency;
    private final ContractService reader;

    public ContractWriteService(ContractRepository repository, ChangeLogRepository changeLog,
                                IdempotencyService idempotency, ContractService reader) {
        this.repository = repository;
        this.changeLog = changeLog;
        this.idempotency = idempotency;
        this.reader = reader;
    }

    /** S1-C03 新增合同并关联设备。允许先建档、后补起止日期，未填的日期保持 null。 */
    public ContractDetail create(JsonNode body) {
        ContractWriteRequest request = parse(body, false);
        return idempotency.execute(request.requestId(), "CONTRACT_CREATE", request,
                ContractDetail.class, () -> doCreate(request));
    }

    /** S1-C04 更新合同期限、资料与覆盖设备；附件与维修单不在本次写入范围内。 */
    public ContractDetail update(String contractId, JsonNode body) {
        long id = ContractService.parseContractId(contractId);
        ContractWriteRequest request = parse(body, true);
        return idempotency.execute(request.requestId(), "CONTRACT_UPDATE:" + id, request,
                ContractDetail.class, () -> doUpdate(id, request));
    }

    private ContractDetail doCreate(ContractWriteRequest request) {
        assertEquipmentUsable(request.equipmentIds());
        long contractId = repository.insert(request.serviceOrganization(), request.contractNumber(),
                request.startDate(), request.endDate(), request.remark());
        repository.replaceEquipment(contractId, request.equipmentIds());
        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(contractId), "CREATE",
                null, snapshot(request), request.reason(), request.operatorName(),
                0, 1, request.requestId()));
        return reader.detail(contractId);
    }

    private ContractDetail doUpdate(long contractId, ContractWriteRequest request) {
        // 锁定合同行：版本比对、主表更新与关联替换之间不允许被其他事务插队。
        ContractRow current = repository.lockRow(contractId)
                .orElseThrow(() -> BusinessException.notFound("合同不存在或已被删除。"));

        long expected = request.expectedVersion();
        if (current.version() != expected) {
            throw BusinessException.versionConflict(expected, current.version());
        }

        assertEquipmentUsable(request.equipmentIds());

        // 变更前的覆盖设备必须在替换之前读出，否则留痕的 before 会变成替换后的样子。
        List<Long> beforeIds = repository.equipmentIds(contractId);

        if (repository.update(contractId, request.serviceOrganization(), request.contractNumber(),
                request.startDate(), request.endDate(), request.remark(), expected) == 0) {
            throw BusinessException.versionConflict(expected, current.version());
        }
        repository.replaceEquipment(contractId, request.equipmentIds());

        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(contractId), "UPDATE",
                snapshot(current, beforeIds), snapshot(request), request.reason(),
                request.operatorName(), toVersion(current.version()),
                toVersion(current.version() + 1), request.requestId()));

        return reader.detail(contractId);
    }

    /** 校验设备集合：只接受存在且为生产用途的设备，缺失项一次列全。 */
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
     * 严格解析：必填字段必须出现，可空字段必须出现但可以传 null。
     * 起止日期都填了才校验先后；只填一个不报错——契约允许"待补日期保留 null"。
     */
    private static ContractWriteRequest parse(JsonNode body, boolean update) {
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
        String serviceOrganization = text(body.get("serviceOrganization"));
        String contractNumber = text(body.get("contractNumber"));
        String remark = text(body.get("remark"));
        String reason = text(body.get("reason"));
        Long expectedVersion = number(body.get("expectedVersion"));

        if (requestId == null) {
            errors.put("requestId", "请求编号为必填项。");
        }
        if (operatorName == null) {
            errors.put("operatorName", "操作人为必填项。");
        }
        if (serviceOrganization == null) {
            errors.put("serviceOrganization", "服务单位名称不得为空。");
        } else if (serviceOrganization.length() > ORGANIZATION_MAX) {
            errors.put("serviceOrganization", "服务单位名称不得超过 " + ORGANIZATION_MAX + " 个字符。");
        }
        checkLength(errors, "contractNumber", contractNumber, NUMBER_MAX);
        checkLength(errors, "remark", remark, REMARK_MAX);
        if (reason != null && reason.length() > REASON_MAX) {
            errors.put("reason", "补充说明不得超过 " + REASON_MAX + " 个字符。");
        }
        if (update && (expectedVersion == null || expectedVersion < 0)) {
            errors.put("expectedVersion", "更新时必须提供非负的当前版本号。");
        }

        LocalDate startDate = date(body.get("startDate"), "startDate", errors);
        LocalDate endDate = date(body.get("endDate"), "endDate", errors);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            errors.put("endDate", "结束日期不得早于开始日期。");
        }

        List<Long> equipmentIds = parseEquipmentIds(body.get("equipmentIds"), errors);
        if (!errors.isEmpty()) {
            throw BusinessException.invalid(errors);
        }
        return new ContractWriteRequest(requestId, operatorName, update ? expectedVersion : null,
                reason, serviceOrganization, contractNumber, startDate, endDate, remark, equipmentIds);
    }

    /** 日期只接受 yyyy-MM-dd；空串按"未填写"处理，不把空字符串解析成今天。 */
    private static LocalDate date(JsonNode node, String field, Map<String, Object> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            errors.put(field, "日期必须是 yyyy-MM-dd 格式的字符串，或 null。");
            return null;
        }
        String raw = node.asText().trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw, DATE_FORMAT);
        } catch (DateTimeParseException error) {
            errors.put(field, "日期格式应为 yyyy-MM-dd，例如 2026-01-31。");
            return null;
        }
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

    private static Map<String, Object> snapshot(ContractWriteRequest request) {
        return snapshot(request.serviceOrganization(), request.contractNumber(), request.startDate(),
                request.endDate(), request.remark(), request.equipmentIds());
    }

    private static Map<String, Object> snapshot(ContractRow row, List<Long> equipmentIds) {
        return snapshot(row.serviceOrganization(), row.contractNumber(), row.startDate(),
                row.endDate(), row.remark(), equipmentIds);
    }

    private static Map<String, Object> snapshot(String serviceOrganization, String contractNumber,
                                                LocalDate startDate, LocalDate endDate,
                                                String remark, List<Long> equipmentIds) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("serviceOrganization", serviceOrganization);
        snapshot.put("contractNumber", contractNumber);
        snapshot.put("startDate", startDate == null ? null : startDate.format(DATE_FORMAT));
        snapshot.put("endDate", endDate == null ? null : endDate.format(DATE_FORMAT));
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
