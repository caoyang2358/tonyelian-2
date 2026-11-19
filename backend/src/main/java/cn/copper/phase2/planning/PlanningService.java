package cn.copper.phase2.planning;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.planning.PlanningModels.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import cn.copper.phase2.common.Json;

@Service
public class PlanningService {
    private final PlanningRepository repository;
    private final IdempotencyService idempotency;
    public PlanningService(PlanningRepository repository, IdempotencyService idempotency){this.repository=repository;this.idempotency=idempotency;}

    public Page<OrderItem> orders(String keyword,String wireMaterialId,String dueFrom,String dueTo,Integer page,Integer pageSize){int p=page(page),s=size(pageSize);Long m=idOrNull(wireMaterialId,"wireMaterialId");LocalDate from=dateOrNull(dueFrom,"dueFrom"),to=dateOrNull(dueTo,"dueTo");if(from!=null&&to!=null&&from.isAfter(to))throw BusinessException.invalid("dueTo","截止日期不得早于开始日期。");return new Page<>(repository.orders(keyword,m,from,to,p,s),p,s,repository.orderCount(keyword,m,from,to));}
    public OrderItem order(String raw){return repository.order(id(raw,"orderId")).orElseThrow(()->BusinessException.notFound("订单不存在。"));}

    public CreateResult createOrder(OrderWrite w){require(w.requestId(),"requestId","请求编号");require(w.orderNo(),"orderNo","订单编号");require(w.customerName(),"customerName","客户名称");require(w.operatorName(),"operatorName","经办人");long material=id(w.wireMaterialId(),"wireMaterialId");MaterialItem item=repository.material(material).orElseThrow(()->BusinessException.invalid("wireMaterialId","铜丝规格不存在。"));if(!item.enabled()||!"FINISHED_WIRE".equals(item.type()))throw BusinessException.invalid("wireMaterialId","请选择已启用的成品铜丝规格。");BigDecimal qty=decimal(w.requiredQuantityTon(),"requiredQuantityTon",true,3);LocalDate due=date(w.dueDate(),"dueDate");BigDecimal rod=decimalNullable(w.manualPlannedRodTon(),"manualPlannedRodTon",3);BigDecimal amount=decimalNullable(w.contractAmountYuan(),"contractAmountYuan",2);if(Boolean.TRUE.equals(w.amountConfirmed())&&amount==null)throw BusinessException.invalid("contractAmountYuan","确认金额时必须填写合同金额。");try{return idempotency.execute(w.requestId(),"ORDER_CREATE",w,CreateResult.class,()->{long newId=repository.insertOrder(w,material,qty,due,rod,amount);return new CreateResult(String.valueOf(newId),1);});}catch(DuplicateKeyException e){throw BusinessException.invalid("orderNo","订单编号已存在。");}}

    public Page<MaterialItem> materials(String type,Boolean includeDisabled,Integer page,Integer pageSize){if(type!=null&&!List.of("COPPER_PLATE","COPPER_ROD","FINISHED_WIRE").contains(type))throw BusinessException.invalid("type","物料类型不在约定范围内。");int p=page(page),s=size(pageSize);boolean all=Boolean.TRUE.equals(includeDisabled);return new Page<>(repository.materials(type,all,p,s),p,s,repository.materialCount(type,all));}
    public CreateResult createMaterial(MaterialWrite w){require(w.requestId(),"requestId","请求编号");require(w.code(),"code","物料编码");require(w.name(),"name","物料名称");require(w.specification(),"specification","铜丝规格");require(w.operatorName(),"operatorName","经办人");try{return idempotency.execute(w.requestId(),"WIRE_MATERIAL_CREATE",w,CreateResult.class,()->new CreateResult(String.valueOf(repository.insertMaterial(w)),1));}catch(DuplicateKeyException e){throw BusinessException.invalid("specification","物料编码或铜丝规格已存在。");}}
    public Page<InventoryItem> inventory(Integer page,Integer pageSize){int p=page(page),s=size(pageSize);List<InventoryItem> all=repository.inventory();int from=Math.min((p-1)*s,all.size()),to=Math.min(from+s,all.size());return new Page<>(all.subList(from,to),p,s,all.size());}
    public List<CapacityItem> capacities(){return repository.capacities();}
    public CapacityCalendar capacityCalendar(String fromRaw,String toRaw){LocalDate from=date(fromRaw,"from"),to=date(toRaw,"to");if(from.isAfter(to))throw BusinessException.invalid("to","结束日期不得早于开始日期。");if(from.plusDays(92).isBefore(to))throw BusinessException.invalid("to","单次最多查询 93 天。");List<CapacityItem> capacities=repository.capacities();Map<String,BigDecimal> overrides=repository.overrides(from,to),planned=repository.planned(from,to);Map<String,Long> versions=repository.overrideVersions(from,to);List<CapacityDay> result=new ArrayList<>();for(LocalDate d=from;!d.isAfter(to);d=d.plusDays(1)){for(CapacityItem c:capacities){String key=c.workshopId()+"@"+d;BigDecimal normal=new BigDecimal(c.maxDailyOutputTon()),special=overrides.get(key),effective=special==null?normal:special,used=planned.getOrDefault(key,BigDecimal.ZERO);result.add(new CapacityDay(d,c.workshopId(),c.workshopName(),fmt(normal),special==null?null:fmt(special),fmt(effective),fmt(used),fmt(effective.subtract(used)),versions.get(key)));}}return new CapacityCalendar(result,from,to,true);}
    public Page<PlanTaskItem> planTasks(String fromRaw,String toRaw,Integer page,Integer pageSize){LocalDate from=date(fromRaw,"from"),to=date(toRaw,"to");if(from.isAfter(to))throw BusinessException.invalid("to","结束日期不得早于开始日期。");int p=page(page),s=size(pageSize);List<PlanTaskItem> all=repository.planTasks(from,to);int a=Math.min((p-1)*s,all.size()),b=Math.min(a+s,all.size());return new Page<>(all.subList(a,b),p,s,all.size());}

