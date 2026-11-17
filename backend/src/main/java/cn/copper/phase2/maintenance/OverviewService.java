package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.deadline.DeadlineModels;
import cn.copper.phase2.deadline.DeadlineModels.DeadlineBreakdown;
import cn.copper.phase2.deadline.DeadlineModels.DeadlineItem;
import cn.copper.phase2.deadline.DeadlineService;
import cn.copper.phase2.maintenance.OverviewModels.DeadlineTypeCounts;
import cn.copper.phase2.maintenance.OverviewModels.DurationBuckets;
import cn.copper.phase2.maintenance.OverviewModels.EquipmentReportCount;
import cn.copper.phase2.maintenance.OverviewModels.MaintenanceOverview;
import cn.copper.phase2.maintenance.OverviewModels.OverviewQuery;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 概览页签业务层（S1-M01）。只读统计，不写任何表。
 *
 * <p>口径来自《运维管理页面接口文档》§5。文档要求服务端在响应中说明口径，
 * 因此每个数字都附一句说明；未实现的统计返回 null 并注明原因，不用 0 冒替。
 */
@Service
public class OverviewService {
    /**
     * 期限提醒的统计口径：算的是"查询这一刻"的状态，与上下方的时间范围无关。
     *
     * <p>提醒对象是<b>设备档案</b>（质保、维保、预计寿命），不是维修工单的时限；
     * 工单是否超时由工单列表自行判断，不复用这张卡，避免两类风险混成一个数字。</p>
     *
     * <p>计数单位是<b>条</b>而不是<b>台</b>：一台设备最多同时有质保、维保（可多份合同）、
     * 预计寿命三类期限，因此 8 台设备的档案全部缺失时是 24 条，不是 24 台。页面上
     * 台数另行给出，避免把"条"读成"台"。</p>
     */
    static final String DEADLINE_NOTE = "期限提醒看设备档案的到期情况：质保到期、维保到期、预计寿命三类，"
            + "按每台设备的每一类各算一条。数据显示的是查询这一刻的状态，与上方的时间范围无关。"
            + "不含维修工单的处理时限——工单超时在时长卡下方单独统计。";

