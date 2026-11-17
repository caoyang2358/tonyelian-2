package cn.copper.phase2.energy;

import cn.copper.phase2.energy.EnergyModels.EnergyPriceSummary;
import cn.copper.phase2.energy.EnergyModels.PriceMatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * p2_energy_price 的数据访问。
 *
 * <p>只读写二期自建表，不碰一期的 {@code dim_energy_type}、{@code dws_energy_day}。
 * 表的 {@code scope_key} 与 {@code current_marker} 是生成列，由 MySQL 维护，
 * 这里只负责把 {@code workshop_id} 与 {@code is_current} 写对，不手工计算这两个列。</p>
 */
@Repository
public class EnergyRepository {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** E04 的筛选条件；全部可空，同时给出时取交集。 */
    public record PriceQuery(String energyType, Long workshopId, String scope,
                             LocalDate effectiveFrom, LocalDate effectiveTo,
                             boolean includeHistory, int page, int pageSize) {}

    /** 价格行；单价按 BigDecimal 读，由服务层决定如何输出，避免在 SQL 层丢精度。 */
    public record PriceRow(long priceId, String energyType, Long workshopId, LocalDate effectiveDate,
                           String unitCode, BigDecimal unitPrice, boolean current, Long supersedesPriceId,
                           String operatorName, String reason, long version, OffsetDateTime recordedAt) {}

    private static final String BASE_SELECT = """
            SELECT p.energy_price_id, p.energy_type, p.workshop_id, p.effective_date, p.unit_code,
                   p.unit_price_yuan_per_unit, p.is_current, p.supersedes_price_id,
                   p.operator_name, p.reason, p.version, p.recorded_at,
                   w.workshop_name
              FROM p2_energy_price p
              LEFT JOIN dim_workshop w ON w.workshop_id = p.workshop_id
            """;

    /** 生效日新的在前；同一天按录入先后倒序，让"最近更正的那条"排在前面。 */
    private static final String ORDER_PRICE = " ORDER BY p.effective_date DESC, p.energy_price_id DESC ";

    private static final String SELECT_ROW = """
            SELECT energy_price_id, energy_type, workshop_id, effective_date, unit_code,
                   unit_price_yuan_per_unit, is_current, supersedes_price_id,
                   operator_name, reason, version, recorded_at
              FROM p2_energy_price WHERE energy_price_id = ?
            """;

    /**
     * 车间专用范围的取价。取生效日不晚于实际日的最近一条当前有效价。
     *
     * <p>不写 {@code ORDER BY effective_date DESC LIMIT 1} 之外的条件是有意的：
     * 专属价一旦生效就持续到下一条专属价，中间新增的通用价不覆盖它——所以专用与通用
     * 必须是两次独立查询，不能合成一个带优先级字段的排序。</p>
     */
    private static final String MATCH_WORKSHOP = """
            SELECT energy_price_id, effective_date, unit_code, unit_price_yuan_per_unit, version
              FROM p2_energy_price
             WHERE energy_type = ? AND workshop_id = ? AND is_current = 1 AND effective_date <= ?
             ORDER BY effective_date DESC, energy_price_id DESC LIMIT 1
            """;

    private static final String MATCH_GENERAL = """
            SELECT energy_price_id, effective_date, unit_code, unit_price_yuan_per_unit, version
              FROM p2_energy_price
             WHERE energy_type = ? AND workshop_id IS NULL AND is_current = 1 AND effective_date <= ?
             ORDER BY effective_date DESC, energy_price_id DESC LIMIT 1
            """;

    private static final String INSERT_PRICE = """
            INSERT INTO p2_energy_price
                (energy_type, workshop_id, effective_date, unit_code, unit_price_yuan_per_unit,
                 supersedes_price_id, is_current, reason, operator_name, version, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 0, NOW(3))
            """;

    private static final String DEACTIVATE = """
            UPDATE p2_energy_price SET is_current = 0 WHERE energy_price_id = ?
            """;

    private static final String WORKSHOP_EXISTS = "SELECT COUNT(*) FROM dim_workshop WHERE workshop_id = ?";

    private final JdbcTemplate jdbc;

