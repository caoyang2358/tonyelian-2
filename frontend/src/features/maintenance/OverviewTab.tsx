import { useCallback, useEffect, useState } from 'react';
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

/** 时长条的满量程：超过 2 小时按满格显示，避免个别长单把其余指标压成一条线。 */
const DURATION_FULL_SCALE = 2 * 60 * 60;

const volumeMeta = [
  {key: 'inspection', label: '巡检次数', color: '#6f9f8a', caliberKey: 'inspectionCount'},
  {key: 'alert', label: '异常事件', color: '#b5694a', caliberKey: 'alertEventCount'},
  {key: 'pending', label: '维修待办', color: '#c2a04a', caliberKey: 'pendingRepairCount'},
  {key: 'completed', label: '完成工单', color: '#9fbd6f', caliberKey: 'completedRepairCount'},
] as const;

const deadlineMeta = [
  {key: 'dueSoon', label: '临期', color: '#c2a04a'},
  {key: 'expired', label: '已超期', color: '#b5694a'},
  {key: 'missing', label: '待补资料', color: '#9aa79a'},
] as const;

function formatDuration(raw: string | null): string | null {
  if (raw == null || raw === '') return null;
  const seconds = Number(raw);
  if (!Number.isFinite(seconds)) return null;
  if (seconds < 60) return `${seconds.toFixed(0)} 秒`;
  if (seconds < 3600) return `${(seconds / 60).toFixed(1)} 分钟`;
  return `${(seconds / 3600).toFixed(1)} 小时`;
}

function durationTone(seconds: number): 'good' | 'warn' | 'bad' {
  if (seconds <= 30 * 60) return 'good';
  if (seconds <= DURATION_FULL_SCALE) return 'warn';
  return 'bad';
}

