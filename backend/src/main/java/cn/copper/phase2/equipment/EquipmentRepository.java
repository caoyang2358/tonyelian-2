package cn.copper.phase2.equipment;

import cn.copper.phase2.common.Json;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentItem;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentProfile;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentQuery;
import cn.copper.phase2.equipment.EquipmentModels.TechnicalParameter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class EquipmentRepository {
    /**
     * 列表查询统一走 LEFT JOIN：未补档的历史设备仍要出现在结果里，
     * 不能因为扩展行缺失而被 JOIN 过滤掉。
     */
    private static final String SELECT_LIST = """
            SELECT e.equipment_id AS id, e.equipment_code AS code, e.equipment_name AS name,
                   e.workshop_id, w.workshop_name, e.responsible_person, e.enabled,
                   p.type_code, p.management_status, COALESCE(p.version, 0) AS version
            """;

    private static final String FROM_LIST = """
            FROM dim_equipment e
            JOIN dim_workshop w ON w.workshop_id = e.workshop_id
            LEFT JOIN p2_equipment_profile p ON p.equipment_id = e.equipment_id
            """;

    private static final String ORDER_LIST = " ORDER BY w.workshop_code, e.equipment_code";

    static final String EQUIPMENT_SQL = SELECT_LIST + FROM_LIST
            + " WHERE e.equipment_usage = 'PRODUCTION'" + ORDER_LIST;

    /**
     * 删除前必须核对的业务关联（S1-E05）。
     * <p>这十张表都建有指向 dim_equipment 的外键且均为 NO ACTION，漏查任何一张都会让删除失败，
     * 因此清单必须与数据库外键保持同步。表名来自本类常量白名单，不接受外部输入。</p>
     * <p>「生产」未列入：p2_output_record / p2_plan_task / p2_task_order 按车间与任务组织，
     * 数据模型上没有 equipment_id，属于不存在该维度，而不是未检查。</p>
     * <p>本设备自身的扩展档案（p2_equipment_profile）不算阻塞项，删除时一并清理；
     * 留痕（p2_change_log）保留，删除后仍可按历史身份查询。</p>
     */
    public static final List<ReferenceScope> REFERENCE_SCOPES = List.of(
            new ReferenceScope("SENSOR", "dim_sensor"),
            new ReferenceScope("ALERT_EVENT", "p2_alert_event"),
            new ReferenceScope("MONITOR", "ads_parameter_monitor"),
            new ReferenceScope("INSPECTION", "p2_inspection"),
            new ReferenceScope("REPAIR", "p2_repair_order"),
            new ReferenceScope("CONTRACT", "p2_contract_equipment"),
            new ReferenceScope("KNOWLEDGE", "p2_knowledge_article"),
            new ReferenceScope("LAYOUT", "p2_network_node"),
            new ReferenceScope("ATTACHMENT", "p2_attachment"),
            new ReferenceScope("SUPPLIER", "p2_equipment_supplier"));

    /** 一类业务关联：type 用于 blockers 类型码，table 为常量表名。 */
    public record ReferenceScope(String type, String table) {}

    static final String DELETE_PROFILE_SQL =
            "DELETE FROM p2_equipment_profile WHERE equipment_id = ?";

    static final String DELETE_EQUIPMENT_SQL =
            "DELETE FROM dim_equipment WHERE equipment_id = ?";

    static final RowMapper<EquipmentItem> EQUIPMENT_MAPPER = (rs, row) -> new EquipmentItem(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getLong("workshop_id"),
            rs.getString("workshop_name"), rs.getString("responsible_person"), rs.getBoolean("enabled"),
            rs.getString("type_code"), null, rs.getString("management_status"), rs.getLong("version"));

    /**
     * 详情聚合：一期共享身份 LEFT JOIN 二期扩展档案。
     * 用 LEFT JOIN 而非 JOIN，保证尚未补档的历史设备仍能正常读出（版本 0）。
     * 本查询只读，不为缺失的扩展行做任何补写。
     */
    static final String DETAIL_SQL = """
            SELECT e.equipment_id, e.equipment_code, e.equipment_name, e.workshop_id,
                   w.workshop_name, e.responsible_person, e.enabled,
                   p.type_code, p.model, p.process_id, p.installation_location, p.manufacturer,
                   p.factory_serial_no, p.purchased_on, p.commissioned_on, p.expected_life_years,
                   p.warranty_expires_on, p.management_status, p.technical_parameters, p.version
            FROM dim_equipment e
            JOIN dim_workshop w ON w.workshop_id = e.workshop_id
            LEFT JOIN p2_equipment_profile p ON p.equipment_id = e.equipment_id
            WHERE e.equipment_id = ? AND e.equipment_usage = 'PRODUCTION'
            """;

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    static final String INSERT_EQUIPMENT_SQL = """
            INSERT INTO dim_equipment
                (equipment_code, equipment_name, workshop_id, responsible_person, enabled, equipment_usage)
            VALUES (?, ?, ?, ?, ?, 'PRODUCTION')
            """;

    static final String UPDATE_EQUIPMENT_SQL = """
            UPDATE dim_equipment
               SET equipment_name = ?, workshop_id = ?, responsible_person = ?, enabled = ?
             WHERE equipment_id = ?
            """;

    static final String INSERT_PROFILE_SQL = """
            INSERT INTO p2_equipment_profile
                (equipment_id, type_code, model, process_id, installation_location, manufacturer,
                 factory_serial_no, purchased_on, commissioned_on, expected_life_years,
                 warranty_expires_on, management_status, technical_parameters, version,
                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
            """;

    static final String UPDATE_PROFILE_SQL = """
            UPDATE p2_equipment_profile
               SET type_code = ?, model = ?, process_id = ?, installation_location = ?,
                   manufacturer = ?, factory_serial_no = ?, purchased_on = ?, commissioned_on = ?,
                   expected_life_years = ?, warranty_expires_on = ?, management_status = ?,
                   technical_parameters = ?, version = version + 1, updated_at = ?
             WHERE equipment_id = ? AND version = ?
            """;

    static final String WORKSHOP_USABLE_SQL =
            "SELECT COUNT(*) FROM dim_workshop WHERE workshop_id = ? AND enabled = 1";
    static final String PROCESS_WORKSHOP_SQL =
            "SELECT workshop_id FROM dim_process WHERE process_id = ? AND enabled = 1";
    static final String CODE_EXISTS_SQL =
            "SELECT COUNT(*) FROM dim_equipment WHERE equipment_code = ?";
    static final String DETAIL_FOR_UPDATE_SQL = DETAIL_SQL + " FOR UPDATE";

    static final RowMapper<EquipmentRow> DETAIL_MAPPER = (rs, row) -> {
        Long version = rs.getObject("version", Long.class);
        Long processId = rs.getObject("process_id", Long.class);
        EquipmentProfile profile = new EquipmentProfile(
                rs.getString("type_code"),
                rs.getString("model"),
                processId == null ? null : String.valueOf(processId),
                rs.getString("installation_location"),
                rs.getString("manufacturer"),
                rs.getString("factory_serial_no"),
                rs.getObject("purchased_on", LocalDate.class),
                rs.getObject("commissioned_on", LocalDate.class),
                rs.getObject("expected_life_years", Integer.class),
                rs.getObject("warranty_expires_on", LocalDate.class),
                rs.getString("management_status"),
                readParameters(rs.getString("technical_parameters")));
        return new EquipmentRow(
                String.valueOf(rs.getLong("equipment_id")),
                rs.getString("equipment_code"),
                rs.getString("equipment_name"),
                String.valueOf(rs.getLong("workshop_id")),
                rs.getString("workshop_name"),
                rs.getString("responsible_person"),
                rs.getBoolean("enabled"),
                profile,
                version == null ? 0L : version);
    };

    private final JdbcTemplate jdbc;

    public EquipmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EquipmentItem> productionEquipment() {
        return jdbc.query(EQUIPMENT_SQL, EQUIPMENT_MAPPER);
    }

    /** 按条件查询；分页参数为空时返回全部匹配项（首批既有行为）。 */
    public List<EquipmentItem> search(EquipmentQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = searchSql(query, args);
        return jdbc.query(sql, EQUIPMENT_MAPPER, args.toArray());
    }

    /** 动态条件与稳定排序；分页仅在显式传入 page 时追加。 */
    static String searchSql(EquipmentQuery query, List<Object> args) {
        StringBuilder sql = new StringBuilder(SELECT_LIST).append(FROM_LIST)
                .append(buildWhere(query, args)).append(ORDER_LIST);
        if (query.page() != null) {
            sql.append(" LIMIT ? OFFSET ?");
            args.add(query.pageSize());
            args.add((long) (query.page() - 1) * query.pageSize());
        }
        return sql.toString();
    }

    public long count(EquipmentQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*)" + FROM_LIST + buildWhere(query, args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public Optional<EquipmentRow> findDetail(long equipmentId) {
        return jdbc.query(DETAIL_SQL, DETAIL_MAPPER, equipmentId).stream().findFirst();
    }

    /** 更正时锁定设备聚合，保证版本比对与写入在同一事务内不被插队。 */
    public Optional<EquipmentRow> lockDetail(long equipmentId) {
        return jdbc.query(DETAIL_FOR_UPDATE_SQL, DETAIL_MAPPER, equipmentId).stream().findFirst();
    }

    /** 统计某类业务关联的条数；table 只能取自 {@link #REFERENCE_SCOPES} 的常量表名。 */
    public long countReferences(String table, long equipmentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE equipment_id = ?", Long.class, equipmentId);
        return count == null ? 0L : count;
    }

    /** 删除本设备自有扩展档案；留痕不在此处清理。 */
    public int deleteProfile(long equipmentId) {
        return jdbc.update(DELETE_PROFILE_SQL, equipmentId);
    }

    /** 物理删除一期共享身份行。有关联时外键会拦截，调用方必须先完成关联检查。 */
    public int deleteEquipment(long equipmentId) {
        return jdbc.update(DELETE_EQUIPMENT_SQL, equipmentId);
    }

    public boolean workshopUsable(long workshopId) {
        Long count = jdbc.queryForObject(WORKSHOP_USABLE_SQL, Long.class, workshopId);
        return count != null && count > 0;
    }

    /** 返回工序所属车间；工序不存在或已停用时为空。 */
    public Optional<Long> processWorkshop(long processId) {
        List<Long> rows = jdbc.query(PROCESS_WORKSHOP_SQL,
                (rs, row) -> rs.getLong("workshop_id"), processId);
        return rows.stream().findFirst();
    }

    public boolean codeExists(String code) {
        Long count = jdbc.queryForObject(CODE_EXISTS_SQL, Long.class, code);
        return count != null && count > 0;
    }

    public long insertEquipment(String code, String name, long workshopId,
                                String responsiblePerson, boolean enabled) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    INSERT_EQUIPMENT_SQL, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setLong(3, workshopId);
            if (responsiblePerson == null) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(4, responsiblePerson);
            }
            statement.setBoolean(5, enabled);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("设备主数据写入未返回主键。");
        }
        return key.longValue();
    }

    public void updateEquipment(long equipmentId, String name, long workshopId,
                                String responsiblePerson, boolean enabled) {
        jdbc.update(UPDATE_EQUIPMENT_SQL, name, workshopId, responsiblePerson, enabled, equipmentId);
    }

    public void insertProfile(long equipmentId, EquipmentProfile profile, String parametersJson) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        jdbc.update(INSERT_PROFILE_SQL,
                equipmentId,
                profile.typeCode(),
                profile.model(),
                parseIdOrNull(profile.processId()),
                profile.installationLocation(),
                profile.manufacturer(),
                profile.factorySerialNo(),
                profile.purchasedOn(),
                profile.commissionedOn(),
                profile.expectedLifeYears(),
                profile.warrantyExpiresOn(),
                EquipmentValidator.trimToNull(profile.managementStatus()),
                parametersJson,
                now,
                now);
    }

    /** 乐观锁更新；影响行数为 0 表示版本已变化。 */
    public int updateProfile(long equipmentId, EquipmentProfile profile,
                             String parametersJson, long expectedVersion) {
        return jdbc.update(UPDATE_PROFILE_SQL,
                profile.typeCode(),
                profile.model(),
                parseIdOrNull(profile.processId()),
                profile.installationLocation(),
                profile.manufacturer(),
                profile.factorySerialNo(),
                profile.purchasedOn(),
                profile.commissionedOn(),
                profile.expectedLifeYears(),
                profile.warrantyExpiresOn(),
                EquipmentValidator.trimToNull(profile.managementStatus()),
                parametersJson,
                LocalDateTime.now(ZONE),
                equipmentId,
                expectedVersion);
    }

    static String parametersJson(List<TechnicalParameter> parameters) {
        try {
            return JSON.writeValueAsString(parameters == null ? List.of() : parameters);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("技术参数无法序列化。");
        }
    }

    private static Long parseIdOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value.trim());
    }

    private static String buildWhere(EquipmentQuery query, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE e.equipment_usage = 'PRODUCTION'");
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND (e.equipment_code LIKE ? OR e.equipment_name LIKE ?)");
            String like = "%" + query.keyword().trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (query.workshopId() != null) {
            where.append(" AND e.workshop_id = ?");
            args.add(query.workshopId());
        }
        if (query.typeCode() != null) {
            where.append(" AND p.type_code = ?");
            args.add(query.typeCode());
        }
        if (query.managementStatus() != null) {
            where.append(" AND p.management_status = ?");
            args.add(query.managementStatus());
        }
        if (query.enabled() != null) {
            where.append(" AND e.enabled = ?");
            args.add(query.enabled() ? 1 : 0);
        }
        return where.toString();
    }

    /** 扩展档案行的原始读取结果；missingFields 与 deletion 由服务层按业务规则组装。 */
    public record EquipmentRow(String id, String code, String name, String workshopId, String workshopName,
                               String responsiblePerson, boolean enabled,
                               EquipmentProfile profile, long version) {}

    static List<TechnicalParameter> readParameters(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<TechnicalParameter> parameters =
                    JSON.readValue(json, new TypeReference<List<TechnicalParameter>>() {});
            return parameters == null ? List.of() : List.copyOf(parameters);
        } catch (JsonProcessingException error) {
            // 技术参数由本方写入，格式异常属于数据缺陷而非请求错误；不把原始 JSON 写进日志。
            throw new IllegalStateException("扩展档案的技术参数无法解析，请检查该设备的历史写入。");
        }
    }
}
