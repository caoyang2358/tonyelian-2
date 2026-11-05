package cn.copper.phase2.sensor;

import cn.copper.phase2.sensor.SensorModels.DeletionBlocker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * S1-P05 的关联检查。
 *
 * <p>详情里的 {@code deletion} 与删除接口共用这一份实现，避免"页面说能删、接口说不能删"。
 * 每一类都直接读库当前事实，不接受前端传来的"无关联"声明。</p>
 *
 * <p>六类关联分两种处理，边界写在代码里而不是文档里：</p>
 * <ul>
 *   <li><b>阻塞</b>：原始采集记录、预警事件、监测统计、能耗统计、下级测点、改过规则的参数。
 *       这些要么是采集侧事实、要么是别的模块的统计产物，随测点删掉会让历史无法解释。</li>
 *   <li><b>随删</b>：组网布局节点、参数定义与初始规则版本、测点自有扩展档案。
 *       它们只属于这个测点，组网侧又没有单独删节点的入口，留着就是孤儿。</li>
 * </ul>
 *
 * <p>原始采集记录按 {@code sensor_code} 统计：原始表没有外键，这是两张表之间唯一的连接键，
 * 也是删除后唯一会留下孤儿数据的地方。</p>
 */
@Component
public class SensorReferenceChecker {

    private final SensorRepository repository;

    public SensorReferenceChecker(SensorRepository repository) {
        this.repository = repository;
    }

    /** 按测点身份检查；返回空列表表示可以删除。 */
    public List<DeletionBlocker> blockers(long sensorId, String sensorCode) {
        List<DeletionBlocker> blockers = new ArrayList<>();

        long collection = repository.countCollection(sensorCode);
        if (collection > 0) {
            blockers.add(new DeletionBlocker("COLLECTION_DATA", collection,
                    "该测点已有 " + collection + " 条原始采集记录。删掉会让这些数据失去归属，"
                            + "编码 " + sensorCode + " 因此不能再被复用；请改为停用。"));
        }

        long events = repository.countAlertEvents(sensorId);
        if (events > 0) {
            blockers.add(new DeletionBlocker("ALERT_EVENT", events,
                    "该测点或其参数已产生 " + events + " 条预警事件，事件需要指向它依据的测点；"
                            + "请改为停用。"));
        }

        long monitor = repository.countMonitor(sensorId);
        if (monitor > 0) {
            blockers.add(new DeletionBlocker("MONITOR", monitor,
                    "该测点已有 " + monitor + " 条监测统计记录，属于监测模块的产物，"
                            + "本模块不代为清理。"));
        }

        long energy = repository.countEnergy(sensorId);
        if (energy > 0) {
            blockers.add(new DeletionBlocker("ENERGY", energy,
                    "该测点已有 " + energy + " 条能耗统计记录，删除会改变历史能耗口径，"
                            + "需先在能耗模块处理。"));
        }

        long children = repository.countChildSensors(sensorId);
        if (children > 0) {
            blockers.add(new DeletionBlocker("CHILD_SENSOR", children,
                    "该测点是 " + children + " 个下级测点的父节点，删除会让能源树断链；"
                            + "请先调整下级测点的归属。"));
        }

        long ruleHistory = repository.countParametersWithRuleHistory(sensorId);
        if (ruleHistory > 0) {
            blockers.add(new DeletionBlocker("PARAMETER_HISTORY", ruleHistory,
                    "该测点有 " + ruleHistory + " 个参数的报警规则被修改过，历史版本是既有预警的判据，"
                            + "不能随测点一起删除。"));
        }

        return List.copyOf(blockers);
    }
}
