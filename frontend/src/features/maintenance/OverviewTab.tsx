import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../../components/Icon';
import { ApiError, api } from '../../lib/api';
import type { DeadlineItem } from '../../lib/api';
import { MaintenanceApiError, overviewApi } from '../../lib/maintenanceApi';
import type { MaintenanceOverview } from '../../lib/maintenanceApi';
import { localInputValue } from './labels';
import { EquipmentSelect, Tip, useWorkshopOptions } from './shared';

/** 时长条的满量程：超过 2 小时按满格显示，避免个别长单把其余指标压成一条线。 */
const DURATION_FULL_SCALE = 2 * 60 * 60;

const volumeMeta = [
  {key: 'inspection', label: '巡检次数', color: '#6f9f8a', caliberKey: 'inspectionCount'},
  {key: 'alert', label: '异常事件', color: '#b5694a', caliberKey: 'alertEventCount'},
  {key: 'pending', label: '维修待办', color: '#c2a04a', caliberKey: 'pendingRepairCount'},
  {key: 'completed', label: '完成工单', color: '#9fbd6f', caliberKey: 'completedRepairCount'},
] as const;

const bucketMeta = [
  {key: 'within30Minutes', label: '30 分钟以内', color: '#5b8f6f'},
  {key: 'within2Hours', label: '30 分钟~2 小时', color: '#c2a04a'},
  {key: 'over2Hours', label: '超过 2 小时', color: '#b5694a'},
] as const;

const deadlineMeta = [
  {key: 'dueSoon', label: '临期', color: '#c2a04a'},
  {key: 'expired', label: '已超期', color: '#b5694a'},
  {key: 'missing', label: '待补资料', color: '#9aa79a'},
] as const;

/** 明细行最多展示几条，剩下的引导去设备档案的期限面板看全量。 */
const DEADLINE_ROWS_SHOWN = 15;

const DEADLINE_TYPE_LABEL: Record<string, string> = {
  WARRANTY: '质保', MAINTENANCE: '维保', EXPECTED_LIFE: '预计寿命',
};

const DEADLINE_STATE_LABEL: Record<string, string> = {
  DUE_SOON: '临期', EXPIRED: '已超期', MISSING_DATA: '待补资料',
};

const MISSING_FIELD_LABEL: Record<string, string> = {
  warrantyExpiresOn: '质保到期日', commissionedOn: '投用日',
  expectedLifeYears: '预计使用年限', contractId: '维保合同', endDate: '合同结束日期',
};

