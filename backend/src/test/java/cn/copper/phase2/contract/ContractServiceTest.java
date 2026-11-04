package cn.copper.phase2.contract;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.contract.ContractModels.ContractDetail;
import cn.copper.phase2.contract.ContractModels.ContractSummary;
import cn.copper.phase2.contract.ContractRepository.ContractQuery;
import cn.copper.phase2.contract.ContractRepository.ContractRow;
import cn.copper.phase2.reminder.ReminderSettingModels.ReminderSetting;
import cn.copper.phase2.reminder.ReminderSettingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1-C01 / S1-C02 的读取契约。
 *
 * <p>两个关注点：期限状态筛选不接受未知取值（静默忽略会让页面"筛了但没筛"），
 * 详情的期限字段必须与列表同源，不能各算一套。</p>
 */
class ContractServiceTest {
    private static ContractService service(ContractRepository repository, int maintenanceLeadDays) {
        ReminderSettingService reminder = mock(ReminderSettingService.class);
        when(reminder.current()).thenReturn(new ReminderSetting(30, maintenanceLeadDays, 1,
                OffsetDateTime.parse("2026-11-01T09:00:00+08:00")));
        return new ContractService(repository, reminder);
    }

    @Test
    void rejectsUnknownExpiryState() {
        ContractService service = service(mock(ContractRepository.class), 30);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.search(null, null, "SOON", 1, 20));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(String.valueOf(error.details().get("fields")).contains("expiryState"));
    }

    /** 前端习惯小写传参，这里归一成契约定义的大写枚举，而不是判成非法值。 */
    @Test
    void normalizesLowerCaseStateToContractValue() {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.search(any(), anyLong())).thenReturn(List.of());
        when(repository.count(any(), anyLong())).thenReturn(0L);
        ContractService service = service(repository, 30);

        service.search(null, null, "expiring_soon", 1, 20);

        ArgumentCaptor<ContractQuery> captor = ArgumentCaptor.forClass(ContractQuery.class);
        verify(repository).search(captor.capture(), anyLong());
        assertEquals("EXPIRING_SOON", captor.getValue().expiryState());
    }

    /** 提前天数取"维保"那一项，不是质保；两者在设置里是独立的。 */
    @Test
    void usesMaintenanceLeadDaysRatherThanWarranty() {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.search(any(), anyLong())).thenReturn(List.of());
        when(repository.count(any(), anyLong())).thenReturn(0L);
        ContractService service = service(repository, 45);

        service.search(null, null, null, 1, 20);

        verify(repository).search(any(), org.mockito.ArgumentMatchers.eq(45L));
        verify(repository).count(any(), org.mockito.ArgumentMatchers.eq(45L));
    }

    @Test
    void rejectsMissingContractInsteadOfReturningEmptyShell() {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.findRow(3L)).thenReturn(Optional.empty());
        ContractService service = service(repository, 30);

        BusinessException error = assertThrows(BusinessException.class, () -> service.detail("3"));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void detailMergesRowWithComputedExpiry() {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.findRow(3L)).thenReturn(Optional.of(new ContractRow(3L, "华东维保", "HT-001",
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "年度维保", 4,
                OffsetDateTime.parse("2026-11-04T09:00:00+08:00"))));
        when(repository.summary(3L, 30)).thenReturn(Optional.of(new ContractSummary("3", "华东维保",
                "HT-001", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"),
                2L, "ACTIVE", 57)));
        when(repository.equipmentIds(3L)).thenReturn(List.of(1L, 2L));
        when(repository.attachmentIds(3L)).thenReturn(List.of());
        ContractService service = service(repository, 30);

        ContractDetail detail = service.detail("3");

        assertEquals("华东维保", detail.serviceOrganization());
        assertEquals("年度维保", detail.remark());
        assertEquals(List.of("1", "2"), detail.equipmentIds());
        assertTrue(detail.attachmentIds().isEmpty());
        assertEquals("ACTIVE", detail.expiryState());
        assertEquals(57, detail.remainingDays());
        assertEquals(4, detail.version());
    }

    /** 缺结束日期的合同返回 UNKNOWN 与 null，不用 0 冒充"今天到期"。 */
    @Test
    void missingEndDateYieldsUnknownRatherThanZero() {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.findRow(5L)).thenReturn(Optional.of(new ContractRow(5L, "华东维保", null,
                null, null, null, 1, OffsetDateTime.parse("2026-11-04T09:00:00+08:00"))));
        when(repository.summary(5L, 30)).thenReturn(Optional.of(new ContractSummary("5", "华东维保",
                null, null, null, 0L, "UNKNOWN", null)));
        when(repository.equipmentIds(5L)).thenReturn(List.of());
        when(repository.attachmentIds(5L)).thenReturn(List.of());
        ContractService service = service(repository, 30);

        ContractDetail detail = service.detail("5");

        assertEquals("UNKNOWN", detail.expiryState());
        assertNull(detail.remainingDays());
        assertNull(detail.endDate());
    }

    @Test
    void pageSizeOverLimitIsRejected() {
        ContractService service = service(mock(ContractRepository.class), 30);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.search(null, null, null, 1, 500));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
    }

    @Test
    void pageResultCarriesTotalFromSeparateCount() {
        ContractRepository repository = mock(ContractRepository.class);
        when(repository.search(any(), anyLong())).thenReturn(List.of());
        when(repository.count(any(), anyLong())).thenReturn(7L);
        ContractService service = service(repository, 30);

        PageResult<ContractSummary> page = service.search(null, null, null, 1, 20);

        assertEquals(7L, page.total());
        assertEquals(1, page.page());
    }
}
