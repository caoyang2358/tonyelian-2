package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.AlertEventModels.AlertEventDetail;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventItem;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventPage;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventQuery;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingCommand;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingRecordItem;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingRecordPage;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingResult;
import cn.copper.phase2.maintenance.AlertEventModels.ManualEventCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 提醒页签业务层：查询条件校验、分页换算、事件详情组装，以及 S1-A03 人工处理的写入编排。
 *
 * <p>人工处理只追加记录并推进事件版本，不改触发值、恢复时间、结束原因等事实列。
 */
@Service
public class AlertEventService {
    static final String OPERATION_HANDLE = "ALERT_HANDLING_CREATE";
    static final String OPERATION_MANUAL_CREATE = "ALERT_EVENT_MANUAL_CREATE";
    private static final Set<String> HANDLING_ACTIONS =
            Set.of(AlertEventStates.ACKNOWLEDGED, AlertEventStates.DEFER_REPAIR);
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final AlertEventRepository repository;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public AlertEventService(AlertEventRepository repository, IdempotencyService idempotency,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    public AlertEventPage events(AlertEventQuery query, Integer page, Integer pageSize) {
        int resolvedPage = MaintenancePaging.page(page);
        int resolvedSize = MaintenancePaging.pageSize(pageSize);
        AlertEventQuery validated = validate(query);

        long total = repository.countEvents(validated);
        if (total == 0) {
            return new AlertEventPage(List.of(), resolvedPage, resolvedSize, 0);
        }
        List<AlertEventItem> items = repository.findEvents(
                validated, MaintenancePaging.offset(resolvedPage, resolvedSize), resolvedSize);
        return new AlertEventPage(items, resolvedPage, resolvedSize, total);
    }

    public AlertEventDetail event(String eventId) {
        long id = MaintenanceIds.parse("eventId", eventId);
        AlertEventDetail detail = repository.findEvent(id);
        if (detail == null) {
            throw new MaintenanceNotFoundException("异常事件不存在或已被删除，请刷新后重试。");
        }
        return detail;
    }

    public HandlingRecordPage handlingRecords(String eventId, Integer page, Integer pageSize) {
        long id = MaintenanceIds.parse("eventId", eventId);
        int resolvedPage = MaintenancePaging.page(page);
        int resolvedSize = MaintenancePaging.pageSize(pageSize);
        if (!repository.eventExists(id)) {
            throw new MaintenanceNotFoundException("异常事件不存在或已被删除，请刷新后重试。");
        }

        long total = repository.countHandlingRecords(id);
        if (total == 0) {
            return new HandlingRecordPage(List.of(), resolvedPage, resolvedSize, 0);
        }
        List<HandlingRecordItem> items = repository.findHandlingRecords(
                id, MaintenancePaging.offset(resolvedPage, resolvedSize), resolvedSize);
        return new HandlingRecordPage(items, resolvedPage, resolvedSize, total);
    }

    /**
     * S1-A03 记录已知悉或暂不报修。
     *
     * <p>先幂等、再锁行校版本：版本不符说明别人刚处理过，返回 409 让前端刷新；
     * 版本正确才追加记录并推进事件版本。整个动作在一个事务内完成。
     */
    @Transactional
    public HandlingResult handle(String eventId, HandlingCommand command) {
        long id = MaintenanceIds.parse("eventId", eventId);
        HandlingCommand request = validate(command);

        Optional<HandlingResult> replayed = idempotency.claim(
                request.requestId(), OPERATION_HANDLE, String.valueOf(id), request, HandlingResult.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        Integer currentVersion = repository.lockEventVersion(id);
        if (currentVersion == null) {
            throw new MaintenanceNotFoundException("异常事件不存在或已被删除，请刷新后重试。");
        }
        if (!currentVersion.equals(request.expectedVersion())) {
            throw new MaintenanceConflictException("VERSION_CONFLICT",
                    "该异常事件已被处理，请刷新后重试。", Map.of("currentVersion", currentVersion));
        }

        long recordId = repository.insertHandlingRecord(
                id, request.action(), request.reason(), request.operatorName());
        AlertEventRepository.EventTouch touch = repository.touchEvent(id);
        HandlingResult result = new HandlingResult(
                String.valueOf(recordId), request.action(), touch.version(), touch.updatedAt());
        idempotency.complete(request.requestId(), result);
        return result;
    }

    /**
     * S1-A05 人工补录异常事件。
     *
     * <p>为什么允许补录：采集缺失、设备离线、现场口头报警、事后核对发现漏报，这些场景下
     * 异常确实发生过，只是系统没记上。禁止补录只会把人逼到库里直接改数据，那才真的失去追溯。</p>
     *
     * <p>补录与采集判定的区别落在 {@code source_reference.type=MANUAL} 上，并强制记录补录人与
     * 补录原因，列表用「人工补录」标签区分——补录是事实记录，但不能冒充自动判定。</p>
     *
     * <p>三条硬约束在这里被显式处理：规则版本是外键（没有就先建一条，判据取参数定义里的阈值）、
     * 同一参数只能有一条未结束事件（唯一索引，这里先查一次给出可读提示）、
     * 触发时间不得早于起始时间（表上的 CHECK）。</p>
     */
    @Transactional
    public AlertEventDetail createManual(ManualEventCommand command) {
        ManualEventCommand request = validate(command);
        long sensorId = MaintenanceIds.parse("sensorId", request.sensorId());
        long parameterId = MaintenanceIds.parse("parameterId", request.parameterId());

        Optional<AlertEventDetail> replayed = idempotency.claim(request.requestId(), OPERATION_MANUAL_CREATE,
                sensorId + ":" + parameterId, request, AlertEventDetail.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        AlertEventRepository.SensorParameterRef target = repository.findSensorParameter(sensorId, parameterId);
        if (target == null) {
            throw new InvalidRequestException("parameterId", "所选测点下没有这个参数，请重新选择。");
        }

        Long activeEventId = repository.findActiveEventId(parameterId);
        if (activeEventId != null) {
            throw new MaintenanceConflictException("EVENT_ALREADY_ACTIVE",
                    "该参数已有进行中的异常事件（#" + activeEventId + "），请先处理或等它恢复后再补录。",
                    Map.of("activeEventId", String.valueOf(activeEventId)));
        }

        LocalDateTime startedAt = MaintenanceTimes.toLocal(
                MaintenanceTimes.parseMoment("startedAt", request.startedAt()));
        LocalDateTime triggeredAt = request.triggeredAt() == null
                ? startedAt
                : MaintenanceTimes.toLocal(MaintenanceTimes.parseMoment("triggeredAt", request.triggeredAt()));
        if (triggeredAt.isBefore(startedAt)) {
            throw new InvalidRequestException("triggeredAt", "触发时间不能早于异常起始时间。");
        }

        Long ruleVersionId = repository.findLatestRuleVersionId(parameterId);
        if (ruleVersionId == null) {
            ruleVersionId = repository.insertInitialRuleVersion(parameterId, startedAt, request.operatorName());
        }

        long eventId;
        try {
            eventId = repository.insertManualEvent(target.equipmentId(), sensorId, parameterId, ruleVersionId,
                    startedAt, triggeredAt, new BigDecimal(request.triggerValue()), target.unitCode(),
                    reference(request.operatorName(), request.reason()));
        } catch (DuplicateKeyException raced) {
            // 有人抢在前面补了同一参数的事件：唯一索引挡住了第二条，转成可读的 409。
            throw new MaintenanceConflictException("EVENT_ALREADY_ACTIVE",
                    "该参数刚刚已有进行中的异常事件，请刷新列表后确认。", Map.of());
        }

        AlertEventDetail detail = repository.findEvent(eventId);
        if (detail == null) {
            throw new IllegalStateException("补录成功但读取事件失败：" + eventId);
        }
        idempotency.complete(request.requestId(), detail);
        return detail;
    }

    /** source_reference 固定写「人工补录 + 补录人 + 补录原因」；字段顺序固定，界面按原顺序翻译。 */
    private String reference(String operatorName, String reason) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", AlertEventStates.SOURCE_MANUAL);
        value.put("operator", operatorName);
        value.put("reason", reason);
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("补录来源无法序列化", error);
        }
    }

    private static ManualEventCommand validate(ManualEventCommand command) {
        if (command == null) {
            throw new InvalidRequestException("body", "请求体不能为空。");
        }
        String requestId = MaintenanceIds.blankToNull(command.requestId());
        if (requestId == null) {
            throw new InvalidRequestException("requestId", "requestId 必填，用于防止重复提交。");
        }
        if (!UUID.matcher(requestId).matches()) {
            throw new InvalidRequestException("requestId", "requestId 应为 36 位 UUID。");
        }
        String operatorName = requiredText("operatorName", command.operatorName(), 100, "补录人");
        String reason = requiredText("reason", command.reason(), 500, "补录原因");
        long sensorId = MaintenanceIds.parse("sensorId", command.sensorId());
        long parameterId = MaintenanceIds.parse("parameterId", command.parameterId());

        String triggerValue = requiredText("triggerValue", command.triggerValue(), 30, "超限数值");
        try {
            new BigDecimal(triggerValue);
        } catch (NumberFormatException error) {
            throw new InvalidRequestException("triggerValue", "超限数值必须是数字，例如 96.5。");
        }

        String startedAt = requiredText("startedAt", command.startedAt(), 40, "异常起始时间");
        OffsetDateTime moment = MaintenanceTimes.parseMoment("startedAt", startedAt);
        if (moment.isAfter(OffsetDateTime.now().plusMinutes(1))) {
            throw new InvalidRequestException("startedAt", "异常起始时间不能晚于当前时间。");
        }
        String triggeredAt = MaintenanceIds.blankToNull(command.triggeredAt());
        if (triggeredAt != null) {
            OffsetDateTime trigger = MaintenanceTimes.parseMoment("triggeredAt", triggeredAt);
            if (trigger.isAfter(OffsetDateTime.now().plusMinutes(1))) {
                throw new InvalidRequestException("triggeredAt", "触发时间不能晚于当前时间。");
            }
        }

        return new ManualEventCommand(requestId, String.valueOf(sensorId), String.valueOf(parameterId),
                triggerValue, startedAt, triggeredAt, reason, operatorName);
    }

    private static HandlingCommand validate(HandlingCommand command) {
        if (command == null) {
            throw new InvalidRequestException("body", "请求体不能为空。");
        }
        String requestId = MaintenanceIds.blankToNull(command.requestId());
        if (requestId == null) {
            throw new InvalidRequestException("requestId", "requestId 必填，用于防止重复提交。");
        }
        if (!UUID.matcher(requestId).matches()) {
            throw new InvalidRequestException("requestId", "requestId 应为 36 位 UUID。");
        }
        String operatorName = requiredText("operatorName", command.operatorName(), 100, "经办人");
        if (command.expectedVersion() == null) {
            throw new InvalidRequestException("expectedVersion", "处理异常事件必须携带 expectedVersion。");
        }
        if (command.expectedVersion() < 0) {
            throw new InvalidRequestException("expectedVersion", "expectedVersion 不能为负数。");
        }
        String action = stateValue("action", command.action(), HANDLING_ACTIONS,
                "人工动作取值无效，应为 ACKNOWLEDGED 或 DEFER_REPAIR。");
        if (action == null) {
            throw new InvalidRequestException("action", "人工动作不能为空。");
        }
        String reason = requiredText("reason", command.reason(), 1000, "处理原因");
        return new HandlingCommand(requestId, operatorName, command.expectedVersion(), action, reason);
    }

    private static String requiredText(String field, String value, int maxLength, String label) {
        String text = MaintenanceIds.blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, label + "不能为空。");
        }
        if (text.length() > maxLength) {
            throw new InvalidRequestException(field, label + "不能超过 " + maxLength + " 个字符。");
        }
        return text;
    }

    private static AlertEventQuery validate(AlertEventQuery query) {
        Long equipmentId = MaintenanceIds.optional("equipmentId", query.equipmentId());
        Long sensorId = MaintenanceIds.optional("sensorId", query.sensorId());
        Long parameterId = MaintenanceIds.optional("parameterId", query.parameterId());
        String eventState = stateValue("eventState", query.eventState(), AlertEventStates.EVENT_STATES,
                "事件状态取值无效，应为 ONGOING / RECOVERED / ENDED。");
        String handlingState = stateValue("handlingState", query.handlingState(), AlertEventStates.HANDLING_STATES,
                "人工状态取值无效，应为 PENDING / ACKNOWLEDGED / DEFER_REPAIR。");
        String sort = stateValue("sort", query.sort(), AlertEventStates.SORTS,
                "排序方式取值无效，应为 time（按时间）或 priority（按优先级）。");
        if (sort == null) {
            sort = AlertEventStates.SORT_TIME;
        }

        String from = MaintenanceIds.blankToNull(query.from());
        String to = MaintenanceIds.blankToNull(query.to());
        if (from != null && to != null) {
            OffsetDateTime start = MaintenanceTimes.parseMoment("from", from);
            OffsetDateTime end = MaintenanceTimes.parseMoment("to", to);
            if (!start.isBefore(end)) {
                throw new InvalidRequestException("from", "开始时间必须早于结束时间。");
            }
        } else {
            if (from != null) {
                MaintenanceTimes.parseMoment("from", from);
            }
            if (to != null) {
                MaintenanceTimes.parseMoment("to", to);
            }
        }

        return new AlertEventQuery(
                equipmentId == null ? null : String.valueOf(equipmentId),
                sensorId == null ? null : String.valueOf(sensorId),
                parameterId == null ? null : String.valueOf(parameterId),
                eventState,
                handlingState,
                from,
                to,
                sort);
    }

    /** 枚举值统一按大写比较，避免前端传小写被误判为无效。 */
    private static String stateValue(String field, String value, java.util.Set<String> allowed, String message) {
        String text = MaintenanceIds.blankToNull(value);
        if (text == null) {
            return null;
        }
        String normalized = text.toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new InvalidRequestException(field, message);
        }
        return normalized;
    }
}
