package cn.copper.phase2.energy;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.energy.EnergyModels.EnergyPricePage;
import cn.copper.phase2.energy.EnergyModels.PriceMatch;
import cn.copper.phase2.energy.EnergyRepository.PriceQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-E04 的查询与匹配契约。
 *
 * <p>匹配这件事只有一个正确答案：先用车间专用价，专用没有适用行才回退通用价，
 * 两边都没有就是"缺价"——返回 null 让上层显示缺项，不能用零或别处价格顶上。</p>
 */
class EnergyPriceServiceTest {

    @Test
    void 有车间专用价时优先采用专用范围() {
        EnergyRepository repository = mock(EnergyRepository.class);
        when(repository.search(any())).thenReturn(List.of());
        when(repository.count(any())).thenReturn(0L);
        when(repository.matchPrice("ELECTRICITY", 2L, LocalDate.parse("2027-01-15")))
                .thenReturn(Optional.of(new PriceMatch("9201", "WORKSHOP",
                        LocalDate.parse("2027-01-01"), "kWh", "0.8000000000", 0)));

        EnergyPricePage page = new EnergyPriceService(repository)
                .search("ELECTRICITY", "2", null, null, null, "2027-01-15", null, null, null);

        assertEquals("WORKSHOP", page.matchedPrice().scope());
        assertEquals("9201", page.matchedPrice().priceId());
    }

    @Test
    void 车间无适用价时回退通用范围() {
        EnergyRepository repository = mock(EnergyRepository.class);
        when(repository.search(any())).thenReturn(List.of());
        when(repository.count(any())).thenReturn(0L);
        when(repository.matchPrice(anyString(), any(), any()))
                .thenReturn(Optional.of(new PriceMatch("9100", "GENERAL",
                        LocalDate.parse("2026-12-01"), "kWh", "0.9000000000", 0)));

        EnergyPricePage page = new EnergyPriceService(repository)
                .search("ELECTRICITY", "1", null, null, null, "2027-01-15", null, null, null);

        // 回退由仓储层的两段查询完成，服务层只负责如实返回匹配到的范围。
        assertEquals("GENERAL", page.matchedPrice().scope());
    }

    @Test
    void 查无价格时匹配为空而不是零价() {
        EnergyRepository repository = mock(EnergyRepository.class);
        when(repository.search(any())).thenReturn(List.of());
        when(repository.count(any())).thenReturn(0L);
        when(repository.matchPrice(anyString(), any(), any())).thenReturn(Optional.empty());

        EnergyPricePage page = new EnergyPriceService(repository)
                .search("GAS", "2", null, null, null, "2027-01-15", null, null, null);

        assertNull(page.matchedPrice());
    }

    @Test
    void 不传实际日时不返回匹配结果() {
        EnergyRepository repository = mock(EnergyRepository.class);
        when(repository.search(any())).thenReturn(List.of());
        when(repository.count(any())).thenReturn(0L);

        EnergyPricePage page = new EnergyPriceService(repository)
                .search(null, null, null, null, null, null, null, null, null);

        assertNull(page.matchedPrice());
    }

    @Test
    void 默认只查当前有效价() {
        EnergyRepository repository = mock(EnergyRepository.class);
        when(repository.search(any())).thenReturn(List.of());
        when(repository.count(any())).thenReturn(0L);
        ArgumentCaptor<PriceQuery> captor = ArgumentCaptor.forClass(PriceQuery.class);

        new EnergyPriceService(repository).search(null, null, null, null, null, null, null, null, null);

        verify(repository).search(captor.capture());
        assertFalse(captor.getValue().includeHistory());
    }

    @Test
    void 通用范围与车间同时给出被拒绝() {
        // scope=GENERAL 表示 workshop_id 为空，再带车间就是自相矛盾的筛选。
        BusinessException error = assertThrows(BusinessException.class,
                () -> new EnergyPriceService(mock(EnergyRepository.class))
                        .search("ELECTRICITY", "2", "GENERAL", null, null, null, null, null, null));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
    }

    @Test
    void 按实际日匹配却不给能源类型被拒绝() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> new EnergyPriceService(mock(EnergyRepository.class))
                        .search(null, "2", null, null, null, "2027-01-15", null, null, null));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
    }

    @Test
    void 非法能源类型被拒绝() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> new EnergyPriceService(mock(EnergyRepository.class))
                        .search("STEAM", null, null, null, null, null, null, null, null));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
    }

    @Test
    void 生效日区间颠倒被拒绝() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> new EnergyPriceService(mock(EnergyRepository.class))
                        .search(null, null, null, "2027-03-01", "2027-01-01", null, null, null, null));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
    }

    @Test
    void 不给车间时只按通用范围匹配() {
        EnergyRepository repository = mock(EnergyRepository.class);
        when(repository.search(any())).thenReturn(List.of());
        when(repository.count(any())).thenReturn(0L);
        when(repository.matchPrice(anyString(), any(), any())).thenReturn(Optional.empty());

        new EnergyPriceService(repository)
                .search("WATER", null, null, null, null, "2027-01-15", null, null, null);

        // workshopId 传 null 表示"该能源的默认价"，仓储层据此只查通用范围。
        verify(repository).matchPrice("WATER", null, LocalDate.parse("2027-01-15"));
    }
}
