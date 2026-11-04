package cn.copper.phase2.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.copper.phase2.network.NetworkService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc
class MySqlNetworkLayoutIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void persistsIsolatedLayoutsAndRejectsInvalidOrConcurrentWrites() throws Exception {
        assertEquals("MySQL", jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName()));
        String suffix = UUID.randomUUID().toString();
        String first = workshop("network-a-" + suffix);
        String second = workshop("network-b-" + suffix);
        List<String> requests = new java.util.ArrayList<>();
        try {
            String equipment = equipment(first, "network-a-" + suffix);
            String foreign = equipment(second, "network-b-" + suffix);
            String sensorCode = "network-s-" + suffix;
            jdbc.update("INSERT INTO dim_sensor(sensor_code, sensor_name, equipment_id) VALUES (?, '测试测点', ?)",
                    sensorCode, Long.parseLong(equipment));
            String sensor = jdbc.queryForObject("SELECT sensor_id FROM dim_sensor WHERE sensor_code = ?", String.class, sensorCode);
            jdbc.update("""
                    INSERT INTO dim_sensor_parameter(sensor_id, parameter_code, parameter_name, data_type)
                    VALUES (?, 'A', '有读数', 'DECIMAL'), (?, 'B', '无读数', 'DECIMAL')
                    """, Long.parseLong(sensor), Long.parseLong(sensor));
            jdbc.update("""
                    INSERT INTO ads_parameter_monitor(sensor_id, parameter_id, workshop_id, equipment_id, latest_value,
                        quality_status, limit_status, latest_collect_time, refresh_time)
                    SELECT sensor_id, parameter_id, ?, ?, 12.5, 'GOOD', 'NORMAL', NOW(), NOW()
                    FROM dim_sensor_parameter WHERE sensor_id = ? AND parameter_code = 'A'
                    """, Long.parseLong(first), Long.parseLong(equipment), Long.parseLong(sensor));

            assertEquals(0, layout(first, false).path("version").asLong());
            assertEquals(0, layout(second, false).path("version").asLong());
            Node a = new Node("device", "EQUIPMENT", equipment, "0", "100");
            Node b = new Node("sensor", "SENSOR", sensor, "20.123456", "32");
            Edge edge = new Edge("link", "device", "sensor", "示意连线");
            Node repeatedDevice = new Node("z-device", "EQUIPMENT", equipment, "60", "50");
            Node repeatedSensor = new Node("z-sensor", "SENSOR", sensor, "70", "50");
            Command prepared = command(requests, 0, List.of(a, b, repeatedDevice, repeatedSensor), List.of(edge));
            Command initial = new Command(prepared.requestId(), prepared.operatorName(), 0L, null,
                    prepared.nodes(), prepared.edges());
            JsonNode firstResult = send(first, initial, 200, "OK");
            ObjectNode withoutReason = json.valueToTree(initial);
            withoutReason.remove("reason");
            var omitted = mvc.perform(put("/api/v1/network-layouts/{id}", first).contentType("application/json")
                    .content(json.writeValueAsBytes(withoutReason))).andReturn().getResponse();
            assertEquals(200, omitted.getStatus(), omitted.getContentAsString());
            assertEquals(firstResult, json.readTree(omitted.getContentAsByteArray()).path("data"));
            assertEquals(1, auditCount(first));
            var sameId = new Command(initial.requestId(), initial.operatorName(), 0L, "改了内容", initial.nodes(), initial.edges());
            send(first, sameId, 409, "IDEMPOTENCY_CONFLICT");

            assertNull(jdbc.queryForObject("SELECT reason FROM p2_change_log WHERE change_log_id = ?",
                    String.class, firstResult.path("changeLogId").asLong()));

            var saved = layout(first, true);
            assertEquals(4, saved.path("nodes").size());
            assertEquals(equipment, saved.path("nodes").get(2).path("objectId").asText());
            assertEquals(sensor, saved.path("nodes").get(3).path("objectId").asText());
            assertEquals(1, saved.path("latestBySensorId").size());
            assertEquals("20.123456", saved.path("nodes").get(1).path("xPercent").asText());
            assertEquals("示意连线", saved.path("edges").get(0).path("label").asText());
            var readings = saved.path("latestBySensorId").path(sensor);
            assertEquals(2, readings.size());
            assertEquals(0, new java.math.BigDecimal(readings.get(0).path("value").asText()).compareTo(new java.math.BigDecimal("12.5")));
            assertTrue(readings.get(1).path("value").isNull());
            assertEquals("UNKNOWN", readings.get(1).path("monitoringStatus").asText());

            send(second, command(requests, 0, List.of(new Node("other", "EQUIPMENT", foreign, "50", "50")), List.of()), 200, "OK");
            var other = layout(second, false);
            assertEquals(foreign, other.path("nodes").get(0).path("objectId").asText());
            send(first, command(requests, 1, List.of(new Node("bad", "EQUIPMENT", equipment, "100.1", "0")), List.of()),
                    400, "VALIDATION_ERROR");
            send(first, command(requests, 1, List.of(a), List.of(edge)), 400, "VALIDATION_ERROR");
            send(first, command(requests, 1, List.of(new Node("foreign", "EQUIPMENT", foreign, "10", "10")), List.of()),
                    409, "STATE_CONFLICT");
            send(first, command(requests, 1, List.of(a, a), List.of()), 400, "VALIDATION_ERROR");
            assertEquals(saved, layout(first, true));
            assertEquals(1, auditCount(first));

            Command left = command(requests, 1, List.of(a), List.of());
            Command right = command(requests, 1, List.of(b), List.of());
            var start = new CyclicBarrier(2);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var one = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return exchange(first, left); });
                var two = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return exchange(first, right); });
                var aResult = one.get(15, TimeUnit.SECONDS);
                var bResult = two.get(15, TimeUnit.SECONDS);
                assertEquals(java.util.Set.of(200, 409), java.util.Set.of(aResult.status(), bResult.status()));
                var loser = aResult.status() == 409 ? aResult : bResult;
                assertEquals("VERSION_CONFLICT", loser.body().path("code").asText());
                assertEquals(2, loser.body().path("data").path("currentVersion").asInt());
            } finally {
                pool.shutdownNow();
            }
            assertEquals(2, layout(first, false).path("version").asLong());
            assertEquals(2, auditCount(first));
            assertEquals(firstResult, send(first, initial, 200, "OK"));
            assertEquals(2, auditCount(first));
            assertEquals(other, layout(second, false));
            assertEquals(equipment, jdbc.queryForObject("SELECT equipment_id FROM dim_sensor WHERE sensor_id = ?",
                    String.class, Long.parseLong(sensor)));
        } finally {
            for (String request : requests) jdbc.update("DELETE FROM p2_request_result WHERE request_id = ?", request);
            cleanup(first);
            cleanup(second);
        }
    }

    private String workshop(String code) {
        jdbc.update("INSERT INTO dim_workshop(workshop_code, workshop_name) VALUES (?, '组网验收车间')", code);
        return jdbc.queryForObject("SELECT workshop_id FROM dim_workshop WHERE workshop_code = ?", String.class, code);
    }

    private String equipment(String workshop, String code) {
        jdbc.update("INSERT INTO dim_equipment(equipment_code, equipment_name, workshop_id) VALUES (?, '测试设备', ?)",
                code, Long.parseLong(workshop));
        return jdbc.queryForObject("SELECT equipment_id FROM dim_equipment WHERE equipment_code = ?", String.class, code);
    }

    private void cleanup(String workshop) {
        long id = Long.parseLong(workshop);
        jdbc.update("DELETE FROM p2_network_edge WHERE workshop_id = ?", id);
        jdbc.update("DELETE FROM p2_network_node WHERE workshop_id = ?", id);
        jdbc.update("DELETE FROM p2_network_layout WHERE workshop_id = ?", id);
        jdbc.update("DELETE FROM p2_change_log WHERE object_type = 'NETWORK_LAYOUT' AND object_id = ?", workshop);
        jdbc.update("DELETE FROM ads_parameter_monitor WHERE workshop_id = ?", id);
        jdbc.update("""
                DELETE p FROM dim_sensor_parameter p JOIN dim_sensor s ON s.sensor_id = p.sensor_id
                JOIN dim_equipment e ON e.equipment_id = s.equipment_id WHERE e.workshop_id = ?
                """, id);
        jdbc.update("DELETE s FROM dim_sensor s JOIN dim_equipment e ON e.equipment_id = s.equipment_id WHERE e.workshop_id = ?", id);
        jdbc.update("DELETE FROM dim_equipment WHERE workshop_id = ?", id);
        jdbc.update("DELETE FROM dim_workshop WHERE workshop_id = ?", id);
    }

    private JsonNode layout(String id, boolean latest) throws Exception {
        var result = mvc.perform(get("/api/v1/network-layouts/{id}", id).param("includeLatest", String.valueOf(latest)))
                .andReturn().getResponse();
        assertEquals(200, result.getStatus(), result.getContentAsString());
        return json.readTree(result.getContentAsByteArray()).path("data");
    }

    private JsonNode send(String id, Command command, int status, String code) throws Exception {
        Exchange result = exchange(id, command);
        assertEquals(status, result.status(), result.body().toString());
        assertEquals(code, result.body().path("code").asText());
        return result.body().path("data");
    }

    private Exchange exchange(String id, Command command) throws Exception {
        var result = mvc.perform(put("/api/v1/network-layouts/{id}", id).contentType("application/json")
                        .content(json.writeValueAsBytes(command))).andReturn().getResponse();
        return new Exchange(result.getStatus(), json.readTree(result.getContentAsByteArray()));
    }

    private int auditCount(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM p2_change_log WHERE object_type = 'NETWORK_LAYOUT' AND object_id = ?",
                Integer.class, id);
    }

    private Command command(List<String> requests, long version, List<Node> nodes, List<Edge> edges) {
        String id = UUID.randomUUID().toString();
        requests.add(id);
        return new Command(id, "测试操作员", version, "组网集成验收", nodes, edges);
    }

    private record Exchange(int status, JsonNode body) {}
}
