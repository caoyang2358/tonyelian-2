package cn.copper.phase2.costing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 订单盈亏四道门的判定测试（S2-K03）。
 *
 * <p>纯规则：给定生产结果、金额确认与成本缺项，判断该不该出利润。这四条门是
 * 「不让页面显示一个假的 0 元利润」的唯一保障，所以每道门都要单独验证。</p>
 *
 * <p>其中「生产已结束」这条曾经的写法是 {@code "COMPLETED".equals(productionResult)}，
 * 而 S2-R16 实际写入 {@code NORMAL_COMPLETED} / {@code SHORTFALL_COMPLETED}，
 * 结果所有正常结束的订单都被判成未结束、利润永远为空。这里用真实结果码锁住这个行为。</p>
 */
class CostQueryServiceTest {

    private static final LocalDate DUE = LocalDate.of(2026, 12, 1);
    private static final long ORDER_ID = 9301L;
    private static final long RUN_ID = 19L;

    private static CostRepository repository(String productionResult) {
        CostRepository repo = mock(CostRepository.class);
        CostRepository.RunRow run = new CostRepository.RunRow(RUN_ID, null, "COMPLETED",
                "MANUAL_RECALCULATION", CostModels.RULE_VERSION, "{}", "{}", "[]",
                "联调补算", "E03联调", OffsetDateTime.now(), OffsetDateTime.now(),
                OffsetDateTime.now(), 1L);
        when(repo.latestPublishedRun()).thenReturn(Optional.of(run));
        when(repo.orderCostTotals(RUN_ID)).thenReturn(List.of(
                new CostRepository.OrderCostTotal(ORDER_ID, new BigDecimal("900000.00"), 4, 0)));
        when(repo.ordersWithUncoveredDirectOutput(RUN_ID)).thenReturn(Set.of());
        when(repo.orders(isNull(), isNull(), anyInt(), anyInt())).thenReturn(List.of(
                new CostRepository.OrderRow(ORDER_ID, "DEMO-R-9301", "联调客户", 2L,
                        new BigDecimal("10.000"), DUE, new BigDecimal("1000000.00"),
                        new BigDecimal("1000000.00"), true, productionResult,
                        "PENDING", "COMPLETED", null, null, 3L)));
        when(repo.countOrders(isNull(), isNull())).thenReturn(1L);
        return repo;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstOrder(CostRepository repo) {
        Map<String, Object> page = new CostQueryService(repo).orders(null, null, 1, 20);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    @Test
    @DisplayName("正常结束（NORMAL_COMPLETED）且金额、成本齐全时给出估算盈利")
    void normalCompletedYieldsProfit() {
        Map<String, Object> item = firstOrder(repository("NORMAL_COMPLETED"));

        assertThat(item.get("profitDisplayable")).isEqualTo(true);
        assertThat(item.get("profitBlockedReason")).isNull();
        assertThat(item.get("totalCostAmountYuan")).isEqualTo("900000.00");
        assertThat(item.get("estimatedProfitYuan")).isEqualTo("100000.00");
    }

    @Test
    @DisplayName("不足量结束也是结束：SHORTFALL_COMPLETED 同样给利润")
    void shortfallCompletedYieldsProfit() {
        Map<String, Object> item = firstOrder(repository("SHORTFALL_COMPLETED"));

        assertThat(item.get("profitDisplayable")).isEqualTo(true);
        assertThat(item.get("estimatedProfitYuan")).isEqualTo("100000.00");
    }

    @Test
    @DisplayName("仍在生产（PENDING）时不给利润，并说明是生产未结束")
    void pendingProductionBlocksProfit() {
        Map<String, Object> item = firstOrder(repository("PENDING"));

        assertThat(item.get("profitDisplayable")).isEqualTo(false);
        assertThat(item.get("profitBlockedReason")).isEqualTo("PRODUCTION_NOT_COMPLETED");
        assertThat(item.get("estimatedProfitYuan")).isNull();
        // 已知成本照常展示：拦住的是利润，不是全部数据。
        assertThat(item.get("knownCostAmountYuan")).isEqualTo("900000.00");
    }

    @Test
    @DisplayName("成品直接归属但原料成本没有计费项写入方时，不给完整成本也不给利润")
    @SuppressWarnings("unchecked")
    void uncoveredDirectOutputBlocksCompleteCost() {
        CostRepository repo = repository("NORMAL_COMPLETED");
        when(repo.ordersWithUncoveredDirectOutput(RUN_ID)).thenReturn(Set.of(ORDER_ID));
        Map<String, Object> item = firstOrder(repo);

        assertThat(item.get("totalCostAmountYuan")).isNull();
        assertThat(item.get("profitDisplayable")).isEqualTo(false);
        assertThat(item.get("profitBlockedReason")).isEqualTo("COST_INCOMPLETE");
        assertThat(item.get("estimatedProfitYuan")).isNull();
        // 拦的是「完整成本」这个结论，已归集的已知金额照常展示。
        assertThat(item.get("knownCostAmountYuan")).isEqualTo("900000.00");
        List<Map<String, Object>> missing = (List<Map<String, Object>>) item.get("missingItems");
        assertThat(missing).extracting(entry -> entry.get("code"))
                .contains(CostModels.CODE_DIRECT_OUTPUT_COST_NOT_COVERED);
    }
}
