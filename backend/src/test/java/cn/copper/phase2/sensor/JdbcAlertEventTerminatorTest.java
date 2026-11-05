package cn.copper.phase2.sensor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P08 结束活动事件的写库规则。
 *
 * <p>这段 SQL 只有一句 UPDATE，真正会出错的地方全在"什么时候不该更新"：
 * 没有活动事件时不许凭空写；已经挂在新规则上的事件不许再结束一次；
 * 更新影响 0 行（被事件服务抢先结束）时不能对外声称结束成功。</p>
 */
class JdbcAlertEventTerminatorTest {
    private static final long PARAMETER_ID = 501L;
    private static final long OLD_RULE_VERSION = 9001L;
    private static final long NEW_RULE_VERSION = 9002L;
    private static final long EVENT_ID = 7L;
    private static final LocalDateTime ENDED_AT = LocalDateTime.of(2026, 11, 4, 22, 30, 15, 123_000_000);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcAlertEventTerminator terminator = new JdbcAlertEventTerminator(jdbc);

    private void givenActiveEvent(long ruleVersionId) {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.<Map<String, Object>>of(Map.of(
                        "event_id", EVENT_ID, "rule_version_id", ruleVersionId)));
    }

    @Test
    void noActiveEventWritesNothing() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertEquals(List.of(), terminator.terminateOnRuleChange(PARAMETER_ID, NEW_RULE_VERSION, ENDED_AT));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void eventOnOlderRuleVersionIsEndedAsRuleChanged() {
        givenActiveEvent(OLD_RULE_VERSION);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertEquals(List.of(String.valueOf(EVENT_ID)),
                terminator.terminateOnRuleChange(PARAMETER_ID, NEW_RULE_VERSION, ENDED_AT));

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), params.capture());
        Object[] values = params.getValue();
        // 结束原因必须是 RULE_CHANGED：写成 RECOVERED 会让"规则改了"看起来像"自己恢复了"。
        assertEquals(AlertEventTerminator.END_REASON_RULE_CHANGED, values[1]);
        assertEquals(EVENT_ID, ((Number) values[3]).longValue());
        // ended_at 与调用方给的时刻一致：P11 版本历史按这个时刻回连事件。
        assertEquals(java.sql.Timestamp.valueOf(ENDED_AT), values[0]);
    }

    @Test
    void eventAlreadyOnTheNewRuleVersionIsLeftAlone() {
        // 事件服务按新规则起的事件挂的就是新版本，下一次保存不该顺手结束它。
        givenActiveEvent(NEW_RULE_VERSION);

        assertEquals(List.of(), terminator.terminateOnRuleChange(PARAMETER_ID, NEW_RULE_VERSION, ENDED_AT));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void eventEndedConcurrentlyIsNotReportedAsTerminated() {
        givenActiveEvent(OLD_RULE_VERSION);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        // 影响 0 行说明别人先结束了；这时候还上报事件号，等于把别人的结束理由也算成自己的。
        assertEquals(List.of(), terminator.terminateOnRuleChange(PARAMETER_ID, NEW_RULE_VERSION, ENDED_AT));
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void selectOnlyLooksAtActiveEventsOfThisParameter() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        terminator.terminateOnRuleChange(PARAMETER_ID, NEW_RULE_VERSION, ENDED_AT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        String statement = sql.getValue();
        assertTrue(statement.contains("parameter_id = ?"), statement);
        assertTrue(statement.contains("ended_at IS NULL"), statement);
        // 触发时间在未来的脏数据不结束：否则 chk 约束会让整次配置保存回滚。
        assertTrue(statement.contains("triggered_at <= ?"), statement);
    }
}
