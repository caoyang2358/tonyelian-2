package cn.copper.phase2.deadline;

import cn.copper.phase2.deadline.DeadlineRepository.DeadlineQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期限语句的拼装与状态判定。
 *
 * <p>这些断言针对的是"口径"而不是 SQL 文本本身：寿命必须固定按一年评估、
 * 超期必须优先于即将到期判定、筛选条件不能吃掉前导空格。</p>
 */
class DeadlineRepositoryTest {

    @Test
    void statementCoversThreeReminderTypesAndBothContractBranches() {
        String sql = DeadlineRepository.withCte(30, 30);

        assertTrue(sql.contains("'WARRANTY' AS reminder_type"), sql);
        assertTrue(sql.contains("'EXPECTED_LIFE' AS reminder_type"), sql);
        assertEquals(2, countOccurrences(sql, "'MAINTENANCE' AS reminder_type"),
                "维保要分「有合同」与「无合同」两支，缺一支就会漏掉待补资料的设备");
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM p2_contract_equipment"), sql);
    }

    @Test
    void leadDaysComeFromSettingsForWarrantyAndMaintenance() {
        String sql = DeadlineRepository.withCte(15, 45);

        assertTrue(sql.contains("WHEN 'WARRANTY' THEN 15"), sql);
        assertTrue(sql.contains("WHEN 'MAINTENANCE' THEN 45"), sql);
    }

    /**
     * 文档明确要求"寿命不足一年不被这两个提前量替换"。
     * 这里把提前量设成极端值，确认寿命分支仍是 365。
     */
    @Test
    void expectedLifeAlwaysUsesOneYearRegardlessOfLeadDays() {
        String sql = DeadlineRepository.withCte(0, 999);

        assertTrue(sql.contains("ELSE 365 END AS lead_days"), sql);
        assertFalse(sql.contains("ELSE 999"), sql);
        assertFalse(sql.contains("ELSE 0 END"), sql);
    }

    @Test
    void negativeLeadDaysAreClampedInsteadOfProducingBrokenSql() {
        String sql = DeadlineRepository.withCte(-5, -1);

        assertTrue(sql.contains("WHEN 'WARRANTY' THEN 0"), sql);
        assertTrue(sql.contains("WHEN 'MAINTENANCE' THEN 0"), sql);
    }

    /** 已过日期必须先判 EXPIRED，否则 remainingDays 为负数时会先命中 DUE_SOON 分支。 */
    @Test
    void expiredIsCheckedBeforeDueSoon() {
        String sql = DeadlineRepository.withCte(30, 30);

        int expired = sql.indexOf("WHEN c.remaining_days < 0 THEN 'EXPIRED'");
        int dueSoon = sql.indexOf("WHEN c.remaining_days <= c.lead_days THEN 'DUE_SOON'");
        assertTrue(expired > 0 && dueSoon > expired, sql);
    }

    /** 到期当天 remainingDays 为 0，必须落在 DUE_SOON 而不是 NORMAL。 */
    @Test
    void dueTodayIsDueSoonNotNormal() {
        String sql = DeadlineRepository.withCte(30, 30);

        assertTrue(sql.contains("<= c.lead_days THEN 'DUE_SOON'"), sql);
    }

    @Test
    void missingDateIsMissingDataBeforeAnyComparison() {
        String sql = DeadlineRepository.withCte(30, 30);

        int missing = sql.indexOf("WHEN c.due_date IS NULL THEN 'MISSING_DATA'");
        int expired = sql.indexOf("WHEN c.remaining_days < 0 THEN 'EXPIRED'");
        assertTrue(missing > 0 && missing < expired, sql);
    }

    /** 今天以占位符传入，避免依赖数据库服务器时区。 */
    @Test
    void todayIsBoundAsParameterNotDatabaseCurrentDate() {
        String sql = DeadlineRepository.withCte(30, 30);

        assertTrue(sql.contains("DATEDIFF(b.due_date, CAST(? AS DATE))"), sql);
        assertFalse(sql.contains("CURDATE()"), "CURDATE() 会跟随数据库服务器时区，与业务日期可能差一天");
    }

    @Test
    void noFilterLeavesOnlyBaseConditionAndNoArguments() {
        List<Object> args = new ArrayList<>();

        String sql = DeadlineRepository.where(new DeadlineQuery(null, null, null, null, 1, 20), args);

        assertEquals(" WHERE 1 = 1", sql);
        assertTrue(args.isEmpty());
    }

    @Test
    void allFourFiltersKeepLeadingSpaceAndBindInOrder() {
        List<Object> args = new ArrayList<>();

        String sql = DeadlineRepository.where(
                new DeadlineQuery(1L, 2L, "WARRANTY", "DUE_SOON", 1, 20), args);

        assertTrue(sql.startsWith(" WHERE 1 = 1 AND ds.equipment_id = ? AND ds.workshop_id = ?"), sql);
        assertTrue(sql.contains(" AND ds.reminder_type = ?"), sql);
        assertTrue(sql.contains(" AND ds.expiry_state = ?"), sql);
        assertEquals(List.of(1L, 2L, "WARRANTY", "DUE_SOON"), args);
    }

    @Test
    void missingFieldsPointAtTheFieldThatActuallyBlocksTheDeadline() {
        LocalDate due = LocalDate.of(2026, 12, 31);

        assertEquals(List.of("warrantyExpiresOn"),
                DeadlineRepository.missingFields("WARRANTY", null, due, 10, null));
        assertTrue(DeadlineRepository.missingFields("WARRANTY", due, due, 10, null).isEmpty());

        assertEquals(List.of("commissionedOn", "expectedLifeYears"),
                DeadlineRepository.missingFields("EXPECTED_LIFE", null, null, null, null));
        assertEquals(List.of("commissionedOn"),
                DeadlineRepository.missingFields("EXPECTED_LIFE", null, null, 12, null));

        assertEquals(List.of("contractId"),
                DeadlineRepository.missingFields("MAINTENANCE", null, null, null, null));
        assertEquals(List.of("endDate"),
                DeadlineRepository.missingFields("MAINTENANCE", null, null, null, "18"));
        assertTrue(DeadlineRepository.missingFields("MAINTENANCE", due, null, null, "18").isEmpty());
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }
}
