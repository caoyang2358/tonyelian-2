package cn.copper.phase2.sensor;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.sensor.SensorModels.ParameterDetail;
import cn.copper.phase2.sensor.SensorModels.ParameterSummary;
import cn.copper.phase2.sensor.SensorModels.SensorDetail;
import cn.copper.phase2.sensor.SensorModels.SensorSummary;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测点接口 S1-P01—P07。
 *
 * <p>读的一侧只取不改：测点身份沿用一期 {@code dim_sensor}，扩展位置资料在
 * {@code p2_sensor_profile}。写的一侧（P03/P04）只写这两张表的约定字段，
 * 不写采集来源；参数定义走 P07，写参数身份与首版规则。</p>
 *
 * <p>P05 删除是物理删除，但只在六类关联全部为空时才允许：原始采集记录、预警事件、
 * 监测统计、能耗统计、下级测点、改过规则的参数。有采集记录的编码因此永久冻结，
 * 停用仍是常态化退出方式，删除只用于纠正误建。</p>
 */
@RestController
@RequestMapping("/api/v1/sensors")
public class SensorController {
    private final SensorService service;
    private final SensorWriteService writeService;
    private final SensorParameterWriteService parameterWriteService;
    private final SensorDeleteService deleteService;

    public SensorController(SensorService service, SensorWriteService writeService,
                            SensorParameterWriteService parameterWriteService,
                            SensorDeleteService deleteService) {
        this.service = service;
        this.writeService = writeService;
        this.parameterWriteService = parameterWriteService;
        this.deleteService = deleteService;
    }

    /** S1-P01：按设备、关键词、启停用、监测状态筛选，分页返回测点摘要。 */
    @GetMapping
    public ApiResponse<PageResult<SensorSummary>> sensors(
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String monitoringStatus,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(
                service.search(equipmentId, keyword, enabled, monitoringStatus, page, pageSize));
    }

    /** S1-P02：测点详情，附带参数摘要，可供组网点选面板复用。 */
    @GetMapping("/{sensorId}")
    public ApiResponse<SensorDetail> sensor(@PathVariable String sensorId) {
        return ApiResponse.success(service.detail(sensorId));
    }

    /** S1-P06：一个测点的参数定义，一测点可有多个参数。 */
    @GetMapping("/{sensorId}/parameters")
    public ApiResponse<PageResult<ParameterSummary>> parameters(
            @PathVariable String sensorId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(service.parameters(sensorId, page, pageSize));
    }

    /** S1-P07：新增参数定义，可选同时带首版规则；同测点内参数编码唯一。 */
    @PostMapping("/{sensorId}/parameters")
    public ApiResponse<ParameterDetail> createParameter(@PathVariable String sensorId,
                                                        @RequestBody JsonNode body) {
        return ApiResponse.success(parameterWriteService.create(sensorId, body));
    }

    /** S1-P03：新增测点，返回与详情同结构，{@code version=1}。 */
    @PostMapping
    public ApiResponse<SensorDetail> create(@RequestBody JsonNode body) {
        return ApiResponse.success(writeService.create(body));
    }

    /** S1-P04：修改测点资料与启停用；有采集记录时编码不可改。 */
    @PutMapping("/{sensorId}")
    public ApiResponse<SensorDetail> update(@PathVariable String sensorId,
                                            @RequestBody JsonNode body) {
        return ApiResponse.success(writeService.update(sensorId, body));
    }

    /** S1-P05：删除误建测点；任一类关联仍有数据时返回 409 与 blockers。 */
    @DeleteMapping("/{sensorId}")
    public ApiResponse<SensorDeleteService.SensorDeleteResult> delete(@PathVariable String sensorId,
                                                                      @RequestBody JsonNode body) {
        return ApiResponse.success(deleteService.delete(sensorId, body));
    }
}
