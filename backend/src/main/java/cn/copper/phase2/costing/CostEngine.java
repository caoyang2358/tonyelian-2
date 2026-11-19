package cn.copper.phase2.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 成本回放引擎：把数量事实翻译成计价事件，回放出成本流水与订单计费项。
 *
 * <p><b>纯函数</b>：输入是已经读好的事实（流水、产出、投料、交接），输出是待写入的
 * 台账草稿。不查库、不写库，因此可以直接用构造数据单测「一次投料多次产出」「材料金额
 * 已知而能源待补」这些规则，而不必先造一整套库存。</p>
 *
 * <p>物料按 <b>铜板 → 铜杆 → 铜丝</b> 的顺序回放：铜丝入库成本引用铜杆交出成本，
 * 顺序反过来就取不到上一段的结果。同一物料内部按（实际日，流水号）排序——这是设计
 * 文档 6.2 的 {@code RECORDED_ORDER} 口径，不跨表拿 ID 比时间。</p>
 */
public final class CostEngine {

    /** 物料类型对应的回放位置。 */
    public enum Kind { PLATE, ROD, WIRE, OTHER }

    public record Material(long materialId, String code, String name, String type, String specification) {
        public Kind kind() {
            return switch (type) {
                case "COPPER_PLATE" -> Kind.PLATE;
                case "COPPER_ROD" -> Kind.ROD;
                case "FINISHED_WIRE" -> Kind.WIRE;
                default -> Kind.OTHER;
            };
        }
    }

    /** 一条数量事实。金额不在这里——它由成本规则另行推导。 */
    public record Movement(long movementId, long materialId, Long orderId, Long taskId,
                           String movementType, String sourceType, String sourceId,
                           LocalDate businessDate, BigDecimal quantityDelta, Long correctsMovementId) {
    }

    /** 铜丝称重引用的领杆份额。 */
    public record RodUseRef(long handoverId, BigDecimal sentQuantityTon) {
    }

    /** 一次合格产出；{@code groupKey} 把「一次投料多次产出」圈在一起。 */
    public record OutputFact(long outputId, long materialId, Long taskId, String groupKey,
                             LocalDate actualDate, BigDecimal qualifiedTon, String recordType,
                             long movementId, List<RodUseRef> rodRefs) {
    }

    /** 一次铜板投料。金额可空——现场常常先录数量、金额后补。 */
    public record PlateInputFact(Long taskId, String groupKey, LocalDate actualDate, BigDecimal amountYuan) {
    }

    /** 一次领杆交接：铜杆出库流水号用于取该笔交出的上游成本。 */
    public record HandoverFact(long handoverId, long movementId, BigDecimal sentQuantityTon) {
    }

    /**
     * 已落库的能源分摊。目标类型决定去向：{@code ROD_STOCK} 进对应产出的铜杆入库成本，
     * {@code ORDER} 成为该订单的车间能源计费项。金额可能为 null（消耗或单价不完整），
     * 此时按缺项处理，不当 0。
     *
     * <p>{@code allocationId} 是分摊行主键。订单计费项必须回指它——表上有一条 CHECK：
     * {@code WORKSHOP_ENERGY} 行只允许有分摊行引用、不允许有成本流水引用。</p>
     */
    public record EnergyFact(long allocationId, long workshopId, LocalDate actualDate, String energyType,
                             String targetType, Long orderId, Long outputId,
                             BigDecimal qualifiedQuantityTon, BigDecimal amountYuan) {
    }

    public record Inputs(List<Material> materials, List<Movement> movements, List<OutputFact> outputs,
                         List<PlateInputFact> plateInputs, List<HandoverFact> handovers,
                         List<EnergyFact> energyAllocations) {
    }

    /**
     * 成品直接归属订单。
     *
     * <p>成本口径：该次产出入库成本 × （归属重量 ÷ 该次合格量）。与验收文档第 4 节
     * 「同次合格 10 吨、总成本 60 万，A 归属 8 吨、公共 2 吨 → A 48 万、公共 12 万」一致。
     * 归属只是成本份额的转移，不再产生一次实物入库。</p>
     *
     * @param allocatedTon 有效归属量（已扣除释放回公共的部分）
     */
    public record FinishedAllocationFact(long allocationId, long orderId, long materialId,
                                         long outputId, BigDecimal allocatedTon) {
    }

