package cn.copper.phase2.energy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static OffsetDateTime toOffset(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atZone(ZONE).toOffsetDateTime();
    }
}
