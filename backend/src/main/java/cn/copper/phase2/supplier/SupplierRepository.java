package cn.copper.phase2.supplier;

import cn.copper.phase2.supplier.SupplierModels.SupplierSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * p2_supplier 与 p2_equipment_supplier 的数据访问。
 *
 * <p>关联表的设备集合按"整体替换"维护：写接口先删后插，全部在同一事务里完成，
 * 中间不会出现只解绑了一半的可见状态。表名与列名全部是常量，不拼接外部输入。</p>
 */
@Repository
public class SupplierRepository {

    /** 列表筛选条件；{@code keyword} 与 {@code equipmentId} 均可为空。 */
    public record SupplierQuery(String keyword, Long equipmentId, int page, int pageSize) {}

    /** 供应商主表的一行；updated_at 按业务时区转成带偏移的时刻。 */
    public record SupplierRow(long supplierId, String name, String contactName, String phone,
                              String remark, long version, OffsetDateTime updatedAt) {}

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 列表主体：关联数用子查询，避免 JOIN 后再 GROUP BY 拖慢分页。 */
    static final String SELECT_SUMMARY = """
            SELECT s.supplier_id, s.name, s.contact_name, s.phone,
                   (SELECT COUNT(*) FROM p2_equipment_supplier es
                     WHERE es.supplier_id = s.supplier_id) AS equipment_count
            FROM p2_supplier s
            """;

    static final String FROM_COUNT = " FROM p2_supplier s ";

    static final String ORDER_SUMMARY = " ORDER BY s.name, s.supplier_id ";

    static final String SELECT_ROW = """
            SELECT supplier_id, name, contact_name, phone, remark, version, updated_at
            FROM p2_supplier WHERE supplier_id = ?
            """;

    static final String SELECT_EQUIPMENT_IDS =
            "SELECT equipment_id FROM p2_equipment_supplier WHERE supplier_id = ? ORDER BY equipment_id";

    static final String INSERT_SUPPLIER = """
            INSERT INTO p2_supplier (name, contact_name, phone, remark, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, 1, NOW(3), NOW(3))
            """;

    static final String UPDATE_SUPPLIER = """
            UPDATE p2_supplier
               SET name = ?, contact_name = ?, phone = ?, remark = ?,
                   version = version + 1, updated_at = NOW(3)
             WHERE supplier_id = ? AND version = ?
            """;

    static final String DELETE_ASSOCIATIONS =
            "DELETE FROM p2_equipment_supplier WHERE supplier_id = ?";

    static final String INSERT_ASSOCIATION =
            "INSERT INTO p2_equipment_supplier (equipment_id, supplier_id) VALUES (?, ?)";

    /** 只认生产用途设备，与设备列表、E02 的可见范围保持一致。 */
    static final String USABLE_EQUIPMENT_SQL = """
            SELECT equipment_id FROM dim_equipment
             WHERE equipment_usage = 'PRODUCTION' AND equipment_id IN (%s)
            """;

    static final RowMapper<SupplierSummary> SUMMARY_MAPPER = (rs, row) -> new SupplierSummary(
            String.valueOf(rs.getLong("supplier_id")),
            rs.getString("name"),
            rs.getString("contact_name"),
            rs.getString("phone"),
            rs.getLong("equipment_count"));

    static final RowMapper<SupplierRow> ROW_MAPPER = (rs, row) -> {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new SupplierRow(
                rs.getLong("supplier_id"),
                rs.getString("name"),
                rs.getString("contact_name"),
                rs.getString("phone"),
                rs.getString("remark"),
                rs.getLong("version"),
                updatedAt == null ? null : updatedAt.toLocalDateTime().atZone(ZONE).toOffsetDateTime());
    };

    private final JdbcTemplate jdbc;

    public SupplierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SupplierSummary> search(SupplierQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = SELECT_SUMMARY + where(query, args) + ORDER_SUMMARY + " LIMIT ? OFFSET ?";
        args.add(query.pageSize());
        args.add((long) (query.page() - 1) * query.pageSize());
        return jdbc.query(sql, SUMMARY_MAPPER, args.toArray());
    }

    public long count(SupplierQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*)" + FROM_COUNT + where(query, args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /** 动态条件：keyword 同时匹配名称、联系人、电话；equipmentId 限定为已关联该设备的供应商。 */
    static String where(SupplierQuery query, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (query.keyword() != null && !query.keyword().isBlank()) {
            sql.append(" AND (s.name LIKE ? OR s.contact_name LIKE ? OR s.phone LIKE ?)");
            String pattern = likePattern(query.keyword().trim());
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if (query.equipmentId() != null) {
            // 用普通字符串而不是 text block：text block 会去掉共同缩进，
            // 拼接时前导空格会被吃掉，导致 "... 1 = 1AND EXISTS ..." 这样的语法错误。
            sql.append(" AND EXISTS (SELECT 1 FROM p2_equipment_supplier es")
                    .append(" WHERE es.supplier_id = s.supplier_id AND es.equipment_id = ?)");
            args.add(query.equipmentId());
        }
        return sql.toString();
    }

    /** MySQL 的 LIKE 默认以反斜杠为转义符，这里把用户输入里的通配符转成字面量。 */
    static String likePattern(String keyword) {
        String escaped = keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public Optional<SupplierRow> findRow(long supplierId) {
        return jdbc.query(SELECT_ROW, ROW_MAPPER, supplierId).stream().findFirst();
    }

    /** 更新前锁定供应商行，版本比对、关联替换与留痕在同一事务内不被插队。 */
    public Optional<SupplierRow> lockRow(long supplierId) {
        return jdbc.query(SELECT_ROW + " FOR UPDATE", ROW_MAPPER, supplierId).stream().findFirst();
    }

    public List<Long> equipmentIds(long supplierId) {
        return jdbc.query(SELECT_EQUIPMENT_IDS,
                (rs, row) -> rs.getLong("equipment_id"), supplierId);
    }

    public long insert(String name, String contactName, String phone, String remark) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_SUPPLIER,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, contactName);
            ps.setString(3, phone);
            ps.setString(4, remark);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("供应商主键未返回。");
        }
        return key.longValue();
    }

    /** 按版本条件更新；返回 0 表示版本已被其他事务推进，由调用方转成 409。 */
    public int update(long supplierId, String name, String contactName, String phone,
                      String remark, long expectedVersion) {
        return jdbc.update(UPDATE_SUPPLIER, name, contactName, phone, remark,
                supplierId, expectedVersion);
    }

    /** 整体替换关联集合：先清空该供应商的全部关联，再按传入顺序写入。 */
    public void replaceAssociations(long supplierId, List<Long> equipmentIds) {
        jdbc.update(DELETE_ASSOCIATIONS, supplierId);
        for (Long equipmentId : equipmentIds) {
            jdbc.update(INSERT_ASSOCIATION, equipmentId, supplierId);
        }
    }

    /**
     * 在给定 ID 中筛出真实存在且可用的生产设备。
     * 调用方用差集判断哪些 ID 不合法，从而给出 400 而不是让外键抛 500。
     */
    public List<Long> usableEquipmentIds(List<Long> equipmentIds) {
        if (equipmentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(equipmentIds.size(), "?"));
        String sql = String.format(USABLE_EQUIPMENT_SQL, placeholders);
        return jdbc.query(sql, (rs, row) -> rs.getLong("equipment_id"), equipmentIds.toArray());
    }
}
