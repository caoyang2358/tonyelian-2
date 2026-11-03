package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.AlertEventModels.AlertEventDetail;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventItem;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventPage;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventQuery;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingRecordItem;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingRecordPage;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 提醒页签业务层：查询条件校验、分页换算，以及事件详情的组装。
 *
 * <p>本页签只读。人工处理（S1-A03）留在后续提交，避免一次引入过多改动。
 */
@Service
public class AlertEventService {
    private final AlertEventRepository repository;

    public AlertEventService(AlertEventRepository repository) {
        this.repository = repository;
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

    private static AlertEventQuery validate(AlertEventQuery query) {
        Long equipmentId = MaintenanceIds.optional("equipmentId", query.equipmentId());
        Long sensorId = MaintenanceIds.optional("sensorId", query.sensorId());
        Long parameterId = MaintenanceIds.optional("parameterId", query.parameterId());
        String eventState = stateValue("eventState", query.eventState(), AlertEventStates.EVENT_STATES,
                "事件状态取值无效，应为 ONGOING / RECOVERED / ENDED。");
        String handlingState = stateValue("handlingState", query.handlingState(), AlertEventStates.HANDLING_STATES,
                "人工状态取值无效，应为 PENDING / ACKNOWLEDGED / DEFER_REPAIR。");

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
                to);
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
