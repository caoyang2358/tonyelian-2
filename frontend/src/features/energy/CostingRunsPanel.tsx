import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { CostRecalcReceipt, CostRunDetail, CostRunSummary } from '../../lib/api';
import {
  COST_MISSING_LABELS, RUN_STATUS_LABELS, TRIGGER_TYPE_LABELS, labelOf, money, ton,
} from './costingFormat';
import './costing.css';
import './energy.css';

/**
 * 「核算运行」视图：S2-K05 受控补算 + S2-K06 运行查询。
 *
 * <p>这个页签最容易骗人，所以先把边界写在页面上：</p>
 * <ul>
 *   <li><b>同步执行</b>。提交后结果就在响应里，没有「执行中」中间态，也没有 `FAILED`
 *       记录可查——写入要么整套可见，要么一行不留。</li>
 *   <li><b>没有发布指针</b>。「当前运行」取的是最近一次已完成的运行，不是「已发布版本」；
 *       因此也没有「候选结果与当前有效结果」的对比。</li>
 *   <li><b>来源修订号未落地</b>。带上并发版本参数不会拦住冲突，只会在缺项里多一条
 *       `SOURCE_REVISION_NOT_TRACKED`。页面上如实标出，不假装校验过。</li>
 *   <li><b>补算不重做数量</b>。它只重算成本投影：不再投料、产出、领用，也不改库存与订单事实。</li>
 * </ul>
 */

export function CostingRunsPanel() {
  const [current, setCurrent] = useState<CostRunSummary | null>(null);
  const [currentReady, setCurrentReady] = useState(false);

  const loadCurrent = useCallback(() => {
    // 只为拿「当前运行」这一行，pageSize 取最小；失败不影响补算本身。
    api.costingStocks({pageSize: 1})
      .then(result => setCurrent(result.data.costRun))
      .catch(() => setCurrent(null))
      .finally(() => setCurrentReady(true));
  }, []);

  useEffect(() => { loadCurrent(); }, [loadCurrent]);

  return (
    <div className="en-cost en-runs">
      {/* 用独立的块级说明类，不复用 .en-notice：后者是 flex，为「一句话 + 一个按钮」
          设计，装长段落会把 <b> 与相邻文本拆成各自独立的 flex 项。 */}
      <p className="en-run-note">
        本版补算是<b>同步执行</b>：提交后直接返回结果，没有「执行中」状态，失败会整批回滚而不是留下一条失败记录。
        发布指针与来源修订号尚未落地，「当前运行」指最近一次完成的运行，不做候选与发布的对比。
      </p>

      <RecalcForm onDone={() => loadCurrent()} />

      <RunLookup current={current} currentReady={currentReady} />
    </div>
  );
}

// ------------------------------------------------------------------ 补算

