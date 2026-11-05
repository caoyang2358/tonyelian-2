package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.OverviewModels.EquipmentReportCount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 概览统计的 SQL 访问层。全部只读，不含任何写入。
 *
 * <p>四张事实表都按"时间窗 + 车间 + 设备"限定范围，因此把范围条件抽成一个方法，
 * 保证巡检、事件、工单三类计数用的是同一个窗口，不会各算各的。
 */
@Repository
public class OverviewRepository {
    /** 待审核、待补充、维修中、待验收：文档定义的"待办"四个状态。 */
    private static final List<String> PENDING_STATUSES = List.of(
            RepairOrderWorkflow.REVIEW_PENDING,
            RepairOrderWorkflow.RETURNED_FOR_INFO,
            RepairOrderWorkflow.IN_REPAIR,
            RepairOrderWorkflow.ACCEPTANCE_PENDING);

    private final JdbcTemplate jdbc;

    public OverviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countInspections(LocalDateTime from, LocalDateTime to, Long workshopId, Long equipmentId) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM p2_inspection t"
                + " JOIN dim_equipment e ON e.equipment_id = t.equipment_id"
                + scope("t.inspected_at", from, to, workshopId, equipmentId, args);
        return count(sql, args);
    }

    /** 同一持续过程只有一条事件行，因此按行计数天然满足"同一持续异常计一次"。 */
    public long countAlertEvents(LocalDateTime from, LocalDateTime to, Long workshopId, Long equipmentId) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM p2_alert_event t"
                + " JOIN dim_equipment e ON e.equipment_id = t.equipment_id"
                + scope("t.triggered_at", from, to, workshopId, equipmentId, args);
        return count(sql, args);
    }

    /** 待办：当前仍处于四个中间状态，且首次正式提交落在范围内。 */
    public long countPendingRepairs(LocalDateTime from, LocalDateTime to, Long workshopId, Long equipmentId) {
        List<Object> args = new ArrayList<>();
        // 占位符按 SQL 出现顺序追加：先范围，再状态。
        String scope = scope("t.first_submitted_at", from, to, workshopId, equipmentId, args);
        String placeholders = String.join(",", PENDING_STATUSES.stream().map(s -> "?").toList());
        args.addAll(PENDING_STATUSES);
        String sql = "SELECT COUNT(*) FROM p2_repair_order t"
                + " JOIN dim_equipment e ON e.equipment_id = t.equipment_id"
                + scope
                + " AND t.status IN (" + placeholders + ")";
        return count(sql, args);
    }

    /** 完成量：按验收通过时刻统计。 */
    public long countCompletedRepairs(LocalDateTime from, LocalDateTime to, Long workshopId, Long equipmentId) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM p2_repair_order t"
                + " JOIN dim_equipment e ON e.equipment_id = t.equipment_id"
                + scope("t.completed_at", from, to, workshopId, equipmentId, args)
                + " AND t.completed_at IS NOT NULL";
        return count(sql, args);
    }

    /**
     * 处理时长：首次正式提交到验收通过的秒数均值。
     * 样本与完成量同一口径，没有样本时返回样本量 0、均值为 null（不返回 0 冒充分均值）。
     */
    public HandlingStats handlingStats(LocalDateTime from, LocalDateTime to, Long workshopId, Long equipmentId) {
        List<Object> args = new ArrayList<>();
        String sql = """
                SELECT AVG(TIMESTAMPDIFF(SECOND, t.first_submitted_at, t.completed_at)) AS average_seconds,
                       COUNT(*) AS sample_count
                FROM p2_repair_order t
                JOIN dim_equipment e ON e.equipment_id = t.equipment_id
                """ + scope("t.completed_at", from, to, workshopId, equipmentId, args)
                + " AND t.completed_at IS NOT NULL AND t.first_submitted_at IS NOT NULL";
        return jdbc.queryForObject(sql, (rs, row) -> new HandlingStats(
                rs.getBigDecimal("average_seconds"), rs.getLong("sample_count")), args.toArray());
    }

    /** 按设备分组的报修次数；完成数只统计完成时刻也落在范围内的工单。 */
    public List<EquipmentReportCount> reportCountsByEquipment(
            LocalDateTime from, LocalDateTime to, Long workshopId, Long equipmentId) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.valueOf(from));
        args.add(Timestamp.valueOf(to));
        String scope = scope("t.first_submitted_at", from, to, workshopId, equipmentId, args);
        String sql = """
                SELECT t.equipment_id, e.equipment_name,
                       COUNT(*) AS report_count,
                       SUM(CASE WHEN t.completed_at IS NOT NULL
                                 AND t.completed_at >= ? AND t.completed_at < ? THEN 1 ELSE 0 END) AS completed_count
                FROM p2_repair_order t
                JOIN dim_equipment e ON e.equipment_id = t.equipment_id
                """ + scope + """
                 GROUP BY t.equipment_id, e.equipment_name
                 ORDER BY report_count DESC, t.equipment_id
                """;
        return jdbc.query(sql, (rs, row) -> new EquipmentReportCount(
                rs.getString("equipment_id"),
                rs.getString("equipment_name"),
                rs.getLong("report_count"),
                rs.getLong("completed_count")), args.toArray());
    }

    /** 均值与样本量。 */
    public record HandlingStats(BigDecimal averageSeconds, long sampleCount) {}

    private long count(String sql, List<Object> args) {
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /**
     * 范围条件：[from, to) 闭开区间（按自然日选择时由调用方换算成次日本零时），
     * 车间与设备按共享主数据表过滤，保证三类事实用同一套归属口径。
     */
    private static String scope(String timeColumn, LocalDateTime from, LocalDateTime to,
                                Long workshopId, Long equipmentId, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE ")
                .append(timeColumn).append(" >= ? AND ").append(timeColumn).append(" < ?");
        args.add(Timestamp.valueOf(from));
        args.add(Timestamp.valueOf(to));
        if (workshopId != null) {
            sql.append(" AND e.workshop_id = ?");
            args.add(workshopId);
        }
        if (equipmentId != null) {
            sql.append(" AND e.equipment_id = ?");
            args.add(equipmentId);
        }
        return sql.toString();
    }
}
