package cn.copper.phase2.contract;

import cn.copper.phase2.contract.ContractModels.ContractSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * p2_maintenance_contract、p2_contract_equipment 与 p2_attachment 的数据访问。
 *
 * <p>覆盖设备按"整体替换"维护：写接口先删后插，全部在同一事务内完成。
 * 附件只读不写——合同附件的上传属于 F01—F05 的职责，合同这边只负责引用。</p>
 */
@Repository
public class ContractRepository {

    /** 列表筛选条件；四个条件均可为空，同时给出时取交集。 */
    public record ContractQuery(Long equipmentId, String serviceOrganization, String expiryState,
                                int page, int pageSize) {}

    /** 合同主表的一行；日期按 MySQL date 直读，不做时区换算。 */
    public record ContractRow(long contractId, String serviceOrganization, String contractNumber,
                              LocalDate startDate, LocalDate endDate, String remark,
                              long version, OffsetDateTime updatedAt) {}

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 期限状态表达式。
     *
     * <p>提前天数来自 {@code p2_reminder_setting.maintenance_lead_days}，由服务端读出后
     * 以数字直接拼入 SQL。这里不用占位符是有意的：表达式在 SELECT 与 WHERE 各出现一次，
     * 用占位符会要求调用方精确记住参数出现顺序，容易在后续维护中错位；而 leadDays
     * 源自本系统配置表的非负整数，不接受外部输入，拼接不构成注入面。</p>
     */
    static String expiryStateExpr(long leadDays) {
        long days = Math.max(0, leadDays);
        return "CASE WHEN c.end_date IS NULL THEN 'UNKNOWN'"
                + " WHEN c.end_date < CURDATE() THEN 'EXPIRED'"
                + " WHEN DATEDIFF(c.end_date, CURDATE()) <= " + days + " THEN 'EXPIRING_SOON'"
                + " ELSE 'ACTIVE' END";
    }

    static final String SUMMARY_TEMPLATE = """
            SELECT c.contract_id, c.service_organization, c.contract_number,
                   c.start_date, c.end_date,
                   (SELECT COUNT(*) FROM p2_contract_equipment ce
                     WHERE ce.contract_id = c.contract_id) AS equipment_count,
                   %s AS expiry_state,
                   CASE WHEN c.end_date IS NULL THEN NULL
                        ELSE DATEDIFF(c.end_date, CURDATE()) END AS remaining_days
            FROM p2_maintenance_contract c
            """;

    static final String COUNT_FROM = " FROM p2_maintenance_contract c ";

    /** 有结束日期的按到期先后排，日期缺失的排在最后，避免"未知"抢占首页。 */
    static final String ORDER_SUMMARY = " ORDER BY c.end_date IS NULL, c.end_date, c.contract_id ";

    static final String SELECT_ROW = """
            SELECT contract_id, service_organization, contract_number, start_date, end_date,
                   remark, version, updated_at
            FROM p2_maintenance_contract WHERE contract_id = ?
            """;

    static final String SELECT_EQUIPMENT_IDS =
            "SELECT equipment_id FROM p2_contract_equipment WHERE contract_id = ? ORDER BY equipment_id";

    static final String SELECT_ATTACHMENT_IDS =
            "SELECT attachment_id FROM p2_attachment WHERE contract_id = ? ORDER BY attachment_id";

    static final String INSERT_CONTRACT = """
            INSERT INTO p2_maintenance_contract
                (service_organization, contract_number, start_date, end_date, remark,
                 version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 1, NOW(3), NOW(3))
            """;

    static final String UPDATE_CONTRACT = """
            UPDATE p2_maintenance_contract
               SET service_organization = ?, contract_number = ?, start_date = ?, end_date = ?,
                   remark = ?, version = version + 1, updated_at = NOW(3)
             WHERE contract_id = ? AND version = ?
            """;

    static final String DELETE_EQUIPMENT_LINKS =
            "DELETE FROM p2_contract_equipment WHERE contract_id = ?";

    static final String INSERT_EQUIPMENT_LINK =
            "INSERT INTO p2_contract_equipment (contract_id, equipment_id) VALUES (?, ?)";

    /** 与设备列表、E02 的可见范围一致：只认生产用途设备。 */
    static final String USABLE_EQUIPMENT_SQL = """
            SELECT equipment_id FROM dim_equipment
             WHERE equipment_usage = 'PRODUCTION' AND equipment_id IN (%s)
            """;

    static final RowMapper<ContractSummary> SUMMARY_MAPPER = (rs, row) -> new ContractSummary(
            String.valueOf(rs.getLong("contract_id")),
            rs.getString("service_organization"),
            rs.getString("contract_number"),
            toLocalDate(rs.getDate("start_date")),
            toLocalDate(rs.getDate("end_date")),
            rs.getLong("equipment_count"),
            rs.getString("expiry_state"),
            rs.getObject("remaining_days") == null ? null : rs.getInt("remaining_days"));

    static final RowMapper<ContractRow> ROW_MAPPER = (rs, row) -> {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ContractRow(
                rs.getLong("contract_id"),
                rs.getString("service_organization"),
                rs.getString("contract_number"),
                toLocalDate(rs.getDate("start_date")),
                toLocalDate(rs.getDate("end_date")),
                rs.getString("remark"),
                rs.getLong("version"),
                updatedAt == null ? null : updatedAt.toLocalDateTime().atZone(ZONE).toOffsetDateTime());
    };

