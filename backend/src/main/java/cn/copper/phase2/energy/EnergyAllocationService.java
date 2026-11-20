package cn.copper.phase2.energy;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.energy.EnergyAllocationModels.AllocationShare;
import cn.copper.phase2.energy.EnergyAllocationModels.CostRun;
import cn.copper.phase2.energy.EnergyAllocationModels.DayConfirmation;
import cn.copper.phase2.energy.EnergyAllocationModels.Denominator;
import cn.copper.phase2.energy.EnergyAllocationModels.EnergyDaySource;
import cn.copper.phase2.energy.EnergyAllocationModels.EnergyReconciliation;
import cn.copper.phase2.energy.EnergyAllocationModels.PriceSnapshot;
import cn.copper.phase2.energy.EnergyAllocationModels.ShareEnergy;
import cn.copper.phase2.energy.EnergyAllocationModels.WorkshopDayAllocation;
import cn.copper.phase2.energy.EnergyModels.PriceMatch;
import cn.copper.phase2.energy.EnergySourceRepository.MeterRow;
import cn.copper.phase2.energy.EnergySourceRepository.OrderAttribution;
import cn.copper.phase2.energy.EnergySourceRepository.OutputFact;
import cn.copper.phase2.energy.EnergySourceRepository.WorkshopRow;
import cn.copper.phase2.energy.EnergyStatModels.MeterIdentity;
import cn.copper.phase2.energy.EnergyStatModels.MissingItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * S2-E03 车间某日的来源与分摊。
 *
 * <p>分摊本身是一行除法，难的是决定「什么时候不许除」。所以这个服务的顺序是：
 * 先建立分母，再判断能否分摊，最后才算比例。任一步不成立就停下来返回缺项，
 * 不用已知部分凑一个看起来合理、实际会低估费用的比例。</p>
 *
 * <p>两个车间口径不同：车间二按当日实际合格重量分摊全日消耗（含待机），属于经营估算；
 * 车间一按库存成本归集，需要统一库存服务的成本台账，本版返回缺项而不硬套重量分摊。</p>
 */
@Service
public class EnergyAllocationService {
    /** 分摊比例的内部精度。输出时再降到 {@code RATIO_SCALE}。 */
    private static final int RATIO_INTERNAL_SCALE = 16;
    private static final String WORKSHOP_TYPE_MELTING = "熔炼";
    private static final String SOURCE_PRODUCTION = "PRODUCTION_RECORD";
    private static final String SOURCE_ENERGY_ALLOCATION = "ENERGY_ALLOCATION";

    private final EnergySourceRepository repository;
    private final EnergyRepository priceRepository;
    private final EnergyAllocationRepository allocationRepository;
    private final EnergyUsageResolver resolver;

    public EnergyAllocationService(EnergySourceRepository repository,
                                   EnergyRepository priceRepository,
                                   EnergyAllocationRepository allocationRepository,
                                   @Value("${phase2.energy.coverage-verified:false}") boolean coverageVerified) {
        this.repository = repository;
        this.priceRepository = priceRepository;
        this.allocationRepository = allocationRepository;
        this.resolver = new EnergyUsageResolver(coverageVerified);
    }

