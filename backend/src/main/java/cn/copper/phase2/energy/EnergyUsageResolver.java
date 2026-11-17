package cn.copper.phase2.energy;

import cn.copper.phase2.energy.EnergySourceRepository.DayReading;
import cn.copper.phase2.energy.EnergySourceRepository.HourReading;
import cn.copper.phase2.energy.EnergySourceRepository.MeterRow;
import cn.copper.phase2.energy.EnergyStatModels.MissingItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单日、单能源的完整性判定。
 *
 * <p>E01 按天汇总、E02 按天展开，两者对「这一天到底算不算完整」必须给出同一答案，
 * 所以判定只写在这一处。规则来自《数据与计算设计》第 2 节的表计选择表，落到代码是四条：</p>
 * <ol>
 *   <li>日表有 GOOD 行优先采用，明确零消耗保留为零；</li>
 *   <li>日表缺行时才看小时，且必须 24 个唯一小时桶全部 GOOD 才可回补；</li>
 *   <li>覆盖范围未与一期核验前，即使数据 GOOD 也只能算部分已知——"有一行数据"不等于
 *       "这一天的计量范围完整"；</li>
 *   <li>BAD / NO_DATA 的读数不可采信，但其存在要作为缺项返回，便于追溯。</li>
 * </ol>
 *
 * <p>这个类不查库，输入是已取好的行，因此可以用构造好的边界用例直接验证。</p>
 */
class EnergyUsageResolver {
    static final String GOOD = "GOOD";

    static final String CODE_METER_NOT_CONFIGURED = "METER_NOT_CONFIGURED";
    static final String CODE_MULTI_METER_UNVERIFIED = "MULTI_METER_COVERAGE_UNVERIFIED";
    static final String CODE_QUALITY_NOT_GOOD = "QUALITY_NOT_GOOD";
    static final String CODE_SOURCE_ROW_MISSING = "SOURCE_ROW_MISSING";
    static final String CODE_HOUR_INCOMPLETE = "HOUR_COVERAGE_INCOMPLETE";
    static final String CODE_COVERAGE_UNVERIFIED = "COVERAGE_UNVERIFIED";

    static final String METHOD_HOUR_ROLLUP = "HOUR_ROLLUP";

    private static final String SOURCE_METER = "ENERGY_METER";
    private static final String SOURCE_READING = "ENERGY_READING";

    /**
     * 覆盖范围是否已与一期核验。默认 false：一期对接尚未完成，无法证明「一行 GOOD 日数据
     * 覆盖了车间全天」。核验完成后由配置打开，E01/E02 才会返回 COMPLETE。
     */
    private final boolean coverageVerified;

    EnergyUsageResolver(boolean coverageVerified) {
        this.coverageVerified = coverageVerified;
    }

    /**
     * 一天的判定结果。
     *
     * @param value       仅 COMPLETE 时有值
     * @param known       可追溯已知部分；明确零为 {@code ZERO}，无从追溯为 null
     * @param meters      参与本日计算的表计，多表计时上层不给单一身份
     */
    record DayUsage(
            BigDecimal value,
            BigDecimal known,
            String completeness,
            String qualityStatus,
            String calculationMethod,
            OffsetDateTime refreshTime,
            List<MeterRow> meters,
            List<MissingItem> missingItems) {}

