package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.InspectionModels.InspectionCreateCommand;
import cn.copper.phase2.maintenance.InspectionModels.InspectionDetail;
import cn.copper.phase2.maintenance.InspectionModels.InspectionItem;
import cn.copper.phase2.maintenance.InspectionModels.InspectionPage;
import cn.copper.phase2.maintenance.InspectionModels.InspectionQuery;
import cn.copper.phase2.maintenance.InspectionModels.InspectionSaved;
import cn.copper.phase2.maintenance.InspectionModels.InspectionUpdateCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 巡检页签业务层：参数校验、分页换算，以及 S1-I03/I04 的写入编排。
 * 写入遵循《接口通用约定》§3：requestId 幂等 → 版本校验 → 事务内保存并留痕。
 */
@Service
public class InspectionService {
    static final Set<String> INSPECTION_RESULTS = Set.of("NORMAL", "ABNORMAL");
    static final String OPERATION_CREATE = "INSPECTION_CREATE";
    static final String OPERATION_UPDATE = "INSPECTION_UPDATE";
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final InspectionRepository repository;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public InspectionService(InspectionRepository repository, IdempotencyService idempotency,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    public InspectionPage inspections(InspectionQuery query, Integer page, Integer pageSize) {
        int resolvedPage = MaintenancePaging.page(page);
        int resolvedSize = MaintenancePaging.pageSize(pageSize);
        InspectionQuery validated = validate(query);

        long total = repository.countInspections(validated);
        if (total == 0) {
            return new InspectionPage(List.of(), resolvedPage, resolvedSize, 0);
        }
        List<InspectionItem> items = repository.findInspections(
                validated, MaintenancePaging.offset(resolvedPage, resolvedSize), resolvedSize);
        return new InspectionPage(items, resolvedPage, resolvedSize, total);
    }

    public InspectionDetail inspection(String inspectionId) {
        long id = parseId("inspectionId", inspectionId);
        InspectionDetail detail = repository.findInspection(id);
        if (detail == null) {
            throw new MaintenanceNotFoundException("巡检记录不存在或已被删除，请刷新后重试。");
        }
        return detail;
    }

    /** S1-I03 新增巡检：同一 requestId 重复提交只落一条记录。 */
    @Transactional
    public InspectionDetail create(InspectionCreateCommand command) {
        InspectionCreateCommand request = validate(command);
        Optional<InspectionDetail> replayed = idempotency.claim(
                request.requestId(), OPERATION_CREATE, null, request, InspectionDetail.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        long equipmentId = parseId("equipmentId", request.equipmentId());
        if (!repository.equipmentExists(equipmentId)) {
            throw new InvalidRequestException("equipmentId", "设备不存在，请重新选择。");
        }
        LocalDateTime inspectedAt = MaintenanceTimes.toLocal(
                MaintenanceTimes.parseMoment("inspectedAt", request.inspectedAt()));

        long inspectionId = repository.insertInspection(equipmentId, inspectedAt, request.inspectorName(),
                request.content(), request.result(), request.description(), request.operatorName());
        InspectionDetail detail = repository.findInspection(inspectionId);
        idempotency.complete(request.requestId(), detail);
        return detail;
    }

    /** S1-I04 更正巡检：版本不符返回 409，更正内容写入 p2_change_log 并回传编号。 */
    @Transactional
    public InspectionSaved update(String inspectionId, InspectionUpdateCommand command) {
        long id = parseId("inspectionId", inspectionId);
        InspectionUpdateCommand request = validate(command);

        Optional<InspectionSaved> replayed = idempotency.claim(
                request.requestId(), OPERATION_UPDATE, String.valueOf(id), request, InspectionSaved.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        Integer currentVersion = repository.lockVersion(id);
        if (currentVersion == null) {
            throw new MaintenanceNotFoundException("巡检记录不存在或已被删除，请刷新后重试。");
        }
        if (!currentVersion.equals(request.expectedVersion())) {
            throw new MaintenanceConflictException("VERSION_CONFLICT",
                    "该巡检记录已被更新，请刷新后重试。", Map.of("currentVersion", currentVersion));
        }

        InspectionDetail before = repository.findInspection(id);
        repository.updateInspection(id,
                MaintenanceTimes.toLocal(MaintenanceTimes.parseMoment("inspectedAt", request.inspectedAt())),
                request.inspectorName(), request.content(), request.result(), request.description());
        InspectionDetail after = repository.findInspection(id);

        long changeLogId = repository.insertChangeLog("INSPECTION", String.valueOf(id), OPERATION_UPDATE,
                json(before), json(after), request.reason(), request.operatorName());
        InspectionSaved saved = new InspectionSaved(after, String.valueOf(changeLogId));
        idempotency.complete(request.requestId(), saved);
        return saved;
    }

    private static InspectionCreateCommand validate(InspectionCreateCommand command) {
        if (command == null) {
            throw new InvalidRequestException("body", "请求体不能为空。");
        }
        return new InspectionCreateCommand(
                requestId(command.requestId()),
                operatorName(command.operatorName()),
                requiredDigits("equipmentId", command.equipmentId()),
                requiredMoment("inspectedAt", command.inspectedAt()),
                requiredText("inspectorName", command.inspectorName(), 100, "巡检人"),
                requiredText("content", command.content(), 2000, "巡检内容"),
                requiredResult(command.result()),
                optionalText(command.description()));
    }

    private static InspectionUpdateCommand validate(InspectionUpdateCommand command) {
        if (command == null) {
            throw new InvalidRequestException("body", "请求体不能为空。");
        }
        if (command.expectedVersion() == null) {
            throw new InvalidRequestException("expectedVersion", "更正已有记录必须携带 expectedVersion。");
        }
        if (command.expectedVersion() < 0) {
            throw new InvalidRequestException("expectedVersion", "expectedVersion 不能为负数。");
        }
        return new InspectionUpdateCommand(
                requestId(command.requestId()),
                operatorName(command.operatorName()),
                command.expectedVersion(),
                requiredText("reason", command.reason(), 1000, "更正原因"),
                requiredMoment("inspectedAt", command.inspectedAt()),
                requiredText("inspectorName", command.inspectorName(), 100, "巡检人"),
                requiredText("content", command.content(), 2000, "巡检内容"),
                requiredResult(command.result()),
                optionalText(command.description()));
    }

    private static String requestId(String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException("requestId", "requestId 必填，用于防止重复提交。");
        }
        if (!UUID.matcher(text).matches()) {
            throw new InvalidRequestException("requestId", "requestId 应为 36 位 UUID。");
        }
        return text;
    }

    private static String operatorName(String value) {
        return requiredText("operatorName", value, 100, "经办人");
    }

    private static String requiredText(String field, String value, int maxLength, String label) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, label + "不能为空。");
        }
        if (text.length() > maxLength) {
            throw new InvalidRequestException(field, label + "不能超过 " + maxLength + " 个字符。");
        }
        return text;
    }

    private static String requiredDigits(String field, String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, "设备 ID 不能为空。");
        }
        parseId(field, text);
        return text;
    }

    private static String requiredMoment(String field, String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, "时间不能为空。");
        }
        MaintenanceTimes.parseMoment(field, text);
        return text;
    }

    private static String requiredResult(String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException("result", "巡检结果不能为空。");
        }
        if (!INSPECTION_RESULTS.contains(text)) {
            throw new InvalidRequestException("result", "巡检结果取值无效，应为 NORMAL 或 ABNORMAL。");
        }
        return text;
    }

    private static String optionalText(String value) {
        return blankToNull(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("留痕内容无法序列化", error);
        }
    }

    private static InspectionQuery validate(InspectionQuery query) {
        String equipmentId = blankToNull(query.equipmentId());
        if (equipmentId != null) {
            parseId("equipmentId", equipmentId);
        }

        String result = blankToNull(query.result());
        if (result != null && !INSPECTION_RESULTS.contains(result)) {
            throw new InvalidRequestException("result", "巡检结果取值无效，应为 NORMAL 或 ABNORMAL。");
        }

        String from = blankToNull(query.from());
        String to = blankToNull(query.to());
        if (from != null) {
            MaintenanceTimes.parseMoment("from", from);
        }
        if (to != null) {
            MaintenanceTimes.parseMoment("to", to);
        }
        if (from != null && to != null
                && !MaintenanceTimes.parseMoment("from", from).isBefore(MaintenanceTimes.parseMoment("to", to))) {
            throw new InvalidRequestException("from", "开始时间必须早于结束时间。");
        }
        return new InspectionQuery(equipmentId, result, from, to);
    }

    static long parseId(String field, String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, "ID 不能为空。");
        }
        if (!text.chars().allMatch(Character::isDigit)) {
            throw new InvalidRequestException(field, "ID 必须是十进制数字字符串。");
        }
        long id = Long.parseLong(text);
        if (id <= 0) {
            throw new InvalidRequestException(field, "ID 必须大于 0。");
        }
        return id;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
