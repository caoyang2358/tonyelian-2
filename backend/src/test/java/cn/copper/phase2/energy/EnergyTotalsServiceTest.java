package cn.copper.phase2.energy;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.energy.EnergySourceRepository.DayReading;
import cn.copper.phase2.energy.EnergySourceRepository.MeterRow;
import cn.copper.phase2.energy.EnergySourceRepository.OutputFact;
import cn.copper.phase2.energy.EnergySourceRepository.WorkshopRow;
import cn.copper.phase2.energy.EnergyStatModels.EnergyQuantity;
import cn.copper.phase2.energy.EnergyStatModels.EnergyTotals;
import cn.copper.phase2.energy.EnergyStatModels.MissingItem;
import cn.copper.phase2.energy.EnergyStatModels.WorkshopTotals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2-E01 的完整性判定。
 *
 * <p>这些用例守的是一条底线：<b>不知道就说不知道</b>。把缺测说成零、把一天的好数据说成整月完整，
 * 都会让后面的能源成本算错而没人察觉。</p>
 */
class EnergyTotalsServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.parse("2026-11-10");

    private final EnergySourceRepository repository = mock(EnergySourceRepository.class);
    private final WorkshopRow workshop = new WorkshopRow(2L, "车间2 · 铜丝拉制", "拉丝");

    @Test
    void 车间没有能源总表时三项均为缺测并指出缺哪台表() {
        when(repository.workshop(2L)).thenReturn(Optional.of(workshop));
        when(repository.productionWorkshops()).thenReturn(List.of(workshop));
        when(repository.meters(anyLong(), any())).thenReturn(List.of());
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of());

        EnergyTotals totals = new EnergyTotalsService(repository, false).totals("2026-11-10", "2026-11-10", null);

        WorkshopTotals row = totals.workshops().get(0);
        assertEquals("MISSING", row.electricity().completeness());
        assertNull(row.electricity().value());
        assertNull(row.electricity().knownValue());
        assertEquals("METER_NOT_CONFIGURED", row.electricity().missingItems().get(0).code());
        assertEquals("kWh", row.electricity().unit());
        assertEquals("Nm³", row.gas().unit());
    }

    @Test
    void 日读数质量GOOD但覆盖未核验时按部分已知返回() {
        stubMeter();
        when(repository.dayReadings(any(), any(), any())).thenReturn(List.of(
                new DayReading(11L, DAY, new BigDecimal("100.5"), "GOOD", now()),
                new DayReading(11L, DAY.plusDays(1), new BigDecimal("99.5"), "GOOD", now())));
        when(repository.hourReadings(any(), any(), any())).thenReturn(List.of());
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of());

        EnergyQuantity electricity = new EnergyTotalsService(repository, false)
                .totals(DAY.toString(), DAY.plusDays(1).toString(), "2").workshops().get(0).electricity();

        assertEquals("PARTIAL", electricity.completeness());
        assertNull(electricity.value(), "覆盖未核验时不能给出完整值");
        assertEquals("200.000000", electricity.knownValue());
        assertEquals("COVERAGE_UNVERIFIED", electricity.missingItems().get(0).code());
    }

    @Test
    void 覆盖核验通过且每日GOOD时才是完整值() {
        stubMeter();
        when(repository.dayReadings(any(), any(), any())).thenReturn(List.of(
                new DayReading(11L, DAY, new BigDecimal("100.5"), "GOOD", now()),
                new DayReading(11L, DAY.plusDays(1), new BigDecimal("99.5"), "GOOD", now())));
        when(repository.hourReadings(any(), any(), any())).thenReturn(List.of());
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of());

        EnergyQuantity electricity = new EnergyTotalsService(repository, true)
                .totals(DAY.toString(), DAY.plusDays(1).toString(), "2").workshops().get(0).electricity();

        assertEquals("COMPLETE", electricity.completeness());
        assertEquals("200.000000", electricity.value());
        assertEquals("200.000000", electricity.knownValue());
        assertTrue(electricity.missingItems().isEmpty());
    }

    @Test
    void 其中一天缺读数时整段只能算部分已知() {
        stubMeter();
        when(repository.dayReadings(any(), any(), any())).thenReturn(List.of(
                new DayReading(11L, DAY, new BigDecimal("100"), "GOOD", now())));
        when(repository.hourReadings(any(), any(), any())).thenReturn(List.of());
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of());

        EnergyQuantity electricity = new EnergyTotalsService(repository, true)
                .totals(DAY.toString(), DAY.plusDays(1).toString(), "2").workshops().get(0).electricity();

        assertEquals("PARTIAL", electricity.completeness());
        assertNull(electricity.value());
        assertEquals("100.000000", electricity.knownValue(), "缺的那天不能被当成零");
        assertEquals("SOURCE_ROW_MISSING", electricity.missingItems().get(0).code());
        assertEquals(DAY.plusDays(1), electricity.missingItems().get(0).actualDate());
    }

    @Test
    void 日表缺行但二十四小时齐全时可回补整日() {
        stubMeter();
        when(repository.dayReadings(any(), any(), any())).thenReturn(List.of());
        when(repository.hourReadings(any(), any(), any())).thenReturn(
                List.of(hour(DAY, 0), hour(DAY, 1), hour(DAY, 2), hour(DAY, 3), hour(DAY, 4), hour(DAY, 5),
                        hour(DAY, 6), hour(DAY, 7), hour(DAY, 8), hour(DAY, 9), hour(DAY, 10), hour(DAY, 11),
                        hour(DAY, 12), hour(DAY, 13), hour(DAY, 14), hour(DAY, 15), hour(DAY, 16), hour(DAY, 17),
                        hour(DAY, 18), hour(DAY, 19), hour(DAY, 20), hour(DAY, 21), hour(DAY, 22), hour(DAY, 23)));
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of());

        EnergyQuantity electricity = new EnergyTotalsService(repository, true)
                .totals(DAY.toString(), DAY.toString(), "2").workshops().get(0).electricity();

        assertEquals("COMPLETE", electricity.completeness());
        assertEquals("24.000000", electricity.value(), "24 个 1.0 的小时合计");
    }

    @Test
    void 小时不足二十四桶时只给已知部分() {
        stubMeter();
        when(repository.dayReadings(any(), any(), any())).thenReturn(List.of());
        when(repository.hourReadings(any(), any(), any())).thenReturn(List.of(hour(DAY, 0), hour(DAY, 1)));
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of());

        EnergyQuantity electricity = new EnergyTotalsService(repository, true)
                .totals(DAY.toString(), DAY.toString(), "2").workshops().get(0).electricity();

        assertEquals("PARTIAL", electricity.completeness());
        assertEquals("2.000000", electricity.knownValue());
        assertEquals("HOUR_COVERAGE_INCOMPLETE", electricity.missingItems().get(0).code());
    }

    @Test
    void 质量BAD的读数不计入已知部分() {
        stubMeter();
        when(repository.dayReadings(any(), any(), any())).thenReturn(List.of(
                new DayReading(11L, DAY, new BigDecimal("500"), "BAD", now())));
        when(repository.hourReadings(any(), any(), any())).thenReturn(List.of());
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of());

        EnergyQuantity electricity = new EnergyTotalsService(repository, false)
                .totals(DAY.toString(), DAY.toString(), "2").workshops().get(0).electricity();

        assertEquals("MISSING", electricity.completeness());
        assertNull(electricity.knownValue());
        assertEquals("QUALITY_NOT_GOOD", electricity.missingItems().get(0).code());
    }

    @Test
    void 产量有称重但未确认录全时只作已知量() {
        when(repository.workshop(2L)).thenReturn(Optional.of(workshop));
        when(repository.productionWorkshops()).thenReturn(List.of(workshop));
        when(repository.meters(anyLong(), any())).thenReturn(List.of());
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of(
                new OutputFact(DAY, "QUALIFIED_OUTPUT", new BigDecimal("10.000"))));

        EnergyQuantity output = new EnergyTotalsService(repository, false)
                .totals(DAY.toString(), DAY.toString(), "2").workshops().get(0).qualifiedOutput();

        assertEquals("PARTIAL", output.completeness());
        assertNull(output.value());
        assertEquals("10.000", output.knownValue());
        assertEquals("DAY_RECORDS_UNCONFIRMED", output.missingItems().get(0).code());
        assertEquals("t", output.unit());
    }

    @Test
    void 没有称重也没有零产出时不能当作零产量() {
        when(repository.workshop(2L)).thenReturn(Optional.of(workshop));
        when(repository.productionWorkshops()).thenReturn(List.of(workshop));
        when(repository.meters(anyLong(), any())).thenReturn(List.of());
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of());

        EnergyQuantity output = new EnergyTotalsService(repository, false)
                .totals(DAY.toString(), DAY.toString(), "2").workshops().get(0).qualifiedOutput();

        assertEquals("MISSING", output.completeness());
        assertNull(output.knownValue(), "没查询到称重不等于当天没生产");
    }

    @Test
    void 零产出确认与正称重同时存在时以称重为准并报冲突() {
        when(repository.workshop(2L)).thenReturn(Optional.of(workshop));
        when(repository.productionWorkshops()).thenReturn(List.of(workshop));
        when(repository.meters(anyLong(), any())).thenReturn(List.of());
        when(repository.outputFacts(anyLong(), any(), any())).thenReturn(List.of(
                new OutputFact(DAY, "NO_OUTPUT", BigDecimal.ZERO),
                new OutputFact(DAY, "QUALIFIED_OUTPUT", new BigDecimal("5.000"))));

        EnergyQuantity output = new EnergyTotalsService(repository, false)
                .totals(DAY.toString(), DAY.toString(), "2").workshops().get(0).qualifiedOutput();

        assertEquals("5.000", output.knownValue());
        assertTrue(output.missingItems().stream().map(MissingItem::code)
                .anyMatch("ZERO_CONFLICT_WITH_OUTPUT"::equals));
    }

    @Test
    void 结束日早于起始日返回参数错误() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new EnergyTotalsService(repository, false).totals("2026-11-11", "2026-11-10", null));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    @Test
    void 区间超过上限返回参数错误() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new EnergyTotalsService(repository, false).totals("2025-01-01", "2026-11-10", null));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    @Test
    void 指定不存在的车间返回未找到() {
        when(repository.workshop(99L)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new EnergyTotalsService(repository, false).totals(DAY.toString(), DAY.toString(), "99"));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    private void stubMeter() {
        when(repository.workshop(2L)).thenReturn(Optional.of(workshop));
        when(repository.productionWorkshops()).thenReturn(List.of(workshop));
        when(repository.meters(anyLong(), any())).thenReturn(List.of(
                new MeterRow(11L, "EM-ELEC-02", "车间二电表", "ELECTRICITY")));
    }

    private static OffsetDateTime now() {
        return DAY.atStartOfDay(ZONE).plusHours(6).toOffsetDateTime();
    }

    private static EnergySourceRepository.HourReading hour(LocalDate date, int hour) {
        return new EnergySourceRepository.HourReading(11L,
                date.atStartOfDay(ZONE).plusHours(hour).toOffsetDateTime(),
                new BigDecimal("1.0"), "GOOD", "DELTA", now());
    }
}
