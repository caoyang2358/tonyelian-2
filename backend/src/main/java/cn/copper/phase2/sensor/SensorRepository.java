package cn.copper.phase2.sensor;

import cn.copper.phase2.sensor.SensorModels.HistoryRecord;
import cn.copper.phase2.sensor.SensorModels.LatestReading;
import cn.copper.phase2.sensor.SensorModels.ParameterDetail;
import cn.copper.phase2.sensor.SensorModels.ParameterSummary;
import cn.copper.phase2.sensor.SensorModels.ParameterWriteRequest;
import cn.copper.phase2.sensor.SensorModels.RuleSnapshot;
import cn.copper.phase2.sensor.SensorModels.RuleVersionView;
import cn.copper.phase2.sensor.SensorModels.SensorDetail;
import cn.copper.phase2.sensor.SensorModels.SensorSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S1-P01/P02/P06/P09/P10/P11 的读取，以及 P03/P04 的写入。
 *
 * <p>读的一侧不碰任何写操作：测点与参数身份属于一期维表，读数属于采集侧，二期只取不改。
 * 写的一侧只写两类列：{@code dim_sensor} 的约定主数据（编码、名称、归属设备、类型、启停用）
 * 与 {@code p2_sensor_profile} 的扩展资料（位置、用途、版本）。采集来源 {@code data_source}
 * 与参数定义都不在这里写——前者由现场对接产生，后者走 P07 的参数写入服务。</p>
 *
 * <p>监测状态在 SQL 里现算，靠一个"每个测点最后刷新时间"的派生表与本机时间比较。
 * 没有冗余状态列，也就没有"停了采集但状态还写着在线"这种失真。</p>
 */
@Repository
public class SensorRepository {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 列表与详情共用同一份取数口径，避免两个接口对同一测点给出不同状态。 */
    static final String BASE_SQL = """
            SELECT s.sensor_id, s.sensor_code, s.sensor_name,
                   s.equipment_id, s.sensor_type, s.data_source, s.enabled,
                   e.equipment_code, e.equipment_name, e.workshop_id, w.workshop_name,
                   p.location, p.purpose, p.version AS profile_version,
                   (SELECT COUNT(*) FROM dim_sensor_parameter dp
                     WHERE dp.sensor_id = s.sensor_id) AS parameter_count,
                   m.last_refresh,
                   CASE WHEN s.enabled = 0 THEN 'DISABLED'
                        WHEN m.last_refresh IS NULL THEN 'UNKNOWN'
                        WHEN m.last_refresh >= ? THEN 'ONLINE'
                        ELSE 'STALE' END AS monitoring_status
              FROM dim_sensor s
              JOIN dim_equipment e ON e.equipment_id = s.equipment_id
              JOIN dim_workshop w ON w.workshop_id = e.workshop_id
              LEFT JOIN p2_sensor_profile p ON p.sensor_id = s.sensor_id
              LEFT JOIN (SELECT sensor_id, MAX(refresh_time) AS last_refresh
                           FROM ads_parameter_monitor
                          GROUP BY sensor_id) m ON m.sensor_id = s.sensor_id
            """;

    static final String PARAMETER_SQL = """
            SELECT dp.parameter_id, dp.parameter_code, dp.parameter_name, dp.data_type,
                   dp.unit_code, dp.decimal_places, dp.sampling_frequency_seconds,
                   dp.process_id, dp.enabled,
                   rv.rule_version_id, rv.enabled AS rule_enabled
              FROM dim_sensor_parameter dp
              LEFT JOIN (SELECT parameter_id, MAX(rule_version_id) AS rule_version_id
                           FROM p2_alert_rule_version
                          GROUP BY parameter_id) rm ON rm.parameter_id = dp.parameter_id
              LEFT JOIN p2_alert_rule_version rv ON rv.rule_version_id = rm.rule_version_id
             WHERE dp.sensor_id = ?
             ORDER BY dp.display_order, dp.parameter_code, dp.parameter_id
            """;

    private final JdbcTemplate jdbc;

    public SensorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 测点

    /** 测点列表筛选；四个条件均可为空，同时给出时取交集。 */
    public record SensorQuery(Long equipmentId, String keyword, Boolean enabled,
                              String monitoringStatus, int page, int pageSize) {
    }

