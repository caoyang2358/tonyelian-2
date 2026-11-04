import { useEffect, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { PageResult, SensorHistoryRecord, SensorRuleVersion } from '../../lib/api';
import './equipment.css';

/**
 * 参数读数与规则的只读弹层（S1-P10/P11）。
 *
 * <p>历史读的是原始采集记录，规则读的是不可覆盖的版本快照。两者都不提供编辑入口——
 * 改规则要走 P08 的整单保存（还要在同一事务里结束受影响事件），不是这里顺手能改的。</p>
 *
 * <p>空态要能区分"区间内没有数据"和"接口失败"：现场排查时把失败当成"没数据"，
 * 会得出完全相反的结论。</p>
 */

type Mode = 'history' | 'rules';

function toInputValue(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function formatTime(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit'});
}

/** 默认查最近 24 小时：现场最常见的诉求是"刚才那波数据到底有没有"。 */
function defaultRange(): {from: string; to: string} {
  const now = new Date();
  return {from: toInputValue(new Date(now.getTime() - 24 * 3600 * 1000)), to: toInputValue(now)};
}

export function SensorReadingsDialog({parameterId, title, unitCode, onClose}: {
  parameterId: string; title: string; unitCode: string | null; onClose: () => void;
}) {
  const [mode, setMode] = useState<Mode>('history');
  const [range, setRange] = useState(defaultRange);
  const [page, setPage] = useState(1);
  const [history, setHistory] = useState<PageResult<SensorHistoryRecord> | null>(null);
  const [rules, setRules] = useState<PageResult<SensorRuleVersion> | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { setPage(1); }, [mode]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    const request = mode === 'history'
      ? api.sensorHistory(parameterId, {from: range.from, to: range.to, page, pageSize: 20}, controller.signal)
          .then(result => setHistory(result.data))
      : api.sensorRuleVersions(parameterId, page, 20, controller.signal)
          .then(result => setRules(result.data));
    request
      .catch((failure: unknown) => {
        if (failure instanceof Error && failure.name === 'AbortError') return;
        setError(failure instanceof ApiError ? failure.message : '读取失败，请稍后重试。');
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [parameterId, mode, page, range.from, range.to]);

  const current = mode === 'history' ? history : rules;
  const totalPages = current ? Math.max(1, Math.ceil((current.total ?? 0) / (current.pageSize ?? 20))) : 1;

  return <div className="eq-overlay" role="presentation"
              onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div className="eq-dialog-wrap" role="dialog" aria-modal="true" aria-label="参数读数与规则">
      <div className="eq-dialog-head">
        <h2>{title}</h2>
        <p>只读视图：历史来自原始采集记录，规则来自不可覆盖的版本快照</p>
      </div>

      <div className="eq-dialog-body">
        <div className="eq-sensor-tabs" role="tablist" aria-label="视图切换">
          <button type="button" role="tab" aria-selected={mode === 'history'} className="eq-tab"
                  onClick={() => setMode('history')}>历史读数</button>
          <button type="button" role="tab" aria-selected={mode === 'rules'} className="eq-tab"
                  onClick={() => setMode('rules')}>规则版本</button>
        </div>

        {mode === 'history' && <div className="eq-sensor-range">
          <label className="eq-form-field">
            <span>起始</span>
            <input type="datetime-local" value={range.from}
                   onChange={event => setRange({...range, from: event.target.value})} />
          </label>
          <label className="eq-form-field">
            <span>截止（不含）</span>
            <input type="datetime-local" value={range.to}
                   onChange={event => setRange({...range, to: event.target.value})} />
          </label>
          <small className="eq-form-hint">单次查询区间不超过 92 天，超出会被服务端拒绝。</small>
        </div>}

        {error && <div className="eq-notice"><span>!</span><div>{error}</div></div>}
        {loading && <p className="eq-form-hint">正在读取…</p>}

        {!loading && !error && mode === 'history' && history && (history.items.length === 0
          ? <p className="eq-param-empty">这个区间没有采集记录。参数存在但没有原始数据时，页面按"待数据"处理，不代表测点故障。</p>
          : <ul className="eq-sensor-records">
            {history.items.map(record => <li key={record.recordId} className="eq-sensor-record">
              <span className="eq-sensor-record-time">{formatTime(record.collectTime)}</span>
              <span className="eq-sensor-record-value">
                {record.valueText && !record.value ? record.valueText : (record.value ?? '—')}
                <small>{record.sourceUnit ?? unitCode ?? ''}</small>
              </span>
              <span className="eq-sensor-record-meta">
                {record.qualityCode ?? '质量码未标'} · {record.sourceSystem}
              </span>
            </li>)}
          </ul>)}

        {!loading && !error && mode === 'rules' && rules && (rules.items.length === 0
          ? <p className="eq-param-empty">这个参数还没有规则版本。阈值未配置时按"待配置"处理，不填工业默认值。</p>
          : <ul className="eq-sensor-records">
            {rules.items.map(version => <li key={version.ruleVersionId} className="eq-sensor-rule">
              <div className="eq-sensor-rule-head">
                <strong>v{version.ruleVersion}</strong>
                <span className={version.rule.enabled ? 'eq-badge' : 'eq-badge is-muted'}>
                  {version.rule.enabled ? '生效' : '已停用'}
                </span>
                <small>{formatTime(version.effectiveAt)} · {version.operatorName}</small>
              </div>
              <div className="eq-sensor-rule-body">
                <span>正常 [{version.rule.normalLower ?? '—'}, {version.rule.normalUpper ?? '—'}]</span>
                <span>报警 [{version.rule.alarmLower ?? '—'}, {version.rule.alarmUpper ?? '—'}]</span>
                <span>
                  {version.rule.triggerMode === 'IMMEDIATE' ? '关键：立即提醒'
                    : version.rule.triggerMode === 'SUSTAINED' ? `持续 ${version.rule.durationSeconds ?? 0} 秒`
                    : '触发方式未配'}
                </span>
                <span>{version.rule.unitCode ?? unitCode ?? '单位未配'}</span>
              </div>
              {version.reason && <p className="eq-form-hint">修改原因：{version.reason}</p>}
              {version.affectedEventIds.length > 0 && <p className="eq-form-hint">
                这一版接手时结束了 {version.affectedEventIds.length} 条活动事件（原因 RULE_CHANGED）
              </p>}
            </li>)}
          </ul>)}

        {current && (current.total ?? 0) > (current.pageSize ?? 20) && <div className="eq-sensor-pager">
          <button type="button" className="button" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
          <span>{page} / {totalPages}</span>
          <button type="button" className="button" disabled={page >= totalPages} onClick={() => setPage(page + 1)}>下一页</button>
        </div>}
      </div>

      <div className="eq-dialog-foot">
        <span className="eq-spacer" />
        <button type="button" className="button" onClick={onClose}>关闭</button>
      </div>
    </div>
  </div>;
}
