package cn.copper.phase2.network;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.copper.phase2.network.NetworkService.Latest;
import static cn.copper.phase2.network.NetworkService.Node;

@Repository
class NetworkReadings {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;

    NetworkReadings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Map<String, List<Latest>> latest(List<Node> nodes) {
        Map<String, List<Latest>> readings = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (!node.kind().equals("SENSOR") || readings.containsKey(node.objectId())) continue;
            readings.put(node.objectId(), jdbc.query("""
                    SELECT p.parameter_id, m.latest_value, m.latest_value_text, COALESCE(m.unit_code, p.unit_code),
                           m.latest_collect_time, m.quality_status, m.limit_status, s.sensor_code,
                           p.parameter_code, m.refresh_time, s.data_source, s.enabled AND p.enabled
                    FROM dim_sensor_parameter p JOIN dim_sensor s ON s.sensor_id = p.sensor_id
                    LEFT JOIN ads_parameter_monitor m ON m.sensor_id = p.sensor_id AND m.parameter_id = p.parameter_id
                    WHERE p.sensor_id = ? ORDER BY p.display_order, p.parameter_id
                    """, (rs, row) -> {
                var time = rs.getTimestamp(5);
                var value = rs.getBigDecimal(2);
                var refreshed = rs.getTimestamp(10);
                return new Latest(rs.getString(1), rs.getString(8), rs.getString(9), value == null ? null : value.toPlainString(), rs.getString(3),
                        rs.getString(4), time == null ? null : time.toInstant().atZone(ZONE).toOffsetDateTime().toString(),
                        refreshed == null ? null : refreshed.toInstant().atZone(ZONE).toOffsetDateTime().toString(),
                        rs.getString(6), rs.getString(7), rs.getBoolean(12) ? "UNKNOWN" : "DISABLED", rs.getString(11));
            }, Long.parseLong(node.objectId())));
        }
        return readings;
    }

}
