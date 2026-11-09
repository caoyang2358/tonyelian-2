package cn.copper.phase2.attachment;

import cn.copper.phase2.attachment.AttachmentModels.AttachmentRow;
import cn.copper.phase2.attachment.AttachmentRepository.AttachmentQuery;
import cn.copper.phase2.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 归属列/表映射与查询拼接。
 *
 * <p>归属类型会决定 SQL 里的列名与表名，所以只认白名单；这里同时钉住拼接后的空白，
 * 避免出现 {@code "= ?AND ..."} 这种只有真实调用才暴露的语法错误。</p>
 */
class AttachmentRepositoryTest {

    @Test
    void ownerTypeMapsToSingleColumnAndTable() {
        assertEquals("equipment_id", AttachmentRepository.ownerColumn("EQUIPMENT"));
        assertEquals("contract_id", AttachmentRepository.ownerColumn("MAINTENANCE_CONTRACT"));
        assertEquals("article_id", AttachmentRepository.ownerColumn("KNOWLEDGE_ARTICLE"));
        assertEquals("repair_order_id", AttachmentRepository.ownerColumn("REPAIR_ORDER"));

        assertEquals("dim_equipment", AttachmentRepository.ownerTable("EQUIPMENT"));
        assertEquals("p2_maintenance_contract", AttachmentRepository.ownerTable("MAINTENANCE_CONTRACT"));
        assertEquals("p2_knowledge_article", AttachmentRepository.ownerTable("KNOWLEDGE_ARTICLE"));
        assertEquals("p2_repair_order", AttachmentRepository.ownerTable("REPAIR_ORDER"));
    }

    @Test
    void unknownOwnerTypeIsRejectedInsteadOfFallingBack() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> AttachmentRepository.ownerColumn("WORKSHOP"));

        assertEquals("VALIDATION_ERROR", error.errorCode().name());
    }

    @Test
    void whereKeepsSpaceBeforeKindFilter() {
        List<Object> args = new ArrayList<>();

        String sql = AttachmentRepository.where(
                new AttachmentQuery("EQUIPMENT", 3L, "MANUAL", 1, 20), args);

        assertTrue(sql.startsWith(" WHERE a.equipment_id = ?"), sql);
        assertTrue(sql.contains("? AND a.file_kind = ?"), sql);
        assertEquals(List.of(3L, "MANUAL"), args);
    }

    @Test
    void whereOmitsKindWhenNotFiltered() {
        List<Object> args = new ArrayList<>();

        String sql = AttachmentRepository.where(
                new AttachmentQuery("MAINTENANCE_CONTRACT", 7L, null, 1, 20), args);

        assertEquals(" WHERE a.contract_id = ?", sql);
        assertEquals(List.of(7L), args);
    }

    /** 插入只能落一个归属列，其余必须由数据库保持 NULL，否则表上的 CHECK 会直接拒绝。 */
    @Test
    void insertTargetsExactlyOneOwnerColumn() {
        String sql = AttachmentRepository.INSERT_TEMPLATE.formatted("repair_order_id");

        assertTrue(sql.contains("(repair_order_id, file_kind"), sql);
        assertFalse(sql.contains("equipment_id"), sql);
        assertFalse(sql.contains("contract_id"), sql);
    }

    /** 列表与详情都不能把 storage_key 带出去；这是契约里"不返回服务器文件路径"的落点。 */
    @Test
    void summarySelectDoesNotExposeStorageKey() {
        String sql = AttachmentRepository.summaryTemplate(
                AttachmentRepository.OWNER_TYPE_EXPR, AttachmentRepository.OWNER_ID_EXPR);

        assertFalse(sql.toLowerCase().contains("storage_key"), sql);
        assertTrue(sql.contains(
                "COALESCE(a.equipment_id, a.contract_id, a.article_id, a.repair_order_id, a.inspection_id)"), sql);
    }

    /**
     * 列表 mapper 不能读 storage_key。
     *
     * <p>这里曾经用同一套全字段 mapper 读列表，真实调用直接报 "Column 'storage_key' not found"，
     * 被通用异常处理器包成了 503。用会抛异常的假 ResultSet 把这个回归钉住。</p>
     */
    @Test
    void summaryMapperDoesNotReadStorageKey() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(anyString())).thenReturn(1L);
        when(rs.getTimestamp(anyString())).thenReturn(null);
        when(rs.getString(anyString())).thenAnswer(invocation -> {
            if ("storage_key".equals(invocation.getArgument(0))) {
                throw new SQLException("Column 'storage_key' not found");
            }
            return "x";
        });

        AttachmentRow row = AttachmentRepository.SUMMARY_MAPPER.mapRow(rs, 0);

        assertEquals(1L, row.attachmentId());
        assertNull(row.storageKey(), "列表行不应带出存储键");
    }
}