    /** 采集"待补资料"设备台数时的每页条数，取接口允许的上限。 */
    private static final int MISSING_SCAN_PAGE_SIZE = 100;
    /** 兜底页数：避免数据异常时无限翻页。按每页 100 条算，足以覆盖任何一个车间的设备量。 */
    private static final int MISSING_SCAN_MAX_PAGES = 50;

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
        DurationBuckets durationBuckets = repository.durationBuckets(start, end, workshopId, equipmentId);
        List<EquipmentReportCount> byEquipment =
                repository.reportCountsByEquipment(start, end, workshopId, equipmentId);
        DeadlineBreakdown deadline = deadlineService.breakdown(equipmentId, workshopId);
        // "待处理"包含待补资料：档案没填本身就是需要处理的事，只报临期+超期会出现
        // "0 条需处理"和"待补资料 24"并排的矛盾，容易被读成"没有问题"。
        long deadlineReminderCount = deadline.dueSoon() + deadline.expired() + deadline.missing();
        long missingEquipmentCount = deadline.missing() > 0
                ? missingEquipmentCount(equipmentId, workshopId)
                : 0;
        DeadlineTypeCounts deadlineTypeCounts = deadline.dueSoon() + deadline.expired() > 0
                ? deadlineTypeCounts(equipmentId, workshopId)
                : new DeadlineTypeCounts(0, 0, 0);
        String deadlineNote = deadlineNote(deadline.missing(), missingEquipmentCount);
        // 工单时效另算：在办工单从提交到现在超过 2 小时仍未结束。与上面的时间范围无关。
        long overdueRepairCount = repository.countOverdueRepairs(
                LocalDateTime.now(MaintenanceTimes.ZONE), workshopId, equipmentId);

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
                durationBuckets,
                byEquipment,
                deadlineReminderCount,
                deadline,
                deadlineTypeCounts,
                missingEquipmentCount,
                overdueRepairCount,
                deadlineNote,
                caliber(deadlineNote));
    }

    /**
     * 待补资料的设备台数：期限接口一行是"设备 × 期限类型"，直接拿条数会高估设备数。
     * 这里按设备去重后计数，用于页面上同时给出"24 条 / 8 台"。
     */
    private long missingEquipmentCount(Long equipmentId, Long workshopId) {
        Set<String> ids = new HashSet<>();
        forEachDeadline(equipmentId, workshopId, DeadlineModels.STATE_MISSING_DATA, item -> {
            if (item.equipmentId() != null) {
                ids.add(item.equipmentId());
            }
        });
        return ids.size();
    }

    /**
     * 临期与超期按期限来源拆分：质保、维保、预计寿命。
     *
     * <p>"维保期限到期"与"档案不完整"是两种不同的业务场景，混在一个数字里
     * 无法回答"到底是哪一类到期了"。待补资料不参与拆分。</p>
     */
    private DeadlineTypeCounts deadlineTypeCounts(Long equipmentId, Long workshopId) {
        long[] counts = new long[3];
        Consumer<DeadlineItem> sink = item -> {
            switch (item.reminderType() == null ? "" : item.reminderType()) {
                case DeadlineModels.TYPE_WARRANTY -> counts[0]++;
                case DeadlineModels.TYPE_MAINTENANCE -> counts[1]++;
                case DeadlineModels.TYPE_EXPECTED_LIFE -> counts[2]++;
                default -> {
                }
            }
        };
        forEachDeadline(equipmentId, workshopId, DeadlineModels.STATE_DUE_SOON, sink);
        forEachDeadline(equipmentId, workshopId, DeadlineModels.STATE_EXPIRED, sink);
        return new DeadlineTypeCounts(counts[0], counts[1], counts[2]);
    }

    /** 按状态分页遍历期限条目，直到取完；数据异常时由最大页数兜底。 */
    private void forEachDeadline(Long equipmentId, Long workshopId, String state,
                                 Consumer<DeadlineItem> sink) {
        String equipment = equipmentId == null ? null : equipmentId.toString();
        String workshop = workshopId == null ? null : workshopId.toString();
        for (int page = 1; page <= MISSING_SCAN_MAX_PAGES; page++) {
            PageResult<DeadlineItem> result = deadlineService.search(
                    equipment, workshop, null, state, page, MISSING_SCAN_PAGE_SIZE);
            if (result == null || result.items() == null || result.items().isEmpty()) {
                return;
            }
            result.items().forEach(sink);
            if (result.items().size() < MISSING_SCAN_PAGE_SIZE
                    || (long) page * MISSING_SCAN_PAGE_SIZE >= result.total()) {
                return;
            }
        }
    }

    /**
     * 期限提醒的说明文字：基础口径之外，把「算不出到期日」的条数与台数一并讲清楚。
     *
     * <p>缺投用日期或质保年限的设备无法判断是否临期，若不提示，页面上会出现
     * 「0 条提醒」而实际有一批设备从未被评估过，容易被当成「没有问题」。</p>
     */
    static String deadlineNote(long missingCount, long missingEquipmentCount) {
        if (missingCount <= 0) {
            return DEADLINE_NOTE;
        }
        String devices = missingEquipmentCount > 0 ? missingEquipmentCount + " 台设备共 " : "";
        return DEADLINE_NOTE + "当前 " + devices + missingCount
                + " 条属于「待补资料」：这些设备缺投用日期、质保年限或维保合同，算不出到期日，"
                + "因此单列而不是计入临期/超期；补齐后自动纳入到期评估，数字随之下降。";
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

    private static Map<String, String> caliber(String deadlineNote) {
        Map<String, String> caliber = new LinkedHashMap<>();
        caliber.put("inspectionCount", "按实际巡检时刻计数。");
        caliber.put("alertEventCount", "按事件触发时刻计数；同一持续过程只有一条事件，因此只计一次。");
        caliber.put("pendingRepairCount", "当前处于待审核（含退回补充）、待维修、待验收的工单数，按首次提交时刻落入范围筛选。");
        caliber.put("completedRepairCount", "按验收通过时刻统计范围内的完成量。");
        caliber.put("averageHandlingSeconds", "维修工单平均处理时长：同一批已完成工单从首次提交（建单即提交）到验收通过的全流程秒数均值，保留一位小数；无样本时为 null。");
        caliber.put("durationBuckets", "同一批完成样本按处理时长分档：30 分钟以内、30 分钟至 2 小时、超过 2 小时；三档之和等于处理时长样本量。");
        caliber.put("handlingSampleCount", "参与均值计算的完成样本量，与 completedRepairCount 同口径。");
        caliber.put("equipmentReportCounts", "每台设备首次正式提交落入范围的工单数；补充后重提不重复计数。");
        caliber.put("deadlineReminderCount", deadlineNote);
        caliber.put("deadlineTypeCounts", "临期与超期按期限来源拆分：质保、维保、预计寿命；待补资料不计入，因为它没有到期日。");
        caliber.put("deadlineMissingEquipmentCount", "「待补资料」涉及的设备台数，按设备去重。条数是设备数乘以期限类型数，两者不相等。");
        caliber.put("overdueRepairCount", "工单时效，与期限提醒无关：当前处于待审核（含退回补充）、待维修、待验收，且从首次提交到现在已超过 2 小时仍未结束的工单数。按查询时刻计算，不受上方时间范围影响。");
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
