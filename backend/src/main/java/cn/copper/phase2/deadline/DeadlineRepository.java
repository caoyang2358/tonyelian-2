package cn.copper.phase2.deadline;

import cn.copper.phase2.deadline.DeadlineModels.DeadlineItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static cn.copper.phase2.deadline.DeadlineModels.LIFE_EVALUATION_DAYS;
import static cn.copper.phase2.deadline.DeadlineModels.TYPE_EXPECTED_LIFE;
import static cn.copper.phase2.deadline.DeadlineModels.TYPE_MAINTENANCE;
import static cn.copper.phase2.deadline.DeadlineModels.TYPE_WARRANTY;

/**
 * S1-T01 期限列表的数据访问。
 *
 * <p>三个期限来源（质保、维保、预计寿命）的表结构不同，到期日算法也不同。
 * 这里先用 {@code UNION ALL} 把它们摊平成同一形状，再在共同的外层算剩余天数与状态——
 * 这样"到期当天为 0、已过为 EXPIRED"这类边界只写一遍，不会出现三个来源三套口径。</p>
 *
 * <p>无合同、无档案的设备同样会出行，状态为 {@code MISSING_DATA}。期限看板的价值
 * 有一半在于"哪些设备缺资料"，把它们过滤掉就等于把待补清单藏起来。</p>
 */
@Repository
public class DeadlineRepository {

    /** 筛选条件；四个条件均可为空，同时给出时取交集。 */
    public record DeadlineQuery(Long equipmentId, Long workshopId, String reminderType,
                                String expiryState, int page, int pageSize) {}

    /**
     * 四个分支的列顺序必须完全一致，{@code UNION} 按位置对齐。
     *
     * <p>占位用的 {@code NULL} 分成两种写法，不是笔误：数字与日期用 {@code CAST} 定型，
     * 避免第一段把整列推成零长度；<b>字符串列不能用 {@code CAST(NULL AS CHAR)}</b>——
     * 那样会带上连接的 collation，而 {@code contract_number} 是 {@code utf8mb4_unicode_ci}、
     * 其余文本列是 {@code utf8mb4_0900_ai_ci}，混合时 MySQL 直接抛
     * {@code ERROR 1271 Illegal mix of collations}。留裸 {@code NULL} 让非空分支决定类型即可。</p>
     */
    static final String UNION_SQL = """
            SELECT e.equipment_id, e.equipment_code, e.equipment_name, e.workshop_id,
                   w.workshop_name, e.responsible_person,
                   'WARRANTY' AS reminder_type,
                   CAST(NULL AS SIGNED) AS contract_id,
                   NULL AS contract_number,
                   p.purchased_on, p.commissioned_on, p.expected_life_years,
                   p.warranty_expires_on AS due_date
              FROM dim_equipment e
              JOIN dim_workshop w ON w.workshop_id = e.workshop_id
              LEFT JOIN p2_equipment_profile p ON p.equipment_id = e.equipment_id
             WHERE e.equipment_usage = 'PRODUCTION'
            UNION ALL
            SELECT e.equipment_id, e.equipment_code, e.equipment_name, e.workshop_id,
                   w.workshop_name, e.responsible_person,
                   'EXPECTED_LIFE' AS reminder_type,
                   CAST(NULL AS SIGNED) AS contract_id,
                   NULL AS contract_number,
                   p.purchased_on, p.commissioned_on, p.expected_life_years,
                   CASE WHEN p.commissioned_on IS NULL OR p.expected_life_years IS NULL THEN NULL
                        ELSE DATE_ADD(p.commissioned_on, INTERVAL p.expected_life_years YEAR) END AS due_date
              FROM dim_equipment e
              JOIN dim_workshop w ON w.workshop_id = e.workshop_id
              LEFT JOIN p2_equipment_profile p ON p.equipment_id = e.equipment_id
             WHERE e.equipment_usage = 'PRODUCTION'
            UNION ALL
            SELECT e.equipment_id, e.equipment_code, e.equipment_name, e.workshop_id,
                   w.workshop_name, e.responsible_person,
                   'MAINTENANCE' AS reminder_type,
                   c.contract_id, c.contract_number,
                   p.purchased_on, p.commissioned_on, p.expected_life_years,
                   c.end_date AS due_date
              FROM dim_equipment e
              JOIN dim_workshop w ON w.workshop_id = e.workshop_id
              LEFT JOIN p2_equipment_profile p ON p.equipment_id = e.equipment_id
              JOIN p2_contract_equipment ce ON ce.equipment_id = e.equipment_id
              JOIN p2_maintenance_contract c ON c.contract_id = ce.contract_id
             WHERE e.equipment_usage = 'PRODUCTION'
            UNION ALL
            SELECT e.equipment_id, e.equipment_code, e.equipment_name, e.workshop_id,
                   w.workshop_name, e.responsible_person,
                   'MAINTENANCE' AS reminder_type,
                   CAST(NULL AS SIGNED) AS contract_id,
                   NULL AS contract_number,
                   p.purchased_on, p.commissioned_on, p.expected_life_years,
                   CAST(NULL AS DATE) AS due_date
              FROM dim_equipment e
              JOIN dim_workshop w ON w.workshop_id = e.workshop_id
              LEFT JOIN p2_equipment_profile p ON p.equipment_id = e.equipment_id
             WHERE e.equipment_usage = 'PRODUCTION'
               AND NOT EXISTS (SELECT 1 FROM p2_contract_equipment ce
                                WHERE ce.equipment_id = e.equipment_id)
            """;

