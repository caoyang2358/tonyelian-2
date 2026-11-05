package cn.copper.phase2.maintenance;

/**
 * 运维模块共用的路径与查询参数 ID 校验：对外一律使用十进制字符串 ID（《接口通用约定》§1）。
 */
final class MaintenanceIds {
    private MaintenanceIds() {}

    static long parse(String field, String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, "ID 不能为空。");
        }
        if (!text.chars().allMatch(Character::isDigit)) {
            throw new InvalidRequestException(field, "ID 必须是十进制数字字符串。");
        }
        long id = Long.parseLong(text);
        if (id <= 0) {
            throw new InvalidRequestException(field, "ID 必须大于 0。");
        }
        return id;
    }

    /** 可选筛选条件：空串与 null 等价，视为不过滤。 */
    static Long optional(String field, String value) {
        return blankToNull(value) == null ? null : parse(field, value);
    }

    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
