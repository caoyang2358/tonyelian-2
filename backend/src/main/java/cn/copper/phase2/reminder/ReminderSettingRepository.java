package cn.copper.phase2.reminder;

import cn.copper.phase2.reminder.ReminderSettingModels.ReminderSetting;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.Optional;

/**
 * p2_reminder_setting 的读写。表上有 {@code CHECK (setting_id = 1)}，
 * 全表只有一行，所有语句都固定按 setting_id = 1 操作。
 */
@Repository
public class ReminderSettingRepository {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long SETTING_ID = 1L;

    static final String SELECT_SQL = """
            SELECT warranty_lead_days, maintenance_lead_days, version, updated_at
            FROM p2_reminder_setting WHERE setting_id = ?
            """;

    static final String SELECT_FOR_UPDATE_SQL = SELECT_SQL + " FOR UPDATE";

    /** 补默认行：V8 迁移已经写过一次，这里保留幂等写法以应对迁移未执行的库。 */
    static final String INSERT_DEFAULT_SQL = """
            INSERT INTO p2_reminder_setting
                (setting_id, warranty_lead_days, maintenance_lead_days, version, updated_at)
            SELECT ?, 30, 30, 1, NOW(3) FROM DUAL
            WHERE NOT EXISTS (SELECT 1 FROM p2_reminder_setting WHERE setting_id = ?)
            """;

    static final String UPDATE_SQL = """
            UPDATE p2_reminder_setting
               SET warranty_lead_days = ?, maintenance_lead_days = ?,
                   version = version + 1, updated_at = NOW(3)
             WHERE setting_id = ? AND version = ?
            """;

    static final RowMapper<ReminderSetting> MAPPER = (rs, row) -> {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ReminderSetting(
                rs.getInt("warranty_lead_days"),
                rs.getInt("maintenance_lead_days"),
                rs.getLong("version"),
                updatedAt == null ? null : updatedAt.toLocalDateTime().atZone(ZONE).toOffsetDateTime());
    };

    private final JdbcTemplate jdbc;

    public ReminderSettingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ReminderSetting> find() {
        return jdbc.query(SELECT_SQL, MAPPER, SETTING_ID).stream().findFirst();
    }

    public Optional<ReminderSetting> lock() {
        return jdbc.query(SELECT_FOR_UPDATE_SQL, MAPPER, SETTING_ID).stream().findFirst();
    }

    public void insertDefault() {
        jdbc.update(INSERT_DEFAULT_SQL, SETTING_ID, SETTING_ID);
    }

    /** 按版本条件更新；返回 0 表示版本已被推进。 */
    public int update(int warrantyLeadDays, int maintenanceLeadDays, long expectedVersion) {
        return jdbc.update(UPDATE_SQL, warrantyLeadDays, maintenanceLeadDays,
                SETTING_ID, expectedVersion);
    }
}
