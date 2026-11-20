package cn.copper.phase2.energy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * S2-E01—E02 的来源读取。
 *
 * <p>只读。一期 {@code dim_sensor}、{@code dim_energy_type}、{@code dws_energy_day}、
 * {@code dws_energy_hour} 归一期采集维护，本模块不写一行——源数据的刷新由采集进程负责，
 * 二期只保存核算所需的快照。{@code p2_output_record} 由生产记录模块写入，这里同样只读。</p>
 *
 * <p>表计筛选刻意写得保守：必须同时满足「能源种类已登记、范围类型为车间、有车间归属、
 * 不是别人的子表计」。缺任一条都不进结果，让上层报「缺计量」——按名称相似去猜一台设备表
 * 当车间总表，比报缺测更糟。</p>
 */
@Repository
public class EnergySourceRepository {
    private static final Logger LOG = LoggerFactory.getLogger(EnergySourceRepository.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 生产车间。先用字典里的类型匹配，再兜一层编码：
     * 一期字典的 {@code workshop_type} 是中文业务名，万一被改成别的写法（如「熔铸」），
     * 还有编码这一层，不至于整个统计页突然没有车间。
     */
    private static final String SELECT_WORKSHOPS = """
            SELECT workshop_id, workshop_code, workshop_name, workshop_type
              FROM dim_workshop
             WHERE enabled = 1
               AND (workshop_type IN ('熔炼', '拉丝') OR workshop_code IN ('WS-ROD', 'WS-WIRE'))
             ORDER BY workshop_id
            """;

    private static final String SELECT_WORKSHOP = """
            SELECT workshop_id, workshop_code, workshop_name, workshop_type
              FROM dim_workshop WHERE workshop_id = ?
            """;

    /**
     * 车间总表。{@code energy_scope_type = 'WORKSHOP'} 与 {@code parent_energy_sensor_id IS NULL}
     * 是「这是车间级总量、不是某台设备的子表」的判据；{@code energy_workshop_id} 必须指到本车间，
     * 防止把别的车间的表算进来。
     */
    private static final String SELECT_METERS = """
            SELECT s.sensor_id, s.sensor_code, s.sensor_name, t.energy_code
              FROM dim_sensor s
              JOIN dim_energy_type t ON t.energy_type_id = s.energy_type_id AND t.enabled = 1
             WHERE s.enabled = 1
               AND s.energy_workshop_id = ?
               AND s.energy_scope_type = 'WORKSHOP'
               AND s.parent_energy_sensor_id IS NULL
               AND t.energy_code IN (%s)
             ORDER BY s.sensor_id
            """;

    private static final String SELECT_DAYS = """
            SELECT sensor_id, statistic_date, consumption, quality_status, refresh_time
              FROM dws_energy_day
             WHERE sensor_id IN (%s) AND statistic_date BETWEEN ? AND ?
            """;

    private static final String SELECT_HOURS = """
            SELECT sensor_id, bucket_time, consumption, quality_status, calculation_method, refresh_time
              FROM dws_energy_hour
             WHERE sensor_id IN (%s) AND bucket_time >= ? AND bucket_time < ?
            """;

    /**
     * 只取两种会影响产量口径的记录：正数称重与明确零产出。
     * {@code EXECUTION} 只有起止时间、没有数量，混进来只会让分母变乱。
     */
    private static final String SELECT_OUTPUT_FACTS = """
            SELECT actual_date, record_type, qualified_quantity_ton
              FROM p2_output_record
             WHERE workshop_id = ? AND actual_date BETWEEN ? AND ?
               AND record_type IN ('QUALIFIED_OUTPUT', 'NO_OUTPUT')
            """;

    /**
     * 当日需要分摊的（车间，日期）组合。零产出日也要参与——那一天的能源不是不存在，
     * 只是不按比例分给订单，需要单独落一行说明。
     */
    private static final String SELECT_OUTPUT_DAYS = """
            SELECT DISTINCT workshop_id, actual_date
              FROM p2_output_record
             WHERE actual_date BETWEEN ? AND ?
               AND record_type IN ('QUALIFIED_OUTPUT', 'NO_OUTPUT')
             ORDER BY actual_date, workshop_id
            """;

    /**
     * 当日逐次称重。车间一按这个明细把能源归集到各次产出入库，
     * 所以必须带 {@code output_id}，不能用按日汇总的数量代替。
     */
    private static final String SELECT_OUTPUT_DETAIL = """
            SELECT output_id, workshop_id, actual_date, qualified_quantity_ton
              FROM p2_output_record
             WHERE workshop_id = ? AND actual_date = ?
               AND record_type = 'QUALIFIED_OUTPUT'
             ORDER BY output_id
            """;

    /**
     * 当日录全确认（S2-R19 写入的日状态）。
     *
     * <p>{@code confirmed_source_revision} 是确认那一刻的来源修订号。补录或更正会把
     * {@code source_revision} 推高，两者一旦不等，这条确认就已失效——不能再拿它当
     * 「当日只生产了这些」的依据。所以判定必须同时看两个字段，只看
     * {@code records_complete} 会把失效的确认当成有效。</p>
     */
    private static final String SELECT_DAY_STATUS = """
            SELECT workshop_id, actual_date, source_revision, confirmed_source_revision,
                   records_complete, confirmed_by, confirmed_at
              FROM p2_production_day_status
             WHERE workshop_id = ? AND actual_date BETWEEN ? AND ?
            """;

    /**
     * 当日订单归属量。关联路径是「成品分配 → 称重记录」，因为归属只记在分配上，
     * 称重记录本身不带订单。{@code output_id IS NULL} 的分配行无法定位日期，不纳入。
     */
    private static final String SELECT_ORDER_ATTRIBUTIONS = """
            SELECT fa.order_id, o.order_no, SUM(fa.allocated_quantity_ton) AS attributed_quantity_ton
              FROM p2_finished_allocation fa
              JOIN p2_output_record r ON r.output_id = fa.output_id
              JOIN p2_customer_order o ON o.order_id = fa.order_id
             WHERE r.workshop_id = ? AND r.actual_date = ?
             GROUP BY fa.order_id, o.order_no
             ORDER BY fa.order_id
            """;

    private final JdbcTemplate jdbc;

    public EnergySourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record WorkshopRow(long workshopId, String workshopName, String workshopType) {}

    public record MeterRow(long sensorId, String sensorCode, String sensorName, String energyCode) {}

    public record DayReading(long sensorId, LocalDate statisticDate, BigDecimal consumption,
                             String qualityStatus, OffsetDateTime refreshTime) {}

    public record HourReading(long sensorId, OffsetDateTime bucketTime, BigDecimal consumption,
                              String qualityStatus, String calculationMethod, OffsetDateTime refreshTime) {}

    /**
     * @param recordType QUALIFIED_OUTPUT 或 NO_OUTPUT
     * @param quantityTon 正数称重为实际吨数，零产出为 0
     */
    public record OutputFact(LocalDate actualDate, String recordType, BigDecimal quantityTon) {}

    public List<WorkshopRow> productionWorkshops() {
        return jdbc.query(SELECT_WORKSHOPS, (rs, row) -> new WorkshopRow(
                rs.getLong("workshop_id"), rs.getString("workshop_name"), rs.getString("workshop_type")));
    }

    public Optional<WorkshopRow> workshop(long workshopId) {
        return jdbc.query(SELECT_WORKSHOP,
                (rs, row) -> new WorkshopRow(rs.getLong("workshop_id"), rs.getString("workshop_name"),
                        rs.getString("workshop_type")),
                workshopId).stream().findFirst();
    }

    /**
     * 一期编码映射：煤气在部分表里叫 {@code COAL_GAS}，二期统一为 {@code GAS}。
     * 只在这里映射，上层一律使用二期编码，避免同一个能源在系统里出现两套名字。
     */
    public List<MeterRow> meters(long workshopId, String energyType) {
        List<String> codes = switch (energyType) {
            case "GAS" -> List.of("GAS", "COAL_GAS");
            default -> List.of(energyType);
        };
        String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(workshopId);
        args.addAll(codes);
        return jdbc.query(SELECT_METERS.formatted(placeholders), (rs, row) -> new MeterRow(
                rs.getLong("sensor_id"), rs.getString("sensor_code"), rs.getString("sensor_name"),
                rs.getString("energy_code")), args.toArray());
    }

    public List<DayReading> dayReadings(List<Long> sensorIds, LocalDate from, LocalDate to) {
        if (sensorIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(sensorIds);
        args.add(from);
        args.add(to);
        return jdbc.query(SELECT_DAYS.formatted(placeholders(sensorIds.size())), (rs, row) -> new DayReading(
                        rs.getLong("sensor_id"),
                        rs.getDate("statistic_date").toLocalDate(),
                        rs.getBigDecimal("consumption"),
                        rs.getString("quality_status"),
                        toOffset(rs.getTimestamp("refresh_time"))),
                args.toArray());
    }

    /** 区间取 [from 00:00, to+1 00:00)，避免把 to 之后的小时误算进来。 */
    public List<HourReading> hourReadings(List<Long> sensorIds, LocalDate from, LocalDate to) {
        if (sensorIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(sensorIds);
        args.add(from.atStartOfDay(ZONE).toOffsetDateTime());
        args.add(to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        return jdbc.query(SELECT_HOURS.formatted(placeholders(sensorIds.size())), (rs, row) -> new HourReading(
                        rs.getLong("sensor_id"),
                        toOffset(rs.getTimestamp("bucket_time")),
                        rs.getBigDecimal("consumption"),
                        rs.getString("quality_status"),
                        rs.getString("calculation_method"),
                        toOffset(rs.getTimestamp("refresh_time"))),
                args.toArray());
    }

    public List<OutputFact> outputFacts(long workshopId, LocalDate from, LocalDate to) {
        return jdbc.query(SELECT_OUTPUT_FACTS, (rs, row) -> new OutputFact(
                        rs.getDate("actual_date").toLocalDate(),
                        rs.getString("record_type"),
                        rs.getBigDecimal("qualified_quantity_ton")),
                workshopId, from, to);
    }

    /**
     * 当日该车间的订单归属量。
     *
     * <p>{@code p2_output_record} 本身没有订单字段——归属只写在成品分配上，所以这里的
     * 口径是「按output_id 关联到当天的称重记录，再按订单汇总分配量」。没有这一层，
     * 任何按订单分摊的权重都只能靠猜。{@code output_id} 为空的分配行无从判断属于哪一天，
     * 本查询不纳入，避免把它们挪到某一天上凑数。</p>
     */
    public List<OrderAttribution> orderAttributions(long workshopId, LocalDate actualDate) {
        return jdbc.query(SELECT_ORDER_ATTRIBUTIONS, (rs, row) -> new OrderAttribution(
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getBigDecimal("attributed_quantity_ton")),
                workshopId, actualDate);
    }

    /**
     * @param attributedQuantityTon 该订单当日归属的合格量合计（吨）
     */
    public record OrderAttribution(long orderId, String orderNo, BigDecimal attributedQuantityTon) {}

    /**
     * 当日录全确认状态。
     *
     * <p>{@link #confirmed()} 要求 {@code confirmed_source_revision} 等于当前
     * {@code source_revision}：确认之后只要又补录或更正过，来源修订号就会被推高，
     * 这条确认自动失效。{@link #invalidated()} 正是这种「曾经确认过、现在不作数」的状态，
     * 页面需要区分它和「从没确认过」——前者是补录导致的，后者是还没录完。</p>
     */
    public record DayStatusRow(long workshopId, LocalDate actualDate, long sourceRevision,
                               Long confirmedSourceRevision, boolean recordsComplete,
                               String confirmedBy, OffsetDateTime confirmedAt) {

        public boolean confirmed() {
            return recordsComplete && confirmedSourceRevision != null
                    && confirmedSourceRevision == sourceRevision;
        }

        public boolean invalidated() {
            return confirmedSourceRevision != null && confirmedSourceRevision != sourceRevision;
        }
    }

    /**
     * 按日读取录全确认。区间内没有状态行的日期不会出现在结果里——「没有这行」本身就是
     * 「没确认过」，调用方据此报未确认，而不是当成确认。
     *
     * <p>日状态表由 V18 引入。库还没升到 V18 时这里返回空表并记一条告警：
     * 没有这张表等于没有任何确认，退化成「未确认」是正确语义，不是把故障藏起来。</p>
     */
    public Map<LocalDate, DayStatusRow> dayStatuses(long workshopId, LocalDate from, LocalDate to) {
        try {
            List<DayStatusRow> rows = jdbc.query(SELECT_DAY_STATUS, (rs, row) -> new DayStatusRow(
                            rs.getLong("workshop_id"),
                            rs.getDate("actual_date").toLocalDate(),
                            rs.getLong("source_revision"),
                            (Long) rs.getObject("confirmed_source_revision", Long.class),
                            rs.getBoolean("records_complete"),
                            rs.getString("confirmed_by"),
                            toOffset(rs.getTimestamp("confirmed_at"))),
                    workshopId, from, to);
            Map<LocalDate, DayStatusRow> index = new HashMap<>();
            for (DayStatusRow row : rows) {
                index.put(row.actualDate(), row);
            }
            return index;
        } catch (DataAccessException unavailable) {
            LOG.warn("读取当日录全确认失败，按未确认处理（车间 {}，{} 至 {}）：{}",
                    workshopId, from, to, unavailable.getMessage());
            return Map.of();
        }
    }

    public Optional<DayStatusRow> dayStatus(long workshopId, LocalDate date) {
        return Optional.ofNullable(dayStatuses(workshopId, date, date).get(date));
    }

    /** 区间内所有有产出（正称重或明确零产出）的车间日，分摊按这个清单逐日落库。 */
    public List<OutputDay> outputDays(LocalDate from, LocalDate to) {
        return jdbc.query(SELECT_OUTPUT_DAYS, (rs, row) -> new OutputDay(
                        rs.getLong("workshop_id"),
                        rs.getDate("actual_date").toLocalDate()),
                from, to);
    }

    /** 当日逐次合格称重，带产出 ID 与重量。 */
    public List<OutputRow> qualifiedOutputs(long workshopId, LocalDate date) {
        return jdbc.query(SELECT_OUTPUT_DETAIL, (rs, row) -> new OutputRow(
                        rs.getLong("output_id"),
                        rs.getLong("workshop_id"),
                        rs.getDate("actual_date").toLocalDate(),
                        rs.getBigDecimal("qualified_quantity_ton")),
                workshopId, date);
    }

    public record OutputDay(long workshopId, LocalDate actualDate) {}

    public record OutputRow(long outputId, long workshopId, LocalDate actualDate, BigDecimal quantityTon) {}

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static OffsetDateTime toOffset(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atZone(ZONE).toOffsetDateTime();
    }
}
