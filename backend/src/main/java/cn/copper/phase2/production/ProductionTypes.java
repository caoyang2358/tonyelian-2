package cn.copper.phase2.production;

import cn.copper.phase2.common.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 生产记录模块共享的常量与格式化口径。
 * <p>数量吨数最多三位小数、金额最多两位，接口一律以十进制字符串传输；
 * 业务日期为 Asia/Shanghai 时区下的 YYYY-MM-DD。</p>
 */
public final class ProductionTypes {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 实际记录类型白名单（接口 recordType）。 */
    public static final String RT_EXECUTION = "EXECUTION";
    public static final String RT_PLATE_INPUT = "PLATE_INPUT";
    public static final String RT_ROD_HANDOVER = "ROD_HANDOVER";
    public static final String RT_ROD_OUTPUT = "ROD_OUTPUT";
    public static final String RT_WIRE_OUTPUT = "WIRE_OUTPUT";
    public static final String RT_NO_OUTPUT = "NO_OUTPUT";

    /** p2_output_record 物理行类型：合格产出以实际物料区分铜杆/铜丝。 */
    public static final String ROW_QUALIFIED_OUTPUT = "QUALIFIED_OUTPUT";
    public static final String ROW_NO_OUTPUT = "NO_OUTPUT";
    public static final String ROW_EXECUTION = "EXECUTION";

    public static final String SOURCE_PLANNED = "PLANNED";
    public static final String SOURCE_UNPLANNED = "UNPLANNED";

    public static final String EXEC_NOT_STARTED = "NOT_STARTED";
    public static final String EXEC_IN_PROGRESS = "IN_PROGRESS";
    public static final String EXEC_STOPPED = "STOPPED";
    public static final String EXEC_COMPLETED = "COMPLETED";

    public static final String WEIGH_PENDING = "PENDING";
    public static final String WEIGH_RECORDED = "RECORDED";
    public static final String WEIGH_CONFIRMED_ZERO = "CONFIRMED_ZERO";

    public static final String HANDOVER_PENDING_RECEIPT = "PENDING_RECEIPT";
    public static final String HANDOVER_CONFIRMED = "CONFIRMED";
    public static final String HANDOVER_RELEASED = "RELEASED";

    public static final String GROUP_ORIGIN_UNPLANNED = "UNPLANNED";

    private ProductionTypes() {
    }

    /** 解析接口传入的吨数字符串；为空、非法或超出三位小数精度时抛字段错误。 */
    public static BigDecimal parseTon(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw BusinessException.invalid(field, "该项为必填的吨数。");
            }
            return null;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value.trim());
        } catch (NumberFormatException error) {
            throw BusinessException.invalid(field, "吨数必须是数字字符串。");
        }
        return checkScale(field, parsed, 3);
    }

    /** 解析接口传入的人民币金额字符串，最多两位小数。 */
    public static BigDecimal parseYuan(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw BusinessException.invalid(field, "该项为必填金额。");
            }
            return null;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value.trim());
        } catch (NumberFormatException error) {
            throw BusinessException.invalid(field, "金额必须是数字字符串。");
        }
        return checkScale(field, parsed, 2);
    }

    public static BigDecimal checkScale(String field, BigDecimal value, int scale) {
        if (value.stripTrailingZeros().scale() > scale) {
            throw BusinessException.invalid(field,
                    "小数位数不得超过 " + scale + " 位。");
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    /** 数量转三位小数字符串；null 原样返回表示资料未齐。 */
    public static String ton(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    /** 金额转两位小数字符串；null 原样返回表示资料未齐。 */
    public static String yuan(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 业务 ID 统一转为十进制字符串。 */
    public static String id(Number value) {
        return value == null ? null : value.toString();
    }

    /** 解析业务日期字符串（YYYY-MM-DD）。 */
    public static LocalDate parseDate(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw BusinessException.invalid(field, "该项为必填的业务日期。");
            }
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException error) {
            throw BusinessException.invalid(field, "日期格式必须是 YYYY-MM-DD。");
        }
    }

    /** 解析带偏移的 ISO-8601 时刻。 */
    public static OffsetDateTime parseTime(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw BusinessException.invalid(field, "该项为必填的发生时间。");
            }
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException error) {
            throw BusinessException.invalid(field, "时间必须是带偏移的 ISO-8601 格式。");
        }
    }

    /** 把带偏移时刻换算成数据库时间（按 Asia/Shanghai 会话时区存储）。 */
    public static java.time.LocalDateTime toDbTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZONE).toLocalDateTime();
    }

    /** 时刻转带偏移 ISO-8601 字符串。 */
    public static String time(java.time.LocalDateTime dbTime) {
        return dbTime == null ? null : dbTime.atZone(ZONE).format(
                java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** 时刻转响应用 OffsetDateTime。 */
    public static OffsetDateTime offset(java.time.LocalDateTime dbTime) {
        return dbTime == null ? null : dbTime.atZone(ZONE).toOffsetDateTime();
    }

}
