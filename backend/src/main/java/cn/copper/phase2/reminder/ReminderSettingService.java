package cn.copper.phase2.reminder;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.reminder.ReminderSettingModels.ReminderSetting;
import cn.copper.phase2.reminder.ReminderSettingModels.ReminderSettingUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S1-T02 / S1-T03 期限提醒设置。
 *
 * <p>配置是全系统共用的一份（质保提前量、维保提前量），因此用单行 + 版本号保护，
 * 两个管理员同时保存时后提交者拿到 409 而不是静默覆盖。</p>
 *
 * <p>这里只存"提前多少天"这一事实，不参与期限计算；T01 的期限列表按这里的值判定 DUE_SOON。
 * 保存设置不会触发任何通知发送。</p>
 */
@Service
public class ReminderSettingService {
    private static final String OBJECT_TYPE = "REMINDER_SETTING";
    private static final String OBJECT_ID = "1";
    private static final int REASON_MAX = 1000;

    private static final List<String> REQUIRED = List.of(
            "requestId", "operatorName", "expectedVersion", "warrantyLeadDays", "maintenanceLeadDays");

    private final ReminderSettingRepository repository;
    private final ChangeLogRepository changeLog;
    private final IdempotencyService idempotency;

    public ReminderSettingService(ReminderSettingRepository repository, ChangeLogRepository changeLog,
                                  IdempotencyService idempotency) {
        this.repository = repository;
        this.changeLog = changeLog;
        this.idempotency = idempotency;
    }

    /** S1-T02：读取当前设置。默认行由 V8 迁移写入，这里对未迁移的库做一次幂等补齐。 */
    public ReminderSetting current() {
        return repository.find().orElseGet(() -> {
            repository.insertDefault();
            return repository.find().orElseThrow(
                    () -> new IllegalStateException("提醒设置初始化失败，请检查 V8 迁移是否执行。"));
        });
    }

    /** S1-T03：更新两个提前量，并保留变更留痕。 */
    public ReminderSetting update(JsonNode body) {
        ReminderSettingUpdate request = parse(body);
        return idempotency.execute(request.requestId(), "REMINDER_SETTING_UPDATE", request,
                ReminderSetting.class, () -> doUpdate(request));
    }

    private ReminderSetting doUpdate(ReminderSettingUpdate request) {
        current();   // 保证行存在，避免读到空表时的 404 噪音
        ReminderSetting before = repository.lock().orElseThrow(
                () -> new IllegalStateException("提醒设置行缺失，无法更新。"));

        if (before.version() != request.expectedVersion()) {
            throw BusinessException.versionConflict(request.expectedVersion(), before.version());
        }
        if (repository.update(request.warrantyLeadDays(), request.maintenanceLeadDays(),
                request.expectedVersion()) == 0) {
            throw BusinessException.versionConflict(request.expectedVersion(), before.version());
        }

        ReminderSetting after = repository.find().orElseThrow(
                () -> new IllegalStateException("提醒设置更新后无法读回。"));
        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, OBJECT_ID, "UPDATE",
                snapshot(before), snapshot(after), request.reason(), request.operatorName(),
                toVersion(before.version()), toVersion(after.version()), request.requestId()));
        return after;
    }

    /** 严格解析：五个字段必须出现；两个天数必须是非负整数。 */
    private static ReminderSettingUpdate parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw BusinessException.invalid("body", "请求体必须是 JSON 对象。");
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        for (String field : REQUIRED) {
            if (!body.has(field)) {
                missing.put(field, "该字段必须出现。");
            }
        }
        if (!missing.isEmpty()) {
            throw BusinessException.invalid(missing);
        }

        Map<String, Object> errors = new LinkedHashMap<>();
        String requestId = text(body.get("requestId"));
        String operatorName = text(body.get("operatorName"));
        String reason = text(body.get("reason"));
        Long expectedVersion = number(body.get("expectedVersion"));
        Integer warrantyLeadDays = nonNegativeInt(body.get("warrantyLeadDays"), "warrantyLeadDays", errors);
        Integer maintenanceLeadDays = nonNegativeInt(body.get("maintenanceLeadDays"), "maintenanceLeadDays", errors);

        if (requestId == null) {
            errors.put("requestId", "请求编号为必填项。");
        }
        if (operatorName == null) {
            errors.put("operatorName", "操作人为必填项。");
        }
        if (reason != null && reason.length() > REASON_MAX) {
            errors.put("reason", "补充说明不得超过 " + REASON_MAX + " 个字符。");
        }
        if (expectedVersion == null || expectedVersion < 0) {
            errors.put("expectedVersion", "更新时必须提供非负的当前版本号。");
        }
        if (!errors.isEmpty()) {
            throw BusinessException.invalid(errors);
        }
        return new ReminderSettingUpdate(requestId, operatorName, expectedVersion, reason,
                warrantyLeadDays, maintenanceLeadDays);
    }

    private static Integer nonNegativeInt(JsonNode node, String field, Map<String, Object> errors) {
        Long value = number(node);
        if (value == null || value < 0 || value > Integer.MAX_VALUE) {
            errors.put(field, "必须是非负整数。");
            return null;
        }
        return value.intValue();
    }

    private static Map<String, Object> snapshot(ReminderSetting setting) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("warrantyLeadDays", setting.warrantyLeadDays());
        snapshot.put("maintenanceLeadDays", setting.maintenanceLeadDays());
        return snapshot;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static Long number(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer toVersion(long version) {
        return version > Integer.MAX_VALUE ? null : Math.toIntExact(version);
    }
}
