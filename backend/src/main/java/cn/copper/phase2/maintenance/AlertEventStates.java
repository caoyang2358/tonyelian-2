package cn.copper.phase2.maintenance;

import java.util.Set;

/**
 * 提醒页签的事件状态口径。
 *
 * <p>事件表只记录事实（触发、恢复、结束及结束原因），不存状态列，因此两个状态都是推导出来的：
 * <ul>
 *   <li>事件状态：未结束为进行中；结束且有实际恢复时间为自然恢复；结束但没有恢复时间是被规则变更终止。</li>
 *   <li>人工状态：取该事件最新一条处理记录；没有记录即待处理。</li>
 * </ul>
 *
 * <p>推导只写在 {@link AlertEventRepository} 的两段 SQL 片段里（由本类常量拼出，取值不会两边漂移），
 * 列表、详情和筛选条件共用同一份，避免出现"筛出来的和显示的不一致"。
 */
final class AlertEventStates {
    /** 事件仍在持续。 */
    static final String ONGOING = "ONGOING";
    /** 已按实际恢复时间结束。 */
    static final String RECOVERED = "RECOVERED";
    /** 已结束但没有恢复时间，即被规则变更终止。 */
    static final String ENDED = "ENDED";
    static final Set<String> EVENT_STATES = Set.of(ONGOING, RECOVERED, ENDED);

    /** 尚无人工处理记录。 */
    static final String PENDING = "PENDING";
    /** 最近一次人工动作为已知悉。 */
    static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    /** 最近一次人工动作为暂不报修。 */
    static final String DEFER_REPAIR = "DEFER_REPAIR";
    static final Set<String> HANDLING_STATES = Set.of(PENDING, ACKNOWLEDGED, DEFER_REPAIR);

    /**
     * 处理优先级：事件表没有优先级列，按"设备是否还在异常 + 是否有人处理"推导。
     *
     * <p>排序用序号（1 最高），出参用语义码。这样工人打开列表时，排在前面的一定是
     * "还坏着且没人管"的事件，而不是单纯按时间倒序被新事件刷下去。</p>
     */
    static final String PRIORITY_URGENT = "URGENT";
    static final String PRIORITY_HIGH = "HIGH";
    static final String PRIORITY_NORMAL = "NORMAL";
    static final String PRIORITY_LOW = "LOW";
    static final Set<String> PRIORITIES =
            Set.of(PRIORITY_URGENT, PRIORITY_HIGH, PRIORITY_NORMAL, PRIORITY_LOW);

    /**
     * 列表排序方式：TIME = 触发时间倒序（默认），PRIORITY = 优先级优先、同优先级按时间倒序。
     *
     * <p>取值大小写不敏感：查询参数写 {@code sort=priority} 也会被归一成 {@code PRIORITY}，
     * 与其余状态码保持同一套约定。</p>
     */
    static final String SORT_TIME = "TIME";
    static final String SORT_PRIORITY = "PRIORITY";
    static final Set<String> SORTS = Set.of(SORT_TIME, SORT_PRIORITY);

    private AlertEventStates() {}
}
