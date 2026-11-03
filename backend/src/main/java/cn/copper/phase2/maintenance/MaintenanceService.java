package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.MaintenanceModels.RepairOrderItem;
import cn.copper.phase2.maintenance.MaintenanceModels.RepairOrderPage;
import cn.copper.phase2.maintenance.MaintenanceModels.RepairOrderQuery;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * 运维管理业务层。当前实现 S1-R02 工单列表：
 * 参数校验、分页换算、排序与统计口径都放在这里，Controller 只做收发。
 */
@Service
public class MaintenanceService {
    static final Set<String> REPAIR_ORDER_STATUSES = Set.of(
            "DRAFT", "REVIEW_PENDING", "RETURNED_FOR_INFO", "IN_REPAIR",
            "ACCEPTANCE_PENDING", "COMPLETED", "CLOSED");
    static final Set<String> REPAIR_SOURCE_TYPES = Set.of("ALERT_EVENT", "INSPECTION", "MANUAL");

    private final MaintenanceRepository repository;

    public MaintenanceService(MaintenanceRepository repository) {
        this.repository = repository;
    }

    public RepairOrderPage repairOrders(RepairOrderQuery query, Integer page, Integer pageSize) {
        int resolvedPage = MaintenancePaging.page(page);
        int resolvedSize = MaintenancePaging.pageSize(pageSize);
        RepairOrderQuery validated = validate(query);

        long total = repository.countRepairOrders(validated);
        if (total == 0) {
            return new RepairOrderPage(List.of(), resolvedPage, resolvedSize, 0);
        }
        List<RepairOrderItem> items = repository.findRepairOrders(
                validated, MaintenancePaging.offset(resolvedPage, resolvedSize), resolvedSize);
        return new RepairOrderPage(items, resolvedPage, resolvedSize, total);
    }

    private static RepairOrderQuery validate(RepairOrderQuery query) {
        String equipmentId = blankToNull(query.equipmentId());
        if (equipmentId != null) {
            if (!equipmentId.chars().allMatch(Character::isDigit)) {
                throw new InvalidRequestException("equipmentId", "设备 ID 必须是十进制数字字符串。");
            }
            if (Long.parseLong(equipmentId) <= 0) {
                throw new InvalidRequestException("equipmentId", "设备 ID 必须大于 0。");
            }
        }

        String status = blankToNull(query.status());
        if (status != null && !REPAIR_ORDER_STATUSES.contains(status)) {
            throw new InvalidRequestException("status",
                    "工单状态取值无效，应为 " + String.join(" / ", REPAIR_ORDER_STATUSES.stream().sorted().toList()) + "。");
        }

        String sourceType = blankToNull(query.sourceType());
        if (sourceType != null && !REPAIR_SOURCE_TYPES.contains(sourceType)) {
            throw new InvalidRequestException("sourceType", "来源类型取值无效，应为 ALERT_EVENT / INSPECTION / MANUAL。");
        }

        String from = blankToNull(query.from());
        String to = blankToNull(query.to());
        OffsetDateTime fromMoment = parseMoment("from", from);
        OffsetDateTime toMoment = parseMoment("to", to);
        if (fromMoment != null && toMoment != null && !fromMoment.isBefore(toMoment)) {
            throw new InvalidRequestException("from", "开始时间必须早于结束时间。");
        }

        return new RepairOrderQuery(equipmentId, status, sourceType, from, to);
    }

    private static OffsetDateTime parseMoment(String field, String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException error) {
            throw new InvalidRequestException(field, "时间格式应为带时区偏移的 ISO 8601，例如 2026-11-03T08:00:00+08:00。");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
