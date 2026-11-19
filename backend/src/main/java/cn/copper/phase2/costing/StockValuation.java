package cn.copper.phase2.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 库存移动加权均价回放内核（纯函数，不读写数据库）。
 *
 * <p>依据 {@code docs/系统二-生产与能源统计-数据与计算设计.md} 6.3 与第 7 节：</p>
 * <ul>
 *   <li>入库数量 {@code q}、本次完整入库成本 {@code c}，新均价 {@code (V+c)/(Q+q)}；
 *       分母是全部在库实物，含已被预留的部分。</li>
 *   <li>出库 {@code s} 按事件<b>前</b>均价带出成本，实物与库存价值同减。</li>
 *   <li>库存清零时剩余尾差随最后一笔有效出库一次带出，余值归零。</li>
 *   <li>池中有成本未知时仍按完整实物量做均价分母；完整均价与该次完整领用成本为
 *       {@code null}，已知部分单独回放。</li>
 *   <li>历史缺项不会因为后来有完整入库而消失——它留在来源链上等补。</li>
 * </ul>
 *
 * <p>本类只处理「整物料一个池」的数量与价值。归属转移（ASSIGN / RELEASE）不改变
 * 实物总量，只把价值在订单份额与公共份额之间搬动，由 K02 按 {@code share_key}
 * 表达，不进这个内核。</p>
 */
public final class StockValuation {

    /** 吨数保留 3 位。 */
    public static final int TON_SCALE = 3;
    /** 单价按其 10 位。 */
    public static final int UNIT_COST_SCALE = 10;
    /** 最终总金额按分。 */
    public static final int AMOUNT_SCALE = 2;

    public enum Direction { IN, OUT }

    /**
     * 待回放的一笔数量事件。
     *
     * @param costYuan      {@code IN} 的完整成本；{@code null} 表示成本不完整
     * @param knownCostYuan 成本不完整时已能定案的部分（例如材料金额已知、能源待补）；
     *                      完整成本事件必须为 {@code null}，避免两份金额同时存在
     * @param missingItems  该事件引入的缺项，例如「材料金额待补」
     */
    public record Event(String key, Direction direction, BigDecimal quantityTon,
                        BigDecimal costYuan, BigDecimal knownCostYuan, List<String> missingItems) {

        public Event {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(quantityTon, "quantityTon");
            quantityTon = quantityTon.setScale(TON_SCALE, RoundingMode.UNNECESSARY);
            if (quantityTon.signum() <= 0) {
                throw new IllegalArgumentException("数量必须大于 0：" + key);
            }
            if (costYuan != null) {
                costYuan = costYuan.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
                if (costYuan.signum() < 0) {
                    throw new IllegalArgumentException("成本不能为负：" + key);
                }
                if (knownCostYuan != null) {
                    throw new IllegalArgumentException("完整成本与已知部分不能同时给出：" + key);
                }
            } else if (knownCostYuan != null) {
                knownCostYuan = knownCostYuan.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
                if (knownCostYuan.signum() < 0) {
                    throw new IllegalArgumentException("已知成本部分不能为负：" + key);
                }
            }
            if (direction == Direction.OUT && (costYuan != null || knownCostYuan != null)) {
                throw new IllegalArgumentException("出库不接受成本，按事件前均价带出：" + key);
            }
            if (direction == Direction.IN && costYuan == null
                    && (missingItems == null || missingItems.isEmpty())) {
                throw new IllegalArgumentException("成本不完整时必须说明缺项，否则无法追溯：" + key);
            }
            missingItems = missingItems == null ? List.of() : List.copyOf(missingItems);
        }

        public static Event in(String key, String quantityTon, String costYuan) {
            return new Event(key, Direction.IN, new BigDecimal(quantityTon),
                    new BigDecimal(costYuan), null, List.of());
        }

        public static Event inWithUnknownCost(String key, String quantityTon, String... missingItems) {
            return new Event(key, Direction.IN, new BigDecimal(quantityTon), null, null,
                    List.of(missingItems));
        }

        /**
         * 成本不完整但已知一部分：<b>铜杆入库＝材料金额（已知）＋车间一能源（待补）</b>
         * 这类情形必须把已知材料金额记进池子，否则「已知在库价值」会一律为 0，
         * 而它恰恰是现场已经能确认的那部分依据。
         */
        public static Event inWithPartialCost(String key, String quantityTon, String knownCostYuan,
                                              String... missingItems) {
            return new Event(key, Direction.IN, new BigDecimal(quantityTon), null,
                    new BigDecimal(knownCostYuan), List.of(missingItems));
        }

        public static Event out(String key, String quantityTon) {
            return new Event(key, Direction.OUT, new BigDecimal(quantityTon), null, null, List.of());
        }
    }