    public WorkshopDayAllocation dayAllocation(String workshopIdText, String actualDateText) {
        long workshopId = parseId(workshopIdText);
        LocalDate date = parseDate(actualDateText);
        WorkshopRow workshop = repository.workshop(workshopId)
                .orElseThrow(() -> BusinessException.notFound("车间不存在：" + workshopIdText));

        String workshopText = String.valueOf(workshopId);
        // 核算运行存在，不等于本次分摊已经写进成本台账：分摊行由核算发布落库。两者要分开报。
        EnergyRepository.PublishedCostRun published = priceRepository.latestPublishedCostRun().orElse(null);
        List<MissingItem> missing = new ArrayList<>();
        EnergySourceRepository.DayStatusRow dayStatus = repository.dayStatus(workshopId, date).orElse(null);
        boolean dayConfirmed = dayStatus != null && dayStatus.confirmed();

        // ---------- 1. 分母：当日全部合格量 ----------
        BigDecimal qualified = null;
        boolean zeroConfirmed = false;
        for (OutputFact fact : repository.outputFacts(workshopId, date, date)) {
            if ("NO_OUTPUT".equals(fact.recordType())) {
                zeroConfirmed = true;
            } else if (fact.quantityTon() != null) {
                qualified = (qualified == null ? BigDecimal.ZERO : qualified).add(fact.quantityTon());
            }
        }

        Denominator denominator;
        if (qualified != null) {
            denominator = new Denominator(format(qualified, EnergyStatModels.TON_SCALE), dayConfirmed,
                    dayConfirmed ? "当日生产记录已确认录全，分母为当日全部合格称重"
                            : "当日生产记录尚未确认录全，分母为已知称重合计，补录后会变");
            if (!dayConfirmed) {
                missing.add(new MissingItem(EnergyAllocationModels.CODE_DAY_RECORDS_UNCONFIRMED,
                        SOURCE_PRODUCTION, null, workshopText, date, "denominator",
                        "当日生产记录尚未确认录全，分摊分母可能随补录变化，暂不作为最终比例"));
            }
            if (zeroConfirmed) {
                missing.add(new MissingItem(EnergyTotalsService.CODE_ZERO_CONFLICT_WITH_OUTPUT,
                        SOURCE_PRODUCTION, null, workshopText, date, "denominator",
                        "当日既有明确零产出确认又有正称重，已按正称重建立分母，请核对并作废其中一条"));
            }
        } else if (zeroConfirmed) {
            denominator = new Denominator("0.000", dayConfirmed,
                    "已确认当日无合格产出，能源列为车间费用，不按比例分摊");
            if (!dayConfirmed) {
                missing.add(new MissingItem(EnergyAllocationModels.CODE_DAY_RECORDS_UNCONFIRMED,
                        SOURCE_PRODUCTION, null, workshopText, date, "denominator",
                        "零产出确认尚未经当日录全确认，补录后可能变为有产出"));
            }
        } else {
            denominator = new Denominator(null, false,
                    "当日既无合格称重也无零产出确认，未建立分摊分母");
            missing.add(new MissingItem(EnergyAllocationModels.CODE_DAY_RECORDS_UNCONFIRMED,
                    SOURCE_PRODUCTION, null, workshopText, date, "denominator",
                    "当日既未称重也未确认零产出，不能按零产量处理，也无法建立分摊分母"));
        }

        // ---------- 2. 三类能源的来源、价格与费用 ----------
        List<EnergyDaySource> sources = new ArrayList<>();
        BigDecimal amountTotal = BigDecimal.ZERO;
        boolean amountTotalComplete = true;
        for (String energyType : EnergyModels.ENERGY_TYPES) {
            EnergyDaySource source = buildSource(workshopId, date, energyType);
            sources.add(source);
            if (EnergyStatModels.COMPLETE.equals(source.completeness()) && source.amountYuan() != null) {
                amountTotal = amountTotal.add(new BigDecimal(source.amountYuan()));
            } else {
                amountTotalComplete = false;
            }
        }

        // ---------- 3. 分摊 ----------
        boolean melting = WORKSHOP_TYPE_MELTING.equals(workshop.workshopType());
        String basis = melting ? EnergyAllocationModels.BASIS_STOCK_COST
                : EnergyAllocationModels.BASIS_OUTPUT_WEIGHT;
        List<AllocationShare> allocations = new ArrayList<>();
        AllocationShare publicShare = null;
        EnergyAllocationRepository.PersistedRun persisted =
                allocationRepository.currentByDay(workshopId, date).orElse(null);
        boolean sourcesComplete = sources.stream()
                .allMatch(item -> EnergyStatModels.COMPLETE.equals(item.completeness()));
        String status;
        String statusNote;

        if (!melting && zeroConfirmed && qualified == null) {
            status = EnergyAllocationModels.STATUS_NO_OUTPUT;
            statusNote = "当日确认无合格产出，能源按车间费用归集，不产生订单份额";
        } else if (qualified == null || qualified.signum() <= 0) {
            status = EnergyAllocationModels.STATUS_PENDING_INPUT;
            statusNote = melting
                    ? "当日尚未称重，车间一能源无法归集到各次产出入库"
                    : "当日尚未称重或未建立分母，暂不能分摊";
        } else {
            if (melting) {
                // 车间一按库存成本归集：把当日能源按各次合格铜杆重量分到产出入库，
                // 随后随入库进入铜杆成本，再由领用带到下游，不按订单直接计费。
                for (EnergySourceRepository.OutputRow output : repository.qualifiedOutputs(workshopId, date)) {
                    BigDecimal quantity = output.quantityTon();
                    if (quantity == null || quantity.signum() <= 0) continue;
                    BigDecimal ratio = quantity.divide(qualified, RATIO_INTERNAL_SCALE, RoundingMode.HALF_UP);
                    List<ShareEnergy> energies = shareEnergies(sources, ratio);
                    allocations.add(new AllocationShare(EnergyAllocationModels.TARGET_ROD_STOCK,
                            String.valueOf(output.outputId()), null, null,
                            format(quantity, EnergyStatModels.TON_SCALE),
                            format(ratio, EnergyAllocationModels.RATIO_SCALE),
                            energies, sumAmount(energies), List.of()));
                }
            } else {
                List<OrderAttribution> attributions = repository.orderAttributions(workshopId, date);
                if (!attributions.isEmpty()) {
                    BigDecimal attributed = attributions.stream()
                            .map(OrderAttribution::attributedQuantityTon)
                            .filter(java.util.Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (attributed.compareTo(qualified) > 0) {
                        missing.add(new MissingItem(EnergyAllocationModels.CODE_ATTRIBUTION_EXCEEDS_OUTPUT,
                                SOURCE_PRODUCTION, null, workshopText, date, "orderAllocation",
                                "订单归属合计 " + format(attributed, EnergyStatModels.TON_SCALE)
                                        + " 吨已超过当日合格量 " + format(qualified, EnergyStatModels.TON_SCALE)
                                        + " 吨，请核对归属记录"));
                        attributions = List.of();
                    } else {
                        for (OrderAttribution attribution : attributions) {
                            BigDecimal quantity = attribution.attributedQuantityTon();
                            if (quantity == null || quantity.signum() <= 0) continue;
                            BigDecimal ratio = quantity.divide(qualified, RATIO_INTERNAL_SCALE,
                                    RoundingMode.HALF_UP);
                            List<ShareEnergy> energies = shareEnergies(sources, ratio);
                            allocations.add(new AllocationShare(EnergyAllocationModels.TARGET_ORDER,
                                    String.valueOf(attribution.orderId()),
                                    String.valueOf(attribution.orderId()), attribution.orderNo(),
                                    format(quantity, EnergyStatModels.TON_SCALE),
                                    format(ratio, EnergyAllocationModels.RATIO_SCALE),
                                    energies, sumAmount(energies), List.of()));
                        }
                        BigDecimal publicQuantity = qualified.subtract(attributed);
                        if (publicQuantity.signum() > 0) {
                            BigDecimal ratio = publicQuantity.divide(qualified, RATIO_INTERNAL_SCALE,
                                    RoundingMode.HALF_UP);
                            List<ShareEnergy> energies = shareEnergies(sources, ratio);
                            publicShare = new AllocationShare(EnergyAllocationModels.TARGET_PUBLIC,
                                    EnergyAllocationModels.TARGET_PUBLIC, null, null,
                                    format(publicQuantity, EnergyStatModels.TON_SCALE),
                                    format(ratio, EnergyAllocationModels.RATIO_SCALE),
                                    energies, sumAmount(energies), List.of());
                        }
                    }
                } else {
                    missing.add(new MissingItem(EnergyAllocationModels.CODE_ORDER_ATTRIBUTION_MISSING,
                            SOURCE_PRODUCTION, null, workshopText, date, "orderAllocation",
                            "当日合格称重未关联订单归属，需先完成称重归属，否则无法按订单分摊"));
                }
            }

            if (allocations.isEmpty() && publicShare == null) {
                status = EnergyAllocationModels.STATUS_PENDING_INPUT;
                statusNote = melting
                        ? "当日有合格量但没有逐次称重明细，无法把能源归集到产出入库"
                        : "当日有合格产出但尚无订单归属，只能给出分母，不能拆分到订单";
            } else if (persisted != null) {
                status = EnergyAllocationModels.STATUS_ALLOCATED;
                statusNote = "分摊已写入核算运行 " + persisted.costRunId()
                        + "，共 " + persisted.rowCount() + " 行，费用已进成本台账";
            } else if (sourcesComplete && dayConfirmed) {
                status = EnergyAllocationModels.STATUS_PENDING_COSTING;
                statusNote = published == null
                        ? "依据齐全，等待核算运行落账并生成成本版本"
                        : "依据齐全，等待本次分摊写入成本台账后由核算运行生成成本版本";
            } else {
                status = EnergyAllocationModels.STATUS_PENDING_INPUT;
                statusNote = "比例已按当前称重给出，但能源来源或当日录全尚未确定，费用不作为最终结果";
            }

            if (persisted == null) {
                missing.add(new MissingItem(
                        published == null
                                ? EnergyAllocationModels.CODE_COST_RUN_MISSING
                                : "ALLOCATION_NOT_PERSISTED",
                        SOURCE_ENERGY_ALLOCATION, null, workshopText, date, "costRun",
                        published == null
                                ? "尚未产生核算运行，分摊消耗与费用未落成本台账，也没有核算版本"
                                : "已有核算运行 " + published.costRunId()
                                + "，但本次分摊尚未写入分摊行，费用仍未落成本台账"));
            }
        }

        List<EnergyReconciliation> reconciliation = reconcile(sources, allocations, publicShare);
        CostRun costRun = published == null
                ? new CostRun(null, null, "NOT_RUN")
                : new CostRun(String.valueOf(published.costRunId()), published.version(), published.status());
        String totalAmount = amountTotalComplete ? format(amountTotal, EnergyAllocationModels.AMOUNT_SCALE) : null;

        return new WorkshopDayAllocation(
                workshopText, workshop.workshopName(), workshop.workshopType(), date,
                basis, status, statusNote,
                dayConfirmed
                        ? new DayConfirmation(true, dayStatus.confirmedBy(), dayStatus.confirmedAt(), false,
                        "当日生产记录已确认录全，分摊比例为最终依据")
                        : new DayConfirmation(false, null, null,
                        dayStatus != null && dayStatus.invalidated(),
                        dayStatus != null && dayStatus.invalidated()
                                ? "当日曾确认录全，但之后又发生补录或更正，确认已失效；分摊比例需重新核对"
                                : "当日生产记录尚未确认录全，分摊比例均为按现有记录的临时结果"),
                denominator, sources, totalAmount, allocations, publicShare,
                reconciliation, costRun, missing);
    }

    /**
     * 一类能源的当日来源。表计缺失时仍保留价格——
     * 「装了表但没有读数的车间」和「连表都没有的车间」是两种问题，价格信息对两者都有用。
     */
    private EnergyDaySource buildSource(long workshopId, LocalDate date, String energyType) {
        String energyName = EnergyModels.ENERGY_TYPE_NAMES.get(energyType);
        String unit = EnergyModels.UNIT_BY_TYPE.get(energyType);
        String workshopText = String.valueOf(workshopId);
        List<MeterRow> meters = repository.meters(workshopId, energyType);
        PriceSnapshot price = priceRepository.matchPrice(energyType, workshopId, date)
                .map(EnergyAllocationService::toSnapshot)
                .orElse(null);

        List<MissingItem> missing = new ArrayList<>();
        if (price == null) {
            missing.add(new MissingItem(EnergyAllocationModels.CODE_PRICE_MISSING,
                    "ENERGY_PRICE", null, workshopText, date, energyType,
                    "该车间当日没有适用单价，费用无法计算"));
        }

        if (meters.isEmpty()) {
            missing.add(new MissingItem(EnergyUsageResolver.CODE_METER_NOT_CONFIGURED,
                    "ENERGY_METER", null, workshopText, date, energyType,
                    "缺少对应车间的" + energyName + "总表计量，未以设备分表替代"));
            return new EnergyDaySource(energyType, energyName, unit, null, null, null,
                    EnergyStatModels.MISSING, null, null, null, price, null, missing);
        }

        List<Long> sensorIds = meters.stream().map(MeterRow::sensorId).toList();
        Map<Long, Map<LocalDate, EnergySourceRepository.DayReading>> dayIndex =
                EnergyUsageResolver.indexDays(repository.dayReadings(sensorIds, date, date));
        Map<Long, Map<LocalDate, List<EnergySourceRepository.HourReading>>> hourIndex =
                EnergyUsageResolver.indexHours(repository.hourReadings(sensorIds, date, date));
        EnergyUsageResolver.DayUsage usage = resolver.resolve(energyType, energyName, unit, meters,
                dayIndex, hourIndex, workshopId, date);
        missing.addAll(usage.missingItems());

        MeterIdentity meter = usage.meters().size() == 1
                ? new MeterIdentity(String.valueOf(usage.meters().get(0).sensorId()),
                        usage.meters().get(0).sensorCode(), usage.meters().get(0).sensorName())
                : null;

        String amount = null;
        if (EnergyStatModels.COMPLETE.equals(usage.completeness()) && price != null) {
            amount = format(new BigDecimal(price.unitPriceYuanPerUnit())
                    .multiply(usage.value()), EnergyAllocationModels.AMOUNT_SCALE);
        }

        return new EnergyDaySource(energyType, energyName, unit, meter,
                format(usage.value(), EnergyStatModels.CONSUMPTION_SCALE),
                format(usage.known(), EnergyStatModels.CONSUMPTION_SCALE),
                usage.completeness(), usage.qualityStatus(), usage.calculationMethod(),
                usage.refreshTime(), price, amount, missing);
    }

    /** 按比例拆一项能源的消耗与费用；源值不完整时整项留空，不用已知部分估算。 */
    private List<ShareEnergy> shareEnergies(List<EnergyDaySource> sources, BigDecimal ratio) {
        List<ShareEnergy> shares = new ArrayList<>();
        for (EnergyDaySource source : sources) {
            if (source.consumption() == null) {
                shares.add(new ShareEnergy(source.energyType(), source.unit(), null, null));
                continue;
            }
            BigDecimal allocated = new BigDecimal(source.consumption()).multiply(ratio);
            String amount = source.price() == null ? null
                    : format(allocated.multiply(new BigDecimal(source.price().unitPriceYuanPerUnit())),
                    EnergyAllocationModels.AMOUNT_SCALE);
            shares.add(new ShareEnergy(source.energyType(), source.unit(),
                    format(allocated, EnergyStatModels.CONSUMPTION_SCALE), amount));
        }
        return shares;
    }

    /**
     * 份额金额：三类能源都算得出来才给合计。
     *
     * <p>缺一类就整项留空，而不是把另两类加起来——那个和看起来是这个订单的真实成本，
     * 实际低了一截，比空着更容易被当成结论用。</p>
     */
    private static String sumAmount(List<ShareEnergy> shares) {
        if (shares.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (ShareEnergy share : shares) {
            if (share.amountYuan() == null) return null;
            total = total.add(new BigDecimal(share.amountYuan()));
        }
        return format(total, EnergyAllocationModels.AMOUNT_SCALE);
    }

    /**
     * 合计校验：源总量应等于各订单份额加公共份额。任一项算不出来就整张表留空，
     * 用缺测数据算出的「差 0」会让人误以为账对上了。
     */
    private static List<EnergyReconciliation> reconcile(List<EnergyDaySource> sources,
                                                        List<AllocationShare> allocations,
                                                        AllocationShare publicShare) {
        if (allocations.isEmpty() && publicShare == null) return List.of();
        List<EnergyReconciliation> rows = new ArrayList<>();
        for (EnergyDaySource source : sources) {
            if (source.consumption() == null) continue;
            BigDecimal allocated = sumShare(source.energyType(), allocations);
            BigDecimal shared = publicShare == null ? BigDecimal.ZERO : shareOf(source.energyType(), publicShare);
            if (allocated == null || shared == null) continue;
            BigDecimal difference = new BigDecimal(source.consumption()).subtract(allocated).subtract(shared);
            rows.add(new EnergyReconciliation(source.energyType(), source.unit(),
                    source.consumption(), format(allocated, EnergyStatModels.CONSUMPTION_SCALE),
                    format(shared, EnergyStatModels.CONSUMPTION_SCALE),
                    format(difference, EnergyStatModels.CONSUMPTION_SCALE)));
        }
        return rows;
    }

    private static BigDecimal sumShare(String energyType, List<AllocationShare> allocations) {
        BigDecimal total = BigDecimal.ZERO;
        for (AllocationShare share : allocations) {
            BigDecimal value = shareOf(energyType, share);
            if (value == null) return null;
            total = total.add(value);
        }
        return total;
    }

    private static BigDecimal shareOf(String energyType, AllocationShare share) {
        for (ShareEnergy energy : share.energies()) {
            if (energy.energyType().equals(energyType)) {
                return energy.allocatedConsumption() == null ? null
                        : new BigDecimal(energy.allocatedConsumption());
            }
        }
        return null;
    }

    private static PriceSnapshot toSnapshot(PriceMatch match) {
        return new PriceSnapshot(match.priceId(), match.scope(), match.effectiveDate(),
                match.unitCode(), match.unitPriceYuanPerUnit(), match.version());
    }

    private static String format(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static long parseId(String text) {
        try {
            long value = Long.parseLong(text.trim());
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException | NullPointerException ex) {
            throw BusinessException.invalid("workshopId", "车间 ID 必须是正整数字符串。");
        }
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            throw BusinessException.invalid("actualDate", "实际日不能为空。");
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException ex) {
            throw BusinessException.invalid("actualDate", "实际日格式应为 yyyy-MM-dd，例如 2026-11-17。");
        }
    }
}
