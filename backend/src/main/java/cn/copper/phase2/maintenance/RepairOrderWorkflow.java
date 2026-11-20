package cn.copper.phase2.maintenance;

import java.util.List;
import java.util.Set;

/**
 * 工单状态机，逐条对应《运维管理页面接口文档》§4 的状态转换表。
 * 所有状态与动作码集中在此，避免散落在 Service 的字符串字面量里。
 *
 * <p>2026-11 评审调整：取消"待提交"（DRAFT）环节，录入工单即直接提交进入待审核。
 * 页面只呈现四类业务状态：待审核、待维修、待验收、已结束；
 * 其中"退回补充"是待审核环节内的补充态，与待审核同组展示、同组筛选。</p>
 */
final class RepairOrderWorkflow {
    /** 待审核。 */
    static final String REVIEW_PENDING = "REVIEW_PENDING";
    /** 待审核环节内被退回、有待报修人补充资料的子状态。 */
    static final String RETURNED_FOR_INFO = "RETURNED_FOR_INFO";
    /** 待维修：已审核通过并派工。 */
    static final String IN_REPAIR = "IN_REPAIR";
    /** 待验收：维修人已登记结果。 */
    static final String ACCEPTANCE_PENDING = "ACCEPTANCE_PENDING";
    /** 已结束：验收通过。 */
    static final String COMPLETED = "COMPLETED";
    /** 已结束：无需维修或重复单关闭。 */
    static final String CLOSED = "CLOSED";

    /** 只有被退回补充的工单需要报修人再编辑；建单即提交，不再有草稿态。 */
    static final Set<String> EDITABLE = Set.of(RETURNED_FOR_INFO);
    /** 已结束，不再接受任何动作。 */
    static final Set<String> TERMINAL = Set.of(COMPLETED, CLOSED);
    /** 页面上的"待审核"一类：待审核与退回补充同属审核环节。 */
    static final Set<String> REVIEW_GROUP = Set.of(REVIEW_PENDING, RETURNED_FOR_INFO);
    /** 页面上的"已结束"一类：验收通过与关闭合并展示。 */
    static final Set<String> CLOSED_GROUP = Set.of(COMPLETED, CLOSED);

    static final String ACTION_SAVE = "SAVE";
    static final String ACTION_SUBMIT = "SUBMIT";
    static final String ACTION_APPROVE = "APPROVE";
    static final String ACTION_RETURN_FOR_INFO = "RETURN_FOR_INFO";
    static final String ACTION_NO_REPAIR = "NO_REPAIR";
    static final String ACTION_DUPLICATE = "DUPLICATE";
    static final String ACTION_REPAIR_RESULT = "RECORD_REPAIR_RESULT";
    static final String ACTION_ACCEPT_PASS = "ACCEPT_PASS";
    static final String ACTION_ACCEPT_FAIL = "ACCEPT_FAIL";

    private RepairOrderWorkflow() {}

    /** 当前状态下允许的动作码，作为 S1-R03 的 availableActions 返回给前端。 */
    static List<String> availableActions(String status) {
        return switch (status) {
            case RETURNED_FOR_INFO -> List.of(ACTION_SAVE, ACTION_SUBMIT);
            case REVIEW_PENDING -> List.of(
                    ACTION_APPROVE, ACTION_RETURN_FOR_INFO, ACTION_NO_REPAIR, ACTION_DUPLICATE);
            case IN_REPAIR -> List.of(ACTION_REPAIR_RESULT);
            case ACCEPTANCE_PENDING -> List.of(ACTION_ACCEPT_PASS, ACTION_ACCEPT_FAIL);
            default -> List.of();
        };
    }

    /** 可编辑字段清单：只有退回补充状态允许改资料。 */
    static List<String> editableFields(String status) {
        return EDITABLE.contains(status)
                ? List.of("description", "supplement", "reporterName", "photoAttachmentIds")
                : List.of();
    }

    /** 状态推进：返回动作后的新状态，状态与动作不匹配时返回 null 由调用方报冲突。 */
    static String nextStatus(String status, String action) {
        return switch (action) {
            case ACTION_SUBMIT -> EDITABLE.contains(status) ? REVIEW_PENDING : null;
            case ACTION_APPROVE -> REVIEW_PENDING.equals(status) ? IN_REPAIR : null;
            case ACTION_RETURN_FOR_INFO -> REVIEW_PENDING.equals(status) ? RETURNED_FOR_INFO : null;
            case ACTION_NO_REPAIR, ACTION_DUPLICATE -> REVIEW_PENDING.equals(status) ? CLOSED : null;
            case ACTION_REPAIR_RESULT -> IN_REPAIR.equals(status) ? ACCEPTANCE_PENDING : null;
            case ACTION_ACCEPT_PASS -> ACCEPTANCE_PENDING.equals(status) ? COMPLETED : null;
            case ACTION_ACCEPT_FAIL -> ACCEPTANCE_PENDING.equals(status) ? IN_REPAIR : null;
            default -> null;
        };
    }

    /** 写入 p2_repair_action 的 action_type。 */
    static String actionType(String action) {
        return switch (action) {
            case ACTION_SUBMIT -> "SUBMIT";
            case ACTION_APPROVE, ACTION_RETURN_FOR_INFO, ACTION_NO_REPAIR, ACTION_DUPLICATE -> "REVIEW";
            case ACTION_REPAIR_RESULT -> "REPAIR_RESULT";
            case ACTION_ACCEPT_PASS, ACTION_ACCEPT_FAIL -> "ACCEPTANCE";
            default -> throw new IllegalArgumentException("未知动作：" + action);
        };
    }
}