    DayUsage resolve(String energyType, String energyName, String unit, List<MeterRow> meters,
                     Map<Long, Map<LocalDate, DayReading>> dayIndex,
                     Map<Long, Map<LocalDate, List<HourReading>>> hourIndex,
                     long workshopId, LocalDate date) {
        String workshopText = String.valueOf(workshopId);
        if (meters.isEmpty()) {
            return new DayUsage(null, null, EnergyStatModels.MISSING, null, null, null, List.of(),
                    List.of(new MissingItem(CODE_METER_NOT_CONFIGURED, SOURCE_METER, null, workshopText, date,
                            energyType, "缺少对应车间的" + energyName + "总表计量，未以设备分表替代")));
        }

        List<MissingItem> missing = new ArrayList<>();
        List<MeterRow> used = new ArrayList<>();
        List<BigDecimal> knownParts = new ArrayList<>();
        List<BigDecimal> valueParts = new ArrayList<>();
        boolean allComplete = true;
        String quality = null;
        String method = null;
        OffsetDateTime refresh = null;

        for (MeterRow meter : meters) {
            DayReading day = dayIndex.getOrDefault(meter.sensorId(), Map.of()).get(date);
            List<HourReading> hours = hourIndex.getOrDefault(meter.sensorId(), Map.of()).getOrDefault(date, List.of());
            Single single = singleMeter(meter, day, hours, energyType, energyName, unit, workshopText, date);
            used.add(meter);
            missing.addAll(single.missingItems());

            if (single.known() != null) knownParts.add(single.known());
            if (EnergyStatModels.COMPLETE.equals(single.completeness())) valueParts.add(single.value());
            else allComplete = false;

            if (quality == null) quality = single.qualityStatus();
            if (method == null) method = single.calculationMethod();
            if (refresh == null || (single.refreshTime() != null && single.refreshTime().isAfter(refresh))) {
                refresh = single.refreshTime();
            }
        }

        if (meters.size() > 1) {
            missing.add(new MissingItem(CODE_MULTI_METER_UNVERIFIED, SOURCE_METER, null, workshopText, date,
                    energyType, "同车间有 " + meters.size() + " 台" + energyName
                            + "总表，分区是否互不重叠、并集是否覆盖车间尚未核验，暂按相加展示"));
        }

        BigDecimal known = knownParts.isEmpty() ? null : knownParts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allComplete) {
            BigDecimal value = valueParts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return new DayUsage(value, known == null ? value : known, EnergyStatModels.COMPLETE,
                    quality, method, refresh, used, missing);
        }
        String completeness = known == null ? EnergyStatModels.MISSING : EnergyStatModels.PARTIAL;
        return new DayUsage(null, known, completeness, quality, method, refresh, used, missing);
    }

    /**
     * 一台表计一天的判定。
     *
     * <p>日行存在就不再叠加小时：两者是同一消费范围的两种粒度，相加会把耗电量算成两倍。
     * 只有日行缺失时才用完整 24 小时回补。</p>
     */
    private Single singleMeter(MeterRow meter, DayReading day, List<HourReading> hours,
                               String energyType, String energyName, String unit,
                               String workshopText, LocalDate date) {
        String sensorText = String.valueOf(meter.sensorId());
        if (day != null) {
            String quality = day.qualityStatus();
            if (!GOOD.equals(quality)) {
                // PARTIAL 的读数可作已知部分；BAD / NO_DATA 不可采信，连已知部分都不给。
                BigDecimal known = "PARTIAL".equals(quality) ? day.consumption() : null;
                return new Single(null, known,
                        known == null ? EnergyStatModels.MISSING : EnergyStatModels.PARTIAL,
                        quality, null, day.refreshTime(), List.of(new MissingItem(CODE_QUALITY_NOT_GOOD,
                        SOURCE_READING, sensorText, workshopText, date, energyType,
                        "日读数质量 " + quality + "，该表计当日用量不可作为完整值")));
            }
            BigDecimal consumption = day.consumption() == null ? BigDecimal.ZERO : day.consumption();
            if (coverageVerified) {
                return new Single(consumption, consumption, EnergyStatModels.COMPLETE,
                        quality, null, day.refreshTime(), List.of());
            }
            return new Single(null, consumption, EnergyStatModels.PARTIAL,
                    quality, null, day.refreshTime(), List.of(new MissingItem(CODE_COVERAGE_UNVERIFIED,
                    SOURCE_READING, sensorText, workshopText, date, energyType,
                    "日读数质量 GOOD，但该表计的计量覆盖范围尚未与一期核验，暂按部分已知展示")));
        }

        if (hours.isEmpty()) {
            return new Single(null, null, EnergyStatModels.MISSING, null, null, null,
                    List.of(new MissingItem(CODE_SOURCE_ROW_MISSING, SOURCE_READING, sensorText, workshopText, date,
                            energyType, "当日无" + energyName + "日读数，也无小时读数可回补")));
        }

        // 一个时间桶只认一行；同一桶出现多行时取刷新时间最新的那行，避免重复计数。
        Map<LocalDateTime, HourReading> buckets = new LinkedHashMap<>();
        for (HourReading hour : hours) {
            LocalDateTime key = hour.bucketTime().atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                    .toLocalDateTime().truncatedTo(ChronoUnit.HOURS);
            HourReading existing = buckets.get(key);
            if (existing == null || isNewer(hour, existing)) buckets.put(key, hour);
        }

        boolean rollupComplete = buckets.size() == 24;
        BigDecimal rollupKnown = BigDecimal.ZERO;
        boolean anyGood = false;
        OffsetDateTime latest = null;
        for (HourReading hour : buckets.values()) {
            boolean usable = GOOD.equals(hour.qualityStatus())
                    && hour.consumption() != null && hour.consumption().signum() >= 0
                    && hour.calculationMethod() != null && !"NO_DATA".equals(hour.calculationMethod());
            if (usable) {
                rollupKnown = rollupKnown.add(hour.consumption());
                anyGood = true;
            } else {
                rollupComplete = false;
            }
            if (hour.refreshTime() != null && (latest == null || hour.refreshTime().isAfter(latest))) {
                latest = hour.refreshTime();
            }
        }

        if (rollupComplete) {
            if (coverageVerified) {
                return new Single(rollupKnown, rollupKnown, EnergyStatModels.COMPLETE,
                        GOOD, METHOD_HOUR_ROLLUP, latest, List.of());
            }
            return new Single(null, rollupKnown, EnergyStatModels.PARTIAL,
                    GOOD, METHOD_HOUR_ROLLUP, latest, List.of(new MissingItem(CODE_COVERAGE_UNVERIFIED,
                    SOURCE_READING, sensorText, workshopText, date, energyType,
                    "由 24 个完整小时合计回补，但该表计的计量覆盖范围尚未与一期核验，暂按部分已知展示")));
        }

        BigDecimal known = anyGood ? rollupKnown : null;
        return new Single(null, known, known == null ? EnergyStatModels.MISSING : EnergyStatModels.PARTIAL,
                anyGood ? "PARTIAL" : null, METHOD_HOUR_ROLLUP, latest, List.of(new MissingItem(
                CODE_HOUR_INCOMPLETE, SOURCE_READING, sensorText, workshopText, date, energyType,
                "小时读数只有 " + buckets.size() + "/24 个桶且并非全部可用，无法回补整日用量")));
    }

    private static boolean isNewer(HourReading candidate, HourReading existing) {
        if (candidate.refreshTime() == null) return false;
        if (existing.refreshTime() == null) return true;
        return candidate.refreshTime().isAfter(existing.refreshTime());
    }

    /** 单台表计的中间结果，合并多表计前不留痕。 */
    private record Single(BigDecimal value, BigDecimal known, String completeness, String qualityStatus,
                          String calculationMethod, OffsetDateTime refreshTime, List<MissingItem> missingItems) {}

    /**
     * 把一段日期的判定合并成一个汇总。
     *
     * <p>只有每一天都完整才叫完整；任一天缺测，整段就是部分已知——跨月总量最容易被
     * 「大部分天数有数」掩盖掉其中一天的缺口。缺项超过 {@code limit} 条时截断，
     * 并明确告知还有多少条未列出，而不是静默丢弃。</p>
     */
    static EnergyStatModels.EnergyQuantity aggregate(String unit, List<DayUsage> days, int scale, int limit) {
        List<MissingItem> missing = new ArrayList<>();
        boolean allComplete = !days.isEmpty();
        List<BigDecimal> knownParts = new ArrayList<>();

        for (DayUsage day : days) {
            if (day.known() != null) knownParts.add(day.known());
            if (!EnergyStatModels.COMPLETE.equals(day.completeness())) allComplete = false;
            missing.addAll(day.missingItems());
        }

        BigDecimal known = knownParts.isEmpty() ? null
                : knownParts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        List<MissingItem> shown = missing;
        if (missing.size() > limit) {
            shown = new ArrayList<>(missing.subList(0, limit));
            shown.add(new MissingItem("MISSING_ITEMS_TRUNCATED", null, null, null, null, null,
                    "另有 " + (missing.size() - limit) + " 条缺项未逐条列出"));
        }

        if (allComplete && known != null) {
            return new EnergyStatModels.EnergyQuantity(unit, format(known, scale), format(known, scale),
                    EnergyStatModels.COMPLETE, List.of());
        }
        if (known == null) {
            return new EnergyStatModels.EnergyQuantity(unit, null, null, EnergyStatModels.MISSING, shown);
        }
        return new EnergyStatModels.EnergyQuantity(unit, null, format(known, scale),
                EnergyStatModels.PARTIAL, shown);
    }

    static String format(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** 按表计、日期索引日读数。同表计同日多行时保留刷新时间最新的一行。 */
    static Map<Long, Map<LocalDate, DayReading>> indexDays(List<DayReading> rows) {
        Map<Long, Map<LocalDate, DayReading>> index = new HashMap<>();
        for (DayReading row : rows) {
            Map<LocalDate, DayReading> byDate = index.computeIfAbsent(row.sensorId(), key -> new HashMap<>());
            DayReading existing = byDate.get(row.statisticDate());
            if (existing == null || isNewerDay(row, existing)) byDate.put(row.statisticDate(), row);
        }
        return index;
    }

    private static boolean isNewerDay(DayReading candidate, DayReading existing) {
        if (candidate.refreshTime() == null) return false;
        if (existing.refreshTime() == null) return true;
        return candidate.refreshTime().isAfter(existing.refreshTime());
    }

    /** 按表计、日期索引小时读数；同一小时桶的多行留给具体判定时再挑。 */
    static Map<Long, Map<LocalDate, List<HourReading>>> indexHours(List<HourReading> rows) {
        Map<Long, Map<LocalDate, List<HourReading>>> index = new HashMap<>();
        for (HourReading row : rows) {
            LocalDate date = row.bucketTime().atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
            index.computeIfAbsent(row.sensorId(), key -> new HashMap<>())
                    .computeIfAbsent(date, key -> new ArrayList<>()).add(row);
        }
        return index;
    }

}