function remainingDaysText(days: number | null): string {
  if (days == null) return '未填日期';
  if (days === 0) return '今天到期';
  if (days > 0) return `剩余 ${days} 天`;
  return `已超期 ${Math.abs(days)} 天`;
}

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
  // 期限明细行：汇总条只给数量，具体是哪台设备的哪个期限在这里列出。
  const [deadlineRows, setDeadlineRows] = useState<DeadlineItem[] | null>(null);
  const [deadlineRowsNote, setDeadlineRowsNote] = useState<string | null>(null);
  // 快捷范围选中值（分钟）；手改时间即视为自定义，清空高亮。
  const [presetMinutes, setPresetMinutes] = useState<number | null>(30 * 24 * 60);

  /** range 用于快捷按钮：此时 state 还没更新，直接按新范围查，避免读到旧闭包。 */
  const load = useCallback(async (range?: {from: string; to: string}) => {
    const fromValue = range?.from ?? from;
    const toValue = range?.to ?? to;
    setLoading(true);
    try {
      const result = await overviewApi.maintenanceOverview({
        from: new Date(fromValue).toISOString(),
        to: new Date(toValue).toISOString(),
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

  /** 快捷范围：点一下改时间并立即统计，按钮同时高亮，让人看得出生效了。 */
  const applyPreset = (minutes: number) => {
    const nextFrom = localInputValue(-minutes);
    const nextTo = localInputValue();
    setFrom(nextFrom);
    setTo(nextTo);
    setPresetMinutes(minutes);
    void load({from: nextFrom, to: nextTo});
  };

  // 进入页签先给一份默认区间的数据：让人先看见图，再决定要不要改筛选。
  useEffect(() => { void load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /** 有待处理期限时拉一份明细：复用设备档案的期限接口，筛掉 NORMAL 只留计入口径内的三种。 */
  useEffect(() => {
    const breakdown = data?.deadlineBreakdown;
    const sum = breakdown ? breakdown.dueSoon + breakdown.expired + breakdown.missing : 0;
    if (!data || sum === 0) { setDeadlineRows(null); setDeadlineRowsNote(null); return; }
    let alive = true;
    api.deadlines({page: 1, pageSize: 100})
      .then(result => {
        if (!alive) return;
        const pending = result.data.items
          .filter(row => row.expiryState !== 'NORMAL')
          .sort((a, b) => (a.remainingDays ?? Number.MAX_SAFE_INTEGER) - (b.remainingDays ?? Number.MAX_SAFE_INTEGER));
        setDeadlineRows(pending);
        setDeadlineRowsNote(pending.length === 0
          ? '明细与汇总暂未对上：期限清单此刻没有待处理行，请刷新重试。' : null);
      })
      .catch((err: unknown) => {
        if (!alive) return;
        setDeadlineRows(null);
        setDeadlineRowsNote(err instanceof ApiError ? '明细加载失败，可到「设备档案 → 期限提醒」查看完整清单。' : null);
      });
    return () => { alive = false; };
  }, [data]);

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
  const buckets = data?.durationBuckets ?? {within30Minutes: 0, within2Hours: 0, over2Hours: 0};
  const bucketTotal = buckets.within30Minutes + buckets.within2Hours + buckets.over2Hours;
  const overdueShare = bucketTotal > 0 ? Math.round(buckets.over2Hours / bucketTotal * 100) : 0;
  const deadlineTypes = data?.deadlineTypeCounts ?? {warranty: 0, maintenance: 0, expectedLife: 0};
  const deadlineAbnormal = (data?.deadlineBreakdown?.dueSoon ?? 0) + (data?.deadlineBreakdown?.expired ?? 0);
  const missingEquipmentCount = data?.deadlineMissingEquipmentCount ?? 0;
  const overdueRepairCount = data?.overdueRepairCount ?? 0;
  const maxReport = Math.max(1, ...(data?.equipmentReportCounts.map(row => row.reportCount) ?? [1]));

  return <>
    <section className="panel" aria-label="运维概览查询">
      <div className="panel-heading"><div><h2>运维概览</h2></div></div>
      <div className="filters">
        <label className="select-field"><span>开始时间</span>
          <input type="datetime-local" value={from} onChange={event => { setFrom(event.target.value); setPresetMinutes(null); }} /></label>
        <label className="select-field"><span>结束时间</span>
          <input type="datetime-local" value={to} onChange={event => { setTo(event.target.value); setPresetMinutes(null); }} /></label>
        <label className="select-field"><span>车间</span>
          <select value={workshopId} onChange={event => setWorkshopId(event.target.value)}>
            <option value="">全部车间</option>
            {workshops.map(item => <option value={item.id} key={item.id}>{item.name}</option>)}
          </select></label>
        <EquipmentSelect label="设备" value={equipmentId} onChange={setEquipmentId} allowEmpty emptyLabel="全部设备" />
        <button className="button button-primary" type="button" onClick={() => void load()} disabled={loading}>{loading ? '统计中…' : '统计'}</button>
        {[{label: '最近 24 小时', minutes: 24 * 60}, {label: '最近 7 天', minutes: 7 * 24 * 60},
          {label: '最近 30 天', minutes: 30 * 24 * 60}].map(item => (
          <button key={item.minutes} type="button" disabled={loading}
            className={presetMinutes === item.minutes ? 'reset-filter is-active' : 'reset-filter'}
            aria-pressed={presetMinutes === item.minutes}
            onClick={() => applyPreset(item.minutes)}>{item.label}</button>
        ))}
      </div>
      {error && <div className="state-error" role="alert"><div className="state-icon"><Icon name="alert" size={24} /></div><div><h3>无法完成统计</h3><p>{error}</p></div></div>}
      {data && <>
        <p className="ops-scope">
          <span>统计范围：<b>{formatGeneratedAt(data.from)}</b> 至 <b>{formatGeneratedAt(data.to)}</b></span>
          <span className="ops-scope-note">期限提醒与工单时效看的是查询这一刻的状态，不受该范围限制</span>
        </p>
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
            <span>维修工单平均处理时长（{data.handlingSampleCount} 单）<Tip text={caliber.averageHandlingSeconds} /></span>
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
            <h3 className="ops-section-title">维修工单平均处理时长</h3>
            <span className="ops-chart-hint">满量程 2 小时 · 30 分钟内绿色 · 30 分钟~2 小时黄色 · 超 2 小时红色<Tip text={caliber.averageHandlingSeconds} /></span>
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
                {bucketTotal > 0 && <div className="ops-buckets">
                  <p className="ops-buckets-title">已完成 {bucketTotal} 单的时长分布</p>
                  <div className="ops-buckets-bar" role="img" aria-label="已完成工单的时长分段：30 分钟以内、30 分钟至 2 小时、超过 2 小时">
                    {bucketMeta.map(meta => buckets[meta.key] > 0 && <span key={meta.key}
                      style={{flexGrow: buckets[meta.key], background: meta.color}}
                      title={`${meta.label}：${buckets[meta.key]} 单`} />)}
                  </div>
                  <div className="ops-buckets-legend">
                    {bucketMeta.map(meta => <span key={meta.key}>
                      <i style={{background: meta.color}} />{meta.label}<b>{buckets[meta.key]}</b> 单
                    </span>)}
                  </div>
                  {buckets.over2Hours > 0
                    ? <p className="ops-buckets-alert">其中 <b>{buckets.over2Hours}</b> 单超过预期处理时间（2 小时），占已完成工单的 {overdueShare}%</p>
                    : <p className="ops-buckets-ok">已完成工单全部在 2 小时内处理完毕</p>}
                </div>}
              </div>}
          <div className={`ops-overdue${overdueRepairCount > 0 ? ' ops-overdue-active' : ''}`}>
            <span>工单时效：当前在办工单中 <b>{overdueRepairCount}</b> 单已超过 2 小时未结束<Tip text={caliber.overdueRepairCount} /></span>
            <span className="ops-overdue-note">与上面的期限提醒不是一回事：这里算的是工单从提交到现在的时长。</span>
          </div>

          <div className="ops-chart-head">
            <h3 className="ops-section-title">期限提醒（设备档案）</h3>
            <span className="ops-chart-hint">
              {data.deadlineReminderCount ?? 0} 条待处理
              {missingEquipmentCount > 0 && `（其中待补资料涉及 ${missingEquipmentCount} 台设备）`}
              <Tip text={caliber.deadlineReminderCount} />
            </span>
          </div>
          {data.deadlineReminderCount == null
            ? <p className="ops-note-block">{data.deadlineReminderNote ?? '期限提醒口径未就绪：此处返回"缺项"而不是"没有提醒"。'}</p>
            : deadlineSum === 0
              ? <div className="ops-reminder"><strong>0</strong><div><span>当前没有需要处理的期限提醒：临期与超期均为 0，档案齐全</span></div></div>
              : <div className="ops-deadline">
                  <div className="ops-deadline-bar" role="img" aria-label="期限构成：临期、已超期与待补资料">
                    {deadlineSegments.filter(item => item.value > 0).map(item =>
                      <span key={item.key} style={{flexGrow: item.value, background: item.color}}
                            title={`${item.label} ${item.value} 条`} />)}
                  </div>
                  <div className="ops-deadline-legend">
                    {deadlineSegments.map(item => <span key={item.key}>
                      <i style={{background: item.color}} />{item.label}<b>{item.value}</b> 条
                    </span>)}
                  </div>
                  {deadlineAbnormal > 0 && <p className="ops-deadline-type">
                    临期与超期按来源：质保 <b>{deadlineTypes.warranty}</b> 条 · 维保 <b>{deadlineTypes.maintenance}</b> 条 · 预计寿命 <b>{deadlineTypes.expectedLife}</b> 条
                  </p>}
                  <ul className="ops-deadline-notes">
                    <li>一条 = 一台设备的<strong>一类期限</strong>（质保、维保、预计寿命各算一条），所以条数通常是台数的 3 倍。</li>
                    <li>「待补资料」= 档案缺日期、算不出到期日，<strong>不是</strong>还没到期；补齐后自动转入临期/超期评估。</li>
                  </ul>
                  <div className="ops-deadline-detail">
                    <p className="ops-deadline-detail-title">明细 · 每行 = 一台设备的一类期限，共 {deadlineSum} 行</p>
                    {deadlineRowsNote != null
                      ? <p className="ops-deadline-detail-note">{deadlineRowsNote}</p>
                      : deadlineRows == null
                        ? <p className="ops-deadline-detail-note">明细加载中…</p>
                        : <table className="ops-deadline-table">
                            <thead><tr>
                              <th>设备</th><th>期限类型</th><th>状态</th><th>到期日 / 缺失项</th><th>负责人</th>
                            </tr></thead>
                            <tbody>
                              {deadlineRows.slice(0, DEADLINE_ROWS_SHOWN).map(row => {
                                const missing = row.missingFields
                                  .map(field => MISSING_FIELD_LABEL[field] ?? field).join('、');
                                return <tr key={`${row.equipmentId}-${row.reminderType}-${row.contractId ?? 'none'}`}>
                                  <td>{row.equipmentName}<span className="ops-deadline-code">{row.equipmentCode}</span></td>
                                  <td>{DEADLINE_TYPE_LABEL[row.reminderType] ?? row.reminderType}
                                    {row.contractNumber ? <span className="ops-deadline-code">{row.contractNumber}</span> : null}</td>
                                  <td><span className={`ops-deadline-state state-${row.expiryState.toLowerCase()}`}>
                                    {DEADLINE_STATE_LABEL[row.expiryState] ?? row.expiryState}</span></td>
                                  <td>{row.expiryState === 'MISSING_DATA'
                                    ? `缺 ${missing || '关键字段'}，算不出到期日`
                                    : [row.dueDate, remainingDaysText(row.remainingDays)].filter(Boolean).join(' · ')}</td>
                                  <td>{row.responsiblePerson ?? '—'}</td>
                                </tr>;
                              })}
                            </tbody>
                          </table>}
                    {deadlineRows != null && deadlineRows.length > DEADLINE_ROWS_SHOWN
                      && <p className="ops-deadline-detail-note">
                        仅显示前 {DEADLINE_ROWS_SHOWN} 条，完整 {deadlineRows.length} 条见「设备档案 → 期限提醒」。
                      </p>}
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