    public PlanConfirmationResult confirmPlan(PlanConfirmationWrite w){
        require(w.requestId(),"requestId","请求编号");require(w.orderId(),"orderId","订单");
        require(w.reason(),"reason","排产原因");require(w.operatorName(),"operatorName","经办人");
        long orderId=id(w.orderId(),"orderId");BigDecimal rodTotal=decimal(w.manualPlannedRodTon(),"manualPlannedRodTon",true,3);
        LocalDate rodStart=date(w.rodStartDate(),"rodStartDate"),requestedWireStart=date(w.wireStartDate(),"wireStartDate");
        if(rodStart.isBefore(LocalDate.now()))throw BusinessException.invalid("rodStartDate","正式计划不能安排在过去日期。");
        return idempotency.execute(w.requestId(),"PLAN_CONFIRM",w,PlanConfirmationResult.class,()->{
            OrderItem order=repository.lockOrder(orderId).orElseThrow(()->BusinessException.notFound("订单不存在。"));
            if(order.version()!=w.expectedOrderVersion())throw BusinessException.versionConflict(w.expectedOrderVersion(),order.version());
            if(repository.activePlannedWire(orderId).signum()>0)throw new BusinessException(cn.copper.phase2.common.ErrorCode.RELATED_DATA_EXISTS,"该订单已有生效计划，请先通过计划重排流程调整。",Map.of());
            long rodWorkshop=repository.workshopId("WS-ROD"),wireWorkshop=repository.workshopId("WS-WIRE"),rodMaterial=repository.materialId("COPPER-ROD-PHI8");
            BigDecimal wireTotal=new BigDecimal(order.requiredQuantityTon());
            List<Scheduled> rodTasks=schedule(rodWorkshop,rodMaterial,rodStart,rodTotal,null);
            LocalDate earliestWire=rodTasks.get(rodTasks.size()-1).date().plusDays(1);List<String>warnings=new ArrayList<>();
            LocalDate resolvedWireStart=requestedWireStart;
            if(resolvedWireStart.isBefore(earliestWire)){resolvedWireStart=earliestWire;warnings.add("为保证车间一先供杆，车间二开始日已顺延至 "+resolvedWireStart+"。");}
            List<Scheduled> wireTasks=schedule(wireWorkshop,Long.parseLong(order.wireMaterialId()),resolvedWireStart,wireTotal,rodTotal);
            BigDecimal plateTotal=rodTotal.multiply(new BigDecimal("1.05")).movePointRight(3).setScale(0,RoundingMode.CEILING).movePointLeft(3);
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("orderId",order.orderId());snapshot.put("orderVersion",order.version());snapshot.put("wireMaterialId",order.wireMaterialId());snapshot.put("requiredWireTon",order.requiredQuantityTon());snapshot.put("manualPlannedRodTon",fmt(rodTotal));snapshot.put("plannedPlateTon",fmt(plateTotal));
            String json;try{json=Json.MAPPER.writeValueAsString(snapshot);}catch(Exception e){throw new IllegalStateException("计划快照无法序列化。",e);}
            long planId=repository.insertPlan(w.requestId(),"web-"+w.requestId(),w.operatorName().trim(),w.reason().trim(),json);
            repository.updateOrderRodDemand(orderId,order.version(),rodTotal);
            String demandKey="order-"+orderId+"-plan-"+planId;List<String> taskIds=new ArrayList<>();List<TaskAmount> sources=new ArrayList<>(),targets=new ArrayList<>();
            BigDecimal plateLeft=plateTotal;
            for(int i=0;i<rodTasks.size();i++){Scheduled s=rodTasks.get(i);BigDecimal plate=i==rodTasks.size()-1?plateLeft:plateTotal.multiply(s.quantity()).divide(rodTotal,3,RoundingMode.DOWN);plateLeft=plateLeft.subtract(plate);long task=repository.insertTask(planId,rodWorkshop,rodMaterial,s.date(),s.sequence(),s.quantity(),null,plate);repository.insertTaskOrder(task,orderId,demandKey,"ROD_DEMAND",s.quantity());taskIds.add(String.valueOf(task));sources.add(new TaskAmount(task,s.quantity()));}
            BigDecimal rodLeft=rodTotal;
            for(int i=0;i<wireTasks.size();i++){Scheduled s=wireTasks.get(i);BigDecimal use=i==wireTasks.size()-1?rodLeft:rodTotal.multiply(s.quantity()).divide(wireTotal,3,RoundingMode.DOWN);rodLeft=rodLeft.subtract(use);long task=repository.insertTask(planId,wireWorkshop,Long.parseLong(order.wireMaterialId()),s.date(),s.sequence(),s.quantity(),use,null);repository.insertTaskOrder(task,orderId,demandKey,"WIRE_OUTPUT",s.quantity());repository.insertTaskOrder(task,orderId,demandKey,"ROD_DEMAND",use);taskIds.add(String.valueOf(task));targets.add(new TaskAmount(task,use));}
            int si=0,ti=0;BigDecimal sr=sources.get(0).quantity(),tr=targets.get(0).quantity();while(si<sources.size()&&ti<targets.size()){BigDecimal q=sr.min(tr);repository.insertCommitment(planId,sources.get(si).taskId(),targets.get(ti).taskId(),rodMaterial,demandKey,q);sr=sr.subtract(q);tr=tr.subtract(q);if(sr.signum()==0&&++si<sources.size())sr=sources.get(si).quantity();if(tr.signum()==0&&++ti<targets.size())tr=targets.get(ti).quantity();}
            LocalDate completion=wireTasks.get(wireTasks.size()-1).date();if(completion.isAfter(order.dueDate()))warnings.add("预计完成日 "+completion+" 晚于订单交期 "+order.dueDate()+"，已保留原交期并记录提醒。");
            return new PlanConfirmationResult(String.valueOf(planId),0,taskIds,List.of(order.orderId()),fmt(rodTotal),fmt(plateTotal),completion,warnings);
        });
    }