    /** 一次铜丝产出入库的估值结果，用于按归属重量拆成本。 */
    private record WireReceipt(long movementId, LocalDate businessDate, BigDecimal quantityTon,
                               BigDecimal costYuan, boolean complete, List<String> missingItems) {
    }

    /**
     * 待写入的一行成本流水。
     *
     * @param costDeltaYuan 本行计入库存价值的金额，有符号：入库为正、出库为负。
     *                      成本不完整时写<b>已知部分</b>，由 {@code missingItems} 说明还缺什么，
     *                      这样「已知在库价值」才有依据，而不是一律为 0。
     * @param unitCostYuanPerTon 成本完整时才给；不完整时留空，不用已知部分除以全量冒充均价。
     */
    public record LedgerDraft(long materialId, Long orderId, Long movementId, String entryType,
                              String sourceType, long sourceId, LocalDate businessDate,
                              BigDecimal quantityTon, BigDecimal unitCostYuanPerTon,
                              BigDecimal costDeltaYuan, List<String> missingItems) {
    }

    /** 待写入的一行订单计费项；{@code movementId} 用来回填成本流水主键，能源项则回填分摊行主键。 */
    public record OrderDraft(long orderId, String itemType, String sourceKey, LocalDate businessDate,
                             Long movementId, Long energyAllocationId, BigDecimal amountYuan,
                             List<String> missingItems) {
    }

    public record MaterialResult(long materialId, StockValuation.Result result, List<LedgerDraft> ledger,
                                 boolean complete, List<String> missingItems) {
    }

    public record Outcome(List<MaterialResult> materials, List<OrderDraft> orders,
                          List<String> missingItems, boolean complete) {
    }

    private CostEngine() {
    }

    public static Outcome replay(Inputs inputs) {
        return replay(inputs, List.of());
    }

