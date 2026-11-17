package cn.copper.phase2.equipment;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentProfile;
import cn.copper.phase2.equipment.EquipmentModels.TechnicalParameter;
import cn.copper.phase2.equipment.EquipmentWriteModels.EquipmentWriteRequest;
import cn.copper.phase2.reference.ReferenceDictionaries;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字段级校验。数据库层虽然有 CHECK 约束，但 8.0 下非法枚举会抛 DataAccessException，
 * 若不在进入写库前拦住，用户改错一个下拉框就会看到"数据库不可用"。
 */
@Component
public class EquipmentValidator {
    static final int CODE_MAX = 64;
    static final int NAME_MAX = 100;
    static final int PERSON_MAX = 100;
    static final int MODEL_MAX = 150;
    static final int MANUFACTURER_MAX = 150;
    static final int SERIAL_MAX = 100;
    static final int LOCATION_MAX = 255;
    static final int PARAM_LABEL_MAX = 100;
    static final int PARAM_UNIT_MAX = 32;

    private static final Set<String> VALUE_TYPES = Set.of("STRING", "DECIMAL", "INTEGER", "BOOLEAN");

    public void validate(EquipmentWriteRequest request, boolean update) {
        Map<String, Object> errors = new LinkedHashMap<>();

        requireText(errors, "code", request.code(), CODE_MAX, "设备编码");
        requireText(errors, "name", request.name(), NAME_MAX, "设备名称");
        optionalText(errors, "responsiblePerson", request.responsiblePerson(), PERSON_MAX, "责任人");
        if (request.enabled() == null) {
            errors.put("enabled", "是否启用为必填项。");
        }
        if (request.workshopId() == null || request.workshopId().isBlank()) {
            errors.put("workshopId", "所属车间为必填项。");
        }
        if (update) {
            if (request.expectedVersion() == null || request.expectedVersion() < 0) {
                errors.put("expectedVersion", "更正时必须提供非负的当前版本号。");
            }
            if (request.reason() == null || request.reason().isBlank()) {
                errors.put("reason", "更正原因不得为空。");
            } else if (request.reason().length() > 1000) {
                errors.put("reason", "更正原因不得超过 1000 个字符。");
            }
        }
        validateProfile(request.profile(), errors);

        if (!errors.isEmpty()) {
            throw BusinessException.invalid(errors);
        }
    }

    private void validateProfile(EquipmentProfile profile, Map<String, Object> errors) {
        if (profile == null) {
            errors.put("profile", "扩展档案为必填项。");
            return;
        }
        if (profile.typeCode() == null || profile.typeCode().isBlank()) {
            errors.put("profile.typeCode", "设备类型为必填项。");
        } else if (!ReferenceDictionaries.isEquipmentType(profile.typeCode().trim())) {
            errors.put("profile.typeCode", "设备类型不在可选范围内。");
        }
        // 按《系统一-设备管理页面接口文档》第 23 行：新增与编辑均必填，历史未知只允许读取为 null。
        // 因此写入路径不再接受 null；存量空值设备在下次更正时必须先选定状态。
        String status = profile.managementStatus();
        if (status == null || status.isBlank()) {
            errors.put("profile.managementStatus", "管理状态为必填项，请按现场核实结果选择。");
        } else if (!ReferenceDictionaries.isManagementStatus(status.trim())) {
            errors.put("profile.managementStatus", "管理状态只能是 IN_USE、STOPPED、SCRAPPED。");
        }
        optionalText(errors, "profile.model", profile.model(), MODEL_MAX, "型号");
        optionalText(errors, "profile.manufacturer", profile.manufacturer(), MANUFACTURER_MAX, "制造商");
        optionalText(errors, "profile.factorySerialNo", profile.factorySerialNo(), SERIAL_MAX, "出厂编号");
        optionalText(errors, "profile.installationLocation", profile.installationLocation(),
                LOCATION_MAX, "安装位置");
        if (profile.expectedLifeYears() != null && profile.expectedLifeYears() <= 0) {
            errors.put("profile.expectedLifeYears", "预计年限必须是正整数或 null。");
        }
        if (profile.processId() != null && profile.processId().isBlank()) {
            errors.put("profile.processId", "工序 ID 不能是空字符串，未知请传 null。");
        }
        validateParameters(profile.technicalParameters(), errors);
    }

    private void validateParameters(List<TechnicalParameter> parameters, Map<String, Object> errors) {
        if (parameters == null) {
            errors.put("profile.technicalParameters", "技术参数必须显式传数组，没有参数请传 []。");
            return;
        }
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < parameters.size(); index++) {
            TechnicalParameter parameter = parameters.get(index);
            String prefix = "profile.technicalParameters[" + index + "]";
            if (parameter == null) {
                errors.put(prefix, "技术参数项不能为空。");
                continue;
            }
            String key = parameter.key() == null ? null : parameter.key().trim();
            if (key == null || key.isEmpty()) {
                errors.put(prefix + ".key", "参数名为必填项。");
            } else if (!keys.add(key)) {
                errors.put(prefix + ".key", "参数名在本设备内必须唯一。");
            }
            if (parameter.label() != null && parameter.label().length() > PARAM_LABEL_MAX) {
                errors.put(prefix + ".label", "参数显示名不得超过 " + PARAM_LABEL_MAX + " 个字符。");
            }
            if (parameter.unitLabel() != null && parameter.unitLabel().length() > PARAM_UNIT_MAX) {
                errors.put(prefix + ".unitLabel", "单位不得超过 " + PARAM_UNIT_MAX + " 个字符。");
            }
            String valueType = parameter.valueType();
            if (valueType == null || !VALUE_TYPES.contains(valueType)) {
                errors.put(prefix + ".valueType", "取值为 STRING、DECIMAL、INTEGER、BOOLEAN 之一。");
                continue;
            }
            validateParameterValue(prefix, valueType, parameter.value(), errors);
        }
    }

    private void validateParameterValue(String prefix, String valueType, Object value,
                                        Map<String, Object> errors) {
        if (value == null) {
            return;
        }
        switch (valueType) {
            case "BOOLEAN" -> {
                if (!(value instanceof Boolean)) {
                    errors.put(prefix + ".value", "BOOLEAN 参数必须使用 JSON 布尔值。");
                }
            }
            case "DECIMAL" -> {
                if (!(value instanceof String text)) {
                    errors.put(prefix + ".value", "DECIMAL 参数必须使用十进制字符串。");
                } else if (!isDecimal(text)) {
                    errors.put(prefix + ".value", "该值不是合法的十进制数。");
                }
            }
            case "INTEGER" -> {
                if (!(value instanceof String text)) {
                    errors.put(prefix + ".value", "INTEGER 参数必须使用十进制字符串。");
                } else if (!text.trim().matches("-?\\d+")) {
                    errors.put(prefix + ".value", "该值不是合法的整数。");
                }
            }
            default -> {
                if (!(value instanceof String)) {
                    errors.put(prefix + ".value", "STRING 参数必须使用字符串。");
                }
            }
        }
    }

    private static boolean isDecimal(String text) {
        try {
            new BigDecimal(text.trim());
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static void requireText(Map<String, Object> errors, String field, String value,
                                    int max, String label) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isEmpty()) {
            errors.put(field, label + "为必填项。");
        } else if (text.length() > max) {
            errors.put(field, label + "不得超过 " + max + " 个字符。");
        }
    }

    private static void optionalText(Map<String, Object> errors, String field, String value,
                                      int max, String label) {
        if (value != null && value.length() > max) {
            errors.put(field, label + "不得超过 " + max + " 个字符。");
        }
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