    public EnergyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EnergyPriceSummary> search(PriceQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = BASE_SELECT + where(query, args) + ORDER_PRICE + " LIMIT ? OFFSET ?";
        args.add(query.pageSize());
        args.add((long) (query.page() - 1) * query.pageSize());
        return jdbc.query(sql, (rs, row) -> {
            Long workshopId = rs.getObject("workshop_id", Long.class);
            Timestamp recordedAt = rs.getTimestamp("recorded_at");
            return new EnergyPriceSummary(
                    String.valueOf(rs.getLong("energy_price_id")),
                    rs.getString("energy_type"),
                    EnergyModels.ENERGY_TYPE_NAMES.get(rs.getString("energy_type")),
                    workshopId == null ? null : String.valueOf(workshopId),
                    rs.getString("workshop_name"),
                    workshopId == null ? EnergyModels.SCOPE_GENERAL : EnergyModels.SCOPE_WORKSHOP,
                    rs.getDate("effective_date").toLocalDate(),
                    rs.getString("unit_code"),
                    plain(rs.getBigDecimal("unit_price_yuan_per_unit")),
                    rs.getLong("version"),
                    rs.getBoolean("is_current"),
                    rs.getObject("supersedes_price_id", Long.class) == null
                            ? null : String.valueOf(rs.getObject("supersedes_price_id", Long.class)),
                    rs.getString("operator_name"),
                    rs.getString("reason"),
                    recordedAt == null ? null : recordedAt.toLocalDateTime().atZone(ZONE).toOffsetDateTime());
        }, args.toArray());
    }

