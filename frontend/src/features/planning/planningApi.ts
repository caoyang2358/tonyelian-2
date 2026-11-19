import {requestApi} from '../../lib/api';

export type PageData<T>={items:T[];page:number;pageSize:number;total:number};
export type Material={materialId:string;code:string;name:string;type:string;specification:string;unit:string;enabled:boolean;version:number};
export type Inventory={materialId:string;code:string;name:string;type:string;specification:string;physicalQuantityTon:string;reservedQuantityTon:string;availableQuantityTon:string;version:number};
export type Order={orderId:string;orderNo:string;customerName:string;wireMaterialId:string;wireSpecification:string;requiredQuantityTon:string;dueDate:string;manualPlannedRodTon:string|null;contractAmountYuan:string|null;amountConfirmed:boolean;plannedQuantityTon:string;manualPriority:boolean;productionResult:string;deliveryResult:string;accountingState:string;version:number};
export type Capacity={workshopId:string;workshopCode:string;workshopName:string;maxDailyOutputTon:string;version:number};
export type CapacityDay={date:string;workshopId:string;workshopName:string;normalCapacityTon:string;overrideCapacityTon:string|null;effectiveCapacityTon:string;plannedQuantityTon:string;remainingCapacityTon:string;overrideVersion:number|null};
export type PlanTask={taskId:string;planId:string;productionDate:string;sequenceNo:number;workshopId:string;workshopName:string;materialId:string;materialName:string;specification:string;orderNumbers:string|null;customerNames:string|null;plannedQualifiedTon:string;status:string;version:number};
export type PlanConfirmation={planId:string;version:number;taskIds:string[];affectedOrderIds:string[];netNewRodTon:string;plannedPlateTon:string;completionDate:string;warnings:string[]};
const pass=<T>(v:unknown)=>v as T;
const params=(values:Record<string,string|undefined>)=>{const p=new URLSearchParams();Object.entries(values).forEach(([k,v])=>{if(v)p.set(k,v)});const s=p.toString();return s?`?${s}`:''};
export const planningApi={
  orders:(q:Record<string,string|undefined>={},signal?:AbortSignal)=>requestApi(`/api/v1/orders${params(q)}`,pass<PageData<Order>>,{signal}),
  order:(id:string,signal?:AbortSignal)=>requestApi(`/api/v1/orders/${encodeURIComponent(id)}`,pass<Order>,{signal}),
  createOrder:(body:Record<string,unknown>)=>requestApi('/api/v1/orders',pass<{id:string;version:number}>,{method:'POST',body}),
  materials:(signal?:AbortSignal)=>requestApi('/api/v1/materials?pageSize=100',pass<PageData<Material>>,{signal}),
  createMaterial:(body:Record<string,unknown>)=>requestApi('/api/v1/materials',pass<{id:string;version:number}>,{method:'POST',body}),
  inventory:(signal?:AbortSignal)=>requestApi('/api/v1/inventory/balances?pageSize=100',pass<PageData<Inventory>>,{signal}),
  capacities:(signal?:AbortSignal)=>requestApi('/api/v1/planning/capacities',pass<Capacity[]>,{signal}),
  capacityDays:(from:string,to:string,signal?:AbortSignal)=>requestApi(`/api/v1/planning/capacity-calendar${params({from,to})}`,pass<{items:CapacityDay[]}>,{signal}),
  planTasks:(from:string,to:string,signal?:AbortSignal)=>requestApi(`/api/v1/planning/calendar${params({from,to,pageSize:'100'})}`,pass<PageData<PlanTask>>,{signal}),
  confirmPlan:(body:Record<string,unknown>)=>requestApi('/api/v1/planning/plan-confirmations',pass<PlanConfirmation>,{method:'POST',body}),
};
