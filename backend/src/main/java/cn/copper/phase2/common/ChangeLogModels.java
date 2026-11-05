package cn.copper.phase2.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * 全项目共用的更正留痕载体（G02）。
 * <p>运维修理单、知识库条目等模块后续同样写这张表，因此这里只放与 E06 契约对齐的最小字段。
 * before/after 为对象快照，新建时 before 为 null，删除后 after 为 null。</p>
 */
public final class ChangeLogModels {
    private ChangeLogModels() {}

    public record ChangeLogItem(String id, String operation, JsonNode before, JsonNode after,
                                String reason, String operatorName, OffsetDateTime recordedAt,
                                String requestId, Integer versionBefore, Integer versionAfter) {}

    /** 写入载荷。objectType 例如 EQUIPMENT，action 为 CREATE/UPDATE/DELETE。 */
    public record ChangeLogWrite(String objectType, String objectId, String action,
                                 Object before, Object after, String reason,
                                 String operatorName, Integer versionBefore, Integer versionAfter,
                                 String requestId) {}
}