function RecalcForm({onDone}: {onDone: () => void}) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [reason, setReason] = useState('');
  const [operator, setOperator] = useState('');
  const [materialIds, setMaterialIds] = useState('');
  const [orderIds, setOrderIds] = useState('');
  const [armed, setArmed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [receipt, setReceipt] = useState<CostRecalcReceipt | null>(null);

  // 改任何一个输入都要退出「待确认」：确认的是那一次的具体范围，不是这个按钮。
  const touch = () => { setArmed(false); setError(null); };

  const submit = async () => {
    const operatorName = operator.trim();
    const why = reason.trim();
    if (!operatorName) { setError('请填写经办人，补算要能追溯到人。'); return; }
    if (!why) { setError('请写明补算原因，否则事后无法解释这次为什么重算。'); return; }
    if (from && to && from > to) { setError('起始日期不能晚于结束日期。'); return; }

    const materials = splitIds(materialIds);
    const orders = splitIds(orderIds);
    const scope = {
      ...(materials.length ? {materialIds: materials} : {}),
      ...(orders.length ? {orderIds: orders} : {}),
      ...(from ? {from} : {}),
      ...(to ? {to} : {}),
    };

    setSubmitting(true);
    setError(null);
    try {
      const result = await api.costingRecalculate({
        // 幂等键：同一请求重试不会产生第二个运行。与生产模块那个同名工具等价，
        // 但不跨模块引用，避免能源视图依赖生产客户端。
        requestId: crypto.randomUUID(),
        operatorName,
        reason: why,
        // 范围必须放 requestedScope：顶层传 from/to 后端不读，会静默算全区间。
        ...(Object.keys(scope).length ? {requestedScope: scope} : {}),
      });
      setReceipt(result.data);
      setArmed(false);
      onDone();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '补算未完成，请稍后重试。');
      setArmed(false);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="en-run-form">
      <header className="en-run-form-head">
        <h3>执行补算</h3>
        <p>
          影响范围由后端沿真实来源追溯确定，这里只能收窄、不能指定金额；留空表示重算全部历史数据。
        </p>
      </header>

      <div className="en-run-fields">
        <label className="en-field">
          <span>起始日期（可空）</span>
          <input type="date" value={from} onChange={event => { setFrom(event.target.value); touch(); }} />
        </label>
        <label className="en-field">
          <span>结束日期（可空）</span>
          <input type="date" value={to} onChange={event => { setTo(event.target.value); touch(); }} />
        </label>
        <label className="en-field en-field-wide">
          <span>补算原因（必填）</span>
          <input value={reason} placeholder="例如：补齐 11-18 铜板投料金额后重算"
                 onChange={event => { setReason(event.target.value); touch(); }} />
        </label>
        <label className="en-field">
          <span>经办人（必填）</span>
          <input value={operator} placeholder="姓名或工号"
                 onChange={event => { setOperator(event.target.value); touch(); }} />
        </label>
      </div>

      <details className="en-run-advanced">
        <summary>只重算指定物料或订单（可空）</summary>
        <div className="en-run-fields">
          <label className="en-field">
            <span>物料 ID，逗号分隔</span>
            <input value={materialIds} placeholder="例如 1,2"
                   onChange={event => { setMaterialIds(event.target.value); touch(); }} />
          </label>
          <label className="en-field">
            <span>订单 ID，逗号分隔</span>
            <input value={orderIds} placeholder="留空表示全部订单"
                   onChange={event => { setOrderIds(event.target.value); touch(); }} />
          </label>
        </div>
        <p className="en-hint">这些是内部编号，通常不用填；用于定位某几件物料的成本重算。</p>
      </details>

      {error && <p className="en-error" role="alert">{error}</p>}

      <div className="en-run-actions">
        {armed
          ? (
            <>
              <button type="button" className="button button-small en-run-confirm"
                      onClick={submit} disabled={submitting}>
                {submitting ? '补算进行中…' : '确认执行，重算成本'}
              </button>
              <button type="button" className="button button-small" onClick={() => setArmed(false)}
                      disabled={submitting}>
                取消
              </button>
              <span className="en-hint">确认后会新增一个核算运行；原运行的台账与计费项保留，不会被覆盖。</span>
            </>
          )
          : (
            <>
              <button type="button" className="button button-small button-primary"
                      onClick={() => { setError(null); setArmed(true); }} disabled={submitting}>
                执行补算
              </button>
              <span className="en-hint">
                补算只重算成本，不再次投料、产出或领用。需要二次确认，是因为它会新增一次运行记录。
              </span>
            </>
          )}
      </div>

      {receipt && <RecalcReceiptCard receipt={receipt} onDismiss={() => setReceipt(null)} />}
    </section>
  );
}

