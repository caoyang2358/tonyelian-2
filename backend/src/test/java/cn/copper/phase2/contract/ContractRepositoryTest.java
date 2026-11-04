package cn.copper.phase2.contract;

import cn.copper.phase2.contract.ContractRepository.ContractQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列表条件的 SQL 拼接。
 *
 * <p>供应商那批踩过一次坑：text block 拼接会吃掉前导空格，生成 {@code ... 1 = 1AND EXISTS ...}，
 * 全 mock 的单元测试发现不了，只有真实调用才报数据库错误。这里对空白做显式断言。</p>
 *
 * <p>另外锁住期限表达式的一个约定：提前天数以字面数字拼入，不留占位符，
 * 避免 SELECT 与 WHERE 两处占位符的顺序依赖。</p>
 */
class ContractRepositoryTest {

    @Test
    void expiryStateExpressionEmbedsLeadDaysAsLiteral() {
        String expr = ContractRepository.expiryStateExpr(45);

        assertTrue(expr.contains("DATEDIFF(c.end_date, CURDATE()) <= 45"), expr);
        assertTrue(expr.contains("WHEN c.end_date IS NULL THEN 'UNKNOWN'"), expr);
        assertTrue(expr.contains("WHEN c.end_date < CURDATE() THEN 'EXPIRED'"), expr);
        assertTrue(!expr.contains("?"), "表达式里不应出现占位符：" + expr);
    }

    /** 负的提前天数按 0 处理，不能让 CASE 出现 "<= -5" 这种无意义的判断。 */
    @Test
    void negativeLeadDaysFallsBackToZero() {
        String expr = ContractRepository.expiryStateExpr(-5);

        assertTrue(expr.contains("<= 0"), expr);
    }

    @Test
    void equipmentFilterKeepsSpaceBeforeExists() {
        List<Object> args = new ArrayList<>();

        String sql = ContractRepository.where(
                new ContractQuery(1L, null, null, 1, 20),
                ContractRepository.expiryStateExpr(30), args);

        assertTrue(sql.startsWith(" WHERE 1 = 1 AND EXISTS"), sql);
        assertTrue(sql.contains("ce.equipment_id = ?"), sql);
        assertEquals(List.of(1L), args);
    }

    @Test
    void organizationFilterEscapesWildcards() {
        List<Object> args = new ArrayList<>();

        String sql = ContractRepository.where(
                new ContractQuery(null, "华东_100%", null, 1, 20),
                ContractRepository.expiryStateExpr(30), args);

        assertTrue(sql.contains("c.service_organization LIKE ?"), sql);
        assertEquals("%华东\\_100\\%%", args.get(0));
    }

    /** 状态过滤必须把整个 CASE 包在括号里，否则 AND 优先级会让条件跑偏。 */
    @Test
    void stateFilterWrapsExpressionInParentheses() {
        List<Object> args = new ArrayList<>();
        String expr = ContractRepository.expiryStateExpr(30);

        String sql = ContractRepository.where(
                new ContractQuery(null, null, "EXPIRED", 1, 20), expr, args);

        assertTrue(sql.contains("AND (" + expr + ") = ?"), sql);
        assertEquals(List.of("EXPIRED"), args);
    }

    @Test
    void noFilterLeavesOnlyBaseCondition() {
        List<Object> args = new ArrayList<>();

        String sql = ContractRepository.where(
                new ContractQuery(null, null, null, 1, 20),
                ContractRepository.expiryStateExpr(30), args);

        assertEquals(" WHERE 1 = 1", sql);
        assertTrue(args.isEmpty());
    }

    /** 摘要模板必须含状态列与剩余天数两列，缺一列页面就没法展示期限。 */
    @Test
    void summaryTemplateExposesStateAndRemainingDays() {
        String sql = ContractRepository.SUMMARY_TEMPLATE.formatted(
                ContractRepository.expiryStateExpr(30));

        assertTrue(sql.contains("AS expiry_state"), sql);
        assertTrue(sql.contains("AS remaining_days"), sql);
        assertTrue(sql.contains("AS equipment_count"), sql);
    }
}
