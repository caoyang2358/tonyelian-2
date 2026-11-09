package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventDetail;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventPage;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventQuery;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingCommand;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingRecordPage;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维管理 · 提醒页签接口：S1-A01 列表、S1-A02 详情、S1-A03 人工处理、S1-A04 处理历史。
 */
@RestController
@RequestMapping("/api/v1/alert-events")
public class AlertEventController {
    private final AlertEventService service;

    public AlertEventController(AlertEventService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AlertEventPage> alertEvents(
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String sensorId,
            @RequestParam(required = false) String parameterId,
            @RequestParam(required = false) String eventState,
            @RequestParam(required = false) String handlingState,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        AlertEventQuery query = new AlertEventQuery(
                equipmentId, sensorId, parameterId, eventState, handlingState, from, to, sort);
        return ApiResponse.success(service.events(query, page, pageSize));
    }

    @GetMapping("/{eventId}")
    public ApiResponse<AlertEventDetail> alertEvent(@PathVariable String eventId) {
        return ApiResponse.success(service.event(eventId));
    }

    @GetMapping("/{eventId}/handling-records")
    public ApiResponse<HandlingRecordPage> handlingRecords(
            @PathVariable String eventId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(service.handlingRecords(eventId, page, pageSize));
    }

    @PostMapping("/{eventId}/handling-records")
    public ApiResponse<HandlingResult> handle(@PathVariable String eventId,
                                              @RequestBody HandlingCommand command) {
        return ApiResponse.success(service.handle(eventId, command));
    }
}
