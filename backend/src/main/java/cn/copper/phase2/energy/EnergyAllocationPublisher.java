package cn.copper.phase2.energy;

import cn.copper.phase2.energy.EnergyAllocationModels.AllocationShare;
import cn.copper.phase2.energy.EnergyAllocationModels.EnergyDaySource;
import cn.copper.phase2.energy.EnergyAllocationModels.ShareEnergy;
import cn.copper.phase2.energy.EnergyAllocationModels.WorkshopDayAllocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把某一天的车间分摊写进成本台账（S2-E03 的落库端）。
 *
 * <p>为什么单独一个类：算「按什么比例分」与「什么时候允许落账」是两件事。前者在
 * {@link EnergyAllocationService} 里做，后者必须跟着核算运行走——分摊行挂在
 * {@code cost_run_id} 下，没有运行就没有版本，写进去也无法追溯。</p>
 *
 * <p><b>只落可落账的日</b>：{@code PENDING_COSTING}（依据齐全）、{@code ALLOCATED}（已落过，
 * 重算时按新运行重写）与 {@code NO_OUTPUT_CONFIRMED}（确认零产出，能源单列车间费用）。
 * 其余状态即使算出了临时比例也不落库——把「还没录全的比例」写进台账，之后没人分得清
 * 哪些数字是最终结果。</p>
 */
