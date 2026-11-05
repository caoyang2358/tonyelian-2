package cn.copper.phase2.equipment;

import cn.copper.phase2.common.Json;
import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogItem;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentDetail;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentProfile;
import cn.copper.phase2.equipment.EquipmentRepository.EquipmentRow;
import cn.copper.phase2.equipment.EquipmentWriteModels.EquipmentWriteRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S1-E03 / S1-E04 / S1-E06 的写入与留痕。
 * <p>每次写入把设备主数据、扩展档案、版本递增和更正留痕放在同一事务里，
 * 由 {@link IdempotencyService} 提供幂等边界；失败时整体回滚，不留半条记录。</p>
 */
@Service
public class EquipmentWriteService {
    private static final ObjectMapper JSON = Json.MAPPER;
    private static final String OBJECT_TYPE = "EQUIPMENT";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private static final List<String> CREATE_REQUIRED = List.of(
            "requestId", "operatorName", "code", "name", "workshopId",
            "responsiblePerson", "enabled", "profile");

    private static final List<String> UPDATE_REQUIRED = List.of(
            "requestId", "operatorName", "expectedVersion", "reason", "code", "name",
            "workshopId", "responsiblePerson", "enabled", "profile");

    private static final List<String> PROFILE_REQUIRED = List.of(
            "typeCode", "model", "processId", "installationLocation", "manufacturer",
            "factorySerialNo", "purchasedOn", "commissionedOn", "expectedLifeYears",
            "warrantyExpiresOn", "managementStatus", "technicalParameters");

    private final EquipmentRepository repository;
    private final ChangeLogRepository changeLog;
    private final IdempotencyService idempotency;
    private final EquipmentValidator validator;
    private final EquipmentService reader;

    public EquipmentWriteService(EquipmentRepository repository, ChangeLogRepository changeLog,
                                 IdempotencyService idempotency, EquipmentValidator validator,
                                 EquipmentService reader) {
        this.repository = repository;
        this.changeLog = changeLog;
        this.idempotency = idempotency;
        this.validator = validator;
        this.reader = reader;
    }

    /** S1-E03 新增设备。 */
    public EquipmentDetail create(JsonNode body) {
        EquipmentWriteRequest request = normalize(parse(body, false));
        validator.validate(request, false);
        validateWorkshopAndProcess(request);
        return idempotency.execute(request.requestId(), "EQUIPMENT_CREATE", request,
                EquipmentDetail.class, () -> doCreate(request));
    }

    /** S1-E04 更正设备。全量表单 + 乐观锁 + 非空原因。 */
    public EquipmentDetail update(String equipmentId, JsonNode body) {
        long id = EquipmentService.parseEquipmentId(equipmentId);
        EquipmentWriteRequest request = normalize(parse(body, true));
        validator.validate(request, true);
        validateWorkshopAndProcess(request);
        return idempotency.execute(request.requestId(), "EQUIPMENT_UPDATE:" + id, request,
                EquipmentDetail.class, () -> doUpdate(id, request));
    }

    /** S1-E06 变更记录。按录入时间倒序，页面按此渲染时间线。 */
    public PageResult<ChangeLogItem> changes(String equipmentId, Integer page, Integer pageSize) {
        long id = EquipmentService.parseEquipmentId(equipmentId);
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolvePageSize(pageSize);
        String objectId = String.valueOf(id);
        long total = changeLog.count(OBJECT_TYPE, objectId);
        if (total == 0 && repository.findDetail(id).isEmpty()) {
            throw BusinessException.notFound("设备不存在，且没有可查询的变更记录。");
        }
        return PageResult.of(changeLog.page(OBJECT_TYPE, objectId, resolvedPage, resolvedSize),
                resolvedPage, resolvedSize, total);
    }

