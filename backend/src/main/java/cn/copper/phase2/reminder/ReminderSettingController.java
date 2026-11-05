package cn.copper.phase2.reminder;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.reminder.ReminderSettingModels.ReminderSetting;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 期限提醒设置 S1-T02 / S1-T03。
 *
 * <p>这是共用接口：设备管理的期限页签与运维管理的提醒配置入口复用同一份设置，
 * 因此路径放在顶层而不挂在某个页面的子路径下。</p>
 */
@RestController
@RequestMapping("/api/v1/reminder-settings")
public class ReminderSettingController {
    private final ReminderSettingService service;

    public ReminderSettingController(ReminderSettingService service) {
        this.service = service;
    }

    /** S1-T02：读取质保、维保两个提前量。 */
    @GetMapping
    public ApiResponse<ReminderSetting> current() {
        return ApiResponse.success(service.current());
    }

    /** S1-T03：分别调整两个提前量，需携带当前版本号。 */
    @PutMapping
    public ApiResponse<ReminderSetting> update(@RequestBody JsonNode body) {
        return ApiResponse.success(service.update(body));
    }
}
