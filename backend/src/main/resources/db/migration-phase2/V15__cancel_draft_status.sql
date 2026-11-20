-- 2026-11 评审调整：取消维修工单"待提交"环节。
-- 建单即提交，历史草稿单统一转为待审核，并补写一条提交留痕，保证老单的处理历史不断档。

INSERT INTO p2_repair_action
    (repair_order_id, action_type, result, previous_status, next_status,
     occurred_at, recorded_at, operator_name, reason, description)
SELECT repair_order_id, 'SUBMIT', NULL, 'DRAFT', 'REVIEW_PENDING',
       COALESCE(first_submitted_at, created_at), CURRENT_TIMESTAMP(3),
       '系统迁移', '取消待提交环节：历史草稿单自动转为待审核', NULL
  FROM p2_repair_order
 WHERE status = 'DRAFT';

UPDATE p2_repair_order
   SET status = 'REVIEW_PENDING',
       first_submitted_at = COALESCE(first_submitted_at, created_at),
       updated_at = CURRENT_TIMESTAMP(3)
 WHERE status = 'DRAFT';
