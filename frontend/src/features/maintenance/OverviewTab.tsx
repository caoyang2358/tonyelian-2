import { useCallback, useState } from 'react';
import { Icon } from '../../components/Icon';
import { MaintenanceApiError, overviewApi } from '../../lib/maintenanceApi';
import type { MaintenanceOverview } from '../../lib/maintenanceApi';
import { label, localInputValue } from './labels';
import { EquipmentSelect, useWorkshopOptions } from './shared';

const caliberLabels: Record<string, string> = {
  inspectionCount: '巡检数', alertEventCount: '异常事件数', pendingRepairCount: '待办工单',
  completedRepairCount: '完成工单', averageHandlingSeconds: '平均处理时长', handlingSampleCount: '均值样本量',
  equipmentReportCounts: '设备报修统计', deadlineReminderCount: '期限提醒',
};

export function OverviewTab() {
  const [from, setFrom] = useState(() => localInputValue(-24 * 60));
  const [to, setTo] = useState(() => localInputValue());
  const [workshopId, setWorkshopId] = useState('');
  const [equipmentId, setEquipmentId] = useState('');
  const workshops = useWorkshopOptions();
  const [data, setData] = useState<MaintenanceOverview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await overviewApi.maintenanceOverview({
        from: new Date(from).toISOString(),
        to: new Date(to).toISOString(),
        workshopId: workshopId.trim() || undefined,
        equipmentId: equipmentId.trim() || undefined,
      });
      setData(result);
      setError(null);
    } catch (err) {
      setError(err instanceof MaintenanceApiError ? err.message : '统计失败，请重试。');
    } finally {
      setLoading(false);
    }
  }, [from, to, workshopId, equipmentId]);

  const metric = (text: string, caption: string) => ({text, caption});

  return <>
    <section className="panel" aria-label="运维概览查询">
      <div className="panel-heading"><div><h2>运维概览<span className="panel-count">S1-M01</span></h2><p>每个统计数字都附带口径说明，不把缺项假装成零</p></div></div>
      <div className="filters">
        <label className="select-field"><span>开始时间</span>
          <input type="datetime-local" value={from} onChange={event => setFrom(event.target.value)} /></label>
        <label className="select-field"><span>结束时间</span>
          <input type="datetime-local" value={to} onChange={event => setTo(event.target.value)} /></label>
        <label className="select-field"><span>车间</span>
          <select value={workshopId} onChange={event => setWorkshopId(event.target.value)}>
            <option value="">全部车间</option>
            {workshops.map(item => <option value={item.id} key={item.id}>{item.name}</option>)}
          </select></label>
        <EquipmentSelect label="设备" value={equipmentId} onChange={setEquipmentId} allowEmpty emptyLabel="全部设备" />
        <button className="button button-primary" type="button" onClick={() => void load()} disabled={loading}>{loading ? '统计中…' : '统计'}</button>
        <button className="reset-filter" type="button" onClick={() => {setFrom(localInputValue(-24 * 60)); setTo(localInputValue());}}>最近 24 小时</button>
        <button className="reset-filter" type="button" onClick={() => {setFrom(localInputValue(-24 * 60 * 7)); setTo(localInputValue());}}>最近 7 天</button>
      </div>
      {error && <div className="state-error" role="alert"><div className="state-icon"><Icon name="alert" size={24} /></div><div><h3>无法完成统计</h3><p>{error}</p></div></div>}
      {data && <>
        <div className="ops-metrics">
          {[metric(String(data.inspectionCount), '巡检次数'), metric(String(data.alertEventCount), '异常事件'),
            metric(String(data.pendingRepairCount), '维修待办'), metric(String(data.completedRepairCount), '完成工单'),
            metric(data.averageHandlingSeconds ? `${data.averageHandlingSeconds} 秒` : '—', `平均处理时长（样本 ${data.handlingSampleCount}）`)].map(item =>
            <div className="ops-metric" key={item.caption}><strong>{item.text}</strong><span>{item.caption}</span></div>)}
        </div>
        <div className="ops-detail ops-detail-padded">
          <h3 className="ops-section-title">按设备报修统计</h3>
          {data.equipmentReportCounts.length === 0 ? <p className="missing-value">统计范围内没有正式提交的报修工单。</p> :
            <div className="table-region"><table>
              <thead><tr><th className="number-column">设备编号</th><th>设备</th><th>报修次数</th><th>完成次数</th></tr></thead>
              <tbody>{data.equipmentReportCounts.map(row => <tr key={row.equipmentId}>
                <td className="row-number">{row.equipmentId}</td><td>{row.equipmentName}</td><td>{row.reportCount}</td><td>{row.completedCount}</td>
              </tr>)}</tbody>
            </table></div>}
          <h3 className="ops-section-title">期限提醒</h3>
          {data.deadlineReminderCount == null
            ? <p className="ops-note-block">{data.deadlineReminderNote ?? '期限提醒口径未就绪，返回 null 表示缺项而不是没有提醒。'}</p>
            : <p className="ops-note-block">当前 {data.deadlineReminderCount} 条期限提醒。</p>}
          <h3 className="ops-section-title">统计口径说明</h3>
          <dl className="ops-caliber">
            {Object.entries(data.caliber).map(([key, text]) => <div key={key}><dt>{label(caliberLabels, key, key)}</dt><dd>{text}</dd></div>)}
          </dl>
          <p className="missing-value">统计生成时间：{formatGeneratedAt(data.generatedAt)}</p>
        </div>
      </>}
      {!data && !error && <div className="empty-state"><div className="empty-icon"><Icon name="statistics" size={30} /></div><h3>选择时间范围后点击统计</h3><p>默认统计最近 24 小时；时间按实际业务时刻计数。</p></div>}
    </section>
  </>;
}

function formatGeneratedAt(value: string): string {
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN', {hour12: false}) : value;
}
