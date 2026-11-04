package cn.copper.phase2.sensor;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.sensor.SensorModels.DeletionBlocker;
import cn.copper.phase2.sensor.SensorModels.HistoryRecord;
import cn.copper.phase2.sensor.SensorModels.LatestReading;
import cn.copper.phase2.sensor.SensorModels.ParameterSummary;
import cn.copper.phase2.sensor.SensorModels.RuleVersionView;
import cn.copper.phase2.sensor.SensorModels.SensorDetail;
import cn.copper.phase2.sensor.SensorModels.SensorSummary;
import cn.copper.phase2.sensor.SensorRepository.MonitorRow;
import cn.copper.phase2.sensor.SensorRepository.ParameterContext;
import cn.copper.phase2.sensor.SensorRepository.SensorQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * S1-P01/P02/P06/P09/P10/P11 的只读查询。
 *
 * <p>全是 GET：没有幂等、没有乐观锁、没有留痕。读数与历史属于采集侧事实，
 * 二期只读不写——写入口不在本模块，也不应该在这里补。</p>
 *
 * <p>"今天"和"现在"都按厂区时区取，不跟随 JVM 或数据库服务器时区。</p>
 */
@Service
public class SensorService {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** 详情里带的参数摘要上限，与列表页每页上限一致，避免一个测点挂几百个参数时撑爆抽屉。 */
    private static final int DETAIL_PARAMETER_LIMIT = MAX_PAGE_SIZE;

    private final SensorRepository repository;
    private final SensorReferenceChecker references;
    private final long staleAfterMinutes;

    public SensorService(SensorRepository repository, SensorReferenceChecker references,
                         @Value("${phase2.monitoring.stale-after-minutes:15}") long staleAfterMinutes) {
        this.repository = repository;
        this.references = references;
        this.staleAfterMinutes = Math.max(1, staleAfterMinutes);
    }

    /** S1-P01：测点列表。 */
    public PageResult<SensorSummary> search(String equipmentId, String keyword, Boolean enabled,
                                            String monitoringStatus, Integer page, Integer pageSize) {
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolvePageSize(pageSize);
        SensorQuery query = new SensorQuery(
                optionalId(equipmentId, "equipmentId", "设备"),
                blankToNull(keyword),
                enabled,
                normalize(monitoringStatus, SensorModels.MONITORING_STATES, "monitoringStatus"),
                resolvedPage, resolvedSize);
        OffsetDateTime staleBefore = staleBefore();
        List<SensorSummary> items = repository.search(query, staleBefore);
        long total = repository.count(query, staleBefore);
        return PageResult.of(items, resolvedPage, resolvedSize, total);
    }

    /** S1-P02：测点详情，附带参数摘要与删除可行性。 */
    public SensorDetail detail(String sensorId) {
        long id = requiredId(sensorId, "sensorId", "测点");
        SensorDetail detail = repository.detail(id, staleBefore())
                .orElseThrow(() -> BusinessException.notFound("测点 " + id + " 不存在。"));
        List<ParameterSummary> parameters =
                repository.parameters(id, 1, DETAIL_PARAMETER_LIMIT);
        return new SensorDetail(
                detail.sensorId(), detail.sensorCode(), detail.name(),
                detail.equipmentId(), detail.equipmentCode(), detail.equipmentName(),
                detail.workshopId(), detail.workshopName(),
                detail.sensorType(), detail.dataSource(), detail.location(), detail.purpose(),
                detail.enabled(), detail.parameterCount(), detail.hasHistory(),
                detail.monitoringStatus(), detail.configurationState(),
                detail.version(), parameters, deletionState(id, detail.sensorCode()));
    }

