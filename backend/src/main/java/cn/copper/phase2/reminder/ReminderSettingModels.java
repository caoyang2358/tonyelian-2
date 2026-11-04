package cn.copper.phase2.reminder;

import java.time.OffsetDateTime;

/**
 * 期限提醒设置（S1-T02 / S1-T03）。
 *
 * <p>单行配置：质保与维保各有一个提前提醒天数，默认各 30 天。
 * 设置只影响"即将到期"的判定，不改变设备管理状态，也不触发任何短信或邮件。</p>
 */
public final class ReminderSettingModels {
    private ReminderSettingModels() {
    }

    /** 当前设置；{@code version} 供更新时做乐观锁。 */
    public record ReminderSetting(int warrantyLeadDays, int maintenanceLeadDays,
                                  long version, OffsetDateTime updatedAt) {}

    /** 更新请求：U 字段 + 两个天数，天数必须是非负整数。 */
    public record ReminderSettingUpdate(String requestId, String operatorName, long expectedVersion,
                                        String reason, int warrantyLeadDays,
                                        int maintenanceLeadDays) {}
}