export function OverviewTab() {
  // 默认看最近 30 天：演示数据与真实使用都更容易落在窗口内，避免一进来全是 0。
  const [from, setFrom] = useState(() => localInputValue(-24 * 60 * 30));
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

  // 进入页签先给一份默认区间的数据：让人先看见图，再决定要不要改筛选。
  useEffect(() => { void load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const caliber = data?.caliber ?? {};
  const volumeValues: Record<(typeof volumeMeta)[number]['key'], number> = {
    inspection: data?.inspectionCount ?? 0,
    alert: data?.alertEventCount ?? 0,
    pending: data?.pendingRepairCount ?? 0,
    completed: data?.completedRepairCount ?? 0,
  };
  const volumeMax = Math.max(1, ...Object.values(volumeValues));
  const volumeTotal = Object.values(volumeValues).reduce((sum, value) => sum + value, 0);
  const seconds = data?.averageHandlingSeconds == null ? null : Number(data.averageHandlingSeconds);
  const durationPercent = seconds == null || !Number.isFinite(seconds)
    ? 0
    : Math.min(100, Math.max(seconds > 0 ? 2 : 0, seconds / DURATION_FULL_SCALE * 100));
  const breakdown = data?.deadlineBreakdown;
  const deadlineSegments = deadlineMeta.map(meta => ({
    ...meta,
    value: breakdown ? breakdown[meta.key] : 0,
  }));
  const deadlineSum = deadlineSegments.reduce((sum, item) => sum + item.value, 0);
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
        <button className="reset-filter" type="button" onClick={() => {setFrom(localInputValue(-24 * 60 * 30)); setTo(localInputValue());}}>最近 30 天</button>
      </div>
      {error && <div className="state-error" role="alert"><div className="state-icon"><Icon name="alert" size={24} /></div><div><h3>无法完成统计</h3><p>{error}</p></div></div>}
      {data && <>
        <div className="ops-metrics">
          {volumeMeta.map(meta => <div className="ops-metric" key={meta.key}>
            <strong>{volumeValues[meta.key]}</strong>
            <span>{meta.label}<Tip text={caliber[meta.caliberKey]} /></span>
            <i className="ops-metric-mini">
              <b style={{
                width: `${Math.max(volumeValues[meta.key] > 0 ? 4 : 0, volumeValues[meta.key] / volumeMax * 100)}%`,
                background: meta.color,
              }} />
            </i>
          </div>)}
          <div className="ops-metric">
            <strong>{formatDuration(data.averageHandlingSeconds) ?? '—'}</strong>
            <span>平均处理时长（{data.handlingSampleCount} 单）<Tip text={caliber.averageHandlingSeconds} /></span>
            <i className="ops-metric-mini">
              <b className={`ops-metric-mini-${seconds == null ? 'good' : durationTone(seconds)}`}
                 style={{width: `${durationPercent}%`}} />
            </i>
          </div>
        </div>

        <div className="ops-detail ops-detail-padded">
          <div className="ops-chart-head">
            <h3 className="ops-section-title">业务量对比</h3>
            <div className="ops-chart-legend">
              {volumeMeta.map(meta => <span key={meta.key}><i style={{background: meta.color}} />{meta.label}</span>)}
            </div>
          </div>
          {volumeTotal === 0
            && <p className="ops-chart-empty">该时间范围内没有业务数据，可把范围放宽到「最近 30 天」再看。</p>}
          <div className="ops-columns" role="img" aria-label="巡检、异常事件、维修待办与完成工单的数量对比">
            {volumeMeta.map(meta => {
              const value = volumeValues[meta.key];
              return <div className="ops-column" key={meta.key}>
                <span className="ops-column-value">{value}</span>
                <div className="ops-column-track">
                  <span className="ops-column-bar"
                        style={{height: `${Math.max(value > 0 ? 4 : 0, value / volumeMax * 100)}%`, background: meta.color}}
                        title={`${meta.label} ${value} 次`} />
                </div>
                <span className="ops-column-label">{meta.label}</span>
              </div>;
            })}
          </div>

          <div className="ops-chart-head">
            <h3 className="ops-section-title">平均处理时长</h3>
            <span className="ops-chart-hint">满量程 2 小时 · 30 分钟内为健康区间<Tip text={caliber.averageHandlingSeconds} /></span>
          </div>
          {seconds == null
            ? <p className="missing-value">统计范围内没有验收通过的工单，暂无可计算的时长。</p>
            : <div className={`ops-duration ops-duration-${durationTone(seconds)}`}>
                <div className="ops-duration-head">
                  <strong>{formatDuration(data.averageHandlingSeconds)}</strong>
                  <span>样本 {data.handlingSampleCount} 单</span>
                </div>
                <div className="ops-duration-track">
                  <span className="ops-duration-fill" style={{width: `${durationPercent}%`}} />
                  <i style={{left: '25%'}} title="30 分钟" />
                  <i style={{left: '50%'}} title="1 小时" />
                </div>
                <div className="ops-duration-scale"><span>0</span><span>30 分钟</span><span>1 小时</span><span>2 小时</span></div>
              </div>}

          <div className="ops-chart-head">
            <h3 className="ops-section-title">期限提醒</h3>
            <span className="ops-chart-hint">{data.deadlineReminderCount ?? 0} 条需处理<Tip text={caliber.deadlineReminderCount} /></span>
          </div>
          {data.deadlineReminderCount == null
            ? <p className="ops-note-block">{data.deadlineReminderNote ?? '期限提醒口径未就绪：此处返回"缺项"而不是"没有提醒"。'}</p>
            : deadlineSum === 0
              ? <div className="ops-reminder"><strong>0</strong><div><span>当前没有需要处理的期限提醒</span></div></div>
              : <div className="ops-deadline">
                  <div className="ops-deadline-bar" role="img" aria-label="期限构成：临期、已超期与待补资料">
                    {deadlineSegments.filter(item => item.value > 0).map(item =>
                      <span key={item.key} style={{flexGrow: item.value, background: item.color}}
                            title={`${item.label} ${item.value} 条`} />)}
                  </div>
                  <div className="ops-deadline-legend">
                    {deadlineSegments.map(item => <span key={item.key}>
                      <i style={{background: item.color}} />{item.label}<b>{item.value}</b>
                    </span>)}
                  </div>
                </div>}

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

          <p className="missing-value">统计生成时间：{formatGeneratedAt(data.generatedAt)}</p>
        </div>
      </>}
      {!data && !error && !loading && <div className="empty-state"><div className="empty-icon"><Icon name="statistics" size={30} /></div><h3>正在准备统计数据</h3><p>默认统计最近 30 天；时间按实际业务时刻计数。</p></div>}
    </section>
  </>;
}

function formatGeneratedAt(value: string): string {
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN', {hour12: false}) : value;
}
