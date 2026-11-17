package cn.copper.phase2.energy;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.energy.EnergyAllocationModels.AllocationShare;
import cn.copper.phase2.energy.EnergyAllocationModels.EnergyDaySource;
import cn.copper.phase2.energy.EnergyAllocationModels.EnergyReconciliation;
import cn.copper.phase2.energy.EnergyAllocationModels.WorkshopDayAllocation;
import cn.copper.phase2.energy.EnergyModels.PriceMatch;
import cn.copper.phase2.energy.EnergySourceRepository.DayReading;
import cn.copper.phase2.energy.EnergySourceRepository.MeterRow;
import cn.copper.phase2.energy.EnergySourceRepository.OrderAttribution;
import cn.copper.phase2.energy.EnergySourceRepository.OutputFact;
import cn.copper.phase2.energy.EnergySourceRepository.WorkshopRow;
import cn.copper.phase2.energy.EnergyStatModels.MissingItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2-E03 当日分摊。
 *
 * <p>这里守的是第二条底线：<b>分母不成立就不许除</b>。用一半的依据算出一个精确到六位小数的比例，
 * 比直接说「还差什么」危险得多——数字看起来是算过的，没人会再回头怀疑它。</p>
 */
class EnergyAllocationServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.parse("2026-11-10");

    private final EnergySourceRepository repository = mock(EnergySourceRepository.class);
    private final EnergyRepository priceRepository = mock(EnergyRepository.class);
    private final WorkshopRow wireWorkshop = new WorkshopRow(2L, "车间2 · 铜丝拉制", "拉丝");
    private final WorkshopRow rodWorkshop = new WorkshopRow(1L, "车间1 · 铜杆生产", "熔炼");

    private EnergyAllocationService service(boolean coverageVerified) {
        return new EnergyAllocationService(repository, priceRepository, coverageVerified);
    }

    @Test
    void 没有总表也没有称重时分母不成立且三项均缺测() {
        when(repository.workshop(2L)).thenReturn(Optional.of(wireWorkshop));
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of());

        WorkshopDayAllocation result = service(false).dayAllocation("2", "2026-11-10");

        assertEquals(EnergyAllocationModels.STATUS_PENDING_INPUT, result.status());
        assertNull(result.denominator().quantityTon());
        assertFalse(result.denominator().complete());
        assertEquals(3, result.sources().size());
        for (EnergyDaySource source : result.sources()) {
            assertEquals(EnergyStatModels.MISSING, source.completeness());
            assertNull(source.meter());
            assertTrue(codes(source).contains(EnergyUsageResolver.CODE_METER_NOT_CONFIGURED));
        }
        assertTrue(result.allocations().isEmpty());
        assertNull(result.publicShare());
        assertNull(result.totalAmountYuan());
        assertNotNull(result.costRun());
        assertNull(result.costRun().costRunId());
    }

    @Test
    void 有合格产出但没有订单归属时只给分母不算比例() {
        when(repository.workshop(2L)).thenReturn(Optional.of(wireWorkshop));
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of(
                new OutputFact(DAY, "QUALIFIED_OUTPUT", new BigDecimal("10.000"))));
        when(repository.orderAttributions(anyLong(), any())).thenReturn(List.of());

        WorkshopDayAllocation result = service(false).dayAllocation("2", "2026-11-10");

        assertEquals("10.000", result.denominator().quantityTon());
        assertEquals(EnergyAllocationModels.STATUS_PENDING_INPUT, result.status());
        assertTrue(result.allocations().isEmpty());
        assertTrue(result.missingItems().stream()
                .anyMatch(item -> EnergyAllocationModels.CODE_ORDER_ATTRIBUTION_MISSING.equals(item.code())));
    }

    /**
     * 契约里的例子：A 3 吨、B 4 吨、公共 3 吨，当日 1000 kWh。
     * 正确结果是 300 / 400 / 300，公共部分也要进分母。
     */
    @Test
    void 按当日实际合格量拆分并把公共部分单独列出() {
        when(repository.workshop(2L)).thenReturn(Optional.of(wireWorkshop));
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of(
                new OutputFact(DAY, "QUALIFIED_OUTPUT", new BigDecimal("10.000"))));
        when(repository.orderAttributions(2L, DAY)).thenReturn(List.of(
                new OrderAttribution(1L, "SO-2026-001", new BigDecimal("3.000")),
                new OrderAttribution(2L, "SO-2026-002", new BigDecimal("4.000"))));

        MeterRow electricity = new MeterRow(11L, "EM-01", "车间二电总表", "ELECTRICITY");
        MeterRow water = new MeterRow(12L, "WM-01", "车间二水总表", "WATER");
        MeterRow gas = new MeterRow(13L, "GM-01", "车间二煤气总表", "GAS");
        when(repository.meters(2L, "ELECTRICITY")).thenReturn(List.of(electricity));
        when(repository.meters(2L, "WATER")).thenReturn(List.of(water));
        when(repository.meters(2L, "GAS")).thenReturn(List.of(gas));
        when(repository.dayReadings(any(), any(), any()))
                .thenReturn(List.of(reading(11L, "1000.000000")))
                .thenReturn(List.of(reading(12L, "100.000000")))
                .thenReturn(List.of(reading(13L, "50.000000")));
        when(priceRepository.matchPrice("ELECTRICITY", 2L, DAY)).thenReturn(Optional.of(
                new PriceMatch("1", EnergyModels.SCOPE_WORKSHOP, DAY, "元/kWh", "1.0000000000", 3L)));
        when(priceRepository.matchPrice("WATER", 2L, DAY)).thenReturn(Optional.of(
                new PriceMatch("2", EnergyModels.SCOPE_GENERAL, DAY, "元/m³", "2.0000000000", 1L)));
        when(priceRepository.matchPrice("GAS", 2L, DAY)).thenReturn(Optional.of(
                new PriceMatch("3", EnergyModels.SCOPE_GENERAL, DAY, "元/Nm³", "3.0000000000", 1L)));

        WorkshopDayAllocation result = service(true).dayAllocation("2", "2026-11-10");

        assertEquals("0.300000", result.allocations().get(0).ratio());
        assertEquals("0.400000", result.allocations().get(1).ratio());
        assertEquals("300.000000", allocated(result.allocations().get(0), "ELECTRICITY"));
        assertEquals("400.000000", allocated(result.allocations().get(1), "ELECTRICITY"));

        assertNotNull(result.publicShare());
        assertEquals("PUBLIC", result.publicShare().targetType());
        assertEquals("3.000", result.publicShare().qualifiedQuantityTon());
        assertEquals("0.300000", result.publicShare().ratio());
        assertEquals("300.000000", allocated(result.publicShare(), "ELECTRICITY"));

        assertEquals("1350.00", result.totalAmountYuan());
        // 一个订单份额的费用是三项能源之和：0.3×1000×1 + 0.3×100×2 + 0.3×50×3 = 405
        assertEquals("405.00", result.allocations().get(0).amountYuan());
        assertEquals("540.00", result.allocations().get(1).amountYuan());
        assertEquals("405.00", result.publicShare().amountYuan());

        EnergyReconciliation electricityRow = result.reconciliation().get(0);
        assertEquals("1000.000000", electricityRow.sourceConsumption());
        assertEquals("700.000000", electricityRow.allocatedConsumption());
        assertEquals("300.000000", electricityRow.publicConsumption());
        assertEquals("0.000000", electricityRow.difference());

        // 来源齐全但当日录全未确认，仍然不能说「等核算运行」——那是依据确定的说法。
        assertEquals(EnergyAllocationModels.STATUS_PENDING_INPUT, result.status());
        assertTrue(result.statusNote().contains("尚未确定"));
        assertNull(result.costRun().costRunId());
        assertTrue(result.missingItems().stream()
                .anyMatch(item -> EnergyAllocationModels.CODE_COST_RUN_MISSING.equals(item.code())));
    }

    @Test
    void 订单归属量超过当日合格量时拒绝分摊() {
        when(repository.workshop(2L)).thenReturn(Optional.of(wireWorkshop));
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of(
                new OutputFact(DAY, "QUALIFIED_OUTPUT", new BigDecimal("10.000"))));
        when(repository.orderAttributions(2L, DAY)).thenReturn(List.of(
                new OrderAttribution(1L, "SO-2026-001", new BigDecimal("12.000"))));

        WorkshopDayAllocation result = service(false).dayAllocation("2", "2026-11-10");

        assertEquals(EnergyAllocationModels.STATUS_PENDING_INPUT, result.status());
        assertTrue(result.allocations().isEmpty());
        assertTrue(result.reconciliation().isEmpty());
        assertTrue(result.missingItems().stream()
                .anyMatch(item -> EnergyAllocationModels.CODE_ATTRIBUTION_EXCEEDS_OUTPUT.equals(item.code())));
    }

    @Test
    void 确认零产出时能源单列车间费用且不产生份额() {
        when(repository.workshop(2L)).thenReturn(Optional.of(wireWorkshop));
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of(
                new OutputFact(DAY, "NO_OUTPUT", BigDecimal.ZERO)));

        WorkshopDayAllocation result = service(false).dayAllocation("2", "2026-11-10");

        assertEquals(EnergyAllocationModels.STATUS_NO_OUTPUT, result.status());
        assertEquals("0.000", result.denominator().quantityTon());
        assertTrue(result.allocations().isEmpty());
        assertNull(result.publicShare());
    }

    @Test
    void 车间一按库存成本归集并说明本版未接入() {
        when(repository.workshop(1L)).thenReturn(Optional.of(rodWorkshop));
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of(
                new OutputFact(DAY, "QUALIFIED_OUTPUT", new BigDecimal("8.000"))));

        WorkshopDayAllocation result = service(false).dayAllocation("1", "2026-11-10");

        assertEquals(EnergyAllocationModels.BASIS_STOCK_COST, result.allocationBasis());
        assertTrue(result.allocations().isEmpty());
        assertTrue(result.missingItems().stream()
                .anyMatch(item -> EnergyAllocationModels.CODE_ALLOCATION_BASIS_UNSUPPORTED.equals(item.code())));
    }

    @Test
    void 缺单价时费用留空但保留消耗与价格缺项() {
        when(repository.workshop(2L)).thenReturn(Optional.of(wireWorkshop));
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of());
        when(repository.meters(2L, "ELECTRICITY")).thenReturn(List.of(
                new MeterRow(11L, "EM-01", "车间二电总表", "ELECTRICITY")));
        when(repository.dayReadings(any(), any(), any()))
                .thenReturn(List.of(reading(11L, "1000.000000")))
                .thenReturn(List.of())
                .thenReturn(List.of());

        WorkshopDayAllocation result = service(true).dayAllocation("2", "2026-11-10");

        EnergyDaySource electricity = result.sources().get(0);
        assertEquals("1000.000000", electricity.consumption());
        assertNull(electricity.price());
        assertNull(electricity.amountYuan());
        assertTrue(codes(electricity).contains(EnergyAllocationModels.CODE_PRICE_MISSING));
        assertNull(result.totalAmountYuan());
    }

    @Test
    void 车间不存在与日期格式错误分别返回404和400() {
        when(repository.workshop(anyLong())).thenReturn(Optional.empty());

        BusinessException notFound = assertThrows(BusinessException.class,
                () -> service(false).dayAllocation("99", "2026-11-10"));
        assertEquals(ErrorCode.NOT_FOUND, notFound.errorCode());

        BusinessException invalid = assertThrows(BusinessException.class,
                () -> service(false).dayAllocation("2", "2026/11/10"));
        assertEquals(ErrorCode.VALIDATION_ERROR, invalid.errorCode());
    }

    private static DayReading reading(long sensorId, String consumption) {
        return new DayReading(sensorId, DAY, new BigDecimal(consumption), "GOOD",
                OffsetDateTime.now(ZONE));
    }

    private static String allocated(AllocationShare share, String energyType) {
        return share.energies().stream()
                .filter(item -> item.energyType().equals(energyType))
                .map(EnergyAllocationModels.ShareEnergy::allocatedConsumption)
                .findFirst().orElse(null);
    }

    private static List<String> codes(EnergyDaySource source) {
        return source.missingItems().stream().map(MissingItem::code).toList();
    }
}
