package cn.copper.phase2.maintenance;

import java.util.List;
import java.util.Set;

/**
 * 工单状态机，逐条对应《运维管理页面接口文档》§4 的状态转换表。
 * 所有状态与动作码集中在此，避免散落在 Service 的字符串字面量里。
 */
final class RepairOrderWorkflow {
    static final String DRAFT = "DRAFT";
    static final String REVIEW_PENDING = "REVIEW_PENDING";
    static final String RETURNED_FOR_INFO = "RETURNED_FOR_INFO";
    static final String IN_REPAIR = "IN_REPAIR";
    static final String ACCEPTANCE_PENDING = "ACCEPTANCE_PENDING";
    static final String COMPLETED = "COMPLETED";
    static final String CLOSED = "CLOSED";

    /** 可保存资料、可正式提交的两个状态。 */
    static final Set<String> EDITABLE = Set.of(DRAFT, RETURNED_FOR_INFO);
    /** 已结束，不再接受任何动作。 */
    static final Set<String> TERMINAL = Set.of(COMPLETED, CLOSED);

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
            case DRAFT, RETURNED_FOR_INFO -> List.of(ACTION_SAVE, ACTION_SUBMIT);
            case REVIEW_PENDING -> List.of(
                    ACTION_APPROVE, ACTION_RETURN_FOR_INFO, ACTION_NO_REPAIR, ACTION_DUPLICATE);
            case IN_REPAIR -> List.of(ACTION_REPAIR_RESULT);
            case ACCEPTANCE_PENDING -> List.of(ACTION_ACCEPT_PASS, ACTION_ACCEPT_FAIL);
            default -> List.of();
        };
    }

    /** 可编辑字段清单：只有草稿与退回补充状态允许改资料。 */
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
