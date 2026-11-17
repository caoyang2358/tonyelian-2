package cn.copper.phase2.equipment;

import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.common.Json;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1-E03 新增设备必须留痕（S1-E06 的起点）。
 *
 * <p>变更记录里缺了「新增」这一条，时间线就从第一次更正开始，看不出设备是哪天、
 * 以什么状态建进来的——一期 8 台设备正因早期由导入脚本直接建表而缺这一条。
 * 这条测试把「新增即留痕」钉在代码里，避免以后改写入流程时又被漏掉。</p>
 */
class EquipmentWriteServiceTest {

    private static final long NEW_ID = 26L;

    private static final String CREATE_BODY = """
            {
              "requestId": "a1b2c3d4-0000-4000-8000-0000000000aa",
              "operatorName": "设备管理员",
              "code": "TEMP-001",
              "name": "新增留痕校验设备",
              "workshopId": "1",
              "responsiblePerson": null,
              "enabled": true,
              "profile": {
                "typeCode": "OTHER",
                "model": null,
                "processId": null,
                "installationLocation": null,
                "manufacturer": null,
                "factorySerialNo": null,
                "purchasedOn": null,
                "commissionedOn": null,
                "expectedLifeYears": null,
                "warrantyExpiresOn": null,
                "managementStatus": "IN_USE",
                "technicalParameters": []
              }
            }
            """;

    private final EquipmentRepository repository = mock(EquipmentRepository.class);
    private final ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final EquipmentWriteService service = new EquipmentWriteService(
            repository, changeLog, idempotency, mock(EquipmentValidator.class), mock(EquipmentService.class));

    EquipmentWriteServiceTest() {
        when(repository.codeExists(anyString())).thenReturn(false);
        when(repository.workshopUsable(1L)).thenReturn(true);
        when(repository.insertEquipment(anyString(), anyString(), anyLong(), any(), anyBoolean()))
                .thenReturn(NEW_ID);
        // 幂等执行器在这里只是个壳：真正要观察的是壳里的建档动作有没有写留痕。
        when(idempotency.execute(anyString(), eq("EQUIPMENT_CREATE"), any(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
    }

    @Test
    void createWritesCreateRecordWithFullSnapshot() throws Exception {
        service.create(Json.MAPPER.readTree(CREATE_BODY));

        ChangeLogWrite written = capturedRecord();

        assertEquals("EQUIPMENT", written.objectType());
        assertEquals("26", written.objectId(), "留痕要挂在新建出来的设备 ID 上，不是编码");
        assertEquals("CREATE", written.action());
        assertNull(written.before(), "新增没有改前状态");
        assertNotNull(written.after(), "新增必须带建档快照");
        assertEquals(Integer.valueOf(0), written.versionBefore(), "建档前没有版本");
        assertEquals(Integer.valueOf(1), written.versionAfter(), "建档后的版本是 1，与 insertProfile 写入的 version 一致");
        assertEquals("设备管理员", written.operatorName());
    }

    /** 建档快照要能看出设备是谁、在哪个车间、什么管理状态，否则「新增」这条记录等于只有一句话。 */
    @Test
    void createSnapshotCarriesIdentityAndManagementStatus() throws Exception {
        service.create(Json.MAPPER.readTree(CREATE_BODY));

        String snapshot = Json.MAPPER.writeValueAsString(capturedRecord().after());

        assertTrue(snapshot.contains("\"code\":\"TEMP-001\""), snapshot);
        assertTrue(snapshot.contains("\"name\":\"新增留痕校验设备\""), snapshot);
        assertTrue(snapshot.contains("\"workshopId\":\"1\""), snapshot);
        assertTrue(snapshot.contains("\"managementStatus\":\"IN_USE\""), snapshot);
    }

    private ChangeLogWrite capturedRecord() {
        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        return captor.getValue();
    }
}