    /**
     * @param finishedAllocations 成品直接归属事实；为空时不生成 {@code PUBLIC_FINISHED} 计费项，
     *                            由查询侧按 {@code DIRECT_OUTPUT_COST_NOT_COVERED} 报缺项。
     */
    public static Outcome replay(Inputs inputs, List<FinishedAllocationFact> finishedAllocations) {
        Map<Long, Material> materials = new LinkedHashMap<>();
        inputs.materials().forEach(material -> materials.put(material.materialId(), material));

        Map<Long, List<Movement>> byMaterial = new HashMap<>();
        for (Movement movement : inputs.movements()) {
            byMaterial.computeIfAbsent(movement.materialId(), key -> new ArrayList<>()).add(movement);
        }
        List<Long> order = new ArrayList<>(byMaterial.keySet());
        order.sort(Comparator
                .comparingInt((Long id) -> materialRank(materials.get(id)))
                .thenComparingLong(Long::longValue));

        Map<Long, OutputFact> outputByMovement = new HashMap<>();
        inputs.outputs().forEach(output -> outputByMovement.put(output.movementId(), output));
        Map<Long, HandoverFact> handoverById = new HashMap<>();
        inputs.handovers().forEach(handover -> handoverById.put(handover.handoverId(), handover));

        GroupAllocation groups = GroupAllocation.of(inputs);

        // 能源分摊按目标归位：产出入库那一批是车间一的制造费用，订单那一批是车间二的。
        // 车间二还要知道当日产出总量，才能把能源摊到每次铜丝入库上。
        Map<Long, BigDecimal> rodEnergyByOutput = new HashMap<>();
        Map<LocalDate, BigDecimal> wireEnergyByDate = new HashMap<>();
        Map<LocalDate, BigDecimal> wireQuantityByDate = new HashMap<>();
        for (EnergyFact fact : inputs.energyAllocations()) {
            if ("ROD_STOCK".equals(fact.targetType()) && fact.outputId() != null
                    && fact.amountYuan() != null) {
                rodEnergyByOutput.merge(fact.outputId(), fact.amountYuan(), BigDecimal::add);
            } else if (("ORDER".equals(fact.targetType()) || "PUBLIC_FINISHED".equals(fact.targetType()))
                    && fact.amountYuan() != null) {
                wireEnergyByDate.merge(fact.actualDate(), fact.amountYuan(), BigDecimal::add);
            }
        }
        for (OutputFact output : inputs.outputs()) {
            Material material = materials.get(output.materialId());
            if (material != null && material.kind() == Kind.WIRE && output.qualifiedTon() != null) {
                wireQuantityByDate.merge(output.actualDate(), output.qualifiedTon(), BigDecimal::add);
            }
        }

        Map<Long, BigDecimal> rodUnitCostByMovement = new HashMap<>();

        List<MaterialResult> results = new ArrayList<>();
        List<OrderDraft> orderDrafts = new ArrayList<>();
        Map<Long, WireReceipt> wireReceiptByOutput = new HashMap<>();
        LinkedHashSet<String> runMissing = new LinkedHashSet<>();
        boolean runComplete = true;

        for (Long materialId : order) {
            Material material = materials.get(materialId);
            List<Movement> movements = new ArrayList<>(byMaterial.get(materialId));
            movements.sort(Comparator.comparing(Movement::businessDate)
                    .thenComparingLong(Movement::movementId));

            List<StockValuation.Event> events = new ArrayList<>(movements.size());
            for (Movement movement : movements) {
                events.add(event(movement, material, groups, outputByMovement, handoverById,
                        rodUnitCostByMovement, rodEnergyByOutput, wireEnergyByDate, wireQuantityByDate));
            }

            StockValuation.Result result;
            try {
                result = StockValuation.replay(events);
            } catch (IllegalStateException inconsistent) {
                // 出库超过实物说明事实链与库存账对不上。这不是能「跳过继续算」的情况：
                // 继续算会把后续均价全部带偏，只能把这个物料整体标缺项。
                results.add(new MaterialResult(materialId, null, List.of(), false,
                        List.of(inconsistent.getMessage())));
                runMissing.add(inconsistent.getMessage());
                runComplete = false;
                continue;
            }

            List<LedgerDraft> ledger = new ArrayList<>(movements.size());
            for (int index = 0; index < movements.size(); index++) {
                Movement movement = movements.get(index);
                StockValuation.Valued valued = result.events().get(index);
                String entryType = entryType(movement);
                BigDecimal signed = valued.direction() == StockValuation.Direction.IN
                        ? valued.knownCostYuan()
                        : valued.knownCostYuan().negate();
                LedgerDraft draft = new LedgerDraft(materialId, movement.orderId(), movement.movementId(),
                        entryType, movement.sourceType(), parseSourceId(movement.sourceId()),
                        movement.businessDate(), valued.quantityTon(),
                        valued.costDeltaYuan() == null ? null : valued.unitCostAppliedYuanPerTon(),
                        CostModels.amount(signed), valued.missingItems());
                ledger.add(draft);

                // 铜丝产出入库的本次估值留一份：成品归属要按「归属重量 ÷ 本次合格量」拆它的成本。
                if (valued.direction() == StockValuation.Direction.IN
                        && material != null && material.kind() == Kind.WIRE) {
                    OutputFact output = outputByMovement.get(movement.movementId());
                    if (output != null) {
                        wireReceiptByOutput.put(output.outputId(),
                                new WireReceipt(movement.movementId(), output.actualDate(),
                                        valued.quantityTon(), valued.knownCostYuan(),
                                        valued.complete(), valued.missingItems()));
                    }
                }

                if (entryType.equals(CostModels.ENTRY_ISSUE)) {
                    rodUnitCostByMovement.put(movement.movementId(), valued.unitCostAppliedYuanPerTon());
                    if (material != null && material.kind() == Kind.ROD) {
                        OrderDraft item = rodIssueItem(draft, movement);
                        if (item != null) {
                            orderDrafts.add(item);
                        }
                    }
                }
                if (!draft.missingItems().isEmpty()) {
                    runMissing.addAll(draft.missingItems());
                }
            }

            boolean materialComplete = result.complete();
            runComplete &= materialComplete;
            runMissing.addAll(result.missingItems());
            results.add(new MaterialResult(materialId, result, List.copyOf(ledger),
                    materialComplete, result.missingItems()));
        }

        // 成品直接归属：把该次产出入库成本按归属重量比例带入订单。
        // 表上 CHECK 要求 PUBLIC_FINISHED 必须引用成本流水，所以这里存产出入库的 movementId，
        // 由写入端回填成 stock_cost_ledger_id；归属只转成本份额，不再生成实物入库。
        for (FinishedAllocationFact fact : finishedAllocations) {
            WireReceipt receipt = wireReceiptByOutput.get(fact.outputId());
            if (receipt == null || receipt.quantityTon() == null || receipt.quantityTon().signum() <= 0) {
                // 找不到对应的产出入库估值：留给 ordersWithUncoveredDirectOutput 报缺项，
                // 不在这里凭空补一笔金额。
                continue;
            }
            BigDecimal allocated = fact.allocatedTon().min(receipt.quantityTon());
            BigDecimal ratio = allocated.divide(receipt.quantityTon(), 12, RoundingMode.HALF_UP);
            BigDecimal amount = receipt.costYuan() == null ? null
                    : CostModels.amount(receipt.costYuan().multiply(ratio));
            List<String> itemMissing = receipt.complete() ? List.of() : receipt.missingItems();
            orderDrafts.add(new OrderDraft(fact.orderId(), CostModels.ITEM_PUBLIC_FINISHED,
                    "ALLOC:" + fact.allocationId(), receipt.businessDate(),
                    receipt.movementId(), null, amount, itemMissing));
            if (!itemMissing.isEmpty()) {
                runMissing.addAll(itemMissing);
                runComplete = false;
            }
        }

        // 车间二能源是订单的计费项，不进铜丝库存价值之外再算一次：
        // 库存那边已经按产出摊入入库成本，订单这边只认自己归属的那一份。
        for (EnergyFact fact : inputs.energyAllocations()) {
            if (!"ORDER".equals(fact.targetType()) || fact.orderId() == null) {
                continue;
            }
            String sourceKey = "ENERGY:" + fact.workshopId() + ":" + fact.actualDate()
                    + ":" + fact.energyType();
            if (fact.amountYuan() == null) {
                orderDrafts.add(new OrderDraft(fact.orderId(), CostModels.ITEM_WORKSHOP_ENERGY,
                        sourceKey, fact.actualDate(), null, fact.allocationId(), null,
                        List.of(CostModels.CODE_WORKSHOP2_ENERGY_MISSING)));
                runMissing.add(CostModels.CODE_WORKSHOP2_ENERGY_MISSING);
                runComplete = false;
            } else {
                orderDrafts.add(new OrderDraft(fact.orderId(), CostModels.ITEM_WORKSHOP_ENERGY,
                        sourceKey, fact.actualDate(), null, fact.allocationId(),
                        fact.amountYuan(), List.of()));
            }
        }

        return new Outcome(List.copyOf(results), List.copyOf(orderDrafts),
                List.copyOf(runMissing), runComplete);
    }

