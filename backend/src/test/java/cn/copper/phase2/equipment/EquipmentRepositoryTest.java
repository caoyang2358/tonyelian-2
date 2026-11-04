package cn.copper.phase2.equipment;

import cn.copper.phase2.equipment.EquipmentModels.EquipmentItem;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EquipmentRepositoryTest {
    @Test
    void equipmentSqlMapsSharedIdentityAndBooleanWithoutInventingDefaults() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(903L);
        when(row.getString("code")).thenReturn("DRAW-03");
        when(row.getString("name")).thenReturn("3#拉丝机");
        when(row.getLong("workshop_id")).thenReturn(82L);
        when(row.getString("workshop_name")).thenReturn("车间2");
        when(row.getString("responsible_person")).thenReturn(null);
        when(row.getBoolean("enabled")).thenReturn(false);
        when(row.getString("type_code")).thenReturn(null);
        when(row.getString("management_status")).thenReturn(null);
        when(row.getLong("version")).thenReturn(0L);
        when(jdbc.query(eq(EquipmentRepository.EQUIPMENT_SQL),
                ArgumentMatchers.<RowMapper<EquipmentItem>>any())).thenAnswer(call -> {
            RowMapper<EquipmentItem> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });

        List<EquipmentItem> result = new EquipmentRepository(jdbc).productionEquipment();
        assertEquals(1, result.size());
        assertEquals(new EquipmentItem(903, "DRAW-03", "3#拉丝机", 82, "车间2", null, false,
                null, null, null, 0L), result.get(0));
        assertTrue(EquipmentRepository.EQUIPMENT_SQL.contains("e.equipment_usage = 'PRODUCTION'"));
        assertTrue(EquipmentRepository.EQUIPMENT_SQL.contains("w.workshop_id = e.workshop_id"));
        assertFalse(EquipmentRepository.EQUIPMENT_SQL.contains("e.enabled ="));
    }

    @Test
    void searchUsesLeftJoinSoProfilelessRowsSurviveAndAppliesBackendFilters() {
        List<Object> args = new ArrayList<>();
        String sql = EquipmentRepository.searchSql(new EquipmentQuery(
                " 拉丝 ", 2L, "DRAWING_MACHINE", "IN_USE", true, null, null), args);

        // LEFT JOIN 才能保证未补档的历史设备不被过滤掉
        assertTrue(sql.contains("LEFT JOIN p2_equipment_profile"));
        assertTrue(sql.contains("e.equipment_code LIKE ?"));
        assertTrue(sql.contains("e.workshop_id = ?"));
        assertTrue(sql.contains("p.type_code = ?"));
        assertTrue(sql.contains("p.management_status = ?"));
        assertTrue(sql.contains("AND e.enabled = ?"));
        assertTrue(sql.contains("ORDER BY w.workshop_code, e.equipment_code"));
        assertFalse(sql.contains("LIMIT"), "未传分页参数时必须返回全部匹配项");
        assertFalse(sql.contains("JOIN dim_equipment e\n JOIN dim_workshop"));
        // 参数顺序与占位符一致：keyword 两次、车间、类型、状态、启用
        assertEquals(6, args.size());
        assertEquals("%拉丝%", args.get(0));
        assertEquals("%拉丝%", args.get(1));
        assertEquals(2L, args.get(2));
        assertEquals("DRAWING_MACHINE", args.get(3));
        assertEquals("IN_USE", args.get(4));
        assertEquals(1, args.get(5));
    }

    @Test
    void explicitPagingAddsLimitOffsetAndDoesNotDisturbFilterOrder() {
        List<Object> args = new ArrayList<>();
        String sql = EquipmentRepository.searchSql(new EquipmentQuery(
                null, null, null, null, null, 3, 20), args);

        assertTrue(sql.contains("LIMIT ? OFFSET ?"));
        assertFalse(sql.contains("LIKE ?"));
        assertEquals(2, args.size());
        assertEquals(20, args.get(0));
        assertEquals(40L, args.get(1));
    }
}
