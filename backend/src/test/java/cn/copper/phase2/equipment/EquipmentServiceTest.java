package cn.copper.phase2.equipment;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentDetail;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentItem;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentListResponse;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentProfile;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentQuery;
import cn.copper.phase2.equipment.EquipmentRepository.EquipmentRow;
import cn.copper.phase2.reminder.ReminderSettingModels.ReminderSetting;
import cn.copper.phase2.reminder.ReminderSettingService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EquipmentServiceTest {
    private static EquipmentProfile emptyProfile() {
        return new EquipmentProfile(null, null, null, null, null, null,
                null, null, null, null, null, List.of());
    }

    private static EquipmentRow smeltingFurnace(EquipmentProfile profile, long version) {
        return new EquipmentRow("1", "SMELT-FUR-01", "1#熔化炉", "1", "车间1 · 铜杆生产",
                null, true, profile, version);
    }

    private static EquipmentService service(EquipmentRepository repository) {
        return service(repository, mock(ReminderSettingService.class));
    }

    private static EquipmentService service(EquipmentRepository repository,
                                            ReminderSettingService reminderSetting) {
        return new EquipmentService(repository, new EquipmentReferenceChecker(repository), reminderSetting);
    }

    /** 列表项构造：只有维保到期三项与用例相关，其余字段固定，避免噪声。 */
    private static EquipmentItem listItem(long id, String code, LocalDate maintenanceDueDate) {
        return new EquipmentItem(id, code, code + " 设备", 1, "车间1", "曹阳", true,
                "DRAWING_MACHINE", "拉丝机", "IN_USE", 4L, maintenanceDueDate, null, null);
    }

    private static ReminderSettingService settingWith(int maintenanceLeadDays) {
        ReminderSettingService reminderSetting = mock(ReminderSettingService.class);
        when(reminderSetting.current()).thenReturn(
                new ReminderSetting(15, maintenanceLeadDays, 1L, null));
        return reminderSetting;
    }

    @Test
    void detailWithoutExtensionReturnsVersionZeroAndMissingType() {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        when(repository.findDetail(1L)).thenReturn(Optional.of(smeltingFurnace(emptyProfile(), 0L)));
        when(repository.countReferences("dim_sensor", 1L)).thenReturn(1L);

        EquipmentDetail detail = service(repository).equipmentDetail("1");

        assertEquals("1", detail.id());
        assertEquals("SMELT-FUR-01", detail.code());
        assertNull(detail.responsiblePerson());
        assertEquals(0L, detail.version());
        assertEquals(List.of("profile.typeCode", "profile.managementStatus"), detail.missingFields());
        assertFalse(detail.deletion().allowed(), "存在关联时不允许返回可删除");
        assertEquals(1, detail.deletion().blockers().size());
        assertEquals("SENSOR", detail.deletion().blockers().get(0).type());
        assertEquals(1L, detail.deletion().blockers().get(0).count());
    }

    @Test
    void completedProfileHasNoMissingFields() {
        EquipmentProfile profile = new EquipmentProfile("MELTING_FURNACE", "MF-120", null,
                "1号厂房东侧", "某铜业设备厂", "SN-2020-001",
                LocalDate.of(2020, 3, 1), LocalDate.of(2020, 4, 1), 15,
                LocalDate.of(2027, 3, 31), "IN_USE", List.of());
        EquipmentRepository repository = mock(EquipmentRepository.class);
        when(repository.findDetail(1L)).thenReturn(Optional.of(smeltingFurnace(profile, 3L)));

        EquipmentDetail detail = service(repository).equipmentDetail("1");

        assertEquals(3L, detail.version());
        assertTrue(detail.missingFields().isEmpty());
        assertEquals("MELTING_FURNACE", detail.profile().typeCode());
        assertEquals(List.of(), detail.profile().technicalParameters());
        assertTrue(detail.deletion().allowed(), "十类关联全部为 0 时应允许删除");
        assertTrue(detail.deletion().blockers().isEmpty());
    }

    @Test
    void deletionListsEveryReferencedTypeNotJustSensors() {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        when(repository.findDetail(1L)).thenReturn(Optional.of(smeltingFurnace(emptyProfile(), 2L)));
        when(repository.countReferences("p2_repair_order", 1L)).thenReturn(2L);
        when(repository.countReferences("p2_contract_equipment", 1L)).thenReturn(1L);

        EquipmentDetail detail = service(repository).equipmentDetail("1");

        assertFalse(detail.deletion().allowed());
        assertEquals(2, detail.deletion().blockers().size());
        assertTrue(detail.deletion().blockers().stream()
                .anyMatch(blocker -> "REPAIR".equals(blocker.type()) && blocker.count() == 2L));
        assertTrue(detail.deletion().blockers().stream()
                .anyMatch(blocker -> "CONTRACT".equals(blocker.type())));
    }

    @Test
    void unknownEquipmentIsNotFoundRatherThanAnEmptyProfile() {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        when(repository.findDetail(999L)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository).equipmentDetail("999"));
        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void nonNumericIdentifierIsValidationError() {
        EquipmentRepository repository = mock(EquipmentRepository.class);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository).equipmentDetail("abc"));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        verifyNoInteractions(repository);
    }

    @Test
    void listWithoutAnyContractReportsMissingDataAndDoesNotReadSettings() {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        ReminderSettingService reminderSetting = mock(ReminderSettingService.class);
        when(repository.search(any(EquipmentQuery.class)))
                .thenReturn(List.of(listItem(1, "DRAW-01", null), listItem(2, "DRAW-02", null)));

        EquipmentListResponse response = service(repository, reminderSetting)
                .equipment(new EquipmentQuery(null, null, null, null, null, null, null));

        assertEquals(2, response.items().size());
        response.items().forEach(item -> {
            assertNull(item.maintenanceDueDate());
            assertNull(item.maintenanceRemainingDays());
            assertEquals("MISSING_DATA", item.maintenanceExpiryState());
        });
        // 整页都没有到期日时不该去碰提醒设置表，避免只读页面产生写入
        verify(reminderSetting, never()).current();
    }

    @Test
    void listComputesRemainingDaysAndStateFromMaintenanceLeadDays() {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        when(repository.search(any(EquipmentQuery.class))).thenReturn(List.of(
                listItem(1, "DRAW-01", today.plusDays(10)),
                listItem(2, "DRAW-02", today.plusDays(200)),
                listItem(3, "DRAW-03", today.minusDays(5))));

        EquipmentListResponse response = service(repository, settingWith(45))
                .equipment(new EquipmentQuery(null, null, null, null, null, null, null));

        assertEquals(List.of(10, 200, -5), response.items().stream()
                .map(EquipmentItem::maintenanceRemainingDays).toList());
        assertEquals(List.of("DUE_SOON", "NORMAL", "EXPIRED"), response.items().stream()
                .map(EquipmentItem::maintenanceExpiryState).toList());
    }
}