    private static int materialRank(Material material) {
        if (material == null) {
            return Kind.OTHER.ordinal() + 1;
        }
        return material.kind().ordinal();
    }

    private static StockValuation.Event event(Movement movement, Material material, GroupAllocation groups,
                                              Map<Long, OutputFact> outputByMovement,
                                              Map<Long, HandoverFact> handoverById,
                                              Map<Long, BigDecimal> rodUnitCostByMovement,
                                              Map<Long, BigDecimal> rodEnergyByOutput,
                                              Map<LocalDate, BigDecimal> wireEnergyByDate,
                                              Map<LocalDate, BigDecimal> wireQuantityByDate) {
        BigDecimal delta = movement.quantityDelta();
        String key = "M" + movement.movementId();
        String quantity = delta.abs().setScale(CostModels.TON_SCALE, RoundingMode.UNNECESSARY).toPlainString();

        if (delta.signum() < 0) {
            return StockValuation.Event.out(key, quantity);
        }
        return switch (movement.movementType()) {
            case "OPENING" -> StockValuation.Event.inWithUnknownCost(key, quantity,
                    CostModels.CODE_OPENING_COST_MISSING);
            case "PLATE_RECEIPT" -> StockValuation.Event.inWithUnknownCost(key, quantity,
                    CostModels.CODE_RECEIPT_AMOUNT_MISSING);
            case "PRODUCTION_RECEIPT" -> productionReceipt(key, quantity, movement, material, groups,
                    outputByMovement, handoverById, rodUnitCostByMovement,
                    rodEnergyByOutput, wireEnergyByDate, wireQuantityByDate);
            case "CORRECTION" -> StockValuation.Event.inWithUnknownCost(key, quantity,
                    CostModels.CODE_CORRECTION_COST_MISSING);
            default -> StockValuation.Event.inWithUnknownCost(key, quantity,
                    CostModels.CODE_COST_CARRY_MISSING);
        };
    }

