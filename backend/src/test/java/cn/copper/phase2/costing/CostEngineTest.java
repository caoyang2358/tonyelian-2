package cn.copper.phase2.costing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成本回放引擎的规则测试。
 *
 * <p>这里全部用构造事实，不连库：一次投料多次产出的分配、领用带出成本、订单计费项、
 * 以及「金额未知时不许编造」这些规则，必须在没有一整套库存的情况下也能验证。</p>
 */
class CostEngineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 17);

    private static final CostEngine.Material PLATE =
            new CostEngine.Material(1, "COPPER-PLATE", "铜板", "COPPER_PLATE", "");
    private static final CostEngine.Material ROD =
            new CostEngine.Material(2, "COPPER-ROD-PHI8", "Φ8铜杆", "COPPER_ROD", "Φ8");
    private static final CostEngine.Material WIRE =
            new CostEngine.Material(3, "WIRE-3.0", "铜丝 3.0", "FINISHED_WIRE", "3.0");

    @Test
    @DisplayName("一次投料多次产出：材料金额按各次合格量分配，能源未归集时只入已知部分")
    void allocatesMaterialAmountByQualifiedQuantity() {
        CostEngine.Inputs inputs = new CostEngine.Inputs(
                List.of(PLATE, ROD),
                List.of(
                        movement(10, 1, null, null, "PLATE_RECEIPT", "2026-11-17", "10.000"),
                        movement(11, 1, null, 1L, "PRODUCTION_ISSUE", "2026-11-17", "-10.000"),
                        movement(12, 2, null, 1L, "PRODUCTION_RECEIPT", "2026-11-17", "4.000"),
                        movement(13, 2, null, 1L, "PRODUCTION_RECEIPT", "2026-11-17", "6.000")),
                List.of(
                        output(101, 2, 1L, "T:1:2026-11-17", "4.000", 12),
                        output(102, 2, 1L, "T:1:2026-11-17", "6.000", 13)),
                List.of(new CostEngine.PlateInputFact(1L, "T:1:2026-11-17", DAY,
                        new BigDecimal("600000.00"))),
                List.of(),
                List.of());

        CostEngine.Outcome outcome = CostEngine.replay(inputs);

        CostEngine.MaterialResult rod = material(outcome, 2);
        assertThat(rod.ledger()).hasSize(2);
        // 4 吨分得 24 万、6 吨分得 36 万：不是把 60 万各记一遍，也不是按吨数平均以外的规则。
        assertThat(rod.ledger().get(0).costDeltaYuan()).isEqualByComparingTo("240000.00");
        assertThat(rod.ledger().get(1).costDeltaYuan()).isEqualByComparingTo("360000.00");
        // 车间一能源还没有归集依据，均价与完整金额一律留空。
        assertThat(rod.ledger().get(0).unitCostYuanPerTon()).isNull();
        assertThat(rod.ledger().get(0).missingItems())
                .containsExactly(CostModels.CODE_WORKSHOP1_ENERGY_MISSING);
        assertThat(outcome.complete()).isFalse();
    }

    @Test
    @DisplayName("铜杆领用按已知成本带出，并生成订单 ROD_ISSUE 计费项（正值）")
    void issuesRodCostToOrder() {
        CostEngine.Inputs inputs = new CostEngine.Inputs(
                List.of(PLATE, ROD),
                List.of(
                        movement(20, 2, null, 1L, "PRODUCTION_RECEIPT", "2026-11-17", "10.000"),
                        movement(21, 2, 7L, 1L, "PRODUCTION_ISSUE", "2026-11-17", "-4.000")),
                List.of(output(201, 2, 1L, "T:1:2026-11-17", "10.000", 20)),
                List.of(new CostEngine.PlateInputFact(1L, "T:1:2026-11-17", DAY,
                        new BigDecimal("600000.00"))),
                List.of(),
                List.of());

        CostEngine.Outcome outcome = CostEngine.replay(inputs);

        CostEngine.LedgerDraft issue = material(outcome, 2).ledger().get(1);
        assertThat(issue.entryType()).isEqualTo(CostModels.ENTRY_ISSUE);
        // 10 吨已知 60 万 → 每吨 6 万，4 吨带出 24 万（负数减少在库价值）。
        assertThat(issue.costDeltaYuan()).isEqualByComparingTo("-240000.00");
        assertThat(issue.orderId()).isEqualTo(7L);

        assertThat(outcome.orders()).hasSize(1);
        CostEngine.OrderDraft item = outcome.orders().get(0);
        assertThat(item.itemType()).isEqualTo(CostModels.ITEM_ROD_ISSUE);
        // 订单承担的是正的成本金额。
        assertThat(item.amountYuan()).isEqualByComparingTo("240000.00");
        assertThat(item.missingItems()).containsExactly(CostModels.CODE_WORKSHOP1_ENERGY_MISSING);
    }

    @Test
    @DisplayName("期初与到货只有数量：金额按 0 入已知部分并标缺项，不静默当成本为零")
    void openingAndReceiptWithoutAmountStayOpen() {
        CostEngine.Inputs inputs = new CostEngine.Inputs(
                List.of(ROD),
                List.of(
                        movement(30, 2, null, null, "OPENING", "2026-11-01", "10.000"),
                        movement(31, 2, null, null, "PLATE_RECEIPT", "2026-11-02", "5.000")),
                List.of(), List.of(), List.of(), List.of());

        CostEngine.Outcome outcome = CostEngine.replay(inputs);

        CostEngine.MaterialResult rod = material(outcome, 2);
        assertThat(rod.ledger().get(0).entryType()).isEqualTo(CostModels.ENTRY_OPENING);
        assertThat(rod.ledger().get(0).costDeltaYuan()).isEqualByComparingTo("0.00");
        assertThat(rod.ledger().get(0).missingItems())
                .containsExactly(CostModels.CODE_OPENING_COST_MISSING);
        assertThat(rod.ledger().get(1).entryType()).isEqualTo(CostModels.ENTRY_RECEIPT);
        assertThat(rod.ledger().get(1).missingItems())
                .containsExactly(CostModels.CODE_RECEIPT_AMOUNT_MISSING);
        assertThat(rod.complete()).isFalse();
    }

    @Test
    @DisplayName("出库超过实物的物料整体标缺项，不抛异常也不影响其它物料")
    void inconsistentMaterialIsMarkedNotThrown() {
        CostEngine.Inputs inputs = new CostEngine.Inputs(
                List.of(PLATE, ROD),
                List.of(
                        movement(40, 1, null, null, "OPENING", "2026-11-01", "5.000"),
                        movement(41, 1, null, null, "PRODUCTION_ISSUE", "2026-11-02", "-6.000"),
                        movement(42, 2, null, null, "OPENING", "2026-11-01", "3.000")),
                List.of(), List.of(), List.of(), List.of());

        CostEngine.Outcome outcome = CostEngine.replay(inputs);

        CostEngine.MaterialResult plate = material(outcome, 1);
        assertThat(plate.result()).isNull();
        assertThat(plate.ledger()).isEmpty();
        assertThat(plate.missingItems().get(0)).contains("超过事件前实物");

        CostEngine.MaterialResult rod = material(outcome, 2);
        assertThat(rod.ledger()).hasSize(1);
        assertThat(outcome.complete()).isFalse();
    }

    @Test
    @DisplayName("成本流水按实际日排序，补录的较早事实不会排到后面")
    void ledgerFollowsBusinessDate() {
        CostEngine.Inputs inputs = new CostEngine.Inputs(
                List.of(ROD),
                List.of(
                        // 先录的是 11-18，后补的是 11-17：排序必须按业务日而不是录入顺序。
                        movement(52, 2, null, null, "OPENING", "2026-11-18", "2.000"),
                        movement(51, 2, null, null, "OPENING", "2026-11-17", "1.000")),
                List.of(), List.of(), List.of(), List.of());

        CostEngine.MaterialResult rod = material(CostEngine.replay(inputs), 2);
        assertThat(rod.ledger()).extracting(CostEngine.LedgerDraft::movementId)
                .containsExactly(51L, 52L);
    }

    @Test
    @DisplayName("铜丝入库引用交接但上游成本未知：标 ROD_COST_MISSING，不按 0 计")
    void wireReceiptWithoutRodCostStaysOpen() {
        CostEngine.Inputs inputs = new CostEngine.Inputs(
                List.of(ROD, WIRE),
                List.of(
                        movement(60, 2, null, 1L, "PRODUCTION_ISSUE", "2026-11-17", "-5.000"),
                        movement(61, 3, null, 2L, "PRODUCTION_RECEIPT", "2026-11-17", "4.800")),
                List.of(new CostEngine.OutputFact(301, 3, 2L, "T:2:2026-11-17", DAY,
                        new BigDecimal("4.800"), "QUALIFIED_OUTPUT", 61,
                        List.of(new CostEngine.RodUseRef(900, new BigDecimal("5.000"))))),
                List.of(),
                List.of(new CostEngine.HandoverFact(900, 60, new BigDecimal("5.000"))),
                List.of());

        CostEngine.Outcome outcome = CostEngine.replay(inputs);

        CostEngine.MaterialResult wire = material(outcome, 3);
        assertThat(wire.ledger()).hasSize(1);
        assertThat(wire.ledger().get(0).costDeltaYuan()).isEqualByComparingTo("0.00");
        assertThat(wire.ledger().get(0).missingItems())
                .containsExactly(CostModels.CODE_ROD_COST_MISSING,
                        CostModels.CODE_WORKSHOP2_ENERGY_MISSING);
    }

    @Test
    @DisplayName("车间一能源已落库：铜杆入库成本＝材料金额＋能源，能源不再标缺项")
    void rodReceiptIncludesWorkshop1Energy() {
        CostEngine.Inputs inputs = new CostEngine.Inputs(
                List.of(PLATE, ROD),
                List.of(
                        movement(10, 1, null, null, "PLATE_RECEIPT", "2026-11-17", "10.000"),
                        movement(11, 1, null, 1L, "PRODUCTION_ISSUE", "2026-11-17", "-10.000"),
                        movement(12, 2, null, 1L, "PRODUCTION_RECEIPT", "2026-11-17", "6.000")),
                List.of(output(101, 2, 1L, "T:1:2026-11-17", "6.000", 12)),
                List.of(new CostEngine.PlateInputFact(1L, "T:1:2026-11-17", DAY,
                        new BigDecimal("600000.00"))),
                List.of(),
                List.of(new CostEngine.EnergyFact(501, 1, DAY, "ELECTRICITY", "ROD_STOCK",
                        null, 101L, new BigDecimal("6.000"), new BigDecimal("7260.00"))));

        CostEngine.Outcome outcome = CostEngine.replay(inputs);

        CostEngine.MaterialResult rod = material(outcome, 2);
        // 60 万材料 + 7260 元车间一能源，一次计入；不把能源另记一笔，也不按 0 计。
        assertThat(rod.ledger().get(0).costDeltaYuan()).isEqualByComparingTo("607260.00");
        assertThat(rod.ledger().get(0).unitCostYuanPerTon()).isEqualByComparingTo("101210.00");
        assertThat(rod.ledger().get(0).missingItems()).isEmpty();
        assertThat(rod.complete()).isTrue();
    }

    @Test
    @DisplayName("车间二能源按当日占比摊入铜丝入库，并生成带分摊行引用的订单能源项")
    void wireReceiptIncludesWorkshop2Energy() {
        CostEngine.OutputFact wireOutput = new CostEngine.OutputFact(202, 3, 2L, "T:2:2026-11-17", DAY,
                new BigDecimal("5.900"), "QUALIFIED_OUTPUT", 22,
                List.of(new CostEngine.RodUseRef(900, new BigDecimal("6.000"))));
        CostEngine.Inputs inputs = new CostEngine.Inputs(
                List.of(PLATE, ROD, WIRE),
                List.of(
                        movement(10, 1, null, null, "PLATE_RECEIPT", "2026-11-17", "10.000"),
                        movement(11, 1, null, 1L, "PRODUCTION_ISSUE", "2026-11-17", "-10.000"),
                        movement(12, 2, null, 1L, "PRODUCTION_RECEIPT", "2026-11-17", "6.000"),
                        movement(21, 2, 7L, 1L, "PRODUCTION_ISSUE", "2026-11-17", "-6.000"),
                        movement(22, 3, null, 2L, "PRODUCTION_RECEIPT", "2026-11-17", "5.900")),
                List.of(output(201, 2, 1L, "T:1:2026-11-17", "6.000", 12), wireOutput),
                List.of(new CostEngine.PlateInputFact(1L, "T:1:2026-11-17", DAY,
                        new BigDecimal("600000.00"))),
                List.of(new CostEngine.HandoverFact(900, 21, new BigDecimal("6.000"))),
                List.of(
                        new CostEngine.EnergyFact(601, 2, DAY, "ELECTRICITY", "ORDER", 7L, null,
                                new BigDecimal("5.000"), new BigDecimal("3313.55")),
                        new CostEngine.EnergyFact(602, 2, DAY, "ELECTRICITY", "PUBLIC_FINISHED", null, null,
                                new BigDecimal("0.900"), new BigDecimal("596.45"))));

        CostEngine.Outcome outcome = CostEngine.replay(inputs);

        CostEngine.MaterialResult wire = material(outcome, 3);
        // 领杆 60 万 + 当日车间二能源 3910（订单 3313.55 + 公共 596.45），当天只有这一次称重，全部摊给它。
        assertThat(wire.ledger().get(0).costDeltaYuan()).isEqualByComparingTo("603910.00");
        assertThat(wire.ledger().get(0).missingItems()).isEmpty();

        // 订单侧只认自己那份能源，并且必须回指分摊行——表上的 CHECK 不允许它没有引用。
        assertThat(outcome.orders()).anySatisfy(item -> {
            assertThat(item.itemType()).isEqualTo(CostModels.ITEM_WORKSHOP_ENERGY);
            assertThat(item.amountYuan()).isEqualByComparingTo("3313.55");
            assertThat(item.energyAllocationId()).isEqualTo(601L);
        });
        // 公共份额不进订单，但已经通过入库成本进了库存价值，不重复计。
        assertThat(outcome.orders()).noneSatisfy(item ->
                assertThat(item.amountYuan()).isEqualByComparingTo("596.45"));
    }

    @Test
    @DisplayName("成品直接归属：按归属重量占该次合格量的比例，把产出入库成本写成 PUBLIC_FINISHED 计费项")
    void allocatesWireReceiptCostToDirectOutputOrders() {
        CostEngine.Inputs inputs = new CostEngine.Inputs(
                List.of(PLATE, ROD, WIRE),
                List.of(
                        movement(70, 1, null, 1L, "PLATE_RECEIPT", "2026-11-17", "10.000"),
                        movement(71, 1, null, 1L, "PRODUCTION_ISSUE", "2026-11-17", "-10.000"),
                        movement(72, 2, null, 1L, "PRODUCTION_RECEIPT", "2026-11-17", "10.000"),
                        movement(73, 2, null, 2L, "PRODUCTION_ISSUE", "2026-11-17", "-10.000"),
                        movement(74, 3, null, 2L, "PRODUCTION_RECEIPT", "2026-11-17", "10.000")),
                List.of(
                        output(801, 2, 1L, "T:1:2026-11-17", "10.000", 72),
                        new CostEngine.OutputFact(802, 3, 2L, "T:2:2026-11-17", DAY,
                                new BigDecimal("10.000"), "QUALIFIED_OUTPUT", 74,
                                List.of(new CostEngine.RodUseRef(910, new BigDecimal("10.000"))))),
                List.of(new CostEngine.PlateInputFact(1L, "T:1:2026-11-17", DAY,
                        new BigDecimal("600000.00"))),
                List.of(new CostEngine.HandoverFact(910, 73, new BigDecimal("10.000"))),
                List.of(
                        // 车间一能源明确为零，铜杆入库成本才能完整，交接才带得出单价。
                        new CostEngine.EnergyFact(701, 1, DAY, "ELECTRICITY", "ROD_STOCK",
                                null, 801L, null, BigDecimal.ZERO),
                        new CostEngine.EnergyFact(702, 2, DAY, "ELECTRICITY", "ORDER",
                                null, null, null, new BigDecimal("1000.00"))));

        CostEngine.Outcome outcome = CostEngine.replay(inputs, List.of(
                new CostEngine.FinishedAllocationFact(1, 9302, 3, 802, new BigDecimal("3.000")),
                new CostEngine.FinishedAllocationFact(2, 9303, 3, 802, new BigDecimal("4.000"))));

        CostEngine.MaterialResult wire = material(outcome, 3);
        assertThat(wire.ledger()).hasSize(1);
        // 领杆 10 吨 × 6 万 = 60 万，加当日车间二能源 1000，铜丝入库成本 601000。
        assertThat(wire.ledger().get(0).costDeltaYuan()).isEqualByComparingTo("601000.00");

        assertThat(outcome.orders())
                .filteredOn(item -> item.itemType().equals(CostModels.ITEM_PUBLIC_FINISHED))
                .hasSize(2)
                .anySatisfy(item -> {
                    assertThat(item.orderId()).isEqualTo(9302L);
                    // 601000 × 3/10
                    assertThat(item.amountYuan()).isEqualByComparingTo("180300.00");
                    // 必须回指产出入库流水：写入端据此回填 stock_cost_ledger_id，表上 CHECK 要求非空。
                    assertThat(item.movementId()).isEqualTo(74L);
                    assertThat(item.missingItems()).isEmpty();
                })
                .anySatisfy(item -> {
                    assertThat(item.orderId()).isEqualTo(9303L);
                    assertThat(item.amountYuan()).isEqualByComparingTo("240400.00");
                });
    }

    @Test
    @DisplayName("期初登记了成本：按登记金额入账，均价可用、不再报期初缺项")
    void openingWithRegisteredCostIsUsable() {
        CostEngine.Inputs inputs = new CostEngine.Inputs(
                List.of(PLATE),
                List.of(movementWithCost(50, 1, null, null, "OPENING", "2026-11-01", "20.000", "1200000.00")),
                List.of(), List.of(), List.of(), List.of());

        CostEngine.Outcome outcome = CostEngine.replay(inputs);

        CostEngine.MaterialResult plate = material(outcome, 1);
        assertThat(plate.ledger().get(0).costDeltaYuan()).isEqualByComparingTo("1200000.00");
        assertThat(plate.ledger().get(0).unitCostYuanPerTon()).isEqualByComparingTo("60000");
        assertThat(plate.ledger().get(0).missingItems()).isEmpty();
        assertThat(plate.complete()).isTrue();
    }

    // ---------------------------------------------------------------- 构造器

    private static CostEngine.Movement movement(long movementId, long materialId, Long orderId, Long taskId,
                                                String type, String date, String delta) {
        return new CostEngine.Movement(movementId, materialId, orderId, taskId, type,
                "TEST", String.valueOf(movementId), LocalDate.parse(date), new BigDecimal(delta), null, null);
    }

    /** 期初/到货带登记的金额；其余字段与 {@link #movement} 一致。 */
    private static CostEngine.Movement movementWithCost(long movementId, long materialId, Long orderId, Long taskId,
                                                        String type, String date, String delta, String cost) {
        return new CostEngine.Movement(movementId, materialId, orderId, taskId, type,
                "TEST", String.valueOf(movementId), LocalDate.parse(date), new BigDecimal(delta), null,
                new BigDecimal(cost));
    }

    private static CostEngine.OutputFact output(long outputId, long materialId, Long taskId, String groupKey,
                                                String qualified, long movementId) {
        return new CostEngine.OutputFact(outputId, materialId, taskId, groupKey, DAY,
                new BigDecimal(qualified), "QUALIFIED_OUTPUT", movementId, List.of());
    }

    private static CostEngine.MaterialResult material(CostEngine.Outcome outcome, long materialId) {
        return outcome.materials().stream()
                .filter(item -> item.materialId() == materialId)
                .findFirst()
                .orElseThrow();
    }
}
