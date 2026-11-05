package cn.copper.phase2.equipment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

public final class EquipmentModels {
    private EquipmentModels() {}

    /**
     * S1-E01 列表项。保留首批的七个共享字段（ID 仍为 JSON 数字，现有调用方无需改动），
     * 并追加类型编码、类型名称、管理状态与聚合版本。
     * typeCode / managementStatus 对尚未补档的历史设备为 null，绝不用名称或图例反推。
     */
    public record EquipmentItem(long id, String code, String name, long workshopId,
                                String workshopName, String responsiblePerson, boolean enabled,
                                String typeCode, String typeName, String managementStatus,
                                long version) {}

    public record EquipmentList(List<EquipmentItem> items) {}

    /**
     * S1-E01 双形态响应：未显式传分页参数时只输出 items，与首批的 data.items 完全一致；
     * 显式传 page/pageSize 时才追加 page/pageSize/total。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EquipmentListResponse(List<EquipmentItem> items, Integer page,
                                        Integer pageSize, Long total) {
        public static EquipmentListResponse all(List<EquipmentItem> items) {
            return new EquipmentListResponse(items, null, null, null);
        }

        public static EquipmentListResponse paged(List<EquipmentItem> items, int page,
                                                  int pageSize, long total) {
            return new EquipmentListResponse(items, page, pageSize, total);
        }
    }

    /** 列表查询条件。分页参数为空表示"返回全部匹配项"。 */
    public record EquipmentQuery(String keyword, Long workshopId, String typeCode,
                                 String managementStatus, Boolean enabled,
                                 Integer page, Integer pageSize) {}

    /**
     * S1-E02 详情：一期共享身份 + 二期扩展档案 + 聚合版本 + 缺项提示 + 删除可用性。
     * 扩展档案不存在时 profile 各字段为 null、technicalParameters 为 []、version 为 0，
     * 这只是并发契约，读取不为本次请求写入任何数据。
     */
    public record EquipmentDetail(String id, String code, String name,
                                  String workshopId, String workshopName,
                                  String responsiblePerson, boolean enabled,
                                  EquipmentProfile profile, long version,
                                  List<String> missingFields, Deletion deletion) {}

    /** 二期扩展档案。字段顺序与《首批接口约定》5.1 一致。 */
    public record EquipmentProfile(String typeCode, String model, String processId,
                                   String installationLocation, String manufacturer,
                                   String factorySerialNo, LocalDate purchasedOn,
                                   LocalDate commissionedOn, Integer expectedLifeYears,
                                   LocalDate warrantyExpiresOn, String managementStatus,
                                   List<TechnicalParameter> technicalParameters) {}

    /**
     * 技术参数项。valueType 为 STRING/DECIMAL/INTEGER/BOOLEAN；
     * 数值按首批约定使用十进制字符串，布尔使用 JSON 布尔，故 value 为 Object。
     */
    public record TechnicalParameter(String key, String label, String valueType,
                                     Object value, String unitLabel) {
        @JsonCreator
        public TechnicalParameter(@JsonProperty("key") String key,
                                  @JsonProperty("label") String label,
                                  @JsonProperty("valueType") String valueType,
                                  @JsonProperty("value") Object value,
                                  @JsonProperty("unitLabel") String unitLabel) {
            this.key = key;
            this.label = label;
            this.valueType = valueType;
            this.value = value;
            this.unitLabel = unitLabel;
        }
    }

    /** 删除可用性。由 EquipmentReferenceChecker 逐类读取真实关联，blockers 为空才允许删除。 */
    public record Deletion(boolean allowed, List<DeletionBlocker> blockers) {}

    public record DeletionBlocker(String type, long count, String message) {}
}
