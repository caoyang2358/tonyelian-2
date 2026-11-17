package cn.copper.phase2.energy;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.energy.EnergySourceRepository.WorkshopRow;
import cn.copper.phase2.energy.EnergyStatModels.DailyConsumptionItem;
import cn.copper.phase2.energy.EnergyStatModels.DailyConsumptionPage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
 * S2-E02 的日槽展开、筛选与分页。
 *
 * <p>核心是「缺口也要占位」：某天某能源没数，槽位仍要在列表里出现并说明缺什么。
 * 如果让它直接消失，页面就会显示成"这一天没问题"。</p>
 */
class EnergyDailyConsumptionServiceTest {
    private static final LocalDate DAY = LocalDate.parse("2026-11-10");
    private final EnergySourceRepository repository = mock(EnergySourceRepository.class);
    private final WorkshopRow workshop = new WorkshopRow(2L, "车间2 · 铜丝拉制", "拉丝");

    @Test
    void 缺表计时每个日期每种能源各占一个缺测槽() {
        when(repository.productionWorkshops()).thenReturn(List.of(workshop));
        when(repository.meters(anyLong(), any())).thenReturn(List.of());

        DailyConsumptionPage page = new EnergyDailyConsumptionService(repository, false)
                .search(DAY.toString(), DAY.plusDays(1).toString(), null, null, null, null, null, null);

        assertEquals(6, page.total(), "2 天 × 3 种能源");
        assertEquals(0, page.summary().completeCount());
        assertEquals(6, page.summary().missingCount());
        DailyConsumptionItem first = page.items().get(0);
        assertEquals("MISSING", first.completeness());
        assertNull(first.meter(), "没有表计时不给表计身份");
        assertEquals("METER_NOT_CONFIGURED", first.missingItems().get(0).code());
        assertEquals("kWh", first.unit());
    }

    @Test
    void 排序把最近的日期排在前面() {
        when(repository.productionWorkshops()).thenReturn(List.of(workshop));
        when(repository.meters(anyLong(), any())).thenReturn(List.of());

        DailyConsumptionPage page = new EnergyDailyConsumptionService(repository, false)
                .search(DAY.toString(), DAY.plusDays(2).toString(), null, "ELECTRICITY", null, null, 1, 100);

        assertEquals(DAY.plusDays(2), page.items().get(0).actualDate());
        assertEquals(DAY, page.items().get(page.items().size() - 1).actualDate());
    }

    @Test
    void 分页只切列表但汇总统计全量() {
        when(repository.productionWorkshops()).thenReturn(List.of(workshop));
        when(repository.meters(anyLong(), any())).thenReturn(List.of());

        DailyConsumptionPage page = new EnergyDailyConsumptionService(repository, false)
                .search(DAY.toString(), DAY.plusDays(2).toString(), null, null, null, null, 1, 2);

        assertEquals(2, page.items().size());
        assertEquals(9, page.total());
        assertEquals(9, page.summary().slotTotal(), "汇总不能只看本页");
        assertEquals(9, page.summary().missingCount());
    }

    @Test
    void 按完整性筛选只留匹配的槽() {
        when(repository.productionWorkshops()).thenReturn(List.of(workshop));
        when(repository.meters(anyLong(), any())).thenReturn(List.of());

        DailyConsumptionPage page = new EnergyDailyConsumptionService(repository, false)
                .search(DAY.toString(), DAY.toString(), null, null, null, "COMPLETE", null, null);

        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
        assertEquals(3, page.summary().slotTotal());
    }

    @Test
    void 车间过滤只查指定车间() {
        when(repository.workshop(2L)).thenReturn(Optional.of(workshop));
        when(repository.meters(anyLong(), any())).thenReturn(List.of());

        DailyConsumptionPage page = new EnergyDailyConsumptionService(repository, false)
                .search(DAY.toString(), DAY.toString(), "2", "GAS", null, null, null, null);

        assertEquals(1, page.total());
        assertEquals("GAS", page.items().get(0).energyType());
        assertEquals("Nm³", page.items().get(0).unit());
    }

    @Test
    void 非法能源类型返回参数错误() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new EnergyDailyConsumptionService(repository, false)
                        .search(DAY.toString(), DAY.toString(), null, "STEAM", null, null, null, null));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    @Test
    void 每页条数超过上限返回参数错误() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new EnergyDailyConsumptionService(repository, false)
                        .search(DAY.toString(), DAY.toString(), null, null, null, null, 1, 101));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    @Test
    void 缺少起始日返回参数错误() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new EnergyDailyConsumptionService(repository, false)
                        .search(null, DAY.toString(), null, null, null, null, null, null));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }
}
