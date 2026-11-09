import { useCallback, useState } from 'react';
import { Icon } from '../../components/Icon';
import { MaintenanceApiError, overviewApi } from '../../lib/maintenanceApi';
import type { MaintenanceOverview } from '../../lib/maintenanceApi';
import { localInputValue } from './labels';
import { EquipmentSelect, useWorkshopOptions } from './shared';

/** 口径说明只在悬停/聚焦时出现：列表里铺十行小字，反而没人看。 */
function Tip({text}: {text: string | undefined}) {
  if (!text) return null;
  return <span className="ops-tip" tabIndex={0} role="note" aria-label={text} data-tip={text}>?</span>;
}

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

  const caliber = data?.caliber ?? {};
  const maxReport = Math.max(1, ...(data?.equipmentReportCounts.map(row => row.reportCount) ?? [1]));

  return <>
    <section className="panel" aria-label="运维概览查询">
      <div className="panel-heading"><div><h2>运维概览</h2><p>数字旁的小问号悬停即看统计口径；未正式提交的工单不计入</p></div></div>
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
          <div className="ops-metric">
            <strong>{data.inspectionCount}</strong>
            <span>巡检次数 <Tip text={caliber.inspectionCount} /></span>
          </div>
          <div className="ops-metric">
            <strong>{data.alertEventCount}</strong>
            <span>异常事件 <Tip text={caliber.alertEventCount} /></span>
          </div>
          <div className="ops-metric">
            <strong>{data.pendingRepairCount}</strong>
            <span>维修待办 <Tip text={caliber.pendingRepairCount} /></span>
          </div>
          <div className="ops-metric">
            <strong>{data.completedRepairCount}</strong>
            <span>完成工单 <Tip text={caliber.completedRepairCount} /></span>
          </div>
          <div className="ops-metric">
            <strong>{data.averageHandlingSeconds ? `${data.averageHandlingSeconds} 秒` : '—'}</strong>
            <span>平均处理时长（样本 {data.handlingSampleCount}）<Tip text={caliber.averageHandlingSeconds} /></span>
          </div>
        </div>

        <div className="ops-detail ops-detail-padded">
          <div className="ops-chart-head">
            <h3 className="ops-section-title">按设备报修统计</h3>
            <div className="ops-chart-legend">
              <span><i className="ops-legend-report" />报修次数</span>
              <span><i className="ops-legend-done" />完成次数</span>
              <Tip text={caliber.equipmentReportCounts} />
            </div>
          </div>
          {data.equipmentReportCounts.length === 0
            ? <p className="missing-value">统计范围内没有正式提交的报修工单。</p>
            : <div className="ops-bars" role="img" aria-label="按设备的报修与完成次数柱状图">
              {data.equipmentReportCounts.map(row => <div className="ops-bar-row" key={row.equipmentId}>
                <span className="ops-bar-name" title={row.equipmentName}>{row.equipmentName}</span>
                <div className="ops-bar-group">
                  <div className="ops-bar-line" title={`${row.equipmentName} 报修 ${row.reportCount} 次`}>
                    <span className="ops-bar ops-bar-report" style={{width: `${Math.max(4, row.reportCount / maxReport * 100)}%`}} />
                    <b>{row.reportCount}</b>
                  </div>
                  <div className="ops-bar-line" title={`${row.equipmentName} 完成 ${row.completedCount} 次`}>
                    <span className="ops-bar ops-bar-done" style={{width: `${row.completedCount ? Math.max(4, row.completedCount / maxReport * 100) : 0}%`}} />
                    <b>{row.completedCount}</b>
                  </div>
                </div>
              </div>)}
            </div>}

          <div className="ops-chart-head">
            <h3 className="ops-section-title">期限提醒</h3>
            <Tip text={caliber.deadlineReminderCount} />
          </div>
          {data.deadlineReminderCount == null
            ? <p className="ops-note-block">{data.deadlineReminderNote ?? '期限提醒口径未就绪：此处返回"缺项"而不是"没有提醒"。'}</p>
            : <div className={`ops-reminder ${data.deadlineReminderCount > 0 ? 'ops-reminder-warn' : ''}`}>
                <strong>{data.deadlineReminderCount}</strong>
                <div>
                  <span>{data.deadlineReminderCount > 0 ? '条期限即将到期或已超期' : '当前没有需要处理的期限提醒'}</span>
                  <small>{data.deadlineReminderNote ?? '口径：统计范围内质保、维保与预计寿命的临期/超期条数。'}</small>
                </div>
              </div>}
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
