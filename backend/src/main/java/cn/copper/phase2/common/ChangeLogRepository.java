package cn.copper.phase2.common;

import cn.copper.phase2.common.ChangeLogModels.ChangeLogItem;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.List;

/** p2_change_log 的写入与分页读取。留痕只增不改，不提供编辑或删除入口。 */
@Repository
public class ChangeLogRepository {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = Json.MAPPER;

    static final String INSERT_SQL = """
            INSERT INTO p2_change_log
                (object_type, object_id, action, before_value, after_value, reason,
                 operator_name, version_before, version_after, request_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    static final String SELECT_PAGE_SQL = """
            SELECT change_log_id, action, before_value, after_value, reason, operator_name,
                   recorded_at, request_id, version_before, version_after
            FROM p2_change_log
            WHERE object_type = ? AND object_id = ?
            ORDER BY recorded_at DESC, change_log_id DESC
            LIMIT ? OFFSET ?
            """;

    static final String COUNT_SQL = """
            SELECT COUNT(*) FROM p2_change_log WHERE object_type = ? AND object_id = ?
            """;

    static final RowMapper<ChangeLogItem> MAPPER = (rs, row) -> {
        Timestamp recordedAt = rs.getTimestamp("recorded_at");
        return new ChangeLogItem(
                String.valueOf(rs.getLong("change_log_id")),
                rs.getString("action"),
                readJson(rs.getString("before_value")),
                readJson(rs.getString("after_value")),
                rs.getString("reason"),
                rs.getString("operator_name"),
                recordedAt == null ? null : recordedAt.toLocalDateTime().atZone(ZONE).toOffsetDateTime(),
                rs.getString("request_id"),
                rs.getObject("version_before", Integer.class),
                rs.getObject("version_after", Integer.class));
    };

    private final JdbcTemplate jdbc;

    public ChangeLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ChangeLogWrite entry) {
        jdbc.update(INSERT_SQL,
                entry.objectType(),
                entry.objectId(),
                entry.action(),
                toJson(entry.before()),
                toJson(entry.after()),
                entry.reason(),
                entry.operatorName(),
                entry.versionBefore(),
                entry.versionAfter(),
                entry.requestId());
    }

    public List<ChangeLogItem> page(String objectType, String objectId, int page, int pageSize) {
        return jdbc.query(SELECT_PAGE_SQL, MAPPER, objectType, objectId,
                pageSize, (long) (page - 1) * pageSize);
    }

    public long count(String objectType, String objectId) {
        Long total = jdbc.queryForObject(COUNT_SQL, Long.class, objectType, objectId);
        return total == null ? 0L : total;
    }

    private static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("留痕快照无法序列化。");
        }
    }

    private static JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("留痕快照无法解析。");
        }
    }
}
