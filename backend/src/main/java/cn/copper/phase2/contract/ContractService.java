package cn.copper.phase2.contract;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.contract.ContractModels.ContractDetail;
import cn.copper.phase2.contract.ContractModels.ContractSummary;
import cn.copper.phase2.contract.ContractRepository.ContractQuery;
import cn.copper.phase2.contract.ContractRepository.ContractRow;
import cn.copper.phase2.reminder.ReminderSettingService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * S1-C01 / S1-C02 合同只读查询。
 *
 * <p>期限状态一律由服务端按业务日期现算，不落库：日期或提醒设置一改，下次查询就是新结果，
 * 不存在"状态字段忘了刷新"的问题。缺结束日期的合同返回 UNKNOWN 而不是"未到期"——
 * 缺资料就该显示缺资料，不用默认值掩盖。</p>
 */
@Service
public class ContractService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ContractRepository repository;
    private final ReminderSettingService reminderSettings;

    public ContractService(ContractRepository repository, ReminderSettingService reminderSettings) {
        this.repository = repository;
        this.reminderSettings = reminderSettings;
    }

    /** S1-C01 分页查询；设备、服务单位、期限状态三者同时给出时取交集。 */
    public PageResult<ContractSummary> search(String equipmentId, String serviceOrganization,
                                              String expiryState, Integer page, Integer pageSize) {
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolvePageSize(pageSize);
        Long resolvedEquipmentId = equipmentId == null || equipmentId.isBlank()
                ? null
                : parsePositiveId(equipmentId, "设备");
        String resolvedState = resolveExpiryState(expiryState);
        long leadDays = maintenanceLeadDays();

        ContractQuery query = new ContractQuery(resolvedEquipmentId, serviceOrganization,
                resolvedState, resolvedPage, resolvedSize);
        return PageResult.of(repository.search(query, leadDays), resolvedPage, resolvedSize,
                repository.count(query, leadDays));
    }

    /** S1-C02 详情；不存在返回 404，不返回空壳合同。 */
    public ContractDetail detail(String contractId) {
        return detail(parseContractId(contractId));
    }

    ContractDetail detail(long contractId) {
        ContractRow row = repository.findRow(contractId)
                .orElseThrow(() -> BusinessException.notFound("合同不存在或已被删除。"));
        ContractSummary summary = repository.summary(contractId, maintenanceLeadDays())
                .orElseThrow(() -> BusinessException.notFound("合同不存在或已被删除。"));
        return new ContractDetail(String.valueOf(row.contractId()), row.serviceOrganization(),
                row.contractNumber(), row.startDate(), row.endDate(), row.remark(),
                toIds(repository.equipmentIds(contractId)),
                toIds(repository.attachmentIds(contractId)),
                summary.expiryState(), summary.remainingDays(), row.version(), row.updatedAt());
    }

    /** 期限提醒用"维保提前天数"，与 T02/T03 保存的配置同源，不另设一套阈值。 */
    long maintenanceLeadDays() {
        return reminderSettings.current().maintenanceLeadDays();
    }

    static List<String> toIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).toList();
    }

    /** 状态筛选值只接受契约定义的四种；非法值给 400，不静默忽略成"全部"。 */
    static String resolveExpiryState(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        if (!ContractModels.EXPIRY_STATES.contains(value)) {
            throw BusinessException.invalid("expiryState",
                    "期限状态只能是 " + String.join("、", ContractModels.EXPIRY_STATES) + " 之一。");
        }
        return value;
    }

    static long parseContractId(String raw) {
        return parsePositiveId(raw, "合同");
    }

    static long parsePositiveId(String raw, String label) {
        try {
            long id = Long.parseLong(raw == null ? "" : raw.trim());
            if (id <= 0) {
                throw new NumberFormatException("非正整数");
            }
            return id;
        } catch (NumberFormatException error) {
            throw BusinessException.invalid("id", label + " ID 必须是正整数字符串。");
        }
    }

    static int resolvePage(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw BusinessException.invalid("page", "页码必须从 1 开始。");
        }
        return page;
    }

    static int resolvePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.invalid("pageSize", "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间。");
        }
        return pageSize;
    }
}
