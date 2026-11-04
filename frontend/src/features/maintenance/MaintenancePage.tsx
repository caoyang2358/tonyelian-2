import { useState } from 'react';
import { AlertsTab } from './AlertsTab';
import { InspectionsTab } from './InspectionsTab';
import { OrdersTab } from './OrdersTab';
import { OverviewTab } from './OverviewTab';

const tabs = [
  {id: 'alerts', label: '异常事件提醒', hint: '事件查询、详情证据与人工处理'},
  {id: 'inspections', label: '巡检记录', hint: '巡检登记、更正与报修入口'},
  {id: 'orders', label: '维修工单', hint: '建单、审核、维修与验收全流程'},
  {id: 'overview', label: '运维概览', hint: '范围统计与口径说明'},
] as const;

type TabId = (typeof tabs)[number]['id'];

export function MaintenancePage() {
  const [tab, setTab] = useState<TabId>('alerts');
  return <>
    <nav className="ops-tabs" aria-label="运维管理页签">
      {tabs.map(item =>
        <button key={item.id} type="button" className={`ops-tab ${tab === item.id ? 'active' : ''}`}
          aria-current={tab === item.id ? 'page' : undefined} onClick={() => setTab(item.id)}>
          <span>{item.label}</span>
          <small>{item.hint}</small>
        </button>)}
    </nav>
    {tab === 'alerts' && <AlertsTab />}
    {tab === 'inspections' && <InspectionsTab />}
    {tab === 'orders' && <OrdersTab />}
    {tab === 'overview' && <OverviewTab />}
  </>;
}
