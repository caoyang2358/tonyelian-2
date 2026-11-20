package cn.copper.phase2.costing;

import cn.copper.phase2.costing.StockValuation.Direction;
import cn.copper.phase2.costing.StockValuation.Event;
import cn.copper.phase2.costing.StockValuation.Result;
import cn.copper.phase2.costing.StockValuation.Valued;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 移动加权均价回放内核。用例数值直接取
 * {@code 系统二-生产与能源统计-数据与计算设计.md} 6.3 的示例，便于与文档逐位对照。
 */
class StockValuationTest {

    @Test
    @DisplayName("文档 6.3 示例：10 吨 60 万 + 10 吨 64 万 → 20 吨 124 万，均价 6.2 万，交出 4 吨带出 24.8 万")
    void matchesDocumentExample() {
        Result result = StockValuation.replay(List.of(
                Event.in("opening", "10.000", "600000.00"),
                Event.in("receipt", "10.000", "640000.00"),
                Event.out("issue", "4.000")));

        List<Valued> events = result.events();
        assertThat(events).hasSize(3);

        assertThat(events.get(1).quantityAfterTon()).isEqualByComparingTo("20.000");
        assertThat(events.get(1).unitCostYuanPerTon()).isEqualByComparingTo("62000");
        assertThat(events.get(1).valueAfterYuan()).isEqualByComparingTo("1240000.00");

        Valued issue = events.get(2);
        assertThat(issue.costDeltaYuan()).isEqualByComparingTo("-248000.00");
        assertThat(issue.quantityAfterTon()).isEqualByComparingTo("16.000");
        assertThat(issue.valueAfterYuan()).isEqualByComparingTo("992000.00");
        assertThat(result.endQuantityTon()).isEqualByComparingTo("16.000");
        assertThat(result.endValueYuan()).isEqualByComparingTo("992000.00");
        assertThat(result.complete()).isTrue();
    }

    @Test
    @DisplayName("分三次清空库存：每步按剩余价值÷剩余实物重算均价，出库合计等于入库成本，余值归零")
    void flushTailDifferenceWhenPoolEmpties() {
        Result result = StockValuation.replay(List.of(
                Event.in("opening", "6.000", "1000000.00"),
                Event.out("issue-1", "2.000"),
                Event.out("issue-2", "2.000"),
                Event.out("issue-3", "2.000")));

        List<Valued> issues = result.events().subList(1, 4);
        // 均价每步重算，所以三笔并非等额：100 万/6 吨 = 166666.6666666667/吨，
        // 第一笔 333333.33 后剩 666666.67/4 吨 = 166666.6675/吨，第二笔便多 1 分。
        assertThat(issues.get(0).knownCostYuan()).isEqualByComparingTo("333333.33");
        assertThat(issues.get(1).knownCostYuan()).isEqualByComparingTo("333333.34");
        assertThat(issues.get(2).knownCostYuan()).isEqualByComparingTo("333333.33");

        // 真正的验收点是守恒：出库合计必须正好等于入库成本，不许多一分也不许少一分。
        BigDecimal issued = issues.stream()
                .map(Valued::knownCostYuan)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(issued).isEqualByComparingTo("1000000.00");

        assertThat(result.endQuantityTon()).isEqualByComparingTo("0.000");
        assertThat(result.endValueYuan()).isEqualByComparingTo("0.00");

        // 清零后均价无意义，不能返回 0 冒充「每吨 0 元」。
        assertThat(issues.get(2).unitCostYuanPerTon()).isNull();
    }

    @Test
    @DisplayName("部分成本未知：均价与完整领用成本为 null，已知部分照常回放，缺项保留到出库行")
    void unknownCostKeepsKnownPartAndMissingItems() {
        Result result = StockValuation.replay(List.of(
                Event.inWithUnknownCost("receipt-unknown", "5.000", "MATERIAL_AMOUNT_MISSING"),
                Event.in("receipt-known", "5.000", "50000.00"),
                Event.out("issue", "2.000")));

        Valued unknownReceipt = result.events().get(0);
        assertThat(unknownReceipt.unitCostYuanPerTon()).isNull();
        assertThat(unknownReceipt.costDeltaYuan()).isNull();
        assertThat(unknownReceipt.missingItems()).containsExactly("MATERIAL_AMOUNT_MISSING");

        // 后来有完整入库，也不能把均价补成已知——历史缺项不会自行消失。
        Valued knownReceipt = result.events().get(1);
        assertThat(knownReceipt.unitCostYuanPerTon()).isNull();
        assertThat(knownReceipt.costDeltaYuan()).isEqualByComparingTo("50000.00");

        Valued issue = result.events().get(2);
        assertThat(issue.costDeltaYuan()).isNull();
        assertThat(issue.knownCostYuan()).isEqualByComparingTo("10000.00");
        assertThat(issue.missingItems()).containsExactly("MATERIAL_AMOUNT_MISSING");

        assertThat(result.complete()).isFalse();
        assertThat(result.endValueYuan()).isNull();
        assertThat(result.endKnownValueYuan()).isEqualByComparingTo("40000.00");
        assertThat(result.missingItems()).containsExactly("MATERIAL_AMOUNT_MISSING");
    }

    @Test
    @DisplayName("出库超过事件前实物直接拒绝，不把库存算成负数")
    void rejectsIssueBeyondPhysical() {
        assertThatThrownBy(() -> StockValuation.replay(List.of(
                Event.in("opening", "5.000", "100000.00"),
                Event.out("issue", "6.000"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超过事件前实物");
    }

    @Test
    @DisplayName("吨数必须真的是三位小数，四位小数应在入口被拦下而不是静默取整")
    void rejectsQuantityBeyondTonScale() {
        assertThatThrownBy(() -> Event.out("issue", "1.2345"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("成本未知的入库必须带缺项说明，否则无法追溯")
    void unknownInboundRequiresMissingReason() {
        assertThatThrownBy(() -> new Event("receipt", Direction.IN, new java.math.BigDecimal("1.000"),
                null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须说明缺项");
    }

    @Test
    @DisplayName("入库成本不完整但材料金额已知：已知部分进池，完整均价留空")
    void partialCostContributesKnownPartOnly() {
        Result result = StockValuation.replay(List.of(
                Event.inWithPartialCost("rod-output", "10.000", "600000.00", "WORKSHOP1_ENERGY_MISSING"),
                Event.out("handover", "4.000")));

        Valued receipt = result.events().get(0);
        assertThat(receipt.costDeltaYuan()).isNull();
        assertThat(receipt.knownCostYuan()).isEqualByComparingTo("600000.00");
        // 单价算不出来：能源那部分还没有依据，不能只用材料金额冒充完整入库单价。
        assertThat(receipt.unitCostAppliedYuanPerTon()).isNull();
        assertThat(receipt.unitCostYuanPerTon()).isNull();

        Valued issue = result.events().get(1);
        assertThat(issue.costDeltaYuan()).isNull();
        assertThat(issue.knownCostYuan()).isEqualByComparingTo("240000.00");
        assertThat(issue.unitCostAppliedYuanPerTon()).isEqualByComparingTo("60000.0000000000");

        assertThat(result.complete()).isFalse();
        assertThat(result.endKnownValueYuan()).isEqualByComparingTo("360000.00");
        assertThat(result.missingItems()).containsExactly("WORKSHOP1_ENERGY_MISSING");
    }
}
