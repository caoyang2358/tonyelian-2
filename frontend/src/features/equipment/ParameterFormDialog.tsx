import { useEffect, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { ProcessOption, SensorParameterEditable, SensorRuleSnapshot, UnitOption } from '../../lib/api';
import './equipment.css';

/**
 * 参数表单弹层：新增（S1-P07）与配置更新（S1-P08）共用一套字段。
 *
 * <p>几处克制：</p>
 * <ul>
 *   <li>不提供"采集来源"：参数有没有真的接到采集，取决于采集映射建立，不是表单填出来的。</li>
 *   <li>新增时规则默认不配。没勾"同时配置告警规则"就传 {@code rule: null}，
 *       参数处于"未配规则"状态；这与"配了但停用"是两种页面状态，不能混为一谈。</li>
 *   <li>修改时规则必填：规则快照只能追加、不能覆盖，允许在配置更新里把规则置空，
 *       页面读回时仍会取到最后一版——会显示一份实际已不生效的规则。
 *       不想要规则就把"规则生效"取消掉，语义与数据都说得通。</li>
 *   <li>修改时编码与数据类型只读：前者是采集映射的连接键，后者决定读数怎么解析，
 *       改了会让历史读数失去解释。</li>
 * </ul>
 */

interface RuleDraft {
  normalLower: string; normalUpper: string;
  alarmLower: string; alarmUpper: string;
  triggerMode: 'SUSTAINED' | 'IMMEDIATE';
  durationSeconds: string;
  enabled: boolean;
}

interface Draft {
  parameterCode: string;
  name: string;
  dataType: string;
  unitCode: string;
  decimalPlaces: string;
  samplingIntervalSeconds: string;
  processId: string;
  enabled: boolean;
  withRule: boolean;
  rule: RuleDraft;
}

const DATA_TYPES = [
  {code: 'DECIMAL', label: '小数 DECIMAL'},
  {code: 'INTEGER', label: '整数 INTEGER'},
  {code: 'BOOLEAN', label: '布尔 BOOLEAN'},
  {code: 'STRING', label: '文本 STRING'},
];

const EMPTY_RULE: RuleDraft = {
  normalLower: '', normalUpper: '', alarmLower: '', alarmUpper: '',
  triggerMode: 'SUSTAINED', durationSeconds: '30', enabled: true,
};

const EMPTY: Draft = {
  parameterCode: '', name: '', dataType: 'DECIMAL', unitCode: '',
  decimalPlaces: '2', samplingIntervalSeconds: '', processId: '', enabled: true,
  withRule: false,
  rule: {...EMPTY_RULE},
};

const TYPE_LABELS = new Map(DATA_TYPES.map(item => [item.code, item.label]));

function fromParameter(parameter: SensorParameterEditable): Draft {
  const rule = parameter.rule;
  return {
    parameterCode: parameter.parameterCode,
    name: parameter.name,
    dataType: parameter.dataType,
    unitCode: parameter.unitCode ?? '',
    decimalPlaces: String(parameter.decimalPlaces),
    samplingIntervalSeconds: parameter.samplingIntervalSeconds == null
      ? '' : String(parameter.samplingIntervalSeconds),
    processId: parameter.processId ?? '',
    enabled: parameter.enabled,
    // 配置更新必须带规则，所以这里恒为 true；是不是"刚建立的第一版"由后端判断。
    withRule: true,
    rule: rule == null ? {...EMPTY_RULE, enabled: false} : {
      normalLower: rule.normalLower ?? '',
      normalUpper: rule.normalUpper ?? '',
      alarmLower: rule.alarmLower ?? '',
      alarmUpper: rule.alarmUpper ?? '',
      triggerMode: rule.triggerMode === 'IMMEDIATE' ? 'IMMEDIATE' : 'SUSTAINED',
      durationSeconds: rule.durationSeconds == null ? '' : String(rule.durationSeconds),
      enabled: rule.enabled,
    },
  };
}

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

function rememberedOperator(): string {
  try { return window.localStorage.getItem('eq-operator-name')?.trim() || '设备管理员'; }
  catch { return '设备管理员'; }
}

/** 空串按 null 送；后端要求可空字段也显式出现，所以不做字段省略。 */
function orNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

export function ParameterFormDialog({sensorId, sensorCode, parameter, onClose, onSaved}: {
  sensorId: string;
  sensorCode: string;
  /** 传了就是修改（S1-P08），不传就是新增（S1-P07）。 */
  parameter?: SensorParameterEditable | null;
  onClose: () => void;
  /** result 是服务端给的生效说明：改没改规则、有没有结束事件，都由它说，不靠前端猜。 */
  onSaved: (result?: {effectNote: string; ruleVersionId: string | null}) => void;
}) {
  const editing = parameter != null;
  const [draft, setDraft] = useState<Draft>(() => (parameter ? fromParameter(parameter) : EMPTY));
  const [units, setUnits] = useState<UnitOption[]>([]);
  const [processes, setProcesses] = useState<ProcessOption[]>([]);
  const [refError, setRefError] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    api.referenceData(controller.signal)
      .then(result => { setUnits(result.data.units); setProcesses(result.data.processes); })
      .catch(() => { if (!controller.signal.aborted) setRefError(true); });
    return () => controller.abort();
  }, []);

  function buildRule(): SensorRuleSnapshot | null {
    if (!draft.withRule) return null;
    const rule = draft.rule;
    const duration = rule.durationSeconds.trim();
    return {
      normalLower: orNull(rule.normalLower),
      normalUpper: orNull(rule.normalUpper),
      alarmLower: orNull(rule.alarmLower),
      alarmUpper: orNull(rule.alarmUpper),
      // 规则单位跟着参数单位走：一个参数出现两个量纲，阈值就没法解释了。
      unitCode: orNull(draft.unitCode),
      triggerMode: rule.triggerMode,
      durationSeconds: rule.triggerMode === 'IMMEDIATE' || duration === '' ? null : Number(duration),
      enabled: rule.enabled,
    };
  }

  function validate(): string | null {
    if (!editing) {
      const code = draft.parameterCode.trim();
      if (!code) return '请填写参数编码。';
      if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(code)) {
        return '参数编码只能包含字母、数字、点、横线与下划线，且以字母或数字开头。';
      }
    }
    if (!draft.name.trim()) return '请填写参数名称。';
    if (draft.decimalPlaces.trim() !== '') {
      const places = Number(draft.decimalPlaces);
      if (!Number.isInteger(places) || places < 0 || places > 10) return '小数位必须是 0 到 10 之间的整数。';
    }
    if (draft.samplingIntervalSeconds.trim() !== '') {
      const seconds = Number(draft.samplingIntervalSeconds);
      if (!Number.isInteger(seconds) || seconds <= 0) return '采样周期必须是正整数秒，或留空表示不设周期。';
    }
    if (!draft.withRule) return null;

    const bounds: Array<[string, string]> = [
      [draft.rule.normalLower, '正常下限'], [draft.rule.normalUpper, '正常上限'],
      [draft.rule.alarmLower, '报警下限'], [draft.rule.alarmUpper, '报警上限'],
    ];
    for (const [value, label] of bounds) {
      if (value.trim() !== '' && !Number.isFinite(Number(value))) return `${label}必须是数字，或留空表示不设限。`;
    }
    if (draft.rule.triggerMode === 'SUSTAINED' && draft.rule.enabled) {
      const seconds = Number(draft.rule.durationSeconds);
      if (!Number.isInteger(seconds) || seconds <= 0) return '持续触发模式需要正整数持续秒数。';
    }
    return null;
  }

  async function submit() {
    const invalid = validate();
    if (invalid) { setError(invalid); return; }

    setSaving(true);
    setError(null);
    try {
      const decimalPlaces = draft.decimalPlaces.trim() === '' ? null : Number(draft.decimalPlaces);
      const samplingIntervalSeconds = draft.samplingIntervalSeconds.trim() === ''
        ? null : Number(draft.samplingIntervalSeconds);
      const unitCode = orNull(draft.unitCode);
      const processId = draft.processId === '' ? null : draft.processId;

      if (editing && parameter) {
        const rule = buildRule();
        // 走到这里 withRule 恒为 true（编辑态没有关闭入口），这行是防御而非可达分支。
        if (!rule) { setError('配置更新必须包含规则。'); return; }
        const result = await api.updateSensorParameter(parameter.parameterId, {
          name: draft.name.trim(), unitCode, decimalPlaces, samplingIntervalSeconds,
          processId, enabled: draft.enabled, rule,
        }, {
          requestId: newRequestId(), operatorName: rememberedOperator(),
          expectedVersion: parameter.version,
        });
        onSaved({effectNote: result.data.effectNote, ruleVersionId: result.data.ruleVersionId});
        return;
      }

      await api.createSensorParameter(sensorId, {
        parameterCode: draft.parameterCode.trim(),
        name: draft.name.trim(),
        dataType: draft.dataType,
        unitCode, decimalPlaces, samplingIntervalSeconds, processId,
        enabled: draft.enabled,
        rule: buildRule(),
      }, {requestId: newRequestId(), operatorName: rememberedOperator()});
      onSaved();
    } catch (failure: unknown) {
      // 后端的 400 是字段级的，直接把字段说明带出来；只显示"校验未通过"等于让人盲改。
      if (failure instanceof ApiError) {
        const detail = Object.values(failure.fields);
        setError(detail.length > 0 ? `${failure.message}（${detail.join('；')}）` : failure.message);
      } else {
        setError('保存失败，请稍后重试。');
      }
    } finally {
      setSaving(false);
    }
  }

  const update = (patch: Partial<Draft>) => setDraft(previous => ({...previous, ...patch}));
  const updateRule = (patch: Partial<RuleDraft>) =>
    setDraft(previous => ({...previous, rule: {...previous.rule, ...patch}}));

  return <div className="eq-overlay" role="presentation"
              onMouseDown={event => { if (event.target === event.currentTarget && !saving) onClose(); }}>
    <div className="eq-dialog-wrap" role="dialog" aria-modal="true"
         aria-label={editing ? '修改参数' : '新增参数'}>
      <div className="eq-dialog-head">
        <h2>{editing ? '修改参数' : '新增参数'}</h2>
        <p>{editing
          ? `测点 ${sensorCode} · 参数 ${parameter.parameterCode} · 当前配置版本 v${parameter.version}`
          : `测点 ${sensorCode} · 保存后参数可直接被采集映射引用`}</p>
      </div>

      <div className="eq-dialog-body">
        <div className="eq-form-row">
          <label className="eq-form-field">
            <span>参数编码{editing ? '' : <em>*</em>}</span>
            <input value={draft.parameterCode} maxLength={64} autoFocus={!editing}
                   readOnly={editing} disabled={editing}
                   onChange={event => update({parameterCode: event.target.value})}
                   placeholder="如 pressure" />
          </label>
          <label className="eq-form-field">
            <span>参数名称<em>*</em></span>
            <input value={draft.name} maxLength={100} autoFocus={editing}
                   onChange={event => update({name: event.target.value})}
                   placeholder="如 出口压力" />
          </label>
        </div>
        {editing && <p className="eq-form-hint">
          编码是采集映射的连接键（sensorCode + parameterCode），创建后不可修改；要换编码请停用后另建参数。
        </p>}

        <div className="eq-form-row">
          <label className="eq-form-field">
            <span>数据类型{editing ? '' : <em>*</em>}</span>
            <select value={draft.dataType} disabled={editing}
                    onChange={event => update({dataType: event.target.value})}>
              {editing && !TYPE_LABELS.has(draft.dataType)
                ? <option value={draft.dataType}>{draft.dataType}</option>
                : null}
              {DATA_TYPES.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
            </select>
          </label>
          <label className="eq-form-field">
            <span>计量单位</span>
            <select value={draft.unitCode} onChange={event => update({unitCode: event.target.value})}
                    disabled={refError}>
              <option value="">不设单位</option>
              {units.map(unit => <option key={unit.code} value={unit.code}>{unit.name}（{unit.code}）</option>)}
            </select>
          </label>
        </div>
        {refError && <p className="eq-form-hint">单位与工序选项加载失败，可直接留空保存；稍后刷新页面再补。</p>}

        <div className="eq-form-row">
          <label className="eq-form-field">
            <span>小数位</span>
            <input type="number" min={0} max={10} value={draft.decimalPlaces}
                   onChange={event => update({decimalPlaces: event.target.value})}
                   placeholder="留空按 2 位" />
          </label>
          <label className="eq-form-field">
            <span>采样周期（秒）</span>
            <input type="number" min={1} value={draft.samplingIntervalSeconds}
                   onChange={event => update({samplingIntervalSeconds: event.target.value})}
                   placeholder="留空表示不设周期" />
          </label>
        </div>

        <div className="eq-form-row is-full">
          <label className="eq-form-field">
            <span>归属工序</span>
            <select value={draft.processId} onChange={event => update({processId: event.target.value})}
                    disabled={refError || processes.length === 0}>
              <option value="">{processes.length === 0 ? '暂无可选工序' : '不归属工序'}</option>
              {processes.map(process => <option key={process.id} value={process.id}>{process.name}</option>)}
            </select>
          </label>
        </div>

        <label className="eq-sensor-switch">
          <input type="checkbox" checked={draft.enabled}
                 onChange={event => update({enabled: event.target.checked})} />
          <span>启用（停用只影响后续判断，不删任何历史读数）</span>
        </label>

        {editing
          ? <p className="eq-form-hint">
              保存配置会带上当前这一版规则。改阈值、触发方式或规则启停会追加一版新规则；
              只改名称、精度这类字段则只推进配置版本。
            </p>
          : <label className="eq-sensor-switch">
              <input type="checkbox" checked={draft.withRule}
                     onChange={event => update({withRule: event.target.checked})} />
              <span>同时配置告警规则（不勾则参数保存为「未配规则」）</span>
            </label>}

        {draft.withRule && <div className="eq-rule-block">
          <div className="eq-form-row">
            <label className="eq-form-field">
              <span>正常下限</span>
              <input value={draft.rule.normalLower}
                     onChange={event => updateRule({normalLower: event.target.value})}
                     placeholder="留空表示不设限" />
            </label>
            <label className="eq-form-field">
              <span>正常上限</span>
              <input value={draft.rule.normalUpper}
                     onChange={event => updateRule({normalUpper: event.target.value})}
                     placeholder="留空表示不设限" />
            </label>
          </div>
          <div className="eq-form-row">
            <label className="eq-form-field">
              <span>报警下限</span>
              <input value={draft.rule.alarmLower}
                     onChange={event => updateRule({alarmLower: event.target.value})}
                     placeholder="留空表示不设限" />
            </label>
            <label className="eq-form-field">
              <span>报警上限</span>
              <input value={draft.rule.alarmUpper}
                     onChange={event => updateRule({alarmUpper: event.target.value})}
                     placeholder="留空表示不设限" />
            </label>
          </div>
          <div className="eq-form-row">
            <label className="eq-form-field">
              <span>触发模式</span>
              <select value={draft.rule.triggerMode}
                      onChange={event => updateRule({triggerMode: event.target.value as RuleDraft['triggerMode']})}>
                <option value="SUSTAINED">持续异常 SUSTAINED</option>
                <option value="IMMEDIATE">立即触发 IMMEDIATE</option>
              </select>
            </label>
            <label className="eq-form-field">
              <span>持续秒数</span>
              <input type="number" min={1} value={draft.rule.durationSeconds}
                     disabled={draft.rule.triggerMode === 'IMMEDIATE'}
                     onChange={event => updateRule({durationSeconds: event.target.value})}
                     placeholder={draft.rule.triggerMode === 'IMMEDIATE' ? '立即触发不带秒数' : '如 30'} />
            </label>
          </div>
          <label className="eq-sensor-switch">
            <input type="checkbox" checked={draft.rule.enabled}
                   onChange={event => updateRule({enabled: event.target.checked})} />
            <span>规则生效（取消后该版规则保留，但不参与判断）</span>
          </label>
        </div>}

        {error && <p className="eq-form-error">{error}</p>}
      </div>

      <div className="eq-dialog-foot">
        <span className="eq-spacer" />
        <button type="button" className="button" disabled={saving} onClick={onClose}>取消</button>
        <button type="button" className="button is-primary" disabled={saving}
                onClick={() => void submit()}>
          {saving ? '保存中…' : (editing ? '保存配置' : '新增参数')}
        </button>
      </div>
    </div>
  </div>;
}