    /**
     * 删除可行性。
     *
     * <p>与 P05 共用同一份检查，页面据此置灰删除入口，而不是等用户点了才报 409。
     * 详情是单条读取，多几次计数查询换"按钮状态永远正确"是值得的。</p>
     */
    private SensorModels.DeletionState deletionState(long sensorId, String sensorCode) {
        List<DeletionBlocker> blockers = references.blockers(sensorId, sensorCode);
        return new SensorModels.DeletionState(blockers.isEmpty(), blockers);
    }

    /** S1-P06：一个测点的参数定义分页。 */
    public PageResult<ParameterSummary> parameters(String sensorId, Integer page, Integer pageSize) {
        long id = requiredId(sensorId, "sensorId", "测点");
        // 测点不存在与"测点存在但没有参数"必须能区分，否则页面会把 404 显示成空列表。
        repository.detail(id, staleBefore())
                .orElseThrow(() -> BusinessException.notFound("测点 " + id + " 不存在。"));
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolvePageSize(pageSize);
        List<ParameterSummary> items = repository.parameters(id, resolvedPage, resolvedSize);
        long total = repository.countParameters(id);
        return PageResult.of(items, resolvedPage, resolvedSize, total);
    }

    /**
     * S1-P09：参数最新读数。
     *
     * <p>四种状态分开报，不合并成一个"离线"：停用是配置决定，未知是从没采到，
     * 过期是采过但停了，在线才是真的有数。合并之后现场无法判断该找仪表还是找配置。</p>
     */
    public LatestReading latest(String parameterId) {
        long id = requiredId(parameterId, "parameterId", "参数");
        ParameterContext context = repository.parameterContext(id)
                .orElseThrow(() -> BusinessException.notFound("参数 " + id + " 不存在。"));
        MonitorRow row = repository.latest(id).orElse(null);
        String monitoringStatus = monitoringStatus(context, row);
        List<String> missing = new ArrayList<>();
        if (row == null) {
            missing.add("latestReading");
        } else if (row.value() == null && row.valueText() == null) {
            missing.add("value");
        }
        return new LatestReading(
                String.valueOf(context.parameterId()),
                String.valueOf(context.sensorId()),
                context.sensorCode(),
                context.parameterCode(),
                row == null ? null : SensorRepository.decimalText(row.value()),
                row == null ? null : row.valueText(),
                // 优先用采集侧记录的单位；采集侧没留单位时才退回参数定义上的单位，
                // 并让 missingFields 不因此报警——两者语义不同，但页面需要有个可显示的量纲。
                row == null || row.unitCode() == null ? context.unitCode()
                        : row.unitCode(),
                row == null ? null : row.qualityStatus(),
                row == null ? null : row.limitStatus(),
                row == null ? null : row.collectTime(),
                row == null ? null : row.refreshTime(),
                monitoringStatus,
                context.dataSource(),
                List.copyOf(missing));
    }

    /** S1-P10：原始历史读数。 */
    public PageResult<HistoryRecord> history(String parameterId, String from, String to,
                                             Integer page, Integer pageSize) {
        long id = requiredId(parameterId, "parameterId", "参数");
        ParameterContext context = repository.parameterContext(id)
                .orElseThrow(() -> BusinessException.notFound("参数 " + id + " 不存在。"));
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolvePageSize(pageSize);

        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw BusinessException.invalid("from", "历史查询必须给出 from 与 to。");
        }
        OffsetDateTime start = parseTime(from, "from", false);
        OffsetDateTime end = parseTime(to, "to", true);
        if (!end.isAfter(start)) {
            throw BusinessException.invalid("to", "to 必须晚于 from。");
        }
        if (ChronoUnit.DAYS.between(start, end) > SensorModels.MAX_HISTORY_RANGE_DAYS) {
            throw BusinessException.invalid("to",
                    "查询区间不能超过 " + SensorModels.MAX_HISTORY_RANGE_DAYS + " 天，请缩小范围后重试。");
        }

