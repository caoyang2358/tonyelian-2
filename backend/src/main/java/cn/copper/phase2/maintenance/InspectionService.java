package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.InspectionModels.InspectionDetail;
import cn.copper.phase2.maintenance.InspectionModels.InspectionItem;
import cn.copper.phase2.maintenance.InspectionModels.InspectionPage;
import cn.copper.phase2.maintenance.InspectionModels.InspectionQuery;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 巡检页签业务层：参数校验与分页换算。当前实现 S1-I01（列表）与 S1-I02（详情）。
 */
@Service
public class InspectionService {
    static final Set<String> INSPECTION_RESULTS = Set.of("NORMAL", "ABNORMAL");

    private final InspectionRepository repository;

    public InspectionService(InspectionRepository repository) {
        this.repository = repository;
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
        if (from != null && to != null
                && !MaintenanceTimes.parseMoment("from", from).isBefore(MaintenanceTimes.parseMoment("to", to))) {
            throw new InvalidRequestException("from", "开始时间必须早于结束时间。");
        }
        if (from != null) {
            MaintenanceTimes.parseMoment("from", from);
        }
        if (to != null) {
            MaintenanceTimes.parseMoment("to", to);
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