    /** 最紧急的排前面；日期缺失的排最后，避免"未知"抢占首页。 */
    static final String ORDER_BY =
            " ORDER BY ds.due_date IS NULL, ds.due_date, ds.equipment_id, ds.reminder_type, ds.contract_id ";

    /**
     * 拼出完整语句。
     *
     * <p>两个提前量来自 {@code p2_reminder_setting}，是服务端读出的配置值，
     * 以整数直接拼入 SQL。不用占位符是因为它在 {@code CASE} 里，且来源是本系统
     * 非负整数配置、不接受外部输入，不构成注入面；若走占位符，调用方必须精确记住
     * 参数出现顺序（今天、质保、维保、筛选、分页），后续维护极易错位。</p>
     */
    static String withCte(long warrantyLeadDays, long maintenanceLeadDays) {
        long warranty = Math.max(0, warrantyLeadDays);
        long maintenance = Math.max(0, maintenanceLeadDays);
        return "WITH deadline_base AS (\n" + UNION_SQL + "\n),\n"
                + "deadline_calc AS (\n"
                + "    SELECT b.*,\n"
                + "           CASE b.reminder_type\n"
                + "                WHEN '" + TYPE_WARRANTY + "' THEN " + warranty + "\n"
                + "                WHEN '" + TYPE_MAINTENANCE + "' THEN " + maintenance + "\n"
                + "                ELSE " + LIFE_EVALUATION_DAYS + " END AS lead_days,\n"
                + "           DATEDIFF(b.due_date, CAST(? AS DATE)) AS remaining_days\n"
                + "      FROM deadline_base b\n"
                + "),\n"
                + "deadline_state AS (\n"
                + "    SELECT c.*,\n"
                + "           CASE WHEN c.due_date IS NULL THEN 'MISSING_DATA'\n"
                + "                WHEN c.remaining_days < 0 THEN 'EXPIRED'\n"
                + "                WHEN c.remaining_days <= c.lead_days THEN 'DUE_SOON'\n"
                + "                ELSE 'NORMAL' END AS expiry_state\n"
                + "      FROM deadline_calc c\n"
                + ")";
    }