    public List<SensorSummary> search(SensorQuery query, OffsetDateTime staleBefore) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(staleBefore.toInstant()));   // 派生表里的阈值，必须排最前
        String sql = "SELECT * FROM (" + BASE_SQL + ") t" + where(query, args)
                + " ORDER BY t.sensor_code, t.sensor_id LIMIT ? OFFSET ?";
        args.add(query.pageSize());
        args.add((long) (query.page() - 1) * query.pageSize());
        return jdbc.query(sql, SENSOR_MAPPER, args.toArray());
    }

    public long count(SensorQuery query, OffsetDateTime staleBefore) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(staleBefore.toInstant()));
        String sql = "SELECT COUNT(*) FROM (" + BASE_SQL + ") t" + where(query, args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    static String where(SensorQuery query, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (query.equipmentId() != null) {
            sql.append(" AND t.equipment_id = ?");
            args.add(query.equipmentId());
        }
        if (query.keyword() != null) {
            // 关键词同时匹配测点编码、测点名称、设备编码、设备名称：现场报修常说设备名，
            // 而现场点检常说测点编码，两边都得能搜到。
            sql.append(" AND (t.sensor_code LIKE ? OR t.sensor_name LIKE ?"
                    + " OR t.equipment_code LIKE ? OR t.equipment_name LIKE ?)");
            for (int i = 0; i < 4; i++) {
                args.add("%" + query.keyword() + "%");
            }
        }
        if (query.enabled() != null) {
            sql.append(" AND t.enabled = ?");
            args.add(query.enabled());
        }
        if (query.monitoringStatus() != null) {
            sql.append(" AND t.monitoring_status = ?");
            args.add(query.monitoringStatus());
        }
        return sql.toString();
    }

    static final RowMapper<SensorSummary> SENSOR_MAPPER = (rs, rowNum) -> {
        long parameterCount = rs.getLong("parameter_count");
        String dataSource = rs.getString("data_source");
        boolean enabled = rs.getBoolean("enabled");
        return new SensorSummary(
                String.valueOf(rs.getLong("sensor_id")),
                rs.getString("sensor_code"),
                rs.getString("sensor_name"),
                String.valueOf(rs.getLong("equipment_id")),
                rs.getString("equipment_code"),
                rs.getString("equipment_name"),
                String.valueOf(rs.getLong("workshop_id")),
                rs.getString("workshop_name"),
                rs.getString("location"),
                rs.getString("sensor_type"),
                dataSource,
                enabled,
                parameterCount,
                rs.getString("monitoring_status"),
                configurationState(parameterCount, dataSource));
    };

    /**
     * 单个测点详情。
     *
     * <p>{@code has_history} 用相关子查询只对一行求值：原始表按 {@code (sensor_code,
     * parameter_code, collect_time)} 建了索引，这一下是索引命中，不会扫表。放在列表里则会变成
     * 每行一次 EXISTS，所以只挂在详情语句上。</p>
     *
     * <p>用 {@code sensor_code} 而不是 {@code sensor_id} 判断：原始表只存编码，
     * 这是两张表之间唯一可靠的连接键。</p>
     */
    public Optional<SensorDetail> detail(long sensorId, OffsetDateTime staleBefore) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(staleBefore.toInstant()));
        args.add(sensorId);
        List<SensorDetail> rows = jdbc.query(
                "SELECT t.*, EXISTS(SELECT 1 FROM ods_sensor_data o"
                        + " WHERE o.sensor_code = t.sensor_code AND o.is_deleted = 0) AS has_history"
                        + " FROM (" + BASE_SQL + ") t WHERE t.sensor_id = ?",
                (rs, rowNum) -> {
                    long parameterCount = rs.getLong("parameter_count");
                    String dataSource = rs.getString("data_source");
                    return new SensorDetail(
                            String.valueOf(rs.getLong("sensor_id")),
                            rs.getString("sensor_code"),
                            rs.getString("sensor_name"),
                            String.valueOf(rs.getLong("equipment_id")),
                            rs.getString("equipment_code"),
                            rs.getString("equipment_name"),
                            String.valueOf(rs.getLong("workshop_id")),
                            rs.getString("workshop_name"),
                            rs.getString("sensor_type"),
                            dataSource,
                            rs.getString("location"),
                            rs.getString("purpose"),
                            rs.getBoolean("enabled"),
                            parameterCount,
                            rs.getBoolean("has_history"),
                            rs.getString("monitoring_status"),
                            configurationState(parameterCount, dataSource),
                            rs.getObject("profile_version") == null ? 0L : rs.getLong("profile_version"),
                            List.of());
                },
                args.toArray());
        return rows.stream().findFirst();
    }

    /**
     * 只有"有参数"且"有采集来源"才算配置完成。
     *
     * <p>不按"有没有规则"判断：规则缺失是参数级待配置，测点本身已经通了，
     * 两者混在一起会让现场分不清该补哪一步。</p>
     */
    static String configurationState(long parameterCount, String dataSource) {
        return parameterCount > 0 && dataSource != null && !dataSource.isBlank()
                ? SensorModels.CONFIGURATION_READY
                : SensorModels.CONFIGURATION_PENDING;
    }

    // ---------------------------------------------------------------- 参数

    public List<ParameterSummary> parameters(long sensorId, int page, int pageSize) {
        return jdbc.query(PARAMETER_SQL + " LIMIT ? OFFSET ?", PARAMETER_MAPPER,
                sensorId, pageSize, (long) (page - 1) * pageSize);
    }

    public long countParameters(long sensorId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dim_sensor_parameter WHERE sensor_id = ?", Long.class, sensorId);
        return total == null ? 0L : total;
    }

    static final RowMapper<ParameterSummary> PARAMETER_MAPPER = (rs, rowNum) -> {
        Object ruleVersionId = rs.getObject("rule_version_id");
        return new ParameterSummary(
                String.valueOf(rs.getLong("parameter_id")),
                rs.getString("parameter_code"),
                rs.getString("parameter_name"),
                rs.getString("data_type"),
                rs.getString("unit_code"),
                rs.getInt("decimal_places"),
                rs.getObject("sampling_frequency_seconds") == null
                        ? null : rs.getInt("sampling_frequency_seconds"),
                rs.getObject("process_id") == null ? null : String.valueOf(rs.getLong("process_id")),
                rs.getBoolean("enabled"),
                ruleVersionId == null ? null : String.valueOf(((Number) ruleVersionId).longValue()),
                ruleVersionId != null && rs.getBoolean("rule_enabled"));
    };

    // ---------------------------------------------------------------- 读数

    /**
     * 参数身份与采集映射。
     *
     * <p>历史查询要先拿到 {@code sensorCode + parameterCode} 才能查原始表，
     * 单独取一次比在 SQL 里连三张表更清楚，也便于把"参数不存在"与"没有数据"区分开。</p>
     */
    public record ParameterContext(long parameterId, long sensorId, String sensorCode,
                                   String parameterCode, String parameterName, String unitCode,
                                   String dataType, boolean parameterEnabled,
                                   boolean sensorEnabled, String dataSource) {
    }

    public Optional<ParameterContext> parameterContext(long parameterId) {
        List<ParameterContext> rows = jdbc.query("""
                SELECT dp.parameter_id, dp.sensor_id, dp.parameter_code, dp.parameter_name,
                       dp.unit_code, dp.data_type, dp.enabled AS parameter_enabled,
                       s.sensor_code, s.data_source, s.enabled AS sensor_enabled
                  FROM dim_sensor_parameter dp
                  JOIN dim_sensor s ON s.sensor_id = dp.sensor_id
                 WHERE dp.parameter_id = ?
                """, (rs, rowNum) -> new ParameterContext(
                rs.getLong("parameter_id"),
                rs.getLong("sensor_id"),
                rs.getString("sensor_code"),
                rs.getString("parameter_code"),
                rs.getString("parameter_name"),
                rs.getString("unit_code"),
                rs.getString("data_type"),
                rs.getBoolean("parameter_enabled"),
                rs.getBoolean("sensor_enabled"),
                rs.getString("data_source")),
                parameterId);
        return rows.stream().findFirst();
    }

    /** 最新值快照的一行；没有采集记录时返回空。 */
    public record MonitorRow(BigDecimal value, String valueText, String unitCode,
                             String qualityStatus, String limitStatus,
                             OffsetDateTime collectTime, OffsetDateTime refreshTime) {
    }

    public Optional<MonitorRow> latest(long parameterId) {
        List<MonitorRow> rows = jdbc.query("""
                SELECT latest_value, latest_value_text, unit_code, quality_status, limit_status,
                       latest_collect_time, refresh_time
                  FROM ads_parameter_monitor
                 WHERE parameter_id = ?
                """, (rs, rowNum) -> new MonitorRow(
                rs.getBigDecimal("latest_value"),
                rs.getString("latest_value_text"),
                rs.getString("unit_code"),
                rs.getString("quality_status"),
                rs.getString("limit_status"),
                toOffset(rs.getTimestamp("latest_collect_time")),
                toOffset(rs.getTimestamp("refresh_time"))),
                parameterId);
        return rows.stream().findFirst();
    }

    public List<HistoryRecord> history(String sensorCode, String parameterCode,
                                       OffsetDateTime from, OffsetDateTime to,
                                       int page, int pageSize) {
        return jdbc.query("""
                SELECT record_id, value_num, value_text, source_unit, quality_code,
                       collect_time, source_system
                  FROM ods_sensor_data
                 WHERE sensor_code = ? AND parameter_code = ? AND is_deleted = 0
                   AND collect_time >= ? AND collect_time < ?
                 ORDER BY collect_time DESC, record_id DESC
                 LIMIT ? OFFSET ?
                """, HISTORY_MAPPER,
                sensorCode, parameterCode,
                Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()),
                pageSize, (long) (page - 1) * pageSize);
    }

    public long countHistory(String sensorCode, String parameterCode,
                             OffsetDateTime from, OffsetDateTime to) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ods_sensor_data
                 WHERE sensor_code = ? AND parameter_code = ? AND is_deleted = 0
                   AND collect_time >= ? AND collect_time < ?
                """, Long.class,
                sensorCode, parameterCode,
                Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
        return total == null ? 0L : total;
    }

    static final RowMapper<HistoryRecord> HISTORY_MAPPER = (rs, rowNum) -> new HistoryRecord(
            String.valueOf(rs.getLong("record_id")),
            decimalText(rs.getBigDecimal("value_num")),
            rs.getString("value_text"),
            rs.getString("source_unit"),
            rs.getString("quality_code"),
            toOffset(rs.getTimestamp("collect_time")),
            rs.getString("source_system"));

    // ---------------------------------------------------------------- 规则版本

    public List<RuleVersionView> ruleVersions(long parameterId, int page, int pageSize) {
        return jdbc.query("""
                SELECT rv.rule_version_id, rv.rule_version, rv.previous_rule_version_id,
                       rv.normal_lower, rv.normal_upper, rv.alarm_lower, rv.alarm_upper,
                       rv.unit_code, rv.trigger_mode, rv.duration_seconds, rv.enabled,
                       rv.effective_at, rv.reason, rv.operator_name,
                       (SELECT GROUP_CONCAT(e.event_id ORDER BY e.event_id)
                          FROM p2_alert_event e
                         WHERE e.rule_version_id = rv.rule_version_id
                           AND e.end_reason = 'RULE_CHANGED') AS affected_event_ids
                  FROM p2_alert_rule_version rv
                 WHERE rv.parameter_id = ?
                 ORDER BY rv.rule_version DESC
                 LIMIT ? OFFSET ?
                """, RULE_MAPPER, parameterId, pageSize, (long) (page - 1) * pageSize);
    }

    public long countRuleVersions(long parameterId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM p2_alert_rule_version WHERE parameter_id = ?",
                Long.class, parameterId);
        return total == null ? 0L : total;
    }

    static final RowMapper<RuleVersionView> RULE_MAPPER = (rs, rowNum) -> {
        Object previous = rs.getObject("previous_rule_version_id");
        RuleSnapshot rule = new RuleSnapshot(
                boundText(rs.getBigDecimal("normal_lower")),
                boundText(rs.getBigDecimal("normal_upper")),
                boundText(rs.getBigDecimal("alarm_lower")),
                boundText(rs.getBigDecimal("alarm_upper")),
                rs.getString("unit_code"),
                rs.getString("trigger_mode"),
                rs.getObject("duration_seconds") == null ? null : rs.getInt("duration_seconds"),
                rs.getBoolean("enabled"));
        return new RuleVersionView(
                String.valueOf(rs.getLong("rule_version_id")),
                rs.getLong("rule_version"),
                previous == null ? null : String.valueOf(((Number) previous).longValue()),
                rule,
                toOffset(rs.getTimestamp("effective_at")),
                rs.getString("reason"),
                rs.getString("operator_name"),
                splitIds(rs.getString("affected_event_ids")));
    };

    /** {@code GROUP_CONCAT} 出来的是逗号串；空串表示这一版没有结束过任何事件。 */
    static List<String> splitIds(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String part : joined.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 十进制转字符串。
     *
     * <p>不用 {@code toString()} 之外的格式化：{@code DECIMAL(20,6)} 的 6 位小数是采集侧
     * 的真实精度，页面要原样显示，四舍五入交给使用者按 {@code decimalPlaces} 决定。</p>
     */
    static String decimalText(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * 规则边界的读法：去掉库上 {@code DECIMAL(20,6)} 补出来的尾随零。
     *
     * <p>存 0.5 读回 0.500000 在数值上没错，但页面会原样回填成六个小数位，现场再改一次就多一层
     * 无意义的精度。读数不走这里——采集值要原样呈现，配置边界是要给人再编辑的。</p>
     */
    static String boundText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    static OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().atZone(ZONE).toOffsetDateTime();
    }

    // ---------------------------------------------------------------- 写入（P03/P04）

    /**
     * 加锁读出的测点当前状态。
     *
     * <p>{@code version} 来自扩展档案；一期种子测点没有档案行时按 0 处理，与详情口径一致。
     * 一次 {@code FOR UPDATE} 同时锁住主表行与档案行，版本比对、两张表更新之间不允许插队。</p>
     */
    public record SensorRow(long sensorId, String sensorCode, String name, long equipmentId,
                            String sensorType, boolean enabled, String dataSource,
                            String location, String purpose, long version) {
    }

    public Optional<SensorRow> lockRow(long sensorId) {
        List<SensorRow> rows = jdbc.query("""
                SELECT s.sensor_id, s.sensor_code, s.sensor_name, s.equipment_id, s.sensor_type,
                       s.enabled, s.data_source, p.location, p.purpose,
                       COALESCE(p.version, 0) AS profile_version
                  FROM dim_sensor s
                  LEFT JOIN p2_sensor_profile p ON p.sensor_id = s.sensor_id
                 WHERE s.sensor_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new SensorRow(
                rs.getLong("sensor_id"),
                rs.getString("sensor_code"),
                rs.getString("sensor_name"),
                rs.getLong("equipment_id"),
                rs.getString("sensor_type"),
                rs.getBoolean("enabled"),
                rs.getString("data_source"),
                rs.getString("location"),
                rs.getString("purpose"),
                rs.getLong("profile_version")),
                sensorId);
        return rows.stream().findFirst();
    }

    /**
     * 是否已有原始采集记录。
     *
     * <p>按 {@code sensor_code} 查，不是按 {@code sensor_id}：原始表只存编码，
     * 这是两张表之间唯一可靠的连接键。P04 用它决定编码能不能改。</p>
     */
    public boolean hasHistory(String sensorCode) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM ods_sensor_data"
                        + " WHERE sensor_code = ? AND is_deleted = 0)",
                Boolean.class, sensorCode);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 编码是否已被占用；返回的是占用它的测点 ID，便于分辨"新建时重复"与"改成别人的编码"。
     */
    public Optional<Long> findIdByCode(String sensorCode) {
        List<Long> rows = jdbc.query("SELECT sensor_id FROM dim_sensor WHERE sensor_code = ?",
                (rs, rowNum) -> rs.getLong("sensor_id"), sensorCode);
        return rows.stream().findFirst();
    }

    /** 设备是否存在且为生产用途：测点挂在非生产设备上会让产能与能耗口径互相打架。 */
    public boolean equipmentUsable(long equipmentId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM dim_equipment
                               WHERE equipment_id = ? AND equipment_usage = 'PRODUCTION')
                """, Boolean.class, equipmentId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 新增测点主数据。
     *
     * <p>不写 {@code data_source}：新建测点还没有采集映射，留空让页面显示"待配置"，
     * 比先写一个占位来源再让现场困惑要诚实。</p>
     */
    public long insertSensor(String sensorCode, String name, long equipmentId,
                             String sensorType, boolean enabled) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO dim_sensor (sensor_code, sensor_name, equipment_id, sensor_type, enabled)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, sensorCode);
            statement.setString(2, name);
            statement.setLong(3, equipmentId);
            statement.setString(4, sensorType);
            statement.setBoolean(5, enabled);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("新增测点后未取得主键。");
        }
        return key.longValue();
    }

    public void updateSensor(long sensorId, String sensorCode, String name, long equipmentId,
                             String sensorType, boolean enabled) {
        jdbc.update("""
                UPDATE dim_sensor
                   SET sensor_code = ?, sensor_name = ?, equipment_id = ?, sensor_type = ?, enabled = ?
                 WHERE sensor_id = ?
                """, sensorCode, name, equipmentId, sensorType, enabled, sensorId);
    }

    public void insertProfile(long sensorId, String location, String purpose, long version) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        jdbc.update("""
                INSERT INTO p2_sensor_profile (sensor_id, location, purpose, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, sensorId, location, purpose, version, now, now);
    }

    /**
     * 乐观锁更新扩展资料；返回 0 表示版本已被推进，由调用方转成 409。
     *
     * <p>条件里带 {@code version = ?} 而不是只依赖前面的 {@code FOR UPDATE}：
     * 锁是"读的这一刻"的保证，写的时候再确认一次，代价一行代码，能兜住未来有人
     * 把 {@code lockRow} 换成非锁定读的改动。</p>
     */
    public int updateProfile(long sensorId, String location, String purpose, long expectedVersion) {
        return jdbc.update("""
                UPDATE p2_sensor_profile
                   SET location = ?, purpose = ?, version = version + 1, updated_at = ?
                 WHERE sensor_id = ? AND version = ?
                 """, location, purpose, LocalDateTime.now(ZONE), sensorId, expectedVersion);
    }

    // ---------------------------------------------------------------- 参数写入（P07）

    /** 同测点下参数编码是否已被占用；返回 ID 便于给出可读的 409。 */
    public Optional<Long> findParameterIdByCode(long sensorId, String parameterCode) {
        List<Long> ids = jdbc.queryForList(
                "SELECT parameter_id FROM dim_sensor_parameter WHERE sensor_id = ? AND parameter_code = ?",
                Long.class, sensorId, parameterCode);
        return ids.stream().findFirst();
    }

    /**
     * 本测点下一个展示序号。
     *
     * <p>只用于让新参数排在末尾；并发新增拿到同一个值时，列表仍按编码与 ID 兜底排序，
     * 不会出现"顺序不稳定"的读结果。</p>
     */
    public int nextDisplayOrder(long sensorId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(display_order), -1) FROM dim_sensor_parameter WHERE sensor_id = ?",
                Integer.class, sensorId);
        return max == null ? 0 : max + 1;
    }

    /**
     * 新增参数定义。
     *
     * <p>一期维表另有 {@code normal_lower_limit} 等四个旧阈值列，这里刻意不写：二期规则以
     * {@code p2_alert_rule_version} 的快照为唯一真相，两处同时写会出现"改了一处、另一处还是旧值"
     * 的分裂。旧列为空表示"未在一期配置过阈值"，不伪装成已配置。</p>
     */
    public long insertParameter(long sensorId, ParameterWriteRequest request, int displayOrder) {
        int decimalPlaces = request.decimalPlaces() == null ? 2 : request.decimalPlaces();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO dim_sensor_parameter
                        (sensor_id, parameter_code, parameter_name, data_type, unit_code,
                         decimal_places, sampling_frequency_seconds, enabled, process_id, display_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, sensorId);
            statement.setString(2, request.parameterCode());
            statement.setString(3, request.name());
            statement.setString(4, request.dataType());
            statement.setString(5, request.unitCode());
            statement.setInt(6, decimalPlaces);
            if (request.samplingIntervalSeconds() == null) {
                statement.setNull(7, java.sql.Types.INTEGER);
            } else {
                statement.setInt(7, request.samplingIntervalSeconds());
            }
            statement.setBoolean(8, request.enabled());
            if (request.processId() == null) {
                statement.setNull(9, java.sql.Types.BIGINT);
            } else {
                statement.setLong(9, request.processId());
            }
            statement.setInt(10, displayOrder);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("新增参数后未取得主键。");
        }
        return key.longValue();
    }

    /** 新参数的配置版本行，首版为 1；与测点扩展档案同一口径。 */
    public void insertParameterProfile(long parameterId, long version) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        jdbc.update("""
                INSERT INTO p2_sensor_parameter_profile (parameter_id, version, updated_at)
                VALUES (?, ?, ?)
                """, parameterId, version, now);
    }

    /** 参数首版规则快照；{@code previous} 为空表示这是该参数的第一版。 */
    public long insertRuleVersion(long parameterId, RuleSnapshot rule, String reason,
                                  String operatorName) {
        KeyHolder keys = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now(ZONE);
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO p2_alert_rule_version
                        (parameter_id, rule_version, previous_rule_version_id,
                         normal_lower, normal_upper, alarm_lower, alarm_upper, unit_code,
                         trigger_mode, duration_seconds, enabled, effective_at, reason, operator_name)
                    VALUES (?, 1, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, parameterId);
            setDecimal(statement, 2, rule.normalLower());
            setDecimal(statement, 3, rule.normalUpper());
            setDecimal(statement, 4, rule.alarmLower());
            setDecimal(statement, 5, rule.alarmUpper());
            statement.setString(6, rule.unitCode());
            statement.setString(7, rule.triggerMode());
            if (rule.durationSeconds() == null) {
                statement.setNull(8, java.sql.Types.INTEGER);
            } else {
                statement.setInt(8, rule.durationSeconds());
            }
            statement.setBoolean(9, rule.enabled());
            statement.setObject(10, now);
            statement.setString(11, reason);
            statement.setString(12, operatorName);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("新增规则版本后未取得主键。");
        }
        return key.longValue();
    }

    /** 参数详情：定义、配置版本与当前规则快照三处合一，供 P07 返回新建结果。 */
    public Optional<ParameterDetail> parameterDetail(long parameterId) {
        List<ParameterDetail> rows = jdbc.query("""
                SELECT dp.parameter_id, dp.sensor_id, dp.parameter_code, dp.parameter_name,
                       dp.data_type, dp.unit_code, dp.decimal_places,
                       dp.sampling_frequency_seconds, dp.process_id, dp.enabled,
                       COALESCE(pp.version, 0) AS parameter_version,
                       rv.rule_version_id, rv.normal_lower, rv.normal_upper,
                       rv.alarm_lower, rv.alarm_upper, rv.unit_code AS rule_unit_code,
                       rv.trigger_mode, rv.duration_seconds, rv.enabled AS rule_enabled
                  FROM dim_sensor_parameter dp
                  LEFT JOIN p2_sensor_parameter_profile pp
                         ON pp.parameter_id = dp.parameter_id
                  LEFT JOIN (SELECT parameter_id, MAX(rule_version_id) AS rule_version_id
                               FROM p2_alert_rule_version
                              GROUP BY parameter_id) rm
                         ON rm.parameter_id = dp.parameter_id
                  LEFT JOIN p2_alert_rule_version rv ON rv.rule_version_id = rm.rule_version_id
                 WHERE dp.parameter_id = ?
                """, (rs, rowNum) -> {
            Object ruleVersionId = rs.getObject("rule_version_id");
            RuleSnapshot rule = ruleVersionId == null ? null : new RuleSnapshot(
                    boundText(rs.getBigDecimal("normal_lower")),
                    boundText(rs.getBigDecimal("normal_upper")),
                    boundText(rs.getBigDecimal("alarm_lower")),
                    boundText(rs.getBigDecimal("alarm_upper")),
                    rs.getString("rule_unit_code"),
                    rs.getString("trigger_mode"),
                    rs.getObject("duration_seconds") == null ? null : rs.getInt("duration_seconds"),
                    rs.getBoolean("rule_enabled"));
            return new ParameterDetail(
                    String.valueOf(rs.getLong("parameter_id")),
                    String.valueOf(rs.getLong("sensor_id")),
                    rs.getString("parameter_code"),
                    rs.getString("parameter_name"),
                    rs.getString("data_type"),
                    rs.getString("unit_code"),
                    rs.getInt("decimal_places"),
                    rs.getObject("sampling_frequency_seconds") == null
                            ? null : rs.getInt("sampling_frequency_seconds"),
                    rs.getObject("process_id") == null ? null : String.valueOf(rs.getLong("process_id")),
                    rs.getBoolean("enabled"),
                    rule,
                    ruleVersionId == null ? null : String.valueOf(((Number) ruleVersionId).longValue()),
                    rs.getLong("parameter_version"));
        }, parameterId);
        return rows.stream().findFirst();
    }

    public boolean unitExists(String unitCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dim_unit WHERE unit_code = ?", Long.class, unitCode);
        return count != null && count > 0;
    }

    public boolean processExists(long processId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dim_process WHERE process_id = ?", Long.class, processId);
        return count != null && count > 0;
    }

    private static void setDecimal(PreparedStatement statement, int index, String value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.DECIMAL);
        } else {
            statement.setBigDecimal(index, new BigDecimal(value));
        }
    }
}
