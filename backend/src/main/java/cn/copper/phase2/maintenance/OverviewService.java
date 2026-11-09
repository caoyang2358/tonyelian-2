package cn.copper.phase2.maintenance;

import cn.copper.phase2.deadline.DeadlineService;
import cn.copper.phase2.maintenance.OverviewModels.EquipmentReportCount;
import cn.copper.phase2.maintenance.OverviewModels.MaintenanceOverview;
import cn.copper.phase2.maintenance.OverviewModels.OverviewQuery;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 概览页签业务层（S1-M01）。只读统计，不写任何表。
 *
 * <p>口径来自《运维管理页面接口文档》§5。文档要求服务端在响应中说明口径，
 * 因此每个数字都附一句说明；未实现的统计返回 null 并注明原因，不用 0 冒替。
 */
@Service
public class OverviewService {
    /** 期限提醒的统计口径：算的是"查询这一刻"的状态，与上下方的时间范围无关。 */
    static final String DEADLINE_NOTE = "统计查询时刻全部生产设备的质保、维保与预计寿命情况，"
            + "只计入「临期」与「已超期」两类；与上方的统计时间范围无关，反映的是当前时点的状态。";

    private final OverviewRepository repository;
    private final DeadlineService deadlineService;

    public OverviewService(OverviewRepository repository, DeadlineService deadlineService) {
        this.repository = repository;
        this.deadlineService = deadlineService;
    }

    public MaintenanceOverview overview(OverviewQuery query) {
        String from = requiredMoment("from", query.from());
        String to = requiredMoment("to", query.to());
        Long workshopId = MaintenanceIds.optional("workshopId", query.workshopId());
        Long equipmentId = MaintenanceIds.optional("equipmentId", query.equipmentId());

        LocalDateTime start = MaintenanceTimes.toLocal(MaintenanceTimes.parseMoment("from", from));
        LocalDateTime end = MaintenanceTimes.toLocal(MaintenanceTimes.parseMoment("to", to));
        if (!start.isBefore(end)) {
            throw new InvalidRequestException("from", "开始时间必须早于结束时间。");
        }

        long inspectionCount = repository.countInspections(start, end, workshopId, equipmentId);
        long alertEventCount = repository.countAlertEvents(start, end, workshopId, equipmentId);
        long pendingRepairCount = repository.countPendingRepairs(start, end, workshopId, equipmentId);
        long completedRepairCount = repository.countCompletedRepairs(start, end, workshopId, equipmentId);
        OverviewRepository.HandlingStats stats = repository.handlingStats(start, end, workshopId, equipmentId);
        List<EquipmentReportCount> byEquipment =
                repository.reportCountsByEquipment(start, end, workshopId, equipmentId);
        long deadlineReminderCount = deadlineService.countAttention(equipmentId, workshopId);

        return new MaintenanceOverview(
                from,
                to,
                OffsetDateTime.now(MaintenanceTimes.ZONE),
                inspectionCount,
                alertEventCount,
                pendingRepairCount,
                completedRepairCount,
                averageSeconds(stats),
                stats.sampleCount(),
                byEquipment,
                deadlineReminderCount,
                DEADLINE_NOTE,
                caliber());
    }

    /** 均值保留一位小数；样本量为 0 时返回 null，避免把"没有样本"呈现成"平均 0 秒"。 */
    private static String averageSeconds(OverviewRepository.HandlingStats stats) {
        if (stats.sampleCount() == 0 || stats.averageSeconds() == null) {
            return null;
        }
        return stats.averageSeconds()
                .setScale(1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static Map<String, String> caliber() {
        Map<String, String> caliber = new LinkedHashMap<>();
        caliber.put("inspectionCount", "按实际巡检时刻计数。");
        caliber.put("alertEventCount", "按事件触发时刻计数；同一持续过程只有一条事件，因此只计一次。");
        caliber.put("pendingRepairCount", "当前处于待审核、待补充、维修中、待验收的工单数，按首次正式提交时刻落入范围筛选。");
        caliber.put("completedRepairCount", "按验收通过时刻统计范围内的完成量。");
        caliber.put("averageHandlingSeconds", "同一批完成样本从首次正式提交到验收通过的秒数均值，保留一位小数；无样本时为 null。");
        caliber.put("handlingSampleCount", "参与均值计算的完成样本量，与 completedRepairCount 同口径。");
        caliber.put("equipmentReportCounts", "每台设备首次正式提交落入范围的工单数；补充后重提不重复计数。");
        caliber.put("deadlineReminderCount", DEADLINE_NOTE);
        return caliber;
    }

    private static String requiredMoment(String field, String value) {
        String text = MaintenanceIds.blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, "统计范围必须同时提供 from 与 to。");
        }
        MaintenanceTimes.parseMoment(field, text);
        return text;
    }
}
