package cn.copper.phase2.network;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class NetworkService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String OPERATION = "S1-N02";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final NetworkReadings readings;

    public NetworkService(JdbcTemplate jdbc, ObjectMapper json, NetworkReadings readings) {
        this.jdbc = jdbc;
        this.json = json;
        this.readings = readings;
    }

    public record Node(String nodeId, String kind, String objectId, String xPercent, String yPercent) {}
    public record Edge(String edgeId, String fromNodeId, String toNodeId, String label) {}
    public record Command(String requestId, String operatorName, Long expectedVersion, String reason,
                          List<Node> nodes, List<Edge> edges) {}
    public record Latest(String parameterId, String sensorCode, String parameterCode, String value, String valueText,
                         String unitCode, String collectTime, String refreshTime, String qualityStatus,
                         String limitStatus, String monitoringStatus, String dataSource) {}
    public record Layout(String workshopId, long version, String updatedAt, List<Node> nodes, List<Edge> edges,
                         List<String> processSteps,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String changeLogId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, List<Latest>> latestBySensorId) {}
    private record Header(long version, String updatedAt) {}
    private record Receipt(String operation, String target, byte[] hash, String response) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Layout read(String workshopId, boolean includeLatest) {
        long id = businessId(workshopId);
        return load(id, workshop(id, false), includeLatest);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Layout save(String workshopId, Command command) {
        long id = businessId(workshopId);
        validate(command);
        String requestId = UUID.fromString(command.requestId()).toString();
        String target = Long.toString(id);
        byte[] hash = hash(command);
        // Claim the global request ID before acquiring workshop and reference locks.
        jdbc.update("""
                INSERT INTO p2_request_result(request_id, operation, target_key, payload_hash)
                VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE request_id = request_id
                """, requestId, OPERATION, target, hash);
        Receipt receipt = jdbc.queryForObject("""
                SELECT operation, target_key, payload_hash, response_data
                FROM p2_request_result WHERE request_id = ? FOR UPDATE
                """, (rs, row) -> new Receipt(rs.getString(1), rs.getString(2), rs.getBytes(3), rs.getString(4)), requestId);
        if (!OPERATION.equals(receipt.operation()) || !target.equals(receipt.target()) || !Arrays.equals(hash, receipt.hash())) {
            throw new LayoutError(409, "IDEMPOTENCY_CONFLICT", "请求 ID 已用于其他提交。", null);
        }
        if (receipt.response() != null) {
            try {
                return json.readValue(receipt.response(), Layout.class);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Invalid stored layout result", error);
            }
        }

        String code = workshop(id, true);
        Layout before = load(id, code, false);
        if (before.version() != command.expectedVersion()) {
            throw new LayoutError(409, "VERSION_CONFLICT", "布局已更新，请重新读取版本。",
                    Map.of("currentVersion", before.version()));
        }
        command.nodes().forEach(node -> checkReference(id, node));
        LocalDateTime now = LocalDateTime.now(ZONE).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (before.version() == 0) {
            jdbc.update("INSERT INTO p2_network_layout(workshop_id, version, updated_at) VALUES (?, 1, ?) ON DUPLICATE KEY UPDATE version = 1, updated_at = ?", id, now, now);
        } else {
            jdbc.update("UPDATE p2_network_layout SET version = version + 1, updated_at = ? WHERE workshop_id = ?", now, id);
        }
        jdbc.update("DELETE FROM p2_network_edge WHERE workshop_id = ?", id);
        jdbc.update("DELETE FROM p2_network_node WHERE workshop_id = ?", id);
        for (Node node : command.nodes()) {
            long objectId = businessId(node.objectId());
            boolean sensor = node.kind().equals("SENSOR");
            jdbc.update("""
                    INSERT INTO p2_network_node(workshop_id, node_id, equipment_id, sensor_id, x_percent, y_percent)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, node.nodeId(), sensor ? null : objectId, sensor ? objectId : null,
                    coordinate(node.xPercent()), coordinate(node.yPercent()));
        }
        for (Edge edge : command.edges()) {
            jdbc.update("""
                    INSERT INTO p2_network_edge(workshop_id, edge_id, from_node_id, to_node_id, label)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, edge.edgeId(), edge.fromNodeId(), edge.toNodeId(), edge.label());
        }

        Layout after = load(id, code, false);
        String beforeJson = encode(before);
        String afterJson = encode(after);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO p2_change_log(object_type, object_id, action, before_value, after_value, reason, operator_name)
                    VALUES ('NETWORK_LAYOUT', ?, 'SAVE', ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, target);
            statement.setString(2, beforeJson);
            statement.setString(3, afterJson);
            statement.setString(4, reason(command));
            statement.setString(5, command.operatorName().strip());
            return statement;
        }, key);
        Layout result = new Layout(target, after.version(), after.updatedAt(), after.nodes(), after.edges(),
                after.processSteps(), key.getKey().toString(), null);
        jdbc.update("UPDATE p2_request_result SET response_data = ?, completed_at = ? WHERE request_id = ?",
                encode(result), now, requestId);
        return result;
    }

    private String workshop(long id, boolean lock) {
        var codes = jdbc.queryForList("SELECT workshop_code FROM dim_workshop WHERE workshop_id = ?"
                + (lock ? " FOR UPDATE" : ""), String.class, id);
        if (codes.isEmpty()) throw new LayoutError(404, "NOT_FOUND", "车间不存在。", null);
        return codes.get(0);
    }

    private Layout load(long id, String code, boolean includeLatest) {
        var headers = jdbc.query("SELECT version, updated_at FROM p2_network_layout WHERE workshop_id = ?",
                (rs, row) -> new Header(rs.getLong(1),
                        rs.getObject(2, LocalDateTime.class).atZone(ZONE).toOffsetDateTime().toString()), id);
        var steps = processSteps(code);
        if (headers.isEmpty()) {
            return new Layout(Long.toString(id), 0, null, List.of(), List.of(), steps, null,
                    includeLatest ? Map.of() : null);
        }
        var nodes = jdbc.query("""
                SELECT node_id, equipment_id, sensor_id, x_percent, y_percent
                FROM p2_network_node WHERE workshop_id = ? ORDER BY node_id
                """, (rs, row) -> {
            String sensorId = rs.getString(3);
            return new Node(rs.getString(1), sensorId == null ? "EQUIPMENT" : "SENSOR",
                    sensorId == null ? rs.getString(2) : sensorId,
                    rs.getBigDecimal(4).stripTrailingZeros().toPlainString(),
                    rs.getBigDecimal(5).stripTrailingZeros().toPlainString());
        }, id);
        var edges = jdbc.query("""
                SELECT edge_id, from_node_id, to_node_id, label
                FROM p2_network_edge WHERE workshop_id = ? ORDER BY edge_id
                """, (rs, row) -> new Edge(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        Header header = headers.get(0);
        return new Layout(Long.toString(id), header.version(), header.updatedAt(), nodes, edges, steps, null,
                includeLatest ? readings.latest(nodes) : null);
    }

    private void checkReference(long id, Node node) {
        String sql = node.kind().equals("EQUIPMENT")
                ? "SELECT workshop_id FROM dim_equipment WHERE equipment_id = ? FOR SHARE"
                : """
                  SELECT e.workshop_id FROM dim_sensor s JOIN dim_equipment e ON e.equipment_id = s.equipment_id
                  WHERE s.sensor_id = ? FOR SHARE
                  """;
        var workshops = jdbc.queryForList(sql, Long.class, businessId(node.objectId()));
        if (workshops.isEmpty() || workshops.get(0) != id) {
            throw new LayoutError(409, "STATE_CONFLICT", "节点引用的对象不存在或不属于该车间。", node);
        }
    }

    private static void validate(Command command) {
        if (command == null || command.requestId() == null ||
                !command.requestId().matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") ||
                !validText(command.operatorName(), 100) ||
                (command.reason() != null && command.reason().length() > 1000) ||
                command.expectedVersion() == null || command.expectedVersion() < 0 ||
                command.nodes() == null || command.edges() == null) {
            throw invalid("请填写有效的请求 ID、操作人、版本、节点和连线，调整原因最长 1000 字。", null);
        }
        Set<String> nodes = new HashSet<>();
        for (Node node : command.nodes()) {
            if (node == null || !validKey(node.nodeId()) || !nodes.add(node.nodeId()) ||
                    !("EQUIPMENT".equals(node.kind()) || "SENSOR".equals(node.kind()))) {
                throw invalid("节点格式无效或重复。", node);
            }
            businessId(node.objectId());
            try {
                coordinate(node.xPercent());
                coordinate(node.yPercent());
            } catch (LayoutError error) {
                throw invalid(error.getMessage(), node);
            }
        }
        Set<String> edges = new HashSet<>();
        for (Edge edge : command.edges()) {
            if (edge == null || !validKey(edge.edgeId()) || !edges.add(edge.edgeId()) ||
                    !nodes.contains(edge.fromNodeId()) || !nodes.contains(edge.toNodeId()) ||
                    edge.fromNodeId().equals(edge.toNodeId()) ||
                    (edge.label() != null && edge.label().length() > 255)) {
                throw invalid("连线格式无效、重复或引用了无效端点。", edge);
            }
        }
    }

    private static String reason(Command command) {
        return command.reason() == null || command.reason().isBlank() ? null : command.reason();
    }

    private static boolean validText(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }

    private static boolean validKey(String value) {
        return validText(value, 128) && value.equals(value.strip());
    }

    private static BigDecimal coordinate(String value) {
        if (value != null && value.matches("-?[0-9]+(\\.[0-9]+)?")) {
            BigDecimal number = new BigDecimal(value).stripTrailingZeros();
            if (number.signum() >= 0 && number.scale() <= 6 && number.compareTo(BigDecimal.valueOf(100)) <= 0) return number;
        }
        throw invalid("坐标必须在 0 到 100 之间，最多保留六位小数。", null);
    }

    private static long businessId(String value) {
        try {
            if (value != null && value.matches("[0-9]+") && Long.parseLong(value) > 0) return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // Report invalid BIGINT IDs through the API envelope.
        }
        throw invalid("业务对象 ID 格式无效。", value);
    }

    private byte[] hash(Command command) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(
                    Arrays.asList(command.operatorName().strip(), command.expectedVersion(), reason(command), command.nodes(), command.edges())));
        } catch (NoSuchAlgorithmException | JsonProcessingException error) {
            throw new IllegalStateException("Cannot hash layout command", error);
        }
    }

    private String encode(Layout layout) {
        try {
            return json.writeValueAsString(layout);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize layout", error);
        }
    }

    private static List<String> processSteps(String code) {
        return switch (code) {
            case "WS-ROD" -> List.of("铜板上料", "熔化", "保温", "结晶水冷", "牵引", "轧制", "冷却清洗", "Φ8 铜杆收卷");
            case "WS-WIRE" -> List.of("铜杆接收", "放线", "多道次冷拉", "在线测径", "成品收卷", "规格检验");
            default -> List.of();
        };
    }

    static LayoutError invalid(String message, Object details) {
        return new LayoutError(400, "VALIDATION_ERROR", message, details);
    }

    static final class LayoutError extends RuntimeException {
        final int status;
        final String code;
        final Object details;

        LayoutError(int status, String code, String message, Object details) {
            super(message);
            this.status = status;
            this.code = code;
            this.details = details;
        }
    }
}
