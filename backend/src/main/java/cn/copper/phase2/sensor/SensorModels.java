package cn.copper.phase2.sensor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 测点、参数与读数（S1-P01/P02/P03/P04/P06/P07/P09/P10/P11）。
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
     *
     * <p>{@code deletion} 与 P05 删除接口共用同一份关联检查，页面据此置灰删除入口；
     * 不做成"点了才知道不行"。与 E02 设备详情同构。</p>
     */
    public record SensorDetail(
            String sensorId, String sensorCode, String name,
            String equipmentId, String equipmentCode, String equipmentName,
            String workshopId, String workshopName,
            String sensorType, String dataSource, String location, String purpose,
            boolean enabled, long parameterCount, boolean hasHistory,
            String monitoringStatus, String configurationState,
            long version, List<ParameterSummary> parameters, DeletionState deletion) {
    }

    /**
     * 删除可行性：{@code allowed} 为 false 时 {@code blockers} 非空，说明为什么不能删。
     *
     * <p>与 E02 同名结构保持字段一致，前端可以复用同一套渲染。</p>
     */
    public record DeletionState(boolean allowed, List<DeletionBlocker> blockers) {
    }

    /** 一类阻塞性关联：{@code type} 是稳定类型码，{@code message} 直接可展示。 */
    public record DeletionBlocker(String type, long count, String message) {
    }

    /**
     * 参数摘要。
     *
     * <p>{@code ruleVersionId} 指向最新一版规则快照，{@code ruleConfigured} 表示这一版是否启用。
     * 两者分开是因为"配过但停用"和"从未配过"在页面上是两种状态。</p>
     *
     * <p>{@code rule} 与 {@code version} 是配置更新（P08）需要的两个值：没有 {@code version}
     * 就无法做乐观锁，没有 {@code rule} 就只能再查一次规则版本列表。契约里没有"单参数详情"
     * 接口，参数页面能拿到全量配置的唯一来源就是这里，因此摘要带全。</p>
     */
    public record ParameterSummary(
            String parameterId, String parameterCode, String name, String dataType,
            String unitCode, int decimalPlaces, Integer samplingIntervalSeconds,
            String processId, boolean enabled,
            String ruleVersionId, boolean ruleConfigured,
            RuleSnapshot rule, long version) {
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
     * 参数写入请求（S1-P07）。
     *
     * <p>可空字段同样要求显式出现：传 {@code null} 表示不设该项，漏传即校验失败。
     * {@code rule} 为 {@code null} 时不产生规则版本行，参数处于"未配规则"状态，
     * 与"配了规则但停用"（{@code rule.enabled=false}）是两回事。</p>
     *
     * <p>{@code decimalPlaces} 给 {@code null} 时按库默认的 2 位小数落库，
     * 而不是让 NOT NULL 列在插入时才报错。</p>
     */
    public record ParameterWriteRequest(
            String requestId, String operatorName, String reason,
            String parameterCode, String name, String dataType,
            String unitCode, Integer decimalPlaces, Integer samplingIntervalSeconds,
            Long processId, boolean enabled, RuleSnapshot rule) {
    }

    /**
     * 参数详情（P07 的新建结果，也供后续配置更新复用）。
     *
     * <p>{@code version} 来自 {@code p2_sensor_parameter_profile}；一期种子参数没有该行时为 0，
     * 与测点、设备"未维护过即版本 0"的口径一致。{@code ruleVersionId} 指向当前生效的快照。</p>
     */
    public record ParameterDetail(
            String parameterId, String sensorId, String parameterCode, String name, String dataType,
            String unitCode, int decimalPlaces, Integer samplingIntervalSeconds, String processId,
            boolean enabled, RuleSnapshot rule, String ruleVersionId, long version) {
    }

    /**
     * 参数配置更新请求（S1-P08）。
     *
     * <p>不含 {@code parameterCode} 与 {@code dataType}：前者是采集映射的连接键
     * （{@code sensorCode + parameterCode} 对应真实采集），后者决定读数怎么解析，
     * 都不属于"配置"。契约给出的可维护字段是名称、单位、精度、采样周期、工序、启用和规则。</p>
     *
     * <p>{@code expectedVersion} 必填：并发两人改同一个参数时，后提交者必须收到 409，
     * 而不是把前一个人的配置悄悄覆盖掉。</p>
     */
    public record ParameterConfigRequest(
            String requestId, String operatorName, long expectedVersion, String reason,
            String name, String unitCode, Integer decimalPlaces, Integer samplingIntervalSeconds,
            Long processId, boolean enabled, RuleSnapshot rule) {
    }

    /**
     * 参数配置更新结果（S1-P08）。
     *
     * <p>三个版本标识各有含义，不能合并：</p>
     * <ul>
     *   <li>{@code version}：参数配置版本，任何一次配置保存都会推进。</li>
     *   <li>{@code previousRuleVersionId}：本次保存前的规则版本，{@code null} 表示原来没有规则。</li>
     *   <li>{@code ruleVersionId}：当前生效的规则版本。判断依据没变时与
     *       {@code previousRuleVersionId} 相同——页面据此知道"配置存了、规则没动"，
     *       而不是误以为规则丢了。</li>
     * </ul>
     *
     * <p>{@code affectedEventIds} 恒为空数组：按契约只有"判断依据变化"才需要结束该参数的
     * 活动事件（{@code RULE_CHANGED}），而结束事件属于异常服务（S1-3）的事务边界。
     * 事件服务未接入前不伪造事件号，也不改一期 {@code limit_status} 冒充事件；
     * {@code effectNote} 会把这层未完成能力直接写进响应。</p>
     */
    public record ParameterConfigResult(
            String parameterId, String sensorId, String parameterCode,
            String name, String dataType, String unitCode, int decimalPlaces,
            Integer samplingIntervalSeconds, String processId, boolean enabled,
            RuleSnapshot rule, long version,
            String previousRuleVersionId, String ruleVersionId,
            List<String> affectedEventIds, String effectNote) {
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