    static final RowMapper<DeadlineItem> ROW_MAPPER = (rs, rowNum) -> {
        String type = rs.getString("reminder_type");
        Object contractId = rs.getObject("contract_id");
        String contractIdText = contractId == null ? null
                : String.valueOf(((Number) contractId).longValue());
        LocalDate dueDate = toLocalDate(rs.getDate("due_date"));
        LocalDate commissionedOn = toLocalDate(rs.getDate("commissioned_on"));
        Integer expectedLifeYears = rs.getObject("expected_life_years") == null
                ? null : rs.getInt("expected_life_years");
        return new DeadlineItem(
                String.valueOf(rs.getLong("equipment_id")),
                rs.getString("equipment_code"),
                rs.getString("equipment_name"),
                String.valueOf(rs.getLong("workshop_id")),
                rs.getString("workshop_name"),
                rs.getString("responsible_person"),
                type,
                contractIdText,
                rs.getString("contract_number"),
                toLocalDate(rs.getDate("purchased_on")),
                commissionedOn,
                expectedLifeYears,
                dueDate,
                rs.getObject("remaining_days") == null ? null : rs.getInt("remaining_days"),
                rs.getString("expiry_state"),
                missingFields(type, dueDate, commissionedOn, expectedLifeYears, contractIdText));
    };

    private final JdbcTemplate jdbc;

    public DeadlineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DeadlineItem> search(DeadlineQuery query, long warrantyLeadDays,
                                     long maintenanceLeadDays, LocalDate today) {
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(today));   // CTE 里的"今天"，必须排在筛选条件之前
        String sql = withCte(warrantyLeadDays, maintenanceLeadDays)
                + "\nSELECT * FROM deadline_state ds" + where(query, args) + ORDER_BY
                + " LIMIT ? OFFSET ?";
        args.add(query.pageSize());
        args.add((long) (query.page() - 1) * query.pageSize());
        return jdbc.query(sql, ROW_MAPPER, args.toArray());
    }

    public long count(DeadlineQuery query, long warrantyLeadDays,
                      long maintenanceLeadDays, LocalDate today) {
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(today));
        String sql = withCte(warrantyLeadDays, maintenanceLeadDays)
                + "\nSELECT COUNT(*) FROM deadline_state ds" + where(query, args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    static String where(DeadlineQuery query, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (query.equipmentId() != null) {
            sql.append(" AND ds.equipment_id = ?");
            args.add(query.equipmentId());
        }
        if (query.workshopId() != null) {
            sql.append(" AND ds.workshop_id = ?");
            args.add(query.workshopId());
        }
        if (query.reminderType() != null) {
            sql.append(" AND ds.reminder_type = ?");
            args.add(query.reminderType());
        }
        if (query.expiryState() != null) {
            sql.append(" AND ds.expiry_state = ?");
            args.add(query.expiryState());
        }
        return sql.toString();
    }

    /**
     * 缺哪些资料才算出这条期限。
     *
     * <p>只报本期限来源真正需要的字段：质保只依赖质保到期日，寿命依赖投用日与预计年限，
     * 维保先看有没有合同、再看合同有没有结束日期。不把整个档案的必填项都罗列一遍，
     * 否则页面提示会失焦。</p>
     */
    static List<String> missingFields(String type, LocalDate dueDate, LocalDate commissionedOn,
                                      Integer expectedLifeYears, String contractId) {
        if (TYPE_WARRANTY.equals(type)) {
            return dueDate == null ? List.of("warrantyExpiresOn") : List.of();
        }
        if (TYPE_EXPECTED_LIFE.equals(type)) {
            List<String> missing = new ArrayList<>();
            if (commissionedOn == null) {
                missing.add("commissionedOn");
            }
            if (expectedLifeYears == null) {
                missing.add("expectedLifeYears");
            }
            return List.copyOf(missing);
        }
        if (TYPE_MAINTENANCE.equals(type) && dueDate == null) {
            return List.of(contractId == null ? "contractId" : "endDate");
        }
        return List.of();
    }

    static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