    private final JdbcTemplate jdbc;

    public ContractRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContractSummary> search(ContractQuery query, long leadDays) {
        List<Object> args = new ArrayList<>();
        String stateExpr = expiryStateExpr(leadDays);
        String sql = SUMMARY_TEMPLATE.formatted(stateExpr)
                + where(query, stateExpr, args) + ORDER_SUMMARY + " LIMIT ? OFFSET ?";
        args.add(query.pageSize());
        args.add((long) (query.page() - 1) * query.pageSize());
        return jdbc.query(sql, SUMMARY_MAPPER, args.toArray());
    }

    public long count(ContractQuery query, long leadDays) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*)" + COUNT_FROM + where(query, expiryStateExpr(leadDays), args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    static String where(ContractQuery query, String stateExpr, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (query.equipmentId() != null) {
            // 用普通字符串而不是 text block：text block 会吃掉共同缩进，
            // 拼接后可能出现 "... 1 = 1AND EXISTS ..." 这类语法错误。
            sql.append(" AND EXISTS (SELECT 1 FROM p2_contract_equipment ce")
                    .append(" WHERE ce.contract_id = c.contract_id AND ce.equipment_id = ?)");
            args.add(query.equipmentId());
        }
        if (query.serviceOrganization() != null && !query.serviceOrganization().isBlank()) {
            sql.append(" AND c.service_organization LIKE ?");
            args.add(likePattern(query.serviceOrganization().trim()));
        }
        if (query.expiryState() != null) {
            sql.append(" AND (").append(stateExpr).append(") = ?");
            args.add(query.expiryState());
        }
        return sql.toString();
    }

    /** MySQL 的 LIKE 默认以反斜杠转义，这里把输入里的通配符转成字面量。 */
    static String likePattern(String keyword) {
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public Optional<ContractRow> findRow(long contractId) {
        return jdbc.query(SELECT_ROW, ROW_MAPPER, contractId).stream().findFirst();
    }

    /**
     * 单条合同的期限计算。
     *
     * <p>详情走这里而不是在 Java 里重算一遍，是为了让列表与详情的期限口径
     * 严格来自同一段 SQL —— 否则"列表显示即将到期、详情显示生效中"这类矛盾迟早会出现。</p>
     */
    public Optional<ContractSummary> summary(long contractId, long leadDays) {
        String sql = SUMMARY_TEMPLATE.formatted(expiryStateExpr(leadDays))
                + " WHERE c.contract_id = ?";
        return jdbc.query(sql, SUMMARY_MAPPER, contractId).stream().findFirst();
    }

    /** 更新前锁定合同行，版本比对、主表更新与关联替换之间不允许被插队。 */
    public Optional<ContractRow> lockRow(long contractId) {
        return jdbc.query(SELECT_ROW + " FOR UPDATE", ROW_MAPPER, contractId).stream().findFirst();
    }

    public List<Long> equipmentIds(long contractId) {
        return jdbc.query(SELECT_EQUIPMENT_IDS,
                (rs, row) -> rs.getLong("equipment_id"), contractId);
    }

    public List<Long> attachmentIds(long contractId) {
        return jdbc.query(SELECT_ATTACHMENT_IDS,
                (rs, row) -> rs.getLong("attachment_id"), contractId);
    }

    public long insert(String serviceOrganization, String contractNumber, LocalDate startDate,
                       LocalDate endDate, String remark) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_CONTRACT,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, serviceOrganization);
            ps.setString(2, contractNumber);
            ps.setDate(3, startDate == null ? null : Date.valueOf(startDate));
            ps.setDate(4, endDate == null ? null : Date.valueOf(endDate));
            ps.setString(5, remark);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("合同主键未返回。");
        }
        return key.longValue();
    }

    /** 按版本条件更新；返回 0 表示版本已被其他事务推进，由调用方转成 409。 */
    public int update(long contractId, String serviceOrganization, String contractNumber,
                      LocalDate startDate, LocalDate endDate, String remark, long expectedVersion) {
        return jdbc.update(UPDATE_CONTRACT, serviceOrganization, contractNumber,
                startDate == null ? null : Date.valueOf(startDate),
                endDate == null ? null : Date.valueOf(endDate),
                remark, contractId, expectedVersion);
    }

    /** 整体替换覆盖设备集合：先清空该合同的全部关联，再按传入顺序写入。 */
    public void replaceEquipment(long contractId, List<Long> equipmentIds) {
        jdbc.update(DELETE_EQUIPMENT_LINKS, contractId);
        for (Long equipmentId : equipmentIds) {
            jdbc.update(INSERT_EQUIPMENT_LINK, contractId, equipmentId);
        }
    }

    /** 筛出真实存在且可用的生产设备，调用方用差集给出 400 而不是让外键抛 500。 */
    public List<Long> usableEquipmentIds(List<Long> equipmentIds) {
        if (equipmentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(equipmentIds.size(), "?"));
        String sql = USABLE_EQUIPMENT_SQL.formatted(placeholders);
        return jdbc.query(sql, (rs, row) -> rs.getLong("equipment_id"), equipmentIds.toArray());
    }

    static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
