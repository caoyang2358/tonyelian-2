package cn.copper.phase2.equipment;

import cn.copper.phase2.common.Json;
import cn.copper.phase2.equipment.EquipmentWriteModels.EquipmentWriteRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写入载荷的时间字段往返。
 * <p>此前各仓库用的是裸 {@code new ObjectMapper()}，带日期的请求一律被拒成
 * "请求体字段类型不符合约定"，而详情读取却正常，问题只在写入侧暴露。</p>
 */
class EquipmentWritePayloadTest {
    private static final String RAW = """
            {
              "requestId": "a1b2c3d4-0000-4000-8000-000000000001",
              "operatorName": "设备管理员",
              "expectedVersion": 2,
              "reason": "补充日期",
              "code": "EQUIP-001",
              "name": "示例设备",
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
                "purchasedOn": "2024-03-01",
                "commissionedOn": null,
                "expectedLifeYears": 15,
                "warrantyExpiresOn": "2027-06-14",
                "managementStatus": "IN_USE",
                "technicalParameters": [
                  {"key": "temperature", "label": "熔体温度", "valueType": "DECIMAL", "value": "1080.5", "unitLabel": "摄氏度"}
                ]
              }
            }
            """;

    @Test
    void parsesIsoDatesAndKeepsUnknownFieldsNull() throws Exception {
        EquipmentWriteRequest request = Json.MAPPER.treeToValue(Json.MAPPER.readTree(RAW), EquipmentWriteRequest.class);

        assertEquals(LocalDate.of(2024, 3, 1), request.profile().purchasedOn());
        assertEquals(LocalDate.of(2027, 6, 14), request.profile().warrantyExpiresOn());
        assertNull(request.profile().commissionedOn());
        assertNull(request.responsiblePerson());
        assertEquals(15, request.profile().expectedLifeYears());
        assertEquals(2, request.expectedVersion());
        assertEquals(1, request.profile().technicalParameters().size());
    }

    @Test
    void serializesDatesAsIsoStringsNotArrays() throws Exception {
        EquipmentWriteRequest request = Json.MAPPER.treeToValue(Json.MAPPER.readTree(RAW), EquipmentWriteRequest.class);

        String written = Json.MAPPER.writeValueAsString(request);

        assertTrue(written.contains("\"purchasedOn\":\"2024-03-01\""), written);
        assertTrue(written.contains("\"warrantyExpiresOn\":\"2027-06-14\""), written);
    }

    @Test
    void rejectsMalformedDateInsteadOfSilentlyPassing() {
        String broken = RAW.replace("2024-03-01", "2024/03/01");
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> Json.MAPPER.treeToValue(Json.MAPPER.readTree(broken), EquipmentWriteRequest.class));
    }
}
