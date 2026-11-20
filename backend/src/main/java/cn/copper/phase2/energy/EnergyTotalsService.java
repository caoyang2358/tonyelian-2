package cn.copper.phase2.energy;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.energy.EnergySourceRepository.MeterRow;
import cn.copper.phase2.energy.EnergySourceRepository.OutputFact;
import cn.copper.phase2.energy.EnergySourceRepository.WorkshopRow;
import cn.copper.phase2.energy.EnergyStatModels.EnergyQuantity;
import cn.copper.phase2.energy.EnergyStatModels.EnergyTotals;
import cn.copper.phase2.energy.EnergyStatModels.MissingItem;
import cn.copper.phase2.energy.EnergyStatModels.WorkshopTotals;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S2-E01 分车间四项总量。
 *
 * <p>这个接口的价值不在于把数字加起来，而在于<b>敢说哪一项不知道</b>。所以三项判定刻意写死：</p>
 * <ul>
 *   <li>没有车间总表 → MISSING 并指出缺哪台表，不拿设备分表凑数；</li>
 *   <li>有读数但覆盖未核验 → PARTIAL，{@code value} 为 null；</li>
 *   <li>产量必须等当日录全确认才可能完整，未确认时永远 PARTIAL/MISSING。</li>
 * </ul>
 */
@Service
public class EnergyTotalsService {
    /**
     * 当日录全确认的判定口径。
     *
     * <p>只认 {@code p2_production_day_status} 里「确认来源修订号仍等于当前来源修订号」的
     * 记录：S2-R19 落库之后，任何补录或更正都会把来源修订号推高，使旧的确认自动失效。
     * 不能用「当天有称重就当录全」抄近路——那会把"还没录完"当成"确实只生产了这些"。</p>
     */
    static final String CODE_DAY_RECORDS_UNCONFIRMED = "DAY_RECORDS_UNCONFIRMED";
    static final String CODE_ZERO_CONFLICT_WITH_OUTPUT = "ZERO_CONFLICT_WITH_OUTPUT";
    static final String SOURCE_PRODUCTION = "PRODUCTION_RECORD";

    /** 单项缺项最多逐条列出多少条；超过的部分汇总成一条，避免长区间响应过大。 */
    private static final int MISSING_LIMIT = 50;

    private final EnergySourceRepository repository;
    private final EnergyUsageResolver resolver;

    public EnergyTotalsService(EnergySourceRepository repository,
                               @Value("${phase2.energy.coverage-verified:false}") boolean coverageVerified) {
        this.repository = repository;
        this.resolver = new EnergyUsageResolver(coverageVerified);
    }

    public EnergyTotals totals(String fromText, String toText, String workshopIdText) {
        LocalDate from = parseDate(fromText, "from", "起始日");
        LocalDate to = parseDate(toText, "to", "结束日");
        if (to.isBefore(from)) {
            throw BusinessException.invalid("to", "结束日不得早于起始日。");
        }
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span > EnergyStatModels.MAX_RANGE_DAYS) {
            throw BusinessException.invalid("to",
                    "查询区间不得超过 " + EnergyStatModels.MAX_RANGE_DAYS + " 天，当前为 " + span + " 天。");
        }

