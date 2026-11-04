package cn.copper.phase2.sensor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 直接写 {@code p2_alert_event} 的结束实现（S1-3 事件服务尚未提供结束接口时的落地方式）。
 *
 * <p>契约只要求一件事：判断依据变化时，把该参数受影响的活动事件以 {@code RULE_CHANGED} 结束。
 * 这条 UPDATE 的每一处写法都由库上的约束兜住，不是自选动作：</p>
 * <ul>
 *   <li>唯一索引 {@code uk_p2_alert_active_parameter} 建在生成列 {@code active_parameter_id} 上，
 *       保证同一参数最多一条未结束事件，所以"受影响事件"天然是 0 或 1 条，不需要批量处理。</li>
 *   <li>{@code chk_2} 要求 {@code ended_at} 与 {@code end_reason} 同时有值，两者必须一起写。</li>
 *   <li>{@code chk_4} 要求 {@code RULE_CHANGED} 时 {@code recovered_at} 为空：规则变更结束
 *       <b>不是恢复</b>，现场看到"未恢复但已结束"才是正确语义，这里因此不碰 {@code recovered_at}。</li>
 *   <li>{@code version} 必须加一：A03 的人工处理用 {@code expectedVersion} 做乐观锁，
 *       不推进版本号，别人还能按结束前的版本往一条已结束的事件上写处理记录。</li>
 * </ul>
 *
 * <p>不做的事：不生成事件、不联动通知与工单（契约写明维修单维持独立状态），也不改
 * {@code source_reference}——那是"当时因何触发"的证据，结束原因另有一列可查。</p>
 */
@Component
public class JdbcAlertEventTerminator implements AlertEventTerminator {
    /**
     * 取该参数上还没结束的事件。
     *
     * <p>"是否属于更早的规则版本"不在 SQL 里过滤，而是回到 Java 里判断：多一个条件只是让 SQL
     * 少返回一行，却让"已挂在新规则上的事件不要动"这条规则失去可测性。</p>
     *
     * <p>{@code triggered_at <= ?} 挡的是时间戳异常的脏数据：{@code chk_2} 要求
     * {@code ended_at >= triggered_at}，万一库里有一条触发时间在未来的行，直接结束会让整次
     * 配置保存跟着回滚；宁可这条事件留着不动，也不要配置改不了。</p>
     */
    private static final String SELECT_ACTIVE_SQL = """
            SELECT event_id, rule_version_id
              FROM p2_alert_event
             WHERE parameter_id = ? AND ended_at IS NULL AND triggered_at <= ?
             ORDER BY event_id
            """;

    /**
     * {@code ended_at IS NULL} 再判一次：选中与更新之间若被事件服务抢先结束，这里影响 0 行，
     * 调用方就当没有需要结束的事件，而不是把别人的结束理由覆盖成 RULE_CHANGED。
     */
    private static final String END_SQL = """
            UPDATE p2_alert_event
               SET ended_at = ?, end_reason = ?, version = version + 1, updated_at = ?
             WHERE event_id = ? AND ended_at IS NULL
            """;

    private final JdbcTemplate jdbc;

    public JdbcAlertEventTerminator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> terminateOnRuleChange(long parameterId, long newRuleVersionId,
                                              LocalDateTime endedAt) {
        // 与规则版本的 effective_at 共用同一个时刻：列都是 datetime(3)，会被截到毫秒，
        // 两个独立的 now() 差几毫秒，历史版本里就回连不到自己结束过的事件。
        Timestamp now = Timestamp.valueOf(endedAt);
        List<Map<String, Object>> active = jdbc.queryForList(SELECT_ACTIVE_SQL, parameterId, now);
        if (active.isEmpty()) {
            return List.of();
        }
        List<String> ended = new ArrayList<>(active.size());
        for (Map<String, Object> row : active) {
            // 已经挂在新规则上的事件不动：它属于"改完之后按新规则起的事件"，
            // 不能因为下一次保存又被顺手结束一遍。
            if (((Number) row.get("rule_version_id")).longValue() == newRuleVersionId) {
                continue;
            }
            long eventId = ((Number) row.get("event_id")).longValue();
            if (jdbc.update(END_SQL, now, END_REASON_RULE_CHANGED, now, eventId) > 0) {
                ended.add(String.valueOf(eventId));
            }
        }
        return List.copyOf(ended);
    }
}
