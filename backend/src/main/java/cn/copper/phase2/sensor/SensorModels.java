package cn.copper.phase2.sensor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 测点、参数与读数（S1-P01/P02/P06/P09/P10/P11）。
 *
 * <p>本批只读：测点身份来自一期 {@code dim_sensor}，扩展资料来自 {@code p2_sensor_profile}；
 * 参数来自 {@code dim_sensor_parameter}，配置版本来自 {@code p2_sensor_parameter_profile}；
 * 读数与历史分别只读 {@code ads_parameter_monitor} 与 {@code ods_sensor_data}。
 * 没有任何一张表需要改结构。</p>
 *
 * <p>几个刻意的边界：</p>
 * <ul>
 *   <li>读数只读不写。最新值是快照，历史是采集事实，二期不补、不改、不重建第二份。</li>
 *   <li>监测状态是查询时现算的，不落库。阈值一变，下次查询就是新结论。</li>
 *   <li>缺读数记为 {@code UNKNOWN}，不等于 {@code STALE}，更不等于设备故障——
 *       文档要求"缺采集值为未知，不直接认定离线或机械故障"。</li>
 * </ul>
 */
public final class SensorModels {
    private SensorModels() {
    }

    /** 有数据且在刷新阈值内。 */
    public static final String MONITORING_ONLINE = "ONLINE";
    /** 有数据，但最后刷新时间早于阈值：数据过期，不代表测点坏了。 */
    public static final String MONITORING_STALE = "STALE";
    /** 从未采到数据，或最新值快照里没有该测点。 */
    public static final String MONITORING_UNKNOWN = "UNKNOWN";
    /** 测点或参数已停用；停用不代表删除历史。 */
    public static final String MONITORING_DISABLED = "DISABLED";

    public static final List<String> MONITORING_STATES =
            List.of(MONITORING_ONLINE, MONITORING_STALE, MONITORING_UNKNOWN, MONITORING_DISABLED);

    /** 有参数且有采集来源，才认为配好了。 */
    public static final String CONFIGURATION_READY = "READY";
    /** 缺参数或缺来源：页面显示待配置，不冒充已对接。 */
    public static final String CONFIGURATION_PENDING = "PENDING";

    /** 规则触发模式，与库上 {@code ck_p2_rule_trigger} 的取值一致。 */
    public static final List<String> TRIGGER_MODES = List.of("SUSTAINED", "IMMEDIATE");

    /**
     * 历史查询允许的最大时间跨度（天）。
     *
     * <p>原始采集表按 {@code (sensor_code, parameter_code, collect_time)} 建索引，
     * 不设上限时一次全表扫会拖垮同库的其他只读接口。这是访问控制，不是业务规则。</p>
     */
    public static final int MAX_HISTORY_RANGE_DAYS = 92;

    /**
     * 测点摘要。
     *
     * <p>{@code parameterCount} 与 {@code monitoringStatus} 都是现算的，
     * 不存冗余列——冗余列会在每次新增参数或停用测点后失真。</p>
     */
    public record SensorSummary(
            String sensorId, String sensorCode, String name,
            String equipmentId, String equipmentCode, String equipmentName,
            String workshopId, String workshopName,
            String location, String sensorType, String dataSource,
            boolean enabled, long parameterCount,
            String monitoringStatus, String configurationState) {
    }

    /**
     * 测点详情。
     *
     * <p>字段是摘要的超集，{@code parameterCount} 也保留：页面用同一套解析逻辑处理列表与详情，
     * 少一个字段就会在前端被判成"响应不完整"。{@code parameters} 是摘要数组，
     * 明细配置（单位、精度、规则全文）走 P06/P08。</p>
     *
     * <p>{@code purpose} 是拟定字段：可以是人工确认的用途，也可以是技术资料里的说法；
     * 没依据时给 {@code null}，不用参数名反推一个看似合理的用途。</p>
     *
     * <p>{@code version} 在没有扩展档案行时为 0，与设备档案"首存用版本 0"的口径一致。</p>
     */
    public record SensorDetail(
            String sensorId, String sensorCode, String name,
            String equipmentId, String equipmentCode, String equipmentName,
            String workshopId, String workshopName,
            String sensorType, String dataSource, String location, String purpose,
            boolean enabled, long parameterCount, boolean hasHistory,
            String monitoringStatus, String configurationState,
            long version, List<ParameterSummary> parameters) {
    }

    /**
     * 参数摘要。
     *
     * <p>{@code ruleVersionId} 指向最新一版规则快照，{@code ruleConfigured} 表示这一版是否启用。
     * 两者分开是因为"配过但停用"和"从未配过"在页面上是两种状态。</p>
     */
    public record ParameterSummary(
            String parameterId, String parameterCode, String name, String dataType,
            String unitCode, int decimalPlaces, Integer samplingIntervalSeconds,
            String processId, boolean enabled,
            String ruleVersionId, boolean ruleConfigured) {
    }

    /**
     * 参数最新读数。
     *
     * <p>数值用字符串而不是 double：{@code DECIMAL(20,6)} 转 double 会丢精度，
     * 而测点值常常要原样回显给现场核对。</p>
     *
     * <p>{@code missingFields} 只报本次真正缺的东西，页面据此提示"待采集"或"单位未配"。</p>
     */
    public record LatestReading(
            String parameterId, String sensorId, String sensorCode, String parameterCode,
            String value, String valueText, String unitCode,
            String qualityStatus, String limitStatus,
            OffsetDateTime collectTime, OffsetDateTime refreshTime,
            String monitoringStatus, String dataSource, List<String> missingFields) {
    }

    /** 一条原始采集记录。只读，不给改写入口。 */
    public record HistoryRecord(
            String recordId, String value, String valueText,
            String sourceUnit, String qualityCode,
            OffsetDateTime collectTime, String sourceSystem) {
    }

    /**
     * 测点写入请求（P03/P04 共用）。
     *
     * <p>可空字段（{@code sensorType/location/purpose/reason}）同样要求显式出现：
     * 传 {@code null} 表示清空，漏传即校验失败，避免"漏传被当成清空"。</p>
     *
     * <p>这里不含 {@code dataSource}：采集来源由对接过程产生，页面不能凭一次表单提交就声称
     * "已接 PLC"。新建测点因此一律是 {@code PENDING}，直到采集映射真实建立。</p>
     */
    public record SensorWriteRequest(
            String requestId, String operatorName, Long expectedVersion, String reason,
            String sensorCode, String name, Long equipmentId,
            String sensorType, String location, String purpose, boolean enabled) {
    }

    /**
     * 一版规则快照。
     *
     * <p>边界值用十进制字符串，{@code null} 表示该侧不设限；{@code IMMEDIATE} 时
     * {@code durationSeconds} 必须为空，否则库上的 CHECK 会拒绝。</p>
     */
    public record RuleSnapshot(
            String normalLower, String normalUpper, String alarmLower, String alarmUpper,
            String unitCode, String triggerMode, Integer durationSeconds, boolean enabled) {
    }

    /**
     * 规则版本行。
     *
     * <p>{@code affectedEventIds} 是被这一版规则"接手时"结束掉的活动事件（结束原因
     * {@code RULE_CHANGED}）。旧版本不可覆盖，历史事件永远指向它当时依据的那一版。</p>
     */
    public record RuleVersionView(
            String ruleVersionId, long ruleVersion, String previousRuleVersionId,
            RuleSnapshot rule, OffsetDateTime effectiveAt,
            String reason, String operatorName, List<String> affectedEventIds) {
    }
}
