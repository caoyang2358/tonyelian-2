package cn.copper.phase2.sensor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则变更时结束该参数上的活动事件（S1-P08 的事件侧半段）。
 *
 * <p>抽成接口而不是把 SQL 直接写进参数写入服务，原因只有一个：<b>结束事件真正的主人是
 * S1-3 异常事件服务</b>。当前由 {@link JdbcAlertEventTerminator} 直接写
 * {@code p2_alert_event}；等事件服务提供了结束接口，只需换一个实现并调整装配，
 * 参数配置的主流程一行都不用动。</p>
 *
 * <p>契约只要求"改变有效判断依据时结束受影响旧事件"，所以本接口只在规则快照真的追加了
 * 新版本时被调用；改名称、改精度这类不动判断依据的保存不会碰事件。</p>
 */
public interface AlertEventTerminator {

    /** 与 {@code p2_alert_event.end_reason} 的约定值一致；不是"恢复"，{@code recovered_at} 必须保持为空。 */
    String END_REASON_RULE_CHANGED = "RULE_CHANGED";

    /**
     * 结束该参数上仍挂在旧规则的活动事件。
     *
     * @param parameterId      参数 ID
     * @param newRuleVersionId 刚刚追加的规则版本 ID
     * @param endedAt          结束时刻；必须与新规则版本的 {@code effective_at} 是同一个值，
     *                         版本历史才能答出"这一版生效时结束了哪些事件"
     * @return 被结束的事件 ID；没有活动事件、或活动事件已挂在新规则上时返回空列表
     */
    List<String> terminateOnRuleChange(long parameterId, long newRuleVersionId, LocalDateTime endedAt);
}