    /**
     * 产出入库成本：铜杆＝本次分得的材料金额＋车间一能源；铜丝＝引用的领杆成本＋车间二能源摊入。
     *
     * <p>能源金额来自已落库的分摊行，取不到就标缺项，不按 0 计：材料金额已知时按已知部分入账，
     * 均价仍留空——半个成本除以全量吨数只会得到一个看起来精确的错数字。</p>
     *
     * <p>铜丝这一步摊的是「当日车间二能源总额 × 本次称重占比」。分摊行是订单级的，
     * 入库是产出级的，两者靠当日合格量换算；同一天分几次称重时按重量比例拆，不重复计。</p>
     */
    private static StockValuation.Event productionReceipt(String key, String quantity, Movement movement,
                                                         Material material, GroupAllocation groups,
                                                         Map<Long, OutputFact> outputByMovement,
                                                         Map<Long, HandoverFact> handoverById,
                                                         Map<Long, BigDecimal> rodUnitCostByMovement,
                                                         Map<Long, BigDecimal> rodEnergyByOutput,
                                                         Map<LocalDate, BigDecimal> wireEnergyByDate,
                                                         Map<LocalDate, BigDecimal> wireQuantityByDate) {
        Kind kind = material == null ? Kind.OTHER : material.kind();
        if (kind == Kind.ROD) {
            OutputFact output = outputByMovement.get(movement.movementId());
            if (output == null) {
                return StockValuation.Event.inWithUnknownCost(key, quantity,
                        CostModels.CODE_MATERIAL_AMOUNT_MISSING, CostModels.CODE_WORKSHOP1_ENERGY_MISSING);
            }
            GroupAllocation.Share share = groups.shareOf(output);
            if (share.allocated().signum() == 0) {
                return StockValuation.Event.inWithUnknownCost(key, quantity,
                        CostModels.CODE_MATERIAL_AMOUNT_MISSING, CostModels.CODE_WORKSHOP1_ENERGY_MISSING);
            }
            BigDecimal energy = rodEnergyByOutput.get(output.outputId());
            if (energy == null) {
                // 材料齐了、能源还没落到这次产出上：已知部分入账，均价留空。
                return StockValuation.Event.inWithPartialCost(key, quantity,
                        share.allocated().toPlainString(), CostModels.CODE_WORKSHOP1_ENERGY_MISSING);
            }
            return StockValuation.Event.in(key, quantity,
                    share.allocated().add(energy).toPlainString());
        }
        if (kind == Kind.WIRE) {
            OutputFact output = outputByMovement.get(movement.movementId());
            if (output == null || output.rodRefs().isEmpty()) {
                return StockValuation.Event.inWithUnknownCost(key, quantity,
                        CostModels.CODE_ROD_COST_MISSING, CostModels.CODE_WORKSHOP2_ENERGY_MISSING);
            }
            BigDecimal rodCost = BigDecimal.ZERO;
            boolean rodKnown = true;
            for (RodUseRef ref : output.rodRefs()) {
                HandoverFact handover = handoverById.get(ref.handoverId());
                BigDecimal unit = handover == null ? null
                        : rodUnitCostByMovement.get(handover.movementId());
                if (unit == null) {
                    rodKnown = false;
                    continue;
                }
                rodCost = rodCost.add(unit.multiply(ref.sentQuantityTon())
                        .setScale(CostModels.AMOUNT_SCALE, RoundingMode.HALF_UP));
            }
            if (!rodKnown) {
                return StockValuation.Event.inWithUnknownCost(key, quantity,
                        CostModels.CODE_ROD_COST_MISSING, CostModels.CODE_WORKSHOP2_ENERGY_MISSING);
            }
            BigDecimal dayEnergy = wireEnergyByDate.get(output.actualDate());
            BigDecimal dayQuantity = wireQuantityByDate.get(output.actualDate());
            if (dayEnergy == null || dayQuantity == null || dayQuantity.signum() <= 0
                    || output.qualifiedTon() == null) {
                return StockValuation.Event.inWithPartialCost(key, quantity, rodCost.toPlainString(),
                        CostModels.CODE_WORKSHOP2_ENERGY_MISSING);
            }
            BigDecimal energy = dayEnergy.multiply(output.qualifiedTon())
                    .divide(dayQuantity, CostModels.AMOUNT_SCALE, RoundingMode.HALF_UP);
            return StockValuation.Event.in(key, quantity, rodCost.add(energy).toPlainString());
        }
        return StockValuation.Event.inWithUnknownCost(key, quantity,
                CostModels.CODE_MATERIAL_AMOUNT_MISSING);
    }

