package cn.copper.phase2.energy;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.energy.EnergySourceRepository.MeterRow;
import cn.copper.phase2.energy.EnergySourceRepository.WorkshopRow;
import cn.copper.phase2.energy.EnergyStatModels.DailyConsumptionItem;
import cn.copper.phase2.energy.EnergyStatModels.DailyConsumptionPage;
import cn.copper.phase2.energy.EnergyStatModels.DailyConsumptionSummary;
import cn.copper.phase2.energy.EnergyStatModels.MeterIdentity;
import cn.copper.phase2.energy.EnergyStatModels.MissingItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S2-E02 日来源明细。
 *
 * <p>这个接口不做「有数据就列一行」，而是把请求范围内的<b>每一个日槽</b>摆出来：
 * 日期 × 车间 × 能源。缺表计、缺读数、质量不合格的槽照样返回，{@code meter} 为 null、
 * {@code missingItems} 说明缺什么。这样页面能回答"哪天哪项没数"，而不是让某天凭空消失。</p>
 *
 * <p>分页只切列表，{@code summary} 始终统计过滤后的全量槽数——否则用户看到"本页 20 条全完整"，
 * 会以为整段区间都完整。</p>
 */
@Service
public class EnergyDailyConsumptionService {
    private static final Set<String> QUALITY_VALUES = Set.of("GOOD", "PARTIAL", "BAD", "NO_DATA");
    private static final Set<String> COMPLETENESS_VALUES =
            Set.of(EnergyStatModels.COMPLETE, EnergyStatModels.PARTIAL, EnergyStatModels.MISSING);
    private static final Map<String, Integer> ENERGY_ORDER =
            Map.of("ELECTRICITY", 0, "WATER", 1, "GAS", 2);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final EnergySourceRepository repository;
    private final EnergyUsageResolver resolver;

    public EnergyDailyConsumptionService(EnergySourceRepository repository,
                                         @Value("${phase2.energy.coverage-verified:false}") boolean coverageVerified) {
        this.repository = repository;
        this.resolver = new EnergyUsageResolver(coverageVerified);
    }

    public DailyConsumptionPage search(String fromText, String toText, String workshopIdText,
                                       String energyType, String qualityStatus, String completeness,
                                       Integer pageArg, Integer pageSizeArg) {
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

        String energyFilter = normalize(energyType);
        if (energyFilter != null && !EnergyModels.ENERGY_TYPES.contains(energyFilter)) {
            throw BusinessException.invalid("energyType",
                    "能源类型只有 " + String.join("、", EnergyModels.ENERGY_TYPES) + "。");
        }
        String qualityFilter = normalize(qualityStatus);
        if (qualityFilter != null && !QUALITY_VALUES.contains(qualityFilter)) {
            throw BusinessException.invalid("qualityStatus", "质量筛选只有 GOOD、PARTIAL、BAD、NO_DATA。");
        }
        String completenessFilter = normalize(completeness);
        if (completenessFilter != null && !COMPLETENESS_VALUES.contains(completenessFilter)) {
            throw BusinessException.invalid("completeness", "完整性筛选只有 COMPLETE、PARTIAL、MISSING。");
        }

        int page = pageArg == null ? 1 : pageArg;
        if (page < 1) throw BusinessException.invalid("page", "页码从 1 开始。");
        int pageSize = pageSizeArg == null ? DEFAULT_PAGE_SIZE : pageSizeArg;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.invalid("pageSize", "每页条数范围为 1—" + MAX_PAGE_SIZE + "。");
        }

        List<WorkshopRow> workshops = resolveWorkshops(workshopIdText);
        List<String> energyTypes = energyFilter == null ? EnergyModels.ENERGY_TYPES : List.of(energyFilter);

        List<DailyConsumptionItem> slots = new ArrayList<>();
        for (WorkshopRow workshop : workshops) {
            for (String type : energyTypes) {
                slots.addAll(buildSlots(workshop, type, from, to));
            }
        }

        // 最近日期在前，方便先看当前状况；同日内按车间、能源、表计稳定排序，翻页不会抖。
        slots.sort(Comparator.comparing(DailyConsumptionItem::actualDate).reversed()
                .thenComparing(item -> Long.parseLong(item.workshopId()))
                .thenComparing(item -> ENERGY_ORDER.getOrDefault(item.energyType(), 9))
                .thenComparing(item -> item.meter() == null ? 0L : Long.parseLong(item.meter().sensorId())));

