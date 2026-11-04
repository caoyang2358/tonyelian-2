package cn.copper.phase2.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 全项目共用的幂等执行器（基于 p2_request_result）。
 * <p>三态语义：首次执行并记录结果；同 requestId 同内容返回首次结果且不重复执行；
 * 同 requestId 不同内容返回 409 IDEMPOTENCY_CONFLICT。</p>
 * <p>业务写入与幂等结果在同一事务内提交，业务失败时幂等记录一起回滚，
 * 因此失败重试仍然可以重新执行，不会被半成品记录挡住。</p>
 */
@Service
public class IdempotencyService {
    private static final ObjectMapper JSON = Json.MAPPER;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    static final String SELECT_SQL = """
            SELECT payload_hash, response_data FROM p2_request_result WHERE request_id = ?
            """;

    static final String INSERT_SQL = """
            INSERT INTO p2_request_result
                (request_id, operation, target_key, payload_hash, response_data, completed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public IdempotencyService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 执行一次可重试的业务提交。
     *
     * @param requestId 客户端生成的 UUID，重试时必须沿用
     * @param operation 操作标识，例如 EQUIPMENT_CREATE
     * @param payload   参与内容比对的对象，字段顺序必须稳定
     * @param type      结果类型，用于重放时反序列化
     * @param action    真正的业务写入，在同一事务内执行
     */
    public <T> T execute(String requestId, String operation, Object payload,
                         Class<T> type, Supplier<T> action) {
        String normalizedId = normalizeRequestId(requestId);
        byte[] hash = sha256(toJson(payload));

        Optional<StoredResult> existing = find(normalizedId);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, type);
        }
        try {
            return transaction.execute(status -> {
                T result = action.get();
                jdbc.update(INSERT_SQL, normalizedId, operation, null, hash,
                        toJson(result), LocalDateTime.now(ZONE));
                return result;
            });
        } catch (DuplicateKeyException conflict) {
            // 只有幂等表里确实存在同一 requestId 时，才说明是并发重试；
            // 否则属于业务唯一键冲突（例如设备编码重复），必须原样抛出，不能误报成幂等冲突。
            Optional<StoredResult> stored = find(normalizedId);
            if (stored.isEmpty()) {
                throw conflict;
            }
            return replay(stored.get(), hash, type);
        }
    }

    private <T> T replay(StoredResult stored, byte[] hash, Class<T> type) {
        if (!Arrays.equals(stored.payloadHash(), hash)) {
            throw BusinessException.idempotencyConflict();
        }
        if (stored.responseJson() == null) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "相同请求编号仍在处理中，请稍后重试。");
        }
        try {
            return JSON.readValue(stored.responseJson(), type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("幂等结果无法反序列化。");
        }
    }

    private Optional<StoredResult> find(String requestId) {
        List<StoredResult> rows = jdbc.query(SELECT_SQL,
                (rs, row) -> new StoredResult(rs.getBytes("payload_hash"), rs.getString("response_data")),
                requestId);
        return rows.stream().findFirst();
    }

    private static String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw BusinessException.invalid("requestId", "请求编号为必填项。");
        }
        String value = requestId.trim();
        if (value.length() > 36) {
            throw BusinessException.invalid("requestId", "请求编号不得超过 36 个字符。");
        }
        return value;
    }

    static byte[] sha256(String payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少 SHA-256 支持。");
        }
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("请求内容或响应结果无法序列化。");
        }
    }

    record StoredResult(byte[] payloadHash, String responseJson) {}
}
