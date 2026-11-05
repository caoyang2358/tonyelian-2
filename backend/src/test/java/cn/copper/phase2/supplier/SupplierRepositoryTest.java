package cn.copper.phase2.supplier;

import cn.copper.phase2.supplier.SupplierRepository.SupplierQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列表条件的 SQL 拼接。
 *
 * <p>这里踩过一次坑：用 text block 拼接片段时，Java 会去掉共同缩进，
 * 前导空格被吃掉，生成 {@code ... 1 = 1AND EXISTS ...}，单元测试（全 mock）发现不了，
 * 只有真实调用才报数据库错误。因此对拼接结果的空白做显式断言。</p>
 */
class SupplierRepositoryTest {

    @Test
    void equipmentFilterKeepsSpaceBeforeAndExists() {
        List<Object> args = new ArrayList<>();

        String sql = SupplierRepository.where(new SupplierQuery(null, 1L, 1, 20), args);

        assertTrue(sql.startsWith(" WHERE 1 = 1 AND EXISTS"), sql);
        assertTrue(sql.contains("es.equipment_id = ?"), sql);
        assertEquals(List.of(1L), args);
    }

    @Test
    void keywordFilterMatchesNameContactAndPhoneWithEscapedWildcards() {
        List<Object> args = new ArrayList<>();

        String sql = SupplierRepository.where(new SupplierQuery("100%_A", null, 1, 20), args);

        assertTrue(sql.contains("(s.name LIKE ? OR s.contact_name LIKE ? OR s.phone LIKE ?)"), sql);
        assertEquals(3, args.size());
        assertEquals("%100\\%\\_A%", args.get(0));
    }

    @Test
    void noFilterLeavesOnlyBaseCondition() {
        List<Object> args = new ArrayList<>();

        String sql = SupplierRepository.where(new SupplierQuery(null, null, 1, 20), args);

        assertEquals(" WHERE 1 = 1", sql);
        assertTrue(args.isEmpty());
    }
}
