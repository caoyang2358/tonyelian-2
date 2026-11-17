-- 2026-11 评审跟进：维修验收通过后，来源异常事件应当闭环。
-- 早期实现只推进工单状态、不结束事件；采集端没有后续数据时事件会永远停在「进行中」，
-- 与工单的「已结束」直接矛盾。这里按工单的验收时间补齐历史事件：
-- ended_at 取验收时刻（不早于触发时间），end_reason 记为 REPAIR_COMPLETED，
-- recovered_at 保持为空——它不是自动恢复，而是维修处理完毕，可顺着工单动作历史追溯。

UPDATE p2_alert_event e
  JOIN (SELECT r.source_alert_event_id AS event_id,
               MIN(r.completed_at)          AS completed_at
          FROM p2_repair_order r
         WHERE r.source_type = 'ALERT_EVENT'
           AND r.status = 'COMPLETED'
           AND r.source_alert_event_id IS NOT NULL
           AND r.completed_at IS NOT NULL
         GROUP BY r.source_alert_event_id) done
    ON done.event_id = e.event_id
   SET e.ended_at = GREATEST(done.completed_at, e.triggered_at),
       e.end_reason = 'REPAIR_COMPLETED',
       e.version = e.version + 1,
       e.updated_at = CURRENT_TIMESTAMP(3)
 WHERE e.ended_at IS NULL;
