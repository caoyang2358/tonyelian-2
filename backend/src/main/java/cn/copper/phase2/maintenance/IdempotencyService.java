package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.IdempotencyRepository.StoredRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * requestId 幂等（《接口通用约定》§3）：
 * 同一标识、同一业务内容重复提交 → 返回第一次的成功结果，不重复入库；
 * 同一标识携带不同内容 → 409 IDEMPOTENCY_CONFLICT；
 * 先查已成功结果，再做版本判断，避免正常重试被误判成版本冲突。
 */
@Service
public class IdempotencyService {
    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 尝试占用 requestId。返回已有响应表示这是一次重试，调用方直接返回它；
     * 返回 empty 表示首次请求，调用方继续执行业务，成功后调用 {@link #complete}。
     */
    public <T> Optional<T> claim(String requestId, String operation, String targetKey, Object payload, Class<T> type) {
        byte[] payloadHash = hash(payload);
        Optional<StoredRequest> existing =
                repository.insertIfAbsent(requestId, operation, targetKey, payloadHash);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        StoredRequest stored = existing.get();
        if (!operation.equals(stored.operation())
                || !equalsNullable(targetKey, stored.targetKey())
                || !Arrays.equals(payloadHash, stored.payloadHash())) {
            throw new MaintenanceConflictException("IDEMPOTENCY_CONFLICT",
                    "同一请求标识携带了不同业务内容，请使用新的请求标识重新提交。",
                    Map.of("requestId", requestId));
        }
        if (stored.responseJson() == null) {
            throw new MaintenanceConflictException("IDEMPOTENCY_CONFLICT",
                    "该请求正在处理中，请稍后重试。", Map.of("requestId", requestId));
        }
        return Optional.of(read(stored.responseJson(), type));
    }

    public void complete(String requestId, Object response) {
        repository.complete(requestId, write(response));
    }

    String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("响应无法序列化", error);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("已保存的响应无法还原", error);
        }
    }

    private byte[] hash(Object payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(write(payload).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
