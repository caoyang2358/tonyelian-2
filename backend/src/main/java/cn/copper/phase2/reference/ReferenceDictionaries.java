package cn.copper.phase2.reference;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 研发字典：设备类型与管理状态。
 * <p>这两组选项不落一期表，属于二期研发编码，BASE-04 直接输出，写入时用于校验。
 * 类型不是给现有设备自动分类的依据，历史设备类型仍可为 null。</p>
 */
public final class ReferenceDictionaries {
    public record Option(String code, String name) {}

    public static final List<Option> EQUIPMENT_TYPES = List.of(
            new Option("MELTING_FURNACE", "熔化炉"),
            new Option("HOLDING_FURNACE", "保温炉"),
            new Option("CASTING_TRACTION", "结晶牵引设备"),
            new Option("ROLLING_MILL", "轧机组"),
            new Option("DRAWING_MACHINE", "拉丝机"),
            new Option("DIAMETER_GAUGE", "测径仪"),
            new Option("OTHER", "其他生产设备"));

    public static final List<Option> MANAGEMENT_STATUSES = List.of(
            new Option("IN_USE", "在用"),
            new Option("STOPPED", "停用"),
            new Option("SCRAPPED", "报废"));

    private static final Set<String> TYPE_CODES = codes(EQUIPMENT_TYPES);
    private static final Set<String> MANAGEMENT_STATUS_CODES = codes(MANAGEMENT_STATUSES);

    private ReferenceDictionaries() {}

    public static boolean isEquipmentType(String code) {
        return code != null && TYPE_CODES.contains(code);
    }

    public static boolean isManagementStatus(String code) {
        return code != null && MANAGEMENT_STATUS_CODES.contains(code);
    }

    public static String equipmentTypeName(String code) {
        return nameOf(EQUIPMENT_TYPES, code);
    }

    public static String managementStatusName(String code) {
        return nameOf(MANAGEMENT_STATUSES, code);
    }

    private static String nameOf(List<Option> options, String code) {
        if (code == null) {
            return null;
        }
        for (Option option : options) {
            if (option.code().equals(code)) {
                return option.name();
            }
        }
        return null;
    }

    private static Set<String> codes(List<Option> options) {
        return options.stream().map(Option::code).collect(Collectors.toUnmodifiableSet());
    }
}
