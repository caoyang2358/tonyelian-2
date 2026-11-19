package cn.copper.phase2.planning;

import cn.copper.phase2.planning.PlanningModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PlanningRepository {
    private final JdbcTemplate jdbc;
    public PlanningRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<OrderItem> orders(String keyword, Long materialId, LocalDate dueFrom, LocalDate dueTo,
                                  int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = orderWhere(keyword, materialId, dueFrom, dueTo, args);
        args.add(size); args.add((long) (page - 1) * size);
        return jdbc.query("""
                SELECT o.*, m.specification,
                  COALESCE((SELECT SUM(tor.quantity_ton) FROM p2_task_order tor
                    JOIN p2_plan_task pt ON pt.task_id=tor.task_id JOIN p2_plan p ON p.plan_id=pt.plan_id
                    WHERE tor.order_id=o.order_id AND tor.quantity_kind='WIRE_OUTPUT'
                      AND p.status='ACTIVE' AND pt.superseded_by_plan_id IS NULL),0) active_planned_ton
                FROM p2_customer_order o
                JOIN p2_material m ON m.material_id=o.wire_material_id
                """ + where + " ORDER BY o.manual_priority DESC,o.due_date,o.created_at,o.order_id LIMIT ? OFFSET ?",
                (rs, n) -> new OrderItem(String.valueOf(rs.getLong("order_id")), rs.getString("order_no"),
                        rs.getString("customer_name"), String.valueOf(rs.getLong("wire_material_id")),
                        rs.getString("specification"), dec(rs.getBigDecimal("required_quantity_ton")),
                        rs.getObject("due_date", LocalDate.class), dec(rs.getBigDecimal("manual_planned_rod_ton")),
                        money(rs.getBigDecimal("contract_amount_yuan")), rs.getBoolean("amount_confirmed"), dec(rs.getBigDecimal("active_planned_ton")),
                        rs.getBoolean("manual_priority"), rs.getString("production_result"),
                        rs.getString("delivery_result"), rs.getString("accounting_state"), rs.getLong("version")), args.toArray());
    }

    public long orderCount(String keyword, Long materialId, LocalDate from, LocalDate to) {
        List<Object> args = new ArrayList<>();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM p2_customer_order o " + orderWhere(keyword, materialId, from, to, args), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private static String orderWhere(String keyword, Long materialId, LocalDate from, LocalDate to, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE o.cancelled_at IS NULL");
        if (keyword != null && !keyword.isBlank()) { sql.append(" AND (o.order_no LIKE ? OR o.customer_name LIKE ?)"); String p="%"+keyword.trim()+"%"; args.add(p); args.add(p); }
        if (materialId != null) { sql.append(" AND o.wire_material_id=?"); args.add(materialId); }
        if (from != null) { sql.append(" AND o.due_date>=?"); args.add(from); }
        if (to != null) { sql.append(" AND o.due_date<=?"); args.add(to); }
        return sql.toString();
    }

    public Optional<OrderItem> order(long id) {
        return ordersBySql(" WHERE o.order_id=?", id).stream().findFirst();
    }
    public Optional<OrderItem> lockOrder(long id) {
        return ordersBySql(" WHERE o.order_id=? FOR UPDATE", id).stream().findFirst();
    }

    public BigDecimal activePlannedWire(long orderId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(tor.quantity_ton),0)
                FROM p2_task_order tor
                JOIN p2_plan_task t ON t.task_id=tor.task_id
                JOIN p2_plan p ON p.plan_id=t.plan_id
                WHERE tor.order_id=? AND tor.quantity_kind='WIRE_OUTPUT'
                  AND p.status='ACTIVE' AND t.superseded_by_plan_id IS NULL
                """, BigDecimal.class, orderId);
        return value == null ? BigDecimal.ZERO : value;
    }

    public void updateOrderRodDemand(long orderId, long expectedVersion, BigDecimal rodTon) {
        int changed = jdbc.update("""
                UPDATE p2_customer_order
                SET manual_planned_rod_ton=?, production_scope_version=production_scope_version+1,
                    version=version+1, updated_at=NOW(3)
                WHERE order_id=? AND version=?
                """, rodTon, orderId, expectedVersion);
        if (changed != 1) throw cn.copper.phase2.common.BusinessException.versionConflict(
                expectedVersion, lockOrder(orderId).map(OrderItem::version).orElse(expectedVersion));
    }
    private List<OrderItem> ordersBySql(String where, Object... args) {
        return jdbc.query("""
                SELECT o.*,m.specification,
                  COALESCE((SELECT SUM(tor.quantity_ton) FROM p2_task_order tor
                    JOIN p2_plan_task pt ON pt.task_id=tor.task_id JOIN p2_plan p ON p.plan_id=pt.plan_id
                    WHERE tor.order_id=o.order_id AND tor.quantity_kind='WIRE_OUTPUT'
                      AND p.status='ACTIVE' AND pt.superseded_by_plan_id IS NULL),0) active_planned_ton
                FROM p2_customer_order o JOIN p2_material m ON m.material_id=o.wire_material_id
                """+where, (rs,n)->new OrderItem(String.valueOf(rs.getLong("order_id")),rs.getString("order_no"),rs.getString("customer_name"),String.valueOf(rs.getLong("wire_material_id")),rs.getString("specification"),dec(rs.getBigDecimal("required_quantity_ton")),rs.getObject("due_date",LocalDate.class),dec(rs.getBigDecimal("manual_planned_rod_ton")),money(rs.getBigDecimal("contract_amount_yuan")),rs.getBoolean("amount_confirmed"),dec(rs.getBigDecimal("active_planned_ton")),rs.getBoolean("manual_priority"),rs.getString("production_result"),rs.getString("delivery_result"),rs.getString("accounting_state"),rs.getLong("version")),args);
    }

    public long insertOrder(OrderWrite w, long materialId, BigDecimal qty, LocalDate due, BigDecimal rod, BigDecimal amount) {
        return insert("INSERT INTO p2_customer_order(order_no,customer_name,wire_material_id,required_quantity_ton,due_date,manual_planned_rod_ton,contract_amount_yuan,amount_confirmed,operator_name,version) VALUES(?,?,?,?,?,?,?,?,?,1)",
                w.orderNo().trim(),w.customerName().trim(),materialId,qty,due,rod,amount,Boolean.TRUE.equals(w.amountConfirmed()),w.operatorName().trim());
    }

    public List<MaterialItem> materials(String type, boolean includeDisabled, int page, int size) {
        List<Object> args=new ArrayList<>(); StringBuilder where=new StringBuilder(" WHERE 1=1");
        if(type!=null&&!type.isBlank()){where.append(" AND material_type=?");args.add(type);} if(!includeDisabled)where.append(" AND enabled=TRUE");
        args.add(size);args.add((long)(page-1)*size);
        return jdbc.query("SELECT * FROM p2_material"+where+" ORDER BY FIELD(material_type,'COPPER_PLATE','COPPER_ROD','FINISHED_WIRE'),specification,material_id LIMIT ? OFFSET ?",(rs,n)->new MaterialItem(String.valueOf(rs.getLong("material_id")),rs.getString("material_code"),rs.getString("material_name"),rs.getString("material_type"),rs.getString("specification"),"t",rs.getBoolean("enabled"),rs.getLong("version")),args.toArray());
    }
    public long materialCount(String type,boolean includeDisabled){List<Object>a=new ArrayList<>();String w=" WHERE 1=1";if(type!=null&&!type.isBlank()){w+=" AND material_type=?";a.add(type);}if(!includeDisabled)w+=" AND enabled=TRUE";Long c=jdbc.queryForObject("SELECT COUNT(*) FROM p2_material"+w,Long.class,a.toArray());return c==null?0:c;}
    public Optional<MaterialItem> material(long id){return jdbc.query("SELECT * FROM p2_material WHERE material_id=?",(rs,n)->new MaterialItem(String.valueOf(rs.getLong("material_id")),rs.getString("material_code"),rs.getString("material_name"),rs.getString("material_type"),rs.getString("specification"),"t",rs.getBoolean("enabled"),rs.getLong("version")),id).stream().findFirst();}
    public long insertMaterial(MaterialWrite w){long id=insert("INSERT INTO p2_material(material_code,material_name,material_type,specification,version) VALUES(?,?,'FINISHED_WIRE',?,1)",w.code().trim(),w.name().trim(),w.specification().trim());jdbc.update("INSERT INTO p2_stock_balance(material_id,physical_quantity_ton,version) VALUES(?,0,0)",id);return id;}

    public List<InventoryItem> inventory(){return jdbc.query("""
            SELECT m.*,COALESCE(b.physical_quantity_ton,0) physical,
              COALESCE((SELECT SUM(r.reserved_quantity_ton-r.consumed_quantity_ton-r.released_quantity_ton) FROM p2_stock_reservation r WHERE r.material_id=m.material_id),0) reserved,
              COALESCE(b.version,0) balance_version FROM p2_material m LEFT JOIN p2_stock_balance b ON b.material_id=m.material_id
            WHERE m.enabled=TRUE ORDER BY FIELD(m.material_type,'COPPER_PLATE','COPPER_ROD','FINISHED_WIRE'),m.specification
            """,(rs,n)->{BigDecimal p=rs.getBigDecimal("physical"),r=rs.getBigDecimal("reserved");return new InventoryItem(String.valueOf(rs.getLong("material_id")),rs.getString("material_code"),rs.getString("material_name"),rs.getString("material_type"),rs.getString("specification"),dec(p),dec(r),dec(p.subtract(r)),rs.getLong("balance_version"));});}

    public List<CapacityItem> capacities(){return jdbc.query("""
            SELECT c.*,w.workshop_code,w.workshop_name FROM p2_workshop_capacity c JOIN dim_workshop w ON w.workshop_id=c.workshop_id ORDER BY w.workshop_code
            """,(rs,n)->new CapacityItem(String.valueOf(rs.getLong("workshop_id")),rs.getString("workshop_code"),rs.getString("workshop_name"),dec(rs.getBigDecimal("max_daily_output_ton")),rs.getLong("version")));}
    public long workshopId(String code){Long id=jdbc.queryForObject("SELECT workshop_id FROM dim_workshop WHERE workshop_code=? AND enabled=TRUE",Long.class,code);if(id==null)throw new IllegalStateException("缺少车间："+code);return id;}
    public long materialId(String code){Long id=jdbc.queryForObject("SELECT material_id FROM p2_material WHERE material_code=? AND enabled=TRUE",Long.class,code);if(id==null)throw new IllegalStateException("缺少物料："+code);return id;}
    public BigDecimal availableCapacity(long workshopId,LocalDate date){BigDecimal cap=jdbc.queryForObject("""
            SELECT COALESCE((SELECT max_daily_output_ton FROM p2_capacity_override WHERE workshop_id=? AND capacity_date=? AND active=TRUE),
                            (SELECT max_daily_output_ton FROM p2_workshop_capacity WHERE workshop_id=?))
            """,BigDecimal.class,workshopId,date,workshopId);BigDecimal used=jdbc.queryForObject("""
            SELECT COALESCE(SUM(t.planned_qualified_ton),0) FROM p2_plan_task t JOIN p2_plan p ON p.plan_id=t.plan_id
            WHERE t.workshop_id=? AND t.production_date=? AND p.status='ACTIVE' AND t.superseded_by_plan_id IS NULL
            """,BigDecimal.class,workshopId,date);return (cap==null?BigDecimal.ZERO:cap).subtract(used==null?BigDecimal.ZERO:used).max(BigDecimal.ZERO);}
    public long insertPlan(String requestId,String clientDraftId,String operatorName,String reason,String snapshotJson){return insert("INSERT INTO p2_plan(previous_plan_id,status,request_id,client_draft_id,operator_name,reason,demand_snapshot,version) VALUES(NULL,'ACTIVE',?,?,?,?,CAST(? AS JSON),0)",requestId,clientDraftId,operatorName,reason,snapshotJson);}
    public long insertTask(long planId,long workshopId,long materialId,LocalDate date,int sequence,BigDecimal qualified,BigDecimal rodUse,BigDecimal plate){return insert("INSERT INTO p2_plan_task(plan_id,workshop_id,material_id,production_date,sequence_no,planned_qualified_ton,planned_rod_use_ton,planned_plate_ton,status,version) VALUES(?,?,?,?,?,?,?,?, 'PLANNED',0)",planId,workshopId,materialId,date,sequence,qualified,rodUse,plate);}
    public void insertTaskOrder(long taskId,long orderId,String demandKey,String kind,BigDecimal quantity){jdbc.update("INSERT INTO p2_task_order(task_id,order_id,demand_key,quantity_kind,quantity_ton) VALUES(?,?,?,?,?)",taskId,orderId,demandKey,kind,quantity);}
    public void insertCommitment(long planId,long sourceTaskId,long targetTaskId,long rodMaterialId,String demandKey,BigDecimal quantity){jdbc.update("INSERT INTO p2_supply_commitment(plan_id,source_task_id,target_task_id,material_id,demand_key,committed_quantity_ton,source_before_target,version) VALUES(?,?,?,?,?,?,TRUE,0)",planId,sourceTaskId,targetTaskId,rodMaterialId,demandKey,quantity);}
    public Map<String,BigDecimal> overrides(LocalDate from,LocalDate to){return jdbc.query("SELECT workshop_id,capacity_date,max_daily_output_ton FROM p2_capacity_override WHERE active=TRUE AND capacity_date BETWEEN ? AND ?",rs->{Map<String,BigDecimal>m=new java.util.HashMap<>();while(rs.next())m.put(rs.getLong(1)+"@"+rs.getObject(2,LocalDate.class),rs.getBigDecimal(3));return m;},from,to);}
    public Map<String,Long> overrideVersions(LocalDate from,LocalDate to){return jdbc.query("SELECT workshop_id,capacity_date,version FROM p2_capacity_override WHERE active=TRUE AND capacity_date BETWEEN ? AND ?",rs->{Map<String,Long>m=new java.util.HashMap<>();while(rs.next())m.put(rs.getLong(1)+"@"+rs.getObject(2,LocalDate.class),rs.getLong(3));return m;},from,to);}
    public Map<String,BigDecimal> planned(LocalDate from,LocalDate to){return jdbc.query("SELECT workshop_id,production_date,SUM(planned_qualified_ton) qty FROM p2_plan_task WHERE superseded_by_plan_id IS NULL AND production_date BETWEEN ? AND ? GROUP BY workshop_id,production_date",rs->{Map<String,BigDecimal>m=new java.util.HashMap<>();while(rs.next())m.put(rs.getLong(1)+"@"+rs.getObject(2,LocalDate.class),rs.getBigDecimal(3));return m;},from,to);}
    public List<PlanTaskItem> planTasks(LocalDate from,LocalDate to){return jdbc.query("""
            SELECT t.*,w.workshop_name,m.material_name,m.specification,
              (SELECT GROUP_CONCAT(DISTINCT o.order_no ORDER BY o.order_no SEPARATOR '、')
                 FROM p2_task_order tor JOIN p2_customer_order o ON o.order_id=tor.order_id
                WHERE tor.task_id=t.task_id) order_numbers,
              (SELECT GROUP_CONCAT(DISTINCT o.customer_name ORDER BY o.customer_name SEPARATOR '、')
                 FROM p2_task_order tor JOIN p2_customer_order o ON o.order_id=tor.order_id
                WHERE tor.task_id=t.task_id) customer_names
            FROM p2_plan_task t
            JOIN dim_workshop w ON w.workshop_id=t.workshop_id
            JOIN p2_material m ON m.material_id=t.material_id
            JOIN p2_plan p ON p.plan_id=t.plan_id
            WHERE p.status='ACTIVE' AND t.superseded_by_plan_id IS NULL
              AND t.production_date BETWEEN ? AND ?
            ORDER BY t.production_date,w.workshop_code,t.sequence_no
            """,(rs,n)->new PlanTaskItem(String.valueOf(rs.getLong("task_id")),String.valueOf(rs.getLong("plan_id")),rs.getObject("production_date",LocalDate.class),rs.getInt("sequence_no"),String.valueOf(rs.getLong("workshop_id")),rs.getString("workshop_name"),String.valueOf(rs.getLong("material_id")),rs.getString("material_name"),rs.getString("specification"),rs.getString("order_numbers"),rs.getString("customer_names"),dec(rs.getBigDecimal("planned_qualified_ton")),rs.getString("status"),rs.getLong("version")),from,to);}

    private long insert(String sql,Object...args){KeyHolder k=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},k);if(k.getKey()==null)throw new IllegalStateException("新增记录未返回主键。");return k.getKey().longValue();}
    static String dec(BigDecimal v){return v==null?null:v.setScale(3).toPlainString();}
    static String money(BigDecimal v){return v==null?null:v.setScale(2).toPlainString();}
}
