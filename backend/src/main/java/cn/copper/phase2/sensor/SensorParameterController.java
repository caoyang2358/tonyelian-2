package cn.copper.phase2.sensor;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.sensor.SensorModels.HistoryRecord;
import cn.copper.phase2.sensor.SensorModels.LatestReading;
import cn.copper.phase2.sensor.SensorModels.RuleVersionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 参数读数与规则快照 S1-P09/P10/P11。
 *
 * <p>三条都是只读，且都明确"不改采集事实"：最新值读 {@code ads_parameter_monitor}，
 * 历史读 {@code ods_sensor_data}，规则读 {@code p2_alert_rule_version}。
 * 这里不会写入、改值，也不会重建第二份采集记录。</p>
 */
@RestController
@RequestMapping("/api/v1/sensor-parameters")
public class SensorParameterController {
    private final SensorService service;

    public SensorParameterController(SensorService service) {
        this.service = service;
    }

    /** S1-P09：最新读数与采集状态；未采到数据时返回缺值状态而不是 404。 */
    @GetMapping("/{parameterId}/latest")
    public ApiResponse<LatestReading> latest(@PathVariable String parameterId) {
        return ApiResponse.success(service.latest(parameterId));
    }

    /** S1-P10：原始历史读数，时间区间与页长都受控。 */
    @GetMapping("/{parameterId}/history")
    public ApiResponse<PageResult<HistoryRecord>> history(
            @PathVariable String parameterId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(service.history(parameterId, from, to, page, pageSize));
    }

    /** S1-P11：规则版本历史，旧版本不可覆盖，附各版接手时结束的事件。 */
    @GetMapping("/{parameterId}/rule-versions")
    public ApiResponse<PageResult<RuleVersionView>> ruleVersions(
            @PathVariable String parameterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(service.ruleVersions(parameterId, page, pageSize));
    }
}
