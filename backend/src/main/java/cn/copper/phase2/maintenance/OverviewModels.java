package cn.copper.phase2.maintenance;

import cn.copper.phase2.deadline.DeadlineModels.DeadlineBreakdown;

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

    /**
     * 已完成工单的处理时长分布：与均值同一批样本，只是按 30 分钟 / 2 小时切开。
     *
     * <p>只看均值会掩盖长尾——均值 40 分钟也可能藏着一张 3 小时的单。分档后
     * "超过预期"的单有多少一目了然。</p>
     */
    public record DurationBuckets(long within30Minutes, long within2Hours, long over2Hours) {}

    /**
     * 临期与超期的期限条目按来源分：质保、维保、预计寿命。
     *
     * <p>待补资料不计入这里——那些条目没有到期日，分类型没有意义，单独作为
     * "档案不完整"的场景呈现。</p>
     */
    public record DeadlineTypeCounts(long warranty, long maintenance, long expectedLife) {}

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
            /** 同一批完成样本的时长分档，用于分段展示"超过预期"的量，而不只是均值。 */
            DurationBuckets durationBuckets,
            List<EquipmentReportCount> equipmentReportCounts,
            long deadlineReminderCount,
            DeadlineBreakdown deadlineBreakdown,
            /** 临期与超期按来源拆分（质保/维保/寿命），回答"是哪一类期限到期"。 */
            DeadlineTypeCounts deadlineTypeCounts,
            /** 待补资料涉及的设备台数：条数是"设备 × 期限类型"，与台数不是一回事。 */
            long deadlineMissingEquipmentCount,
            /** 在办工单中从首次提交到现在超过 2 小时仍未结束的数量，与上面的时间范围无关。 */
            long overdueRepairCount,
            String deadlineReminderNote,
            Map<String, String> caliber) {}
}
