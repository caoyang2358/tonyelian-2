package cn.copper.phase2.equipment;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.equipment.EquipmentModels.Deletion;
import cn.copper.phase2.equipment.EquipmentModels.DeletionBlocker;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentDetail;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentItem;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentListResponse;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentProfile;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentQuery;
import cn.copper.phase2.equipment.EquipmentRepository.EquipmentRow;
import cn.copper.phase2.reference.ReferenceDictionaries;
import cn.copper.phase2.reminder.ReminderSettingService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static cn.copper.phase2.deadline.DeadlineModels.STATE_DUE_SOON;
import static cn.copper.phase2.deadline.DeadlineModels.STATE_EXPIRED;
import static cn.copper.phase2.deadline.DeadlineModels.STATE_MISSING_DATA;
import static cn.copper.phase2.deadline.DeadlineModels.STATE_NORMAL;

@Service
public class EquipmentService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 业务日期按厂区时区取，与 S1-T01 期限列表一致，不跟随 JVM 或数据库服务器时区。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final EquipmentRepository repository;
    private final EquipmentReferenceChecker referenceChecker;
    private final ReminderSettingService reminderSetting;

    public EquipmentService(EquipmentRepository repository, EquipmentReferenceChecker referenceChecker,
                            ReminderSettingService reminderSetting) {
        this.repository = repository;
        this.referenceChecker = referenceChecker;
        this.reminderSetting = reminderSetting;
    }

    /**
     * S1-E01：未传分页参数时返回全部匹配项，与首批 data.items 形态一致；
     * 显式传 page/pageSize 时才附加分页元数据。
     */
    public EquipmentListResponse equipment(EquipmentQuery input) {
        EquipmentQuery query = normalize(input);
        List<EquipmentItem> items = withMaintenanceExpiry(repository.search(query).stream()
                .map(EquipmentService::withTypeName)
                .toList());
        if (query.page() == null) {
            return EquipmentListResponse.all(items);
        }
        return EquipmentListResponse.paged(items, query.page(), query.pageSize(), repository.count(query));
    }

    /**
     * 维保到期列：日期已由仓库层取到（最近一份合同的结束日），这里补剩余天数与状态。
     *
     * <p>判定口径与 S1-T01 的 {@code DeadlineRepository.withCte} 完全一致：到期日缺失为
     * MISSING_DATA，已过为 EXPIRED，剩余天数不超过当前维保提前量为 DUE_SOON，其余 NORMAL。
     * 两处必须同步，否则列表说"正常"、看板说"即将到期"。</p>
     *
     * <p>只有确实存在到期日时才去读提醒设置：整页都没有合同时（当前就是这种情况），
     * 不该为了算一个用不上的状态去碰配置表，也避免只读页面产生写入。</p>
     */
    private List<EquipmentItem> withMaintenanceExpiry(List<EquipmentItem> items) {
        boolean anyDated = items.stream().anyMatch(item -> item.maintenanceDueDate() != null);
        if (!anyDated) {
            return items.stream().map(item -> withExpiry(item, null, STATE_MISSING_DATA)).toList();
        }
        long leadDays = reminderSetting.current().maintenanceLeadDays();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return items.stream().map(item -> {
            LocalDate dueDate = item.maintenanceDueDate();
            if (dueDate == null) {
                return withExpiry(item, null, STATE_MISSING_DATA);
            }
            int remainingDays = (int) ChronoUnit.DAYS.between(today, dueDate);
            String state = remainingDays < 0 ? STATE_EXPIRED
                    : remainingDays <= leadDays ? STATE_DUE_SOON : STATE_NORMAL;
            return withExpiry(item, remainingDays, state);
        }).toList();
    }

    private static EquipmentItem withExpiry(EquipmentItem item, Integer remainingDays, String state) {
        return new EquipmentItem(item.id(), item.code(), item.name(), item.workshopId(),
                item.workshopName(), item.responsiblePerson(), item.enabled(),
                item.typeCode(), item.typeName(), item.managementStatus(), item.version(),
                item.maintenanceDueDate(), remainingDays, state);
    }

    /** 类型名称来自研发字典；历史设备类型为 null 时名称也为 null，不反推、不猜测。 */
    private static EquipmentItem withTypeName(EquipmentItem item) {
        return new EquipmentItem(item.id(), item.code(), item.name(), item.workshopId(),
                item.workshopName(), item.responsiblePerson(), item.enabled(),
                item.typeCode(), ReferenceDictionaries.equipmentTypeName(item.typeCode()),
                item.managementStatus(), item.version(), item.maintenanceDueDate(),
                item.maintenanceRemainingDays(), item.maintenanceExpiryState());
    }

    /**
     * S1-E02 设备详情。纯只读：扩展档案不存在时返回版本 0 与缺项提示，不建立任何扩展行。
     */
    public EquipmentDetail equipmentDetail(String equipmentId) {
        long id = parseEquipmentId(equipmentId);
        EquipmentRow row = repository.findDetail(id)
                .orElseThrow(() -> BusinessException.notFound("设备不存在或不可用。"));
        return new EquipmentDetail(
                row.id(), row.code(), row.name(), row.workshopId(), row.workshopName(),
                row.responsiblePerson(), row.enabled(),
                row.profile(), row.version(),
                missingFields(row.profile()),
                deletion(id));
    }

    private static EquipmentQuery normalize(EquipmentQuery input) {
        Integer page = input.page();
        Integer pageSize = input.pageSize();
        if (page != null && page < 1) {
            throw BusinessException.invalid("page", "页码必须从 1 开始。");
        }
        if (pageSize != null && (pageSize < 1 || pageSize > MAX_PAGE_SIZE)) {
            throw BusinessException.invalid("pageSize", "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间。");
        }
        if (page == null && pageSize != null) {
            page = 1;
        }
        if (page != null && pageSize == null) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        String typeCode = blankToNull(input.typeCode());
        if (typeCode != null && !ReferenceDictionaries.isEquipmentType(typeCode)) {
            throw BusinessException.invalid("typeCode", "设备类型不在可选范围内。");
        }
        String managementStatus = blankToNull(input.managementStatus());
        if (managementStatus != null && !ReferenceDictionaries.isManagementStatus(managementStatus)) {
            throw BusinessException.invalid("managementStatus", "管理状态不在可选范围内。");
        }
        return new EquipmentQuery(blankToNull(input.keyword()), input.workshopId(),
                typeCode, managementStatus, input.enabled(), page, pageSize);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 只列基线必需但尚未补充的字段，不把全部可选资料都当错误。
     * <p>必填范围取自《系统一-设备管理页面接口文档》第 19-26 行：类型与管理状态为新增/编辑必填，
     * 历史设备可读为 null。这里如实列出，供页面提示，不阻断读取。</p>
     */
    private static List<String> missingFields(EquipmentProfile profile) {
        List<String> fields = new ArrayList<>(2);
        if (profile.typeCode() == null || profile.typeCode().isBlank()) {
            fields.add("profile.typeCode");
        }
        if (profile.managementStatus() == null || profile.managementStatus().isBlank()) {
            fields.add("profile.managementStatus");
        }
        return List.copyOf(fields);
    }

    /**
     * 删除可用性：与 E05 删除接口共用 {@link EquipmentReferenceChecker}，逐类读取当前业务关联。
     * 全部为空才允许删除，有任何一类存在关联就返回具体条数，
     * 由页面给出可读原因（文档要求不得伪称已查到无关联）。
     */
    private Deletion deletion(long equipmentId) {
        List<DeletionBlocker> blockers = referenceChecker.blockers(equipmentId);
        return new Deletion(blockers.isEmpty(), blockers);
    }

    static long parseEquipmentId(String raw) {
        try {
            long id = Long.parseLong(raw == null ? "" : raw.trim());
            if (id <= 0) {
                throw new NumberFormatException("非正整数");
            }
            return id;
        } catch (NumberFormatException error) {
            throw BusinessException.invalid("equipmentId", "设备 ID 必须是正整数。");
        }
    }
}