    public long count(PriceQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM p2_energy_price p" + where(query, args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    static String where(PriceQuery query, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (query.energyType() != null) {
            sql.append(" AND p.energy_type = ?");
            args.add(query.energyType());
        }
        if (EnergyModels.SCOPE_GENERAL.equals(query.scope())) {
            // 通用范围就是"不属于任何车间"，此时再带 workshopId 属于自相矛盾的筛选，
            // 由服务层提前拒绝，这里只按 scope 走。
            sql.append(" AND p.workshop_id IS NULL");
        } else if (query.workshopId() != null) {
            sql.append(" AND p.workshop_id = ?");
            args.add(query.workshopId());
        } else if (EnergyModels.SCOPE_WORKSHOP.equals(query.scope())) {
            sql.append(" AND p.workshop_id IS NOT NULL");
        }
        if (query.effectiveFrom() != null) {
            sql.append(" AND p.effective_date >= ?");
            args.add(Date.valueOf(query.effectiveFrom()));
        }
        if (query.effectiveTo() != null) {
            sql.append(" AND p.effective_date <= ?");
            args.add(Date.valueOf(query.effectiveTo()));
        }
        if (!query.includeHistory()) {
            sql.append(" AND p.is_current = 1");
        }
        return sql.toString();
    }

    /**
     * 按实际用能日取价：先车间专用，专用无适用行才回退通用。
     *
     * @param workshopId 为 null 表示这次查询不限定车间，只取通用价
     */
    public Optional<PriceMatch> matchPrice(String energyType, Long workshopId, LocalDate actualDate) {
        if (workshopId != null) {
            Optional<PriceMatch> own = jdbc.query(MATCH_WORKSHOP,
                    (rs, row) -> new PriceMatch(String.valueOf(rs.getLong("energy_price_id")),
                            EnergyModels.SCOPE_WORKSHOP,
                            rs.getDate("effective_date").toLocalDate(),
                            rs.getString("unit_code"),
                            plain(rs.getBigDecimal("unit_price_yuan_per_unit")),
                            rs.getLong("version")),
                    energyType, workshopId, Date.valueOf(actualDate)).stream().findFirst();
            if (own.isPresent()) {
                return own;
            }
        }
        return jdbc.query(MATCH_GENERAL,
                (rs, row) -> new PriceMatch(String.valueOf(rs.getLong("energy_price_id")),
                        EnergyModels.SCOPE_GENERAL,
                        rs.getDate("effective_date").toLocalDate(),
                        rs.getString("unit_code"),
                        plain(rs.getBigDecimal("unit_price_yuan_per_unit")),
                        rs.getLong("version")),
                energyType, Date.valueOf(actualDate)).stream().findFirst();
    }

    public Optional<PriceRow> findRow(long priceId) {
        return jdbc.query(SELECT_ROW, PRICE_ROW_MAPPER, priceId).stream().findFirst();
    }

    /** 更正前锁定原价行：版本比对、停用原行与插入新行之间不允许插队。 */
    public Optional<PriceRow> lockRow(long priceId) {
        return jdbc.query(SELECT_ROW + " FOR UPDATE", PRICE_ROW_MAPPER, priceId).stream().findFirst();
    }

    /**
     * 同一个"能源 + 范围 + 生效日"是否已有另一条当前有效价。
     *
     * <p>与数据库唯一键 {@code uk_p2_price_active} 同口径，但提前查一次是为了给出
     * "同一天已有价格，请打开原价格核对"这种可执行提示，而不是把唯一键异常抛成 500。</p>
     *
     * @param excludePriceId 更正时需要排除自身
     */
    public boolean existsCurrent(String energyType, Long workshopId, LocalDate effectiveDate,
                                 Long excludePriceId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM p2_energy_price
                 WHERE energy_type = ? AND is_current = 1 AND effective_date = ?
                """);
        args.add(energyType);
        args.add(Date.valueOf(effectiveDate));
        if (workshopId == null) {
            sql.append(" AND workshop_id IS NULL");
        } else {
            sql.append(" AND workshop_id = ?");
            args.add(workshopId);
        }
        if (excludePriceId != null) {
            sql.append(" AND energy_price_id <> ?");
            args.add(excludePriceId);
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total != null && total > 0;
    }

    /**
     * 同范围同能源中，比给定日期更晚的下一条当前有效价的生效日。
     *
     * <p>用来算出"这次价格影响到哪里为止"：下一条价的前一天就是本次影响的终点。
     * 没有更晚的价时返回空，表示一直向后生效。</p>
     */
    public Optional<LocalDate> nextEffectiveDate(String energyType, Long workshopId,
                                                 LocalDate after, Long excludePriceId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT MIN(effective_date) FROM p2_energy_price
                 WHERE energy_type = ? AND is_current = 1 AND effective_date > ?
                """);
        args.add(energyType);
        args.add(Date.valueOf(after));
        if (workshopId == null) {
            sql.append(" AND workshop_id IS NULL");
        } else {
            sql.append(" AND workshop_id = ?");
            args.add(workshopId);
        }
        if (excludePriceId != null) {
            sql.append(" AND energy_price_id <> ?");
            args.add(excludePriceId);
        }
        Date next = jdbc.queryForObject(sql.toString(), Date.class, args.toArray());
        return next == null ? Optional.empty() : Optional.of(next.toLocalDate());
    }

    public long insert(String energyType, Long workshopId, LocalDate effectiveDate, String unitCode,
                       BigDecimal unitPrice, Long supersedesPriceId, String reason, String operatorName) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_PRICE,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, energyType);
            if (workshopId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, workshopId);
            }
            ps.setDate(3, Date.valueOf(effectiveDate));
            ps.setString(4, unitCode);
            ps.setBigDecimal(5, unitPrice);
            if (supersedesPriceId == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, supersedesPriceId);
            }
            ps.setString(7, reason);
            ps.setString(8, operatorName);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("能源价格主键未返回。");
        }
        return key.longValue();
    }

    /** 停用一条价格；被更正的原行保留全部字段，只退出"当前有效"集合。 */
    public int deactivate(long priceId) {
        return jdbc.update(DEACTIVATE, priceId);
    }

    public boolean workshopExists(long workshopId) {
        Long total = jdbc.queryForObject(WORKSHOP_EXISTS, Long.class, workshopId);
        return total != null && total > 0;
    }

    private static final RowMapper<PriceRow> PRICE_ROW_MAPPER = (rs, row) -> {
        Timestamp recordedAt = rs.getTimestamp("recorded_at");
        return new PriceRow(
                rs.getLong("energy_price_id"),
                rs.getString("energy_type"),
                rs.getObject("workshop_id", Long.class),
                rs.getDate("effective_date").toLocalDate(),
                rs.getString("unit_code"),
                rs.getBigDecimal("unit_price_yuan_per_unit"),
                rs.getBoolean("is_current"),
                rs.getObject("supersedes_price_id", Long.class),
                rs.getString("operator_name"),
                rs.getString("reason"),
                rs.getLong("version"),
                recordedAt == null ? null : recordedAt.toLocalDateTime().atZone(ZONE).toOffsetDateTime());
    };

    /**
     * 单价输出为字符串，保留数据库的 10 位小数。
     *
     * <p>这里不用 {@code stripTrailingZeros}：契约示例是 {@code "1.1000000000"}，
     * 单价的有效位数本身是信息（能看出录到了哪一位），抹掉尾零会让 "1.1" 与 "1.1000000000"
     * 看起来一样，反而不利于核对。</p>
     */
    static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
