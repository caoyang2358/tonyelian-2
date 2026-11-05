package cn.copper.phase2.deadline;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.deadline.DeadlineModels.DeadlineItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 期限与提醒查询 S1-T01。
 *
 * <p>同时挂在设备管理与运维管理两个页面的"期限"入口下，是共用只读接口，
 * 不做任何写操作——提醒设置的维护在 S1-T02/T03。</p>
 */
@RestController
@RequestMapping("/api/v1/equipment-deadlines")
public class DeadlineController {
    private final DeadlineService service;

    public DeadlineController(DeadlineService service) {
        this.service = service;
    }

    /** S1-T01：按设备、车间、期限类型、计算状态筛选，分页返回设备与期限来源的身份。 */
    @GetMapping
    public ApiResponse<PageResult<DeadlineItem>> deadlines(
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String workshopId,
            @RequestParam(required = false) String reminderType,
            @RequestParam(required = false) String expiryState,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(
                service.search(equipmentId, workshopId, reminderType, expiryState, page, pageSize));
    }
}