        List<HistoryRecord> items = repository.history(context.sensorCode(), context.parameterCode(),
                start, end, resolvedPage, resolvedSize);
        long total = repository.countHistory(context.sensorCode(), context.parameterCode(), start, end);
        return PageResult.of(items, resolvedPage, resolvedSize, total);
    }

    /** S1-P11：规则版本历史，含每一版接手时结束掉的事件。 */
    public PageResult<RuleVersionView> ruleVersions(String parameterId, Integer page, Integer pageSize) {
        long id = requiredId(parameterId, "parameterId", "参数");
        repository.parameterContext(id)
                .orElseThrow(() -> BusinessException.notFound("参数 " + id + " 不存在。"));
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolvePageSize(pageSize);
        List<RuleVersionView> items = repository.ruleVersions(id, resolvedPage, resolvedSize);
        long total = repository.countRuleVersions(id);
        return PageResult.of(items, resolvedPage, resolvedSize, total);
    }

    // ---------------------------------------------------------------- 内部

    /** 停机阈值 = 现在 - 允许的静默时长。超过这个时刻没刷新，就只是数据过期，不是设备坏了。 */
    OffsetDateTime staleBefore() {
        return OffsetDateTime.now(BUSINESS_ZONE).minusMinutes(staleAfterMinutes);
    }

    String monitoringStatus(ParameterContext context, MonitorRow row) {
        if (!context.parameterEnabled() || !context.sensorEnabled()) {
            return SensorModels.MONITORING_DISABLED;
        }
        if (row == null || row.refreshTime() == null) {
            return SensorModels.MONITORING_UNKNOWN;
        }
        return row.refreshTime().isAfter(staleBefore())
                ? SensorModels.MONITORING_ONLINE
                : SensorModels.MONITORING_STALE;
    }

    /**
     * 解析时间参数。
     *
     * <p>三种写法都收：带偏移的时刻、不带时区的本地时刻、纯日期。纯日期用于 {@code to} 时
     * 顺延到次日零点——否则"查 11 月 4 日一整天"会因为开区间把当天数据全漏掉。</p>
     */
    static OffsetDateTime parseTime(String raw, String field, boolean asEndExclusive) {
        String value = raw.trim();
        try {
            // 带偏移的输入只保留时刻本身，统一换算到厂区偏移再比较，避免跨时区歧义。
            return OffsetDateTime.parse(value).atZoneSameInstant(BUSINESS_ZONE).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地时刻与纯日期
        }
        try {
            LocalDateTime local = LocalDateTime.parse(value);
            return local.atZone(BUSINESS_ZONE).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试纯日期
        }
        try {
            LocalDate date = LocalDate.parse(value);
            LocalDateTime local = asEndExclusive ? date.plusDays(1).atStartOfDay()
                    : date.atStartOfDay();
            return local.atZone(BUSINESS_ZONE).toOffsetDateTime();
        } catch (DateTimeParseException error) {
            throw BusinessException.invalid(field,
                    "时间格式应为 2026-11-04、2026-11-04T08:00:00 或带偏移的 ISO 时刻。");
        }
    }

    static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    static String normalize(String raw, List<String> allowed, String field) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw BusinessException.invalid(field, "取值范围为 " + String.join("、", allowed) + "。");
        }
        return upper;
    }

    static Long optionalId(String raw, String field, String label) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        return parseId(value, field, label);
    }

    static long requiredId(String raw, String field, String label) {
        String value = blankToNull(raw);
        if (value == null) {
            throw BusinessException.invalid(field, label + " ID 不能为空。");
        }
        return parseId(value, field, label);
    }

    static long parseId(String value, String field, String label) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException("非正整数");
            }
            return id;
        } catch (NumberFormatException error) {
            throw BusinessException.invalid(field, label + " ID 必须是正整数字符串。");
        }
    }

    static int resolvePage(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw BusinessException.invalid("page", "页码必须从 1 开始。");
        }
        return page;
    }

    static int resolvePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.invalid("pageSize", "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间。");
        }
        return pageSize;
    }
}
