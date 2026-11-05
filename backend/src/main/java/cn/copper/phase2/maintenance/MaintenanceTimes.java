package cn.copper.phase2.maintenance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 运维模块共用的时间处理：数据库存 Asia/Shanghai 的 DATETIME(3)，对外返回带偏移的 ISO 8601。
 */
final class MaintenanceTimes {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private MaintenanceTimes() {}

    static OffsetDateTime toOffset(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
    }

    static OffsetDateTime parseMoment(String field, String text) {
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException error) {
            throw new InvalidRequestException(field,
                    "时间格式应为带时区偏移的 ISO 8601，例如 2026-11-03T08:00:00+08:00。");
        }
    }

    static LocalDateTime toLocal(OffsetDateTime moment) {
        return moment.atZoneSameInstant(ZONE).toLocalDateTime();
    }
}
