package cn.copper.phase2.maintenance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 概览页签对外数据契约（S1-M01）。
 *
 * <p>文档要求"服务端在响应中说明所用口径"，因此每个统计值都在 {@code caliber} 里附一句口径说明，
 * 避免前端把"异常事件数"当成"报修次数"或把"未实现"当成"没有"。
 */
public final class OverviewModels {
    private OverviewModels() {}

    /** 查询条件：from/to 必传，车间与设备可选。 */
    public record OverviewQuery(String workshopId, String equipmentId, String from, String to) {}

    /** 按设备分组的报修次数；补充资料后重提不重复计数（依赖 first_submitted_at 只写一次）。 */
    public record EquipmentReportCount(
            String equipmentId,
            String equipmentName,
            long reportCount,
            long completedCount) {}

    public record MaintenanceOverview(
            String from,
            String to,
            OffsetDateTime generatedAt,
            long inspectionCount,
            long alertEventCount,
            long pendingRepairCount,
            long completedRepairCount,
            String averageHandlingSeconds,
            long handlingSampleCount,
            List<EquipmentReportCount> equipmentReportCounts,
            long deadlineReminderCount,
            String deadlineReminderNote,
            Map<String, String> caliber) {}
}