    /**
     * 单事件回放结果。
     *
     * @param unitCostYuanPerTon 事件后的完整均价；成本不完整时为 {@code null}
     * @param unitCostAppliedYuanPerTon 该事件实际采用的单价：入库为本次入库单价
     *        （{@code c/q}），出库为事件前带出均价；算不出时为 {@code null}
     * @param costDeltaYuan      完整金额变动，入库为正、出库为负；不完整时为 {@code null}
     * @param knownCostYuan      已知部分金额变动的绝对值，始终可算
     * @param valueBeforeYuan    事件前的完整库存价值；不完整时为 {@code null}
     * @param knownValueBeforeYuan 事件前的已知部分价值
     * @param valueAfterYuan     事件后的完整库存价值；不完整时为 {@code null}
     * @param knownValueAfterYuan 事件后已知部分的价值
     */
    public record Valued(String key, Direction direction, BigDecimal quantityTon,
                         BigDecimal unitCostYuanPerTon, BigDecimal unitCostAppliedYuanPerTon,
                         BigDecimal costDeltaYuan, BigDecimal knownCostYuan,
                         BigDecimal quantityBeforeTon, BigDecimal quantityAfterTon,
                         BigDecimal valueBeforeYuan, BigDecimal knownValueBeforeYuan,
                         BigDecimal valueAfterYuan, BigDecimal knownValueAfterYuan,
                         boolean complete, List<String> missingItems) {
    }

    /**
     * @param endValueYuan 期末完整价值；期间有任何缺项则为 {@code null}
     * @param missingItems 全程累积的缺项，按首次出现顺序
     */
    public record Result(List<Valued> events, BigDecimal endQuantityTon, BigDecimal endValueYuan,
                         BigDecimal endKnownValueYuan, boolean complete, List<String> missingItems) {
    }

    private StockValuation() {
    }

    /**
     * 按给定顺序回放。调用方负责按（实际日、valuationSequence）排好序——顺序错了，
     * 均价就是错的，而这里无法从数量本身看出来。
     */
    public static Result replay(List<Event> events) {
        Objects.requireNonNull(events, "events");
        BigDecimal quantity = BigDecimal.ZERO.setScale(TON_SCALE);
        BigDecimal knownValue = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
        boolean complete = true;
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        List<Valued> valued = new ArrayList<>(events.size());

        for (Event event : events) {
            BigDecimal before = quantity;
            BigDecimal beforeKnown = knownValue;
            BigDecimal beforeComplete = complete ? beforeKnown : null;

            if (event.direction() == Direction.IN) {
                quantity = quantity.add(event.quantityTon());
                BigDecimal delta;
                BigDecimal knownPart;
                BigDecimal applied;
                List<String> eventMissing;
                if (event.costYuan() != null) {
                    delta = event.costYuan();
                    knownPart = delta;
                    knownValue = knownValue.add(delta);
                    // 本次入库单价＝本次成本÷本次数量，不掺入历史池子。
                    applied = delta.divide(event.quantityTon(), UNIT_COST_SCALE, RoundingMode.HALF_UP);
                    eventMissing = List.of();
                } else {
                    delta = null;
                    knownPart = event.knownCostYuan() == null
                            ? BigDecimal.ZERO.setScale(AMOUNT_SCALE)
                            : event.knownCostYuan();
                    knownValue = knownValue.add(knownPart);
                    applied = null;
                    complete = false;
                    missing.addAll(event.missingItems());
                    eventMissing = event.missingItems();
                }
                // 入库这一笔自己的金额是已知的，即使整个池还有别的缺项；
                // 未知的是均价，所以只把均价与期末价值留空。
                valued.add(new Valued(event.key(), Direction.IN, event.quantityTon(),
                        unitCost(quantity, knownValue, complete),
                        applied, delta, knownPart,
                        before, quantity,
                        beforeComplete, beforeKnown,
                        complete ? knownValue : null, knownValue, complete, eventMissing));
                continue;
            }

            // 出库：按事件前均价带出。超出实物的数量说明上游事实与库存对不上，
            // 不能悄悄当成负数库存继续算。
            if (event.quantityTon().compareTo(before) > 0) {
                throw new IllegalStateException("出库数量 " + event.quantityTon().toPlainString()
                        + " 超过事件前实物 " + before.toPlainString() + "：" + event.key());
            }
            BigDecimal after = before.subtract(event.quantityTon());
            BigDecimal unitBefore = before.signum() == 0
                    ? BigDecimal.ZERO.setScale(UNIT_COST_SCALE)
                    : beforeKnown.divide(before, UNIT_COST_SCALE, RoundingMode.HALF_UP);

            // 清零时把剩余价值（含分位尾差）一次带出，余值归零。
            boolean empties = after.signum() == 0;
            BigDecimal knownOut = empties
                    ? beforeKnown
                    : unitBefore.multiply(event.quantityTon()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            knownValue = knownValue.subtract(knownOut);
            quantity = after;

            List<String> eventMissing = complete ? List.of() : List.copyOf(missing);
            valued.add(new Valued(event.key(), Direction.OUT, event.quantityTon(),
                    unitCost(quantity, knownValue, complete),
                    unitBefore, complete ? knownOut.negate() : null, knownOut,
                    before, quantity,
                    beforeComplete, beforeKnown,
                    complete ? knownValue : null, knownValue, complete, eventMissing));
        }

        return new Result(List.copyOf(valued), quantity,
                complete ? knownValue : null, knownValue, complete, List.copyOf(missing));
    }

    private static BigDecimal unitCost(BigDecimal quantity, BigDecimal value, boolean complete) {
        if (!complete || quantity.signum() == 0) {
            return null;
        }
        return value.divide(quantity, UNIT_COST_SCALE, RoundingMode.HALF_UP);
    }
}
