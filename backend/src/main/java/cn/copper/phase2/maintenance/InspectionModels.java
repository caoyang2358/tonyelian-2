package cn.copper.phase2.maintenance;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 巡检页签对外数据契约（S1-I01 列表、S1-I02 详情）。
 */
public final class InspectionModels {
    private InspectionModels() {}

    /** 列表行：含设备、实际巡检时刻、巡检人、结果及关联报修单。 */
    public record InspectionItem(
            String inspectionId,
            String equipmentId,
            String equipmentName,
            OffsetDateTime inspectedAt,
            String inspectorName,
            String result,
            List<String> repairOrderIds,
            int version) {}

    /** 详情：在列表行基础上补巡检内容、情况说明与录入时间。 */
    public record InspectionDetail(
            String inspectionId,
            String equipmentId,
            String equipmentName,
            OffsetDateTime inspectedAt,
            String inspectorName,
            String content,
            String result,
            String description,
            List<String> repairOrderIds,
            OffsetDateTime recordedAt,
            int version) {}

    public record InspectionPage(List<InspectionItem> items, int page, int pageSize, long total) {}

    /** 列表查询条件；未传的项为 null，表示不参与过滤。 */
    public record InspectionQuery(String equipmentId, String result, String from, String to) {}
}