    private List<Scheduled> schedule(long workshop,long material,LocalDate start,BigDecimal total,BigDecimal ignored){List<Scheduled> result=new ArrayList<>();BigDecimal left=total;LocalDate day=start;int guard=0;while(left.signum()>0){if(++guard>366)throw BusinessException.invalid("rodStartDate","未来一年内没有足够产能完成该计划。");BigDecimal available=repository.availableCapacity(workshop,day);if(available.signum()>0){BigDecimal q=left.min(available).setScale(3,RoundingMode.UNNECESSARY);result.add(new Scheduled(material,day,1,q));left=left.subtract(q);}day=day.plusDays(1);}return result;}
    private record Scheduled(long materialId,LocalDate date,int sequence,BigDecimal quantity){}
    private record TaskAmount(long taskId,BigDecimal quantity){}

    private static int page(Integer v){if(v==null)return 1;if(v<1)throw BusinessException.invalid("page","页码必须从 1 开始。");return v;}
    private static int size(Integer v){if(v==null)return 20;if(v<1||v>100)throw BusinessException.invalid("pageSize","每页条数必须在 1 到 100 之间。");return v;}
    private static long id(String v,String field){try{long id=Long.parseLong(v==null?"":v.trim());if(id<=0)throw new NumberFormatException();return id;}catch(NumberFormatException e){throw BusinessException.invalid(field,"必须是正整数字符串。");}}
    private static Long idOrNull(String v,String f){return v==null||v.isBlank()?null:id(v,f);}
    private static LocalDate date(String v,String f){try{return LocalDate.parse(v==null?"":v.trim());}catch(DateTimeParseException e){throw BusinessException.invalid(f,"请使用 YYYY-MM-DD 日期格式。");}}
    private static LocalDate dateOrNull(String v,String f){return v==null||v.isBlank()?null:date(v,f);}
    private static BigDecimal decimal(String v,String f,boolean positive,int scale){BigDecimal d=decimalNullable(v,f,scale);if(d==null||(positive&&d.signum()<=0))throw BusinessException.invalid(f,"必须是大于 0 的数值。");return d;}
    private static BigDecimal decimalNullable(String v,String f,int scale){if(v==null||v.isBlank())return null;try{BigDecimal d=new BigDecimal(v.trim());if(d.signum()<0||d.scale()>scale)throw new NumberFormatException();return d.setScale(scale, RoundingMode.UNNECESSARY);}catch(ArithmeticException|NumberFormatException e){throw BusinessException.invalid(f,"数值格式有误，最多 "+scale+" 位小数。");}}
    private static String fmt(BigDecimal v){return v.setScale(3,RoundingMode.HALF_UP).toPlainString();}
    private static void require(String v,String f,String label){if(v==null||v.isBlank())throw BusinessException.invalid(f,label+"为必填项。");}
}