        List<WorkshopRow> workshops = resolveWorkshops(workshopIdText);
        List<WorkshopTotals> result = new ArrayList<>();
        for (WorkshopRow workshop : workshops) {
            result.add(new WorkshopTotals(
                    String.valueOf(workshop.workshopId()),
                    workshop.workshopName(),
                    energyQuantity(workshop.workshopId(), "ELECTRICITY", "电", from, to),
                    energyQuantity(workshop.workshopId(), "WATER", "水", from, to),
                    energyQuantity(workshop.workshopId(), "GAS", "煤气", from, to),
                    qualifiedOutput(workshop.workshopId(), from, to)));
        }
        return new EnergyTotals(from, to, result);
    }

    /**
     * 省略车间时返回全部生产车间。车间一、车间二的产量分别返回，不合并成"全厂合格产量"——
     * 铜杆和铜丝是两个工序的产出，相加会得到一个没有业务含义的数。
     */
    private List<WorkshopRow> resolveWorkshops(String workshopIdText) {
        if (workshopIdText == null || workshopIdText.isBlank()) {
            return repository.productionWorkshops();
        }
        long workshopId = parseId(workshopIdText, "workshopId");
        WorkshopRow workshop = repository.workshop(workshopId)
                .orElseThrow(() -> BusinessException.notFound("车间不存在：" + workshopIdText));
        return List.of(workshop);
    }

    private EnergyQuantity energyQuantity(long workshopId, String energyType, String energyName,
                                          LocalDate from, LocalDate to) {
        String unit = EnergyModels.UNIT_BY_TYPE.get(energyType);
        List<MeterRow> meters = repository.meters(workshopId, energyType);

        // 没有总表就直接给结论，不下去逐日查一期表：查了也只能得到一堆"当日无读数"，
        // 反而把"这台车间根本没装表"这个真正的缺口埋在 365 条重复缺项里。
        if (meters.isEmpty()) {
            return new EnergyQuantity(unit, null, null, EnergyStatModels.MISSING,
                    List.of(new MissingItem(EnergyUsageResolver.CODE_METER_NOT_CONFIGURED,
                            "ENERGY_METER", null, String.valueOf(workshopId), null, energyType,
                            "车间未配置" + energyName + "总表计量，无法出具该能源用量")));
        }

        List<Long> sensorIds = meters.stream().map(MeterRow::sensorId).toList();
        Map<Long, Map<LocalDate, EnergySourceRepository.DayReading>> dayIndex =
                EnergyUsageResolver.indexDays(repository.dayReadings(sensorIds, from, to));
        Map<Long, Map<LocalDate, List<EnergySourceRepository.HourReading>>> hourIndex =
                EnergyUsageResolver.indexHours(repository.hourReadings(sensorIds, from, to));

        List<EnergyUsageResolver.DayUsage> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            days.add(resolver.resolve(energyType, energyName, unit, meters, dayIndex, hourIndex,
                    workshopId, date));
        }
        return EnergyUsageResolver.aggregate(unit, days, EnergyStatModels.CONSUMPTION_SCALE, MISSING_LIMIT);
    }

    /**
     * 合格产量。来源是二期真实称重与明确零产出事实，不用一期日表里的 {@code production_quantity}。
     *
     * <p>三种日状态必须分开：有正称重、只有明确零产出、两者都没有。第三种不能说成零——
     * "没有查询到称重行"可能只是还没录。</p>
     */
    private EnergyQuantity qualifiedOutput(long workshopId, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> qualifiedByDate = new HashMap<>();
        Set<LocalDate> zeroDays = new HashSet<>();
        for (OutputFact fact : repository.outputFacts(workshopId, from, to)) {
            if ("NO_OUTPUT".equals(fact.recordType())) {
                zeroDays.add(fact.actualDate());
            } else if (fact.quantityTon() != null) {
                qualifiedByDate.merge(fact.actualDate(), fact.quantityTon(), BigDecimal::add);
            }
        }
        Map<LocalDate, EnergySourceRepository.DayStatusRow> dayStatus =
                repository.dayStatuses(workshopId, from, to);

        List<MissingItem> missing = new ArrayList<>();
        List<BigDecimal> knownParts = new ArrayList<>();
        boolean allComplete = true;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            BigDecimal qualified = qualifiedByDate.get(date);
            boolean zero = zeroDays.contains(date);
            EnergySourceRepository.DayStatusRow status = dayStatus.get(date);
            boolean confirmed = status != null && status.confirmed();
            if (qualified == null && !zero) {
                allComplete = false;
                missing.add(unconfirmed(workshopId, date, status,
                        "当日未查询到称重或零产出事实，且当日生产记录尚未确认录全，不能按零产量处理"));
                continue;
            }
            knownParts.add(qualified == null ? BigDecimal.ZERO : qualified);
            if (qualified != null && zero) {
                // 零产出确认与正称重互斥。以正称重为准（不能抹掉已发生的产量），但把冲突暴露出来。
                missing.add(new MissingItem(CODE_ZERO_CONFLICT_WITH_OUTPUT, SOURCE_PRODUCTION, null,
                        String.valueOf(workshopId), date, "qualifiedOutput",
                        "当日既有明确零产出确认又有正称重，已按正称重计，请核对并作废其中一条"));
            }
            if (!confirmed) {
                allComplete = false;
                missing.add(unconfirmed(workshopId, date, status,
                        status != null && status.invalidated()
                                ? "当日曾确认录全，但之后又发生补录或更正，确认已失效，产量需重新核对"
                                : "当日生产记录尚未确认录全，产量为已知称重合计，不作为完整产量"));
            }
        }

        BigDecimal known = knownParts.isEmpty() ? null
                : knownParts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<MissingItem> shown = truncate(missing);
        if (allComplete && known != null) {
            return new EnergyQuantity(EnergyStatModels.UNIT_TON,
                    EnergyUsageResolver.format(known, EnergyStatModels.TON_SCALE),
                    EnergyUsageResolver.format(known, EnergyStatModels.TON_SCALE),
                    EnergyStatModels.COMPLETE, List.of());
        }
        if (known == null) {
            return new EnergyQuantity(EnergyStatModels.UNIT_TON, null, null, EnergyStatModels.MISSING, shown);
        }
        return new EnergyQuantity(EnergyStatModels.UNIT_TON, null,
                EnergyUsageResolver.format(known, EnergyStatModels.TON_SCALE),
                EnergyStatModels.PARTIAL, shown);
    }

    private static MissingItem unconfirmed(long workshopId, LocalDate date,
                                          EnergySourceRepository.DayStatusRow status, String message) {
        return new MissingItem(CODE_DAY_RECORDS_UNCONFIRMED, SOURCE_PRODUCTION, null,
                String.valueOf(workshopId), date, "qualifiedOutput", message);
    }

    private static List<MissingItem> truncate(List<MissingItem> missing) {
        if (missing.size() <= MISSING_LIMIT) return missing;
        List<MissingItem> shown = new ArrayList<>(missing.subList(0, MISSING_LIMIT));
        shown.add(new MissingItem("MISSING_ITEMS_TRUNCATED", null, null, null, null, null,
                "另有 " + (missing.size() - MISSING_LIMIT) + " 条缺项未逐条列出"));
        return shown;
    }

    private static LocalDate parseDate(String text, String field, String label) {
        if (text == null || text.isBlank()) {
            throw BusinessException.invalid(field, label + "不能为空。");
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException ex) {
            throw BusinessException.invalid(field, label + "格式应为 yyyy-MM-dd，例如 2026-11-17。");
        }
    }

    private static long parseId(String text, String field) {
        try {
            long value = Long.parseLong(text.trim());
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw BusinessException.invalid(field, "车间 ID 必须是正整数字符串。");
        }
    }
}