    private EquipmentDetail doCreate(EquipmentWriteRequest request) {
        if (repository.codeExists(request.code())) {
            throw BusinessException.duplicateCode(request.code());
        }
        long equipmentId = repository.insertEquipment(request.code(), request.name(),
                Long.parseLong(request.workshopId()), request.responsiblePerson(), request.enabled());
        repository.insertProfile(equipmentId, request.profile(),
                EquipmentRepository.parametersJson(request.profile().technicalParameters()));
        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(equipmentId), "CREATE",
                null, snapshot(request), request.reason(), request.operatorName(),
                0, 1, request.requestId()));
        return reader.equipmentDetail(String.valueOf(equipmentId));
    }

    private EquipmentDetail doUpdate(long equipmentId, EquipmentWriteRequest request) {
        // 锁定聚合行：版本比对与写入之间不允许被其他事务插队。
        EquipmentRow current = repository.lockDetail(equipmentId)
                .orElseThrow(() -> BusinessException.notFound("设备不存在或不可用。"));

        if (!current.code().equals(request.code())) {
            throw BusinessException.invalid("code", "设备编码是身份字段，更正时不能修改。");
        }
        long expected = request.expectedVersion();
        if (current.version() != expected) {
            throw BusinessException.versionConflict(expected, current.version());
        }

        repository.updateEquipment(equipmentId, request.name(),
                Long.parseLong(request.workshopId()), request.responsiblePerson(), request.enabled());

        // type_code 为 NOT NULL，因此有扩展行时必不为空；为空说明尚未建立扩展档案。
        boolean hasProfile = current.profile().typeCode() != null;
        int nextVersion;
        if (hasProfile) {
            int updated = repository.updateProfile(equipmentId, request.profile(),
                    EquipmentRepository.parametersJson(request.profile().technicalParameters()), expected);
            if (updated == 0) {
                throw BusinessException.versionConflict(expected, expected + 1);
            }
            nextVersion = Math.toIntExact(expected + 1);
        } else {
            try {
                repository.insertProfile(equipmentId, request.profile(),
                        EquipmentRepository.parametersJson(request.profile().technicalParameters()));
            } catch (DuplicateKeyException concurrentInsert) {
                // 两个客户端同时首次补档：后到的那个按版本冲突处理，而不是静默覆盖。
                throw BusinessException.versionConflict(expected, expected + 1);
            }
            nextVersion = 1;
        }

        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(equipmentId), "UPDATE",
                snapshot(current), snapshot(request), request.reason(), request.operatorName(),
                Math.toIntExact(expected), nextVersion, request.requestId()));

        return reader.equipmentDetail(String.valueOf(equipmentId));
    }

    /**
     * 全量表单校验：可空字段必须显式出现（传 null 表示清空），遗漏即 400。
     * 只读字段（id/workshopName/version/missingFields/deletion）即使出现也不采信。
     */
    private static EquipmentWriteRequest parse(JsonNode body, boolean update) {
        if (body == null || !body.isObject()) {
            throw BusinessException.invalid("body", "请求体必须是 JSON 对象。");
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        for (String field : update ? UPDATE_REQUIRED : CREATE_REQUIRED) {
            if (!body.has(field)) {
                missing.put(field, "该字段必须出现；可空字段请显式传 null。");
            }
        }
        JsonNode profile = body.get("profile");
        if (profile == null || !profile.isObject()) {
            missing.put("profile", "扩展档案必须是对象。");
        } else {
            for (String field : PROFILE_REQUIRED) {
                if (!profile.has(field)) {
                    missing.put("profile." + field, "该字段必须出现；可空字段请显式传 null。");
                }
            }
        }
        if (!missing.isEmpty()) {
            throw BusinessException.invalid(missing);
        }
        try {
            return JSON.treeToValue(body, EquipmentWriteRequest.class);
        } catch (JsonProcessingException error) {
            throw BusinessException.invalid("body", "请求体字段类型不符合约定，请检查日期与数字格式。");
        }
    }

    private static EquipmentWriteRequest normalize(EquipmentWriteRequest request) {
        EquipmentProfile profile = request.profile();
        EquipmentProfile normalizedProfile = new EquipmentProfile(
                EquipmentValidator.trimToNull(profile.typeCode()),
                EquipmentValidator.trimToNull(profile.model()),
                EquipmentValidator.trimToNull(profile.processId()),
                EquipmentValidator.trimToNull(profile.installationLocation()),
                EquipmentValidator.trimToNull(profile.manufacturer()),
                EquipmentValidator.trimToNull(profile.factorySerialNo()),
                profile.purchasedOn(),
                profile.commissionedOn(),
                profile.expectedLifeYears(),
                profile.warrantyExpiresOn(),
                EquipmentValidator.trimToNull(profile.managementStatus()),
                profile.technicalParameters() == null ? List.of() : List.copyOf(profile.technicalParameters()));
        return new EquipmentWriteRequest(
                request.requestId() == null ? null : request.requestId().trim(),
                request.operatorName() == null ? null : request.operatorName().trim(),
                request.expectedVersion(),
                EquipmentValidator.trimToNull(request.reason()),
                EquipmentValidator.trimToNull(request.code()),
                EquipmentValidator.trimToNull(request.name()),
                EquipmentValidator.trimToNull(request.workshopId()),
                EquipmentValidator.trimToNull(request.responsiblePerson()),
                request.enabled(),
                normalizedProfile);
    }

    /** 车间须存在且可用；工序须存在、可用，且属于所选车间。 */
    private void validateWorkshopAndProcess(EquipmentWriteRequest request) {
        long workshopId;
        try {
            workshopId = Long.parseLong(request.workshopId());
        } catch (NumberFormatException error) {
            throw BusinessException.invalid("workshopId", "车间 ID 必须是正整数。");
        }
        if (!repository.workshopUsable(workshopId)) {
            throw BusinessException.invalid("workshopId", "所选车间不存在或已停用。");
        }
        String processId = request.profile().processId();
        if (processId == null) {
            return;
        }
        long parsedProcessId;
        try {
            parsedProcessId = Long.parseLong(processId);
        } catch (NumberFormatException error) {
            throw BusinessException.invalid("profile.processId", "工序 ID 必须是正整数。");
        }
        Long owner = repository.processWorkshop(parsedProcessId)
                .orElseThrow(() -> BusinessException.invalid("profile.processId", "所选工序不存在或已停用。"));
        if (owner != workshopId) {
            throw BusinessException.invalid("profile.processId", "所选工序不属于该车间。");
        }
    }

    private static Map<String, Object> snapshot(EquipmentWriteRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", request.code());
        snapshot.put("name", request.name());
        snapshot.put("workshopId", request.workshopId());
        snapshot.put("responsiblePerson", request.responsiblePerson());
        snapshot.put("enabled", request.enabled());
        snapshot.put("profile", request.profile());
        return snapshot;
    }

    /** 供删除服务复用：删除留痕的 before 快照必须与更正留痕同构。 */
    static Map<String, Object> snapshot(EquipmentRow row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", row.code());
        snapshot.put("name", row.name());
        snapshot.put("workshopId", row.workshopId());
        snapshot.put("responsiblePerson", row.responsiblePerson());
        snapshot.put("enabled", row.enabled());
        snapshot.put("profile", row.profile());
        return snapshot;
    }

    private static int resolvePage(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw BusinessException.invalid("page", "页码必须从 1 开始。");
        }
        return page;
    }

    private static int resolvePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.invalid("pageSize", "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间。");
        }
        return pageSize;
    }
}