@Service
public class EnergyAllocationPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(EnergyAllocationPublisher.class);

    private final EnergySourceRepository sourceRepository;
    private final EnergyAllocationService allocationService;
    private final EnergyAllocationRepository allocationRepository;

    public EnergyAllocationPublisher(EnergySourceRepository sourceRepository,
                                     EnergyAllocationService allocationService,
                                     EnergyAllocationRepository allocationRepository) {
        this.sourceRepository = sourceRepository;
        this.allocationService = allocationService;
        this.allocationRepository = allocationRepository;
    }

    /**
     * 重写某次运行在给定日期区间内的分摊行。
     *
     * @return 写入的行数
     */
    public int publish(long costRunId, LocalDate from, LocalDate to) {
        allocationRepository.deleteByRun(costRunId);
        if (from == null || to == null) {
            return 0;
        }
        List<EnergyAllocationRepository.Draft> drafts = new ArrayList<>();
        for (EnergySourceRepository.OutputDay day : sourceRepository.outputDays(from, to)) {
            WorkshopDayAllocation allocation;
            try {
                allocation = allocationService.dayAllocation(
                        String.valueOf(day.workshopId()), day.actualDate().toString());
            } catch (RuntimeException failure) {
                // 单个车间日算不出来不该让整批补算失败：其余日期照常落账，
                // 这一天在 E03 里仍是「依据不全」，缺项由查询侧报。
                LOG.warn("车间 {} 日期 {} 的分摊未能落库：{}", day.workshopId(), day.actualDate(),
                        failure.getMessage());
                continue;
            }
            drafts.addAll(drafts(allocation));
        }
        int rows;
        try {
            rows = allocationRepository.insertAll(costRunId, drafts);
        } catch (org.springframework.dao.DataAccessException rejected) {
            // 前端只会看到「数据库不可用」，所以这里补一句能定位的上下文：
            // 分摊表有一组 CHECK（目标为订单必须带 order_id、比例 0–1、金额非负）。
            Throwable cause = rejected.getMostSpecificCause();
            LOG.warn("写入分摊行被拒绝：待写 {} 行，运行 {}，sqlState={}，vendorCode={}",
                    drafts.size(), costRunId,
                    cause instanceof java.sql.SQLException sql ? sql.getSQLState() : null,
                    cause instanceof java.sql.SQLException sql ? sql.getErrorCode() : null);
            throw new IllegalStateException("写入分摊行被数据库拒绝：" + drafts.size() + " 行", rejected);
        }
        LOG.info("核算运行 {} 写入分摊行 {} 行（{} 至 {}）", costRunId, rows, from, to);
        return rows;
    }

    private List<EnergyAllocationRepository.Draft> drafts(WorkshopDayAllocation allocation) {
        List<EnergyAllocationRepository.Draft> drafts = new ArrayList<>();
        long workshopId = Long.parseLong(allocation.workshopId());
        LocalDate date = allocation.actualDate();

        if (EnergyAllocationModels.STATUS_NO_OUTPUT.equals(allocation.status())) {
            // 零产出：能源是车间费用，不按比例分给订单。整日每个能源一行，金额即当日费用。
            for (EnergyDaySource source : allocation.sources()) {
                drafts.add(row(allocation, workshopId, date, source,
                        EnergyAllocationModels.TARGET_NO_OUTPUT, "DAY", null, null,
                        "0.000", "0.000", BigDecimal.ZERO, source.amountYuan(), List.of()));
            }
            return drafts;
        }
        if (!EnergyAllocationModels.STATUS_PENDING_COSTING.equals(allocation.status())
                && !EnergyAllocationModels.STATUS_ALLOCATED.equals(allocation.status())) {
            return drafts;
        }

        List<AllocationShare> shares = new ArrayList<>(allocation.allocations());
        if (allocation.publicShare() != null) {
            shares.add(allocation.publicShare());
        }
        for (AllocationShare share : shares) {
            Long orderId = EnergyAllocationModels.TARGET_ORDER.equals(share.targetType())
                    && share.orderId() != null ? Long.parseLong(share.orderId()) : null;
            Long outputId = EnergyAllocationModels.TARGET_ROD_STOCK.equals(share.targetType())
                    && share.targetKey() != null ? Long.parseLong(share.targetKey()) : null;
            List<String> missingCodes = share.missingItems().stream()
                    .map(item -> item.code()).toList();
            for (ShareEnergy energy : share.energies()) {
                EnergyDaySource source = sourceOf(allocation, energy.energyType());
                if (source == null) {
                    continue;
                }
                drafts.add(row(allocation, workshopId, date, source,
                        storedTargetType(share.targetType()), share.targetKey(), orderId, outputId,
                        share.qualifiedQuantityTon(), allocation.denominator().quantityTon(),
                        share.ratio() == null ? null : new BigDecimal(share.ratio()),
                        energy.amountYuan(), missingCodes));
            }
        }
        return drafts;
    }

    /**
     * 对外口径与落库口径的差异：接口把公共份额叫 {@code PUBLIC}（页面只区分「订单」与「公共」），
     * 分摊表的 CHECK 只认 {@code PUBLIC_FINISHED}。转换放在这里，不改对外字段——
     * 让数据库约束决定接口字段名，会把前端已有的判断全部推翻。
     */
    private static String storedTargetType(String targetType) {
        return EnergyAllocationModels.TARGET_PUBLIC.equals(targetType)
                ? "PUBLIC_FINISHED" : targetType;
    }

    private EnergyAllocationRepository.Draft row(WorkshopDayAllocation allocation, long workshopId,
                                                 LocalDate date, EnergyDaySource source,
                                                 String targetType, String targetKey, Long orderId,
                                                 Long outputId, String qualifiedQuantityTon,
                                                 String denominatorQuantityTon, BigDecimal ratio,
                                                 String amountYuan, List<String> missingCodes) {
        String meterKey = source.meter() == null
                ? "WORKSHOP:" + workshopId + ":" + source.energyType()
                : "SENSOR:" + source.meter().sensorId();
        BigDecimal qualified = qualifiedQuantityTon == null || qualifiedQuantityTon.isBlank()
                ? null : new BigDecimal(qualifiedQuantityTon);
        BigDecimal denominator = denominatorQuantityTon == null || denominatorQuantityTon.isBlank()
                ? null : new BigDecimal(denominatorQuantityTon);
        return new EnergyAllocationRepository.Draft(
                workshopId, date, source.energyType(), meterKey,
                source.refreshTime(),
                source.qualityStatus() == null ? "UNKNOWN" : source.qualityStatus(),
                snapshot(source),
                source.unit(),
                source.consumption() == null ? null : new BigDecimal(source.consumption()),
                source.price() == null ? null : Long.parseLong(source.price().priceId()),
                source.price() == null ? null : new BigDecimal(source.price().unitPriceYuanPerUnit()),
                targetType, targetKey, orderId, outputId,
                qualified, denominator, ratio,
                null,
                amountYuan == null ? null : new BigDecimal(amountYuan),
                missingCodes);
    }

    /**
     * 来源快照：把「为什么是这个费用」的当场依据固化下来。
     * 之后价格改了、表计换了，这一行仍然能回答当时的判断。
     */
    private static Map<String, Object> snapshot(EnergyDaySource source) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("energyType", source.energyType());
        snapshot.put("consumption", source.consumption());
        snapshot.put("knownConsumption", source.knownConsumption());
        snapshot.put("completeness", source.completeness());
        snapshot.put("qualityStatus", source.qualityStatus());
        snapshot.put("calculationMethod", source.calculationMethod());
        snapshot.put("meterCode", source.meter() == null ? null : source.meter().sensorCode());
        snapshot.put("unit", source.unit());
        if (source.price() != null) {
            Map<String, Object> price = new LinkedHashMap<>();
            price.put("priceId", source.price().priceId());
            price.put("effectiveDate", source.price().effectiveDate() == null ? null
                    : source.price().effectiveDate().toString());
            price.put("unitPriceYuanPerUnit", source.price().unitPriceYuanPerUnit());
            price.put("version", source.price().version());
            snapshot.put("price", price);
        }
        return snapshot;
    }

    private static EnergyDaySource sourceOf(WorkshopDayAllocation allocation, String energyType) {
        for (EnergyDaySource source : allocation.sources()) {
            if (source.energyType().equals(energyType)) {
                return source;
            }
        }
        return null;
    }
}
