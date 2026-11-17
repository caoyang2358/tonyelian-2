import {useState} from 'react';
import {RecordsTab} from './RecordsTab';
import {TasksTab} from './TasksTab';

const tabs = [
  {id: 'tasks', label: '生产任务', hint: '今日两车间任务、详情登记与结束'},
  {id: 'records', label: '实际记录', hint: '历史时间线、计划外补录与当日录全'},
] as const;

type TabId = (typeof tabs)[number]['id'];

export function ProductionPage() {
  const [tab, setTab] = useState<TabId>('tasks');
  return <>
    <nav className="ops-tabs" style={{gridTemplateColumns: 'repeat(2, minmax(0,1fr))'}}
      aria-label="生产记录页签">
      {tabs.map(item =>
        <button key={item.id} type="button" className={`ops-tab ${tab === item.id ? 'active' : ''}`}
          aria-current={tab === item.id ? 'page' : undefined} onClick={() => setTab(item.id)}>
          <span>{item.label}</span>
          <small>{item.hint}</small>
        </button>)}
    </nav>
    {tab === 'tasks' && <TasksTab/>}
    {tab === 'records' && <RecordsTab/>}
  </>;
}
