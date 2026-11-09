package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.AlertEventModels.AlertEventDetail;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventItem;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventPage;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventQuery;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingCommand;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingRecordItem;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingRecordPage;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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
    private static final Set<String> HANDLING_ACTIONS =
            Set.of(AlertEventStates.ACKNOWLEDGED, AlertEventStates.DEFER_REPAIR);
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final AlertEventRepository repository;
    private final IdempotencyService idempotency;

    public AlertEventService(AlertEventRepository repository, IdempotencyService idempotency) {
        this.repository = repository;
        this.idempotency = idempotency;
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
