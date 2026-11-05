package cn.copper.phase2.maintenance;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 幂等记录表 p2_request_result 的访问层。
 * 采用两阶段写入：先占位（response 为 null），业务成功后补响应，
 * 满足表上的 CHECK（completed_at 与 response_data 同时有值或同时为空）。
 */
@Repository
public class IdempotencyRepository {
    record StoredRequest(String operation, String targetKey, byte[] payloadHash, String responseJson) {}

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 占位成功返回 empty；主键已存在则返回已有记录（并发安全，由主键保证唯一）。 */
    public Optional<StoredRequest> insertIfAbsent(String requestId, String operation, String targetKey, byte[] payloadHash) {
        try {
            jdbc.update("""
                    INSERT INTO p2_request_result (request_id, operation, target_key, payload_hash)
                    VALUES (?, ?, ?, ?)
                    """, requestId, operation, targetKey, payloadHash);
            return Optional.empty();
        } catch (DuplicateKeyException duplicate) {
            return find(requestId);
        }
    }

    public Optional<StoredRequest> find(String requestId) {
        List<StoredRequest> rows = jdbc.query("""
                SELECT operation, target_key, payload_hash, response_data
                FROM p2_request_result WHERE request_id = ?
                """, (rs, row) -> new StoredRequest(
                rs.getString("operation"),
                rs.getString("target_key"),
                rs.getBytes("payload_hash"),
                rs.getString("response_data")), requestId);
        return rows.stream().findFirst();
    }

    public void complete(String requestId, String responseJson) {
        jdbc.update("""
                UPDATE p2_request_result
                   SET response_data = ?, completed_at = CURRENT_TIMESTAMP(3)
                 WHERE request_id = ?
                """, responseJson, requestId);
    }
}