function RecalcReceiptCard({receipt, onDismiss}: {receipt: CostRecalcReceipt; onDismiss: () => void}) {
  return (
    <div className="en-run-receipt" role="status">
      <header>
        <span className={`en-badge ${statusBadge(receipt.status)}`}>{labelOf(RUN_STATUS_LABELS, receipt.status)}</span>
        <strong>已生成核算运行 #{receipt.costRunId}</strong>
        {receipt.previousCostRunId && <span className="en-muted">上一运行 #{receipt.previousCostRunId}</span>}
        <button type="button" className="en-link" onClick={onDismiss}>收起</button>
      </header>
      <dl className="en-run-grid">
        <div><dt>成本台账行数</dt><dd className="en-mono">{receipt.ledgerRows}</dd></div>
        <div><dt>订单计费项行数</dt><dd className="en-mono">{receipt.orderItemRows}</dd></div>
        <div><dt>写入分摊行数</dt><dd className="en-mono">{receipt.energyAllocationRows}</dd></div>
        <div><dt>规则版本</dt><dd className="en-mono">{receipt.ruleVersion}</dd></div>
        <div>
          <dt>来源修订号核对</dt>
          <dd>
            {receipt.sourceRevisionChecked === false
              ? <span className="en-missing">未核过（本版没有来源修订号）</span>
              : <span className="en-muted">本次未要求核对</span>}
          </dd>
        </div>
      </dl>
      <p className="en-hint">{receipt.note}</p>
      {receipt.missingItems.length > 0 && <GapList items={receipt.missingItems} />}
    </div>
  );
}

// ------------------------------------------------------------------ 查询

function RunLookup({current, currentReady}: {current: CostRunSummary | null; currentReady: boolean}) {
  const [runIdInput, setRunIdInput] = useState('');
  const [detail, setDetail] = useState<CostRunDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const autoLoaded = useRef<string | null>(null);

  const open = useCallback(async (rawId: string) => {
    const target = rawId.trim();
    if (!target) { setError('请输入运行号。'); return; }
    setLoading(true);
    setError(null);
    try {
      const result = await api.costingRun(target);
      setDetail(result.data);
      setRunIdInput(target);
    } catch (cause) {
      setDetail(null);
      setError(cause instanceof ApiError ? cause.message : '读取核算运行失败，请稍后重试。');
    } finally {
      setLoading(false);
    }
  }, []);

  // 首屏直接展示当前运行：K06 的用途就是「现在的成本是哪个版本算的」。
  useEffect(() => {
    const id = current?.costRunId;
    if (!id || autoLoaded.current === id) return;
    autoLoaded.current = id;
    void open(id);
  }, [current, open]);

  return (
    <section className="en-run-lookup">
      <header className="en-run-form-head">
        <h3>运行查询</h3>
        <p>没有运行列表接口，按运行号查。当前运行的编号取自库存成本视图的同一份结果。</p>
      </header>

      <div className="en-toolbar">
        <label className="en-field">
          <span>核算运行号</span>
          <input value={runIdInput} placeholder="例如 21"
                 onChange={event => { setRunIdInput(event.target.value); setError(null); }}
                 onKeyDown={event => { if (event.key === 'Enter') void open(runIdInput); }} />
        </label>
        <button type="button" className="button button-small" onClick={() => void open(runIdInput)}
                disabled={loading}>
          {loading ? '查询中…' : '查询'}
        </button>
        {current?.costRunId && (
          <button type="button" className="button button-small en-run-current"
                  onClick={() => void open(String(current.costRunId))}
                  disabled={loading || detail?.costRunId === current.costRunId}>
            查看当前运行 #{current.costRunId}
          </button>
        )}
        {currentReady && !current?.costRunId && (
          <span className="en-hint">还没有任何核算运行：上面的表单产生第一次补算后就能查。</span>
        )}
      </div>

      {error && <p className="en-error" role="alert">{error}</p>}
      {detail && <RunDetailCard run={detail} onOpen={id => void open(id)} />}
      {!detail && !error && (
        <p className="en-notice">
          还没有可展示的运行。补算完成、或在上面输入运行号后，这里会显示状态、受影响范围、
          读到的输入规模与每件物料的期末完整性。
        </p>
      )}
    </section>
  );
}

function RunDetailCard({run, onOpen}: {run: CostRunDetail; onOpen: (id: string) => void}) {
  const snapshot = run.inputSnapshot;
  return (
    <article className="en-run-card">
      <header className="en-run-card-head">
        <div>
          <h3>核算运行 #{run.costRunId}</h3>
          <p className="en-cost-runline">
            <span className={`en-badge ${statusBadge(run.status)}`}>{labelOf(RUN_STATUS_LABELS, run.status)}</span>
            <span>{labelOf(TRIGGER_TYPE_LABELS, run.triggerType)}</span>
            <span className="en-muted">规则 {run.ruleVersion} · 记录版本 {run.version}</span>
            {/* 后端 retryable 的语义是「补齐后可再算」，本版没有重试按钮，别让人去找。 */}
            {run.retryable && (
              <span className="en-badge en-badge-partial" title="补齐缺项后重新执行补算即可">
                补齐后可重算
              </span>
            )}
          </p>
        </div>
        <div className="en-run-links">
          {run.previousCostRunId && (
            <button type="button" className="en-link"
                    onClick={() => onOpen(String(run.previousCostRunId))}>
              上一次 #{run.previousCostRunId}
            </button>
          )}
          {run.nextCostRunId && (
            <button type="button" className="en-link"
                    onClick={() => onOpen(String(run.nextCostRunId))}>
              下一次 #{run.nextCostRunId}
            </button>
          )}
        </div>
      </header>

      <dl className="en-run-grid">
        <div><dt>请求时间</dt><dd className="en-mono">{when(run.requestedAt)}</dd></div>
        <div><dt>开始时间</dt><dd className="en-mono">{when(run.startedAt)}</dd></div>
        <div><dt>完成时间</dt><dd className="en-mono">{when(run.completedAt)}</dd></div>
        <div><dt>经办人</dt><dd>{run.operatorName}</dd></div>
        <div className="en-run-grid-wide"><dt>补算原因</dt><dd>{run.reason ?? '—'}</dd></div>
      </dl>

      <h4 className="en-run-subtitle">受影响范围</h4>
      <dl className="en-run-grid">
        <div><dt>物料</dt><dd className="en-mono">{idList(run.affectedScope.materialIds)}</dd></div>
        <div><dt>订单</dt><dd className="en-mono">{idList(run.affectedScope.orderIds)}</dd></div>
        <div>
          <dt>日期区间</dt>
          <dd className="en-mono">
            {run.affectedScope.from || run.affectedScope.to
              ? `${run.affectedScope.from ?? '最早'} 至 ${run.affectedScope.to ?? '最新'}`
              : '全部'}
          </dd>
        </div>
        <div><dt>来源引用</dt><dd className="en-mono">{run.affectedScope.sourceReferenceCount} 条</dd></div>
      </dl>

      <h4 className="en-run-subtitle">本次写入</h4>
      <dl className="en-run-grid">
        <div><dt>成本台账行数</dt><dd className="en-mono">{run.result.ledgerRows}</dd></div>
        <div><dt>订单计费项行数</dt><dd className="en-mono">{run.result.orderItemRows}</dd></div>
        <div><dt>成本金额合计</dt><dd className="en-mono">{money(run.result.costDeltaYuan)}</dd></div>
        <div><dt>订单成本合计</dt><dd className="en-mono">{money(run.result.orderAmountYuan)}</dd></div>
        <div><dt>涉及物料</dt><dd className="en-mono">{run.result.materialCount} 件</dd></div>
        <div>
          <dt>缺项</dt>
          <dd>{run.result.missingRows > 0
            ? <span className="en-missing">{run.result.missingRows} 行流水带缺项</span>
            : <span className="en-muted">无</span>}</dd>
        </div>
      </dl>

      {run.missingItems.length > 0 && <GapList items={run.missingItems} />}

      <h4 className="en-run-subtitle">读到的输入</h4>
      <dl className="en-run-grid">
        <div><dt>库存流水</dt><dd className="en-mono">{countText(snapshot.movementCount)}</dd></div>
        <div><dt>产出记录</dt><dd className="en-mono">{countText(snapshot.outputCount)}</dd></div>
        <div><dt>投料记录</dt><dd className="en-mono">{countText(snapshot.plateInputCount)}</dd></div>
        <div><dt>铜杆交接</dt><dd className="en-mono">{countText(snapshot.handoverCount)}</dd></div>
        <div className="en-run-grid-wide">
          <dt>计价顺序</dt>
          <dd className="en-mono">{snapshot.valuationOrder ?? '—'}</dd>
        </div>
      </dl>

      <div className="en-table-wrap">
        <table className="en-table en-table-compact">
          <thead>
            <tr>
              <th>物料</th>
              <th>期末数量</th>
              <th>已知价值</th>
              <th>完整性</th>
              <th>缺项</th>
            </tr>
          </thead>
          <tbody>
            {snapshot.materials.length === 0 && (
              <tr><td className="en-empty" colSpan={5}>这次运行没有读到物料。</td></tr>
            )}
            {snapshot.materials.map(item => (
              <tr key={item.materialId} className={item.complete ? undefined : 'en-row-gap'}>
                <td className="en-mono">#{item.materialId}</td>
                <td className="en-mono">{ton(item.endQuantityTon)}<span className="en-unit">吨</span></td>
                <td className="en-mono">{snapshotMoney(item)}</td>
                <td>
                  {item.complete
                    ? <span className="en-badge en-badge-complete">完整</span>
                    : <span className="en-badge en-badge-partial">有缺项</span>}
                </td>
                <td>
                  {item.missingItems.length === 0
                    ? <span className="en-muted">—</span>
                    : (
                      <ul className="en-gap-list en-cost-missing">
                        {item.missingItems.map(code => (
                          <li key={code}>
                            <code>{code}</code>
                            <span>{COST_MISSING_LABELS[code] ?? '未收录的缺项码'}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="en-hint">
        快照只记物料编号与当时的数量、金额，不存名称——名称会随主数据修改，快照要能复原当时的判断。
      </p>
    </article>
  );
}

// ------------------------------------------------------------------ 小件

function GapList({items}: {items: {code: string; message: string}[]}) {
  return (
    <ul className="en-gap-list en-cost-missing en-run-gaps">
      {items.map((item, index) => (
        <li key={`${item.code}-${index}`}><code>{item.code}</code><span>{item.message}</span></li>
      ))}
    </ul>
  );
}

/**
 * 快照里的「已知价值」。
 *
 * <p>不完整、且已知部分恰好是 0 时显示「—」：期初只有数量没有金额的物料，
 * `knownValue` 就是 0，可那个 0 的意思是「还不知道」，写成 ¥0.00 等于说这批铜板不值钱。
 * 只有「完整且确实是 0」（比如用光了的库存）才显示 ¥0.00。</p>
 */
function snapshotMoney(item: {knownValueYuan: string | null; complete: boolean}) {
  if (item.knownValueYuan == null) return <span className="en-muted">—</span>;
  if (!item.complete && Number(item.knownValueYuan) === 0) {
    return <span className="en-missing" title="有数量但没有成本依据，补齐前不给 0">—</span>;
  }
  return <>{money(item.knownValueYuan)}</>;
}

function statusBadge(status: string): string {
  if (status === 'COMPLETED') return 'en-badge-complete';
  if (status === 'INCOMPLETE') return 'en-badge-partial';
  if (status === 'FAILED') return 'en-badge-missing';
  return '';
}

/** 日期字符串是 YYYY-MM-DD，可以直接按字典序切分；同时接受逗号、中文逗号与空白。 */
function splitIds(text: string): string[] {
  return text.split(/[,，\s]+/).map(item => item.trim()).filter(Boolean);
}

function idList(ids: string[]): string {
  return ids.length === 0 ? '全部' : ids.map(value => `#${value}`).join('、');
}

function countText(value: number | null): string {
  return value == null ? '—' : `${value} 条`;
}

function when(value: string | null): string {
  if (!value) return '—';
  return value.replace('T', ' ').slice(0, 19);
}
