package cn.copper.phase2.sensor;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.sensor.SensorModels.HistoryRecord;
import cn.copper.phase2.sensor.SensorModels.LatestReading;
import cn.copper.phase2.sensor.SensorModels.ParameterConfigResult;
import cn.copper.phase2.sensor.SensorModels.RuleVersionView;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 参数读数、规则快照与配置更新 S1-P08/P09/P10/P11。
 *
 * <p>读的三条都明确"不改采集事实"：最新值读 {@code ads_parameter_monitor}，
 * 历史读 {@code ods_sensor_data}，规则读 {@code p2_alert_rule_version}。
 * 写的一条只改参数配置与规则版本，不改参数编码、数据类型，也不写任何一条读数。</p>
 */
@RestController
@RequestMapping("/api/v1/sensor-parameters")
public class SensorParameterController {
    private final SensorService service;
    private final SensorParameterWriteService writeService;

    public SensorParameterController(SensorService service,
                                     SensorParameterWriteService writeService) {
        this.service = service;
        this.writeService = writeService;
    }

    /**
     * S1-P08：一次保存参数配置与规则。
     *
     * <p>判断依据变化才追加规则版本，没变化只推进配置版本。判断依据变化时，在同一事务里把
     * 该参数仍挂着的活动事件按 {@code RULE_CHANGED} 结束，并把事件号回填到
     * {@code affectedEventIds}（同一参数至多一条活动事件，所以是 0 或 1 条）。结束动作由
     * {@code AlertEventTerminator} 承载，当前实现直接写 {@code p2_alert_event}；事件的生成与
     * 人工处理仍属异常服务，等它提供接口后只替换实现，本流程不动。</p>
     */
    @PutMapping("/{parameterId}/configuration")
    public ApiResponse<ParameterConfigResult> updateConfiguration(
            @PathVariable String parameterId,
            @RequestBody JsonNode body) {
        return ApiResponse.success(writeService.updateConfiguration(parameterId, body));
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
