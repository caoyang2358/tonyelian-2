package cn.copper.phase2.equipment;

/**
 * S1-E05 误建删除的请求与响应。
 * <p>请求体为《首批接口约定》中的公共字段 U（requestId / operatorName / expectedVersion / reason），
 * 另带 {@code confirmed:true} 作为二次确认。响应只有 id 与 deleted，符合接口文档的
 * {@code {id, deleted:true}}。</p>
 */
public final class EquipmentDeleteModels {
    private EquipmentDeleteModels() {}

    public record EquipmentDeleteRequest(String requestId, String operatorName,
                                        Long expectedVersion, String reason, boolean confirmed) {}

    public record EquipmentDeleteResult(String id, boolean deleted) {}
}