    private static OrderDraft rodIssueItem(LedgerDraft draft, Movement movement) {
        if (movement.orderId() == null) {
            return null;
        }
        return new OrderDraft(movement.orderId(), CostModels.ITEM_ROD_ISSUE,
                "ROD_ISSUE:" + movement.movementId(), movement.businessDate(), movement.movementId(),
                null,
                draft.costDeltaYuan() == null ? null : draft.costDeltaYuan().negate(),
                draft.missingItems());
    }

    private static String entryType(Movement movement) {
        return switch (movement.movementType()) {
            case "OPENING" -> CostModels.ENTRY_OPENING;
            case "PLATE_RECEIPT", "PRODUCTION_RECEIPT" -> CostModels.ENTRY_RECEIPT;
            case "PRODUCTION_ISSUE" -> CostModels.ENTRY_ISSUE;
            case "DELIVERY" -> CostModels.ENTRY_DELIVERY;
            case "CORRECTION" -> CostModels.ENTRY_CORRECTION;
            default -> CostModels.ENTRY_CORRECTION;
        };
    }

    /**
     * 流水的来源编号在库中是字符串，台账要的是数字。来源不是数字时用 0 占位并在
     * {@code share_key} 之外的单据上仍能由 {@code source_type} 定位，不编造 ID。
     */
    private static long parseSourceId(String sourceId) {
        try {
            return Long.parseLong(sourceId);
        } catch (NumberFormatException notNumeric) {
            return 0L;
        }
    }

    /**
     * 「一次投料、多次产出」的分摊范围。
     *
     * <p>范围内材料总金额按各次合格量分配（设计文档 5.2）。范围键优先用
     * {@code production_group_key}，计划内没有分组键时退回「任务＋实际日」——
     * 同一次投料的多笔称重必然同任务同日，用日期兜底不会把两次生产混在一起。</p>
     */
    static final class GroupAllocation {

        record Share(BigDecimal allocated, boolean complete) {
        }

        private final Map<String, BigDecimal> totalQuantity = new HashMap<>();
        private final Map<String, BigDecimal> knownAmount = new HashMap<>();
        private final Map<String, Boolean> amountComplete = new HashMap<>();

        static GroupAllocation of(Inputs inputs) {
            GroupAllocation allocation = new GroupAllocation();
            Map<String, OutputFact> outputsByGroup = new HashMap<>();
            for (OutputFact output : inputs.outputs()) {
                if (!"QUALIFIED_OUTPUT".equals(output.recordType()) || output.qualifiedTon() == null) {
                    continue;
                }
                String key = output.groupKey();
                allocation.totalQuantity.merge(key, output.qualifiedTon(), BigDecimal::add);
                outputsByGroup.putIfAbsent(key + "#" + output.outputId(), output);
                allocation.amountComplete.putIfAbsent(key, Boolean.TRUE);
            }
            for (PlateInputFact input : inputs.plateInputs()) {
                String key = input.groupKey();
                if (!allocation.totalQuantity.containsKey(key)) {
                    continue;
                }
                if (input.amountYuan() == null) {
                    allocation.amountComplete.put(key, Boolean.FALSE);
                } else {
                    allocation.knownAmount.merge(key, input.amountYuan(), BigDecimal::add);
                }
            }
            return allocation;
        }

        Share shareOf(OutputFact output) {
            BigDecimal total = totalQuantity.get(output.groupKey());
            if (total == null || total.signum() == 0) {
                return new Share(BigDecimal.ZERO.setScale(CostModels.AMOUNT_SCALE), true);
            }
            BigDecimal known = knownAmount.getOrDefault(output.groupKey(),
                    BigDecimal.ZERO.setScale(CostModels.AMOUNT_SCALE));
            BigDecimal allocated = known.multiply(output.qualifiedTon())
                    .divide(total, CostModels.AMOUNT_SCALE, RoundingMode.HALF_UP);
            return new Share(allocated, amountComplete.getOrDefault(output.groupKey(), Boolean.TRUE));
        }
    }
}