        List<DailyConsumptionItem> filtered = slots.stream()
                .filter(item -> qualityFilter == null || qualityFilter.equals(item.qualityStatus()))
                .filter(item -> completenessFilter == null || completenessFilter.equals(item.completeness()))
                .toList();

        // summary 统计筛选前的全量：筛选只是缩小列表，回答"整段区间到底完不完整"要靠这个分母，
        // 否则筛掉缺测后看到的全是 COMPLETE，会得出相反结论。
        long completeCount = slots.stream()
                .filter(item -> EnergyStatModels.COMPLETE.equals(item.completeness())).count();
        long partialCount = slots.stream()
                .filter(item -> EnergyStatModels.PARTIAL.equals(item.completeness())).count();
        long missingCount = slots.stream()
                .filter(item -> EnergyStatModels.MISSING.equals(item.completeness())).count();

        int start = (page - 1) * pageSize;
        List<DailyConsumptionItem> items = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, Math.min(start + pageSize, filtered.size()));

        return new DailyConsumptionPage(items, page, pageSize, filtered.size(),
                new DailyConsumptionSummary(slots.size(), completeCount, partialCount, missingCount));
    }

    private List<DailyConsumptionItem> buildSlots(WorkshopRow workshop, String energyType,
                                                  LocalDate from, LocalDate to) {
        String energyName = EnergyModels.ENERGY_TYPE_NAMES.get(energyType);
        String unit = EnergyModels.UNIT_BY_TYPE.get(energyType);
        List<MeterRow> meters = repository.meters(workshop.workshopId(), energyType);

        if (meters.isEmpty()) {
            List<DailyConsumptionItem> slots = new ArrayList<>();
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                slots.add(new DailyConsumptionItem(date, String.valueOf(workshop.workshopId()),
                        workshop.workshopName(), energyType, energyName, unit, null,
                        null, null, EnergyStatModels.MISSING, null, null, null,
                        List.of(new MissingItem(EnergyUsageResolver.CODE_METER_NOT_CONFIGURED, "ENERGY_METER",
                                null, String.valueOf(workshop.workshopId()), date, energyType,
                                "车间未配置" + energyName + "总表计量，该日无来源可采纳"))));
            }
            return slots;
        }

        List<Long> sensorIds = meters.stream().map(MeterRow::sensorId).toList();
        Map<Long, Map<LocalDate, EnergySourceRepository.DayReading>> dayIndex =
                EnergyUsageResolver.indexDays(repository.dayReadings(sensorIds, from, to));
        Map<Long, Map<LocalDate, List<EnergySourceRepository.HourReading>>> hourIndex =
                EnergyUsageResolver.indexHours(repository.hourReadings(sensorIds, from, to));

        List<DailyConsumptionItem> slots = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            EnergyUsageResolver.DayUsage usage = resolver.resolve(energyType, energyName, unit, meters,
                    dayIndex, hourIndex, workshop.workshopId(), date);
            MeterIdentity identity = usage.meters().size() == 1 ? toIdentity(usage.meters().get(0)) : null;
            slots.add(new DailyConsumptionItem(date, String.valueOf(workshop.workshopId()),
                    workshop.workshopName(), energyType, energyName, unit, identity,
                    EnergyUsageResolver.format(usage.value(), EnergyStatModels.CONSUMPTION_SCALE),
                    EnergyUsageResolver.format(usage.known(), EnergyStatModels.CONSUMPTION_SCALE),
                    usage.completeness(), usage.qualityStatus(), usage.calculationMethod(),
                    usage.refreshTime(), usage.missingItems()));
        }
        return slots;
    }

    private static MeterIdentity toIdentity(MeterRow meter) {
        return new MeterIdentity(String.valueOf(meter.sensorId()), meter.sensorCode(), meter.sensorName());
    }

    private List<WorkshopRow> resolveWorkshops(String workshopIdText) {
        if (workshopIdText == null || workshopIdText.isBlank()) {
            return repository.productionWorkshops();
        }
        long workshopId;
        try {
            workshopId = Long.parseLong(workshopIdText.trim());
            if (workshopId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            throw BusinessException.invalid("workshopId", "车间 ID 必须是正整数字符串。");
        }
        WorkshopRow workshop = repository.workshop(workshopId)
                .orElseThrow(() -> BusinessException.notFound("车间不存在：" + workshopIdText));
        return List.of(workshop);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
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
}
