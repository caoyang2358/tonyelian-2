import { useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { EquipmentDetail, EquipmentFormValues, ParameterValueType, ReferenceData } from '../../lib/api';
import './equipment.css';

const VALUE_TYPES: {value: ParameterValueType; label: string}[] = [
  {value: 'STRING', label: '文本'},
  {value: 'DECIMAL', label: '小数'},
  {value: 'INTEGER', label: '整数'},
  {value: 'BOOLEAN', label: '布尔'},
];

interface ParamRow {
  uid: string;
  key: string;
  label: string;
  valueType: ParameterValueType;
  value: string;
  unitLabel: string;
}

interface FormState {
  code: string;
  name: string;
  workshopId: string;
  responsiblePerson: string;
  enabled: boolean;
  typeCode: string;
  managementStatus: string;
  model: string;
  processId: string;
  installationLocation: string;
  manufacturer: string;
  factorySerialNo: string;
  purchasedOn: string;
  commissionedOn: string;
  expectedLifeYears: string;
  warrantyExpiresOn: string;
  parameters: ParamRow[];
  reason: string;
  operatorName: string;
}

let uidSeed = 0;
const nextUid = () => `p${++uidSeed}`;

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, char => {
    const random = Math.floor(Math.random() * 16);
    return (char === 'x' ? random : (random & 0x3) | 0x8).toString(16);
  });
}

function rememberedOperator(): string {
  try { return window.localStorage.getItem('eq-operator-name') || '设备管理员'; }
  catch { return '设备管理员'; }
}

function initialForm(detail: EquipmentDetail | null): FormState {
  const profile = detail?.profile;
  return {
    code: detail?.code ?? '',
    name: detail?.name ?? '',
    workshopId: detail?.workshopId ?? '',
    responsiblePerson: detail?.responsiblePerson ?? '',
    enabled: detail?.enabled ?? true,
    typeCode: profile?.typeCode ?? '',
    // 已有设备必须原样带出当前值：空值就是空值，不能借默认值把它变成「在用」，
    // 否则打开再保存一次就会凭空产生一条未经核实的业务事实。
    // 新增设备也不预填「在用」——该值必须由录入人按核实结果选择，否则等于替现场下了结论。
    managementStatus: detail ? (profile?.managementStatus ?? '') : '',
    model: profile?.model ?? '',
    processId: profile?.processId ?? '',
    installationLocation: profile?.installationLocation ?? '',
    manufacturer: profile?.manufacturer ?? '',
    factorySerialNo: profile?.factorySerialNo ?? '',
    purchasedOn: profile?.purchasedOn ?? '',
    commissionedOn: profile?.commissionedOn ?? '',
    expectedLifeYears: profile?.expectedLifeYears == null ? '' : String(profile.expectedLifeYears),
    warrantyExpiresOn: profile?.warrantyExpiresOn ?? '',
    parameters: (profile?.technicalParameters ?? []).map(item => ({
      uid: nextUid(),
      key: item.key,
      label: item.label,
      valueType: item.valueType,
      value: item.value == null ? '' : typeof item.value === 'boolean' ? String(item.value) : String(item.value),
      unitLabel: item.unitLabel ?? '',
    })),
    reason: '',
    operatorName: rememberedOperator(),
  };
}

function nullable(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

function toValues(form: FormState): EquipmentFormValues {
  const years = form.expectedLifeYears.trim();
  return {
    code: form.code.trim(),
    name: form.name.trim(),
    workshopId: Number(form.workshopId),
    responsiblePerson: nullable(form.responsiblePerson),
    enabled: form.enabled,
    profile: {
      typeCode: nullable(form.typeCode),
      model: nullable(form.model),
      processId: nullable(form.processId),
      installationLocation: nullable(form.installationLocation),
      manufacturer: nullable(form.manufacturer),
      factorySerialNo: nullable(form.factorySerialNo),
      purchasedOn: nullable(form.purchasedOn),
      commissionedOn: nullable(form.commissionedOn),
      expectedLifeYears: years === '' ? null : Number(years),
      warrantyExpiresOn: nullable(form.warrantyExpiresOn),
      managementStatus: (nullable(form.managementStatus) as EquipmentFormValues['profile']['managementStatus']) ?? null,
      technicalParameters: form.parameters.map(item => ({
        key: item.key.trim(),
        label: item.label.trim(),
        valueType: item.valueType,
        value: item.valueType === 'BOOLEAN'
          ? (item.value === 'true' ? true : item.value === 'false' ? false : null)
          : (item.value.trim() === '' ? null : item.value.trim()),
        unitLabel: nullable(item.unitLabel),
      })),
    },
  };
}

export interface FormDialogProps {
  detail: EquipmentDetail | null;
  options: ReferenceData | null;
  onClose: () => void;
  onSaved: (detail: EquipmentDetail, mode: 'create' | 'edit') => void;
}

export function EquipmentFormDialog({detail, options, onClose, onSaved}: FormDialogProps) {
  const mode: 'create' | 'edit' = detail ? 'edit' : 'create';
  const [form, setForm] = useState<FormState>(() => initialForm(detail));
  const [requestId] = useState(() => newRequestId());
  const [fields, setFields] = useState<Record<string, string>>({});
  const [alert, setAlert] = useState<{tone: 'error' | 'warn'; message: string; hints?: string[]} | null>(null);
  const [conflictVersion, setConflictVersion] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm(previous => ({...previous, [key]: value}));
  const errorFor = (name: string) => fields[name];
  const marked = (name: string) => (fields[name] ? true : undefined);

  const updateParam = (uid: string, patch: Partial<ParamRow>) =>
    setForm(previous => ({...previous, parameters: previous.parameters.map(item => item.uid === uid ? {...item, ...patch} : item)}));
  const removeParam = (uid: string) =>
    setForm(previous => ({...previous, parameters: previous.parameters.filter(item => item.uid !== uid)}));
  const addParam = () =>
    setForm(previous => ({...previous, parameters: [...previous.parameters, {uid: nextUid(), key: '', label: '', valueType: 'DECIMAL', value: '', unitLabel: ''}]}));

  const submit = async () => {
    setSubmitting(true); setFields({}); setAlert(null); setConflictVersion(null);
    try {
      const values = toValues(form);
      const meta = {requestId, operatorName: form.operatorName.trim() || '设备管理员'};
      const result = mode === 'create'
        ? await api.createEquipment(values, meta)
        : await api.updateEquipment(detail!.id, values, {...meta, expectedVersion: detail!.version, reason: form.reason.trim()});
      try { window.localStorage.setItem('eq-operator-name', meta.operatorName); } catch { /* 隐私模式下忽略 */ }
      onSaved(result.data, mode);
    } catch (cause: unknown) {
      if (cause instanceof ApiError) {
        setFields(cause.fields);
        const fieldCount = Object.keys(cause.fields).length;
        if (cause.code === 'VERSION_CONFLICT') {
          const current = cause.detail?.currentVersion;
          setConflictVersion(typeof current === 'number' ? current : null);
          setAlert({tone: 'warn', message: cause.message,
            hints: ['这台设备在你编辑期间被他人修改过。请关闭后重新打开详情，基于最新版本再改一次。']});
        } else if (cause.code === 'IDEMPOTENCY_CONFLICT') {
          setAlert({tone: 'error', message: cause.message, hints: ['同一请求编号不能提交不同内容。请关掉弹窗重新打开，再提交一次。']});
        } else if (fieldCount > 0) {
          setAlert({tone: 'error', message: `有 ${fieldCount} 处内容需要修正，已在对应输入框下方标出。`});
        } else {
          setAlert({tone: 'error', message: cause.message});
        }
      } else {
        setAlert({tone: 'error', message: cause instanceof Error ? cause.message : '保存失败，请稍后重试。'});
      }
    } finally {
      setSubmitting(false);
    }
  };

  const workshops = (options?.workshops ?? []).filter(item => item.enabled);
  const processes = (options?.processes ?? []).filter(item => item.enabled && item.workshopId === form.workshopId);

  return <div className="eq-overlay" role="presentation" onClick={event => { if (event.target === event.currentTarget && !submitting) onClose(); }}>
    <div className="eq-dialog-wrap" role="dialog" aria-modal="true" aria-labelledby="eq-dialog-title">
      <div className="eq-dialog-head">
        <h2 id="eq-dialog-title">{mode === 'create' ? '新增设备档案' : '更正设备档案'}</h2>
        <p>{mode === 'create'
          ? '一次填完共享字段与扩展档案，保存后列表立即可查。'
          : `当前版本 v${detail!.version}，保存后版本升为 v${detail!.version + 1} 并留下变更记录。`}</p>
      </div>

      <div className="eq-dialog-body">
        {alert && <div className={`eq-alert is-${alert.tone}`}>
          {alert.message}
          {alert.hints && <ul>{alert.hints.map(hint => <li key={hint}>{hint}</li>)}</ul>}
          {conflictVersion !== null && <p style={{margin: '6px 0 0'}}>服务器当前版本：v{conflictVersion}，你提交的版本：v{detail!.version}。</p>}
        </div>}

        <h3 className="eq-section-title">设备身份</h3>
        <div className="eq-form-row is-three">
          <label className="eq-form-field">
            <span>设备编码<em>*</em></span>
            <input value={form.code} onChange={event => set('code', event.target.value)} aria-invalid={marked('code')}
                   readOnly={mode === 'edit'} maxLength={64} placeholder="如 CAST-01" />
            {errorFor('code') ? <small className="eq-form-error">{errorFor('code')}</small>
              : <small className="eq-form-hint">{mode === 'edit' ? '编码在更正时不可修改' : '编码唯一，最长 64 字符'}</small>}
          </label>
          <label className="eq-form-field is-wide">
            <span>设备名称<em>*</em></span>
            <input value={form.name} onChange={event => set('name', event.target.value)} aria-invalid={marked('name')} maxLength={100} />
            {errorFor('name') && <small className="eq-form-error">{errorFor('name')}</small>}
          </label>
        </div>

        <div className="eq-form-row is-three">
          <label className="eq-form-field">
            <span>所属车间<em>*</em></span>
            <select value={form.workshopId} onChange={event => { set('workshopId', event.target.value); set('processId', ''); }} aria-invalid={marked('workshopId')}>
              <option value="">请选择车间</option>
              {workshops.map(item => <option value={item.id} key={item.id}>{item.name}</option>)}
            </select>
            {errorFor('workshopId') && <small className="eq-form-error">{errorFor('workshopId')}</small>}
          </label>
          <label className="eq-form-field">
            <span>责任人</span>
            <input value={form.responsiblePerson} onChange={event => set('responsiblePerson', event.target.value)} aria-invalid={marked('responsiblePerson')} maxLength={100} />
            {errorFor('responsiblePerson') && <small className="eq-form-error">{errorFor('responsiblePerson')}</small>}
          </label>
          <label className="eq-form-field">
            <span>启用状态</span>
            <select value={form.enabled ? 'true' : 'false'} onChange={event => set('enabled', event.target.value === 'true')}>
              <option value="true">已启用</option>
              <option value="false">已停用</option>
            </select>
            <small className="eq-form-hint">是否参与业务，与档案中的管理状态相互独立。</small>
          </label>
        </div>

        <h3 className="eq-section-title">设备档案</h3>
        <div className="eq-form-row is-three">
          <label className="eq-form-field">
            <span>设备类型<em>*</em></span>
            <select value={form.typeCode} onChange={event => set('typeCode', event.target.value)} aria-invalid={marked('profile.typeCode')}>
              <option value="">请选择类型</option>
              {(options?.equipmentTypes ?? []).map(item => <option value={item.code} key={item.code}>{item.name}</option>)}
            </select>
            {errorFor('profile.typeCode') && <small className="eq-form-error">{errorFor('profile.typeCode')}</small>}
          </label>
          <label className="eq-form-field">
            <span>管理状态<em>*</em></span>
            <select value={form.managementStatus} onChange={event => set('managementStatus', event.target.value)} aria-invalid={marked('profile.managementStatus')}>
              <option value="">请选择状态</option>
              {(options?.equipmentManagementStatuses ?? []).map(item => <option value={item.code} key={item.code}>{item.name}</option>)}
            </select>
            {errorFor('profile.managementStatus')
              ? <small className="eq-form-error">{errorFor('profile.managementStatus')}</small>
              : <small className="eq-form-hint">设备当前所处阶段，按实际核实填写，不从启用状态推定。</small>}
          </label>
          <label className="eq-form-field">
            <span>型号</span>
            <input value={form.model} onChange={event => set('model', event.target.value)} aria-invalid={marked('profile.model')} maxLength={150} />
            {errorFor('profile.model') && <small className="eq-form-error">{errorFor('profile.model')}</small>}
          </label>
        </div>

        <div className="eq-form-row is-three">
          <label className="eq-form-field">
            <span>安装位置</span>
            <input value={form.installationLocation} onChange={event => set('installationLocation', event.target.value)} aria-invalid={marked('profile.installationLocation')} maxLength={255} />
            {errorFor('profile.installationLocation') && <small className="eq-form-error">{errorFor('profile.installationLocation')}</small>}
          </label>
          <label className="eq-form-field">
            <span>制造厂家</span>
            <input value={form.manufacturer} onChange={event => set('manufacturer', event.target.value)} aria-invalid={marked('profile.manufacturer')} maxLength={150} />
            {errorFor('profile.manufacturer') && <small className="eq-form-error">{errorFor('profile.manufacturer')}</small>}
          </label>
          <label className="eq-form-field">
            <span>出厂编号</span>
            <input value={form.factorySerialNo} onChange={event => set('factorySerialNo', event.target.value)} aria-invalid={marked('profile.factorySerialNo')} maxLength={100} />
            {errorFor('profile.factorySerialNo') && <small className="eq-form-error">{errorFor('profile.factorySerialNo')}</small>}
          </label>
        </div>

        <div className="eq-form-row is-three">
          <label className="eq-form-field">
            <span>购置日期</span>
            <input type="date" value={form.purchasedOn} onChange={event => set('purchasedOn', event.target.value)} aria-invalid={marked('profile.purchasedOn')} />
            {errorFor('profile.purchasedOn') && <small className="eq-form-error">{errorFor('profile.purchasedOn')}</small>}
          </label>
          <label className="eq-form-field">
            <span>投用日期</span>
            <input type="date" value={form.commissionedOn} onChange={event => set('commissionedOn', event.target.value)} aria-invalid={marked('profile.commissionedOn')} />
            {errorFor('profile.commissionedOn') && <small className="eq-form-error">{errorFor('profile.commissionedOn')}</small>}
          </label>
          <label className="eq-form-field">
            <span>预计使用年限</span>
            <input type="number" min={1} step={1} value={form.expectedLifeYears}
                   onChange={event => set('expectedLifeYears', event.target.value)} aria-invalid={marked('profile.expectedLifeYears')} placeholder="年" />
            {errorFor('profile.expectedLifeYears') && <small className="eq-form-error">{errorFor('profile.expectedLifeYears')}</small>}
          </label>
        </div>

        <div className="eq-form-row is-three">
          <label className="eq-form-field">
            <span>质保到期日</span>
            <input type="date" value={form.warrantyExpiresOn} onChange={event => set('warrantyExpiresOn', event.target.value)} aria-invalid={marked('profile.warrantyExpiresOn')} />
            {errorFor('profile.warrantyExpiresOn') && <small className="eq-form-error">{errorFor('profile.warrantyExpiresOn')}</small>}
          </label>
          <label className="eq-form-field">
            <span>所属工序</span>
            <select value={form.processId} onChange={event => set('processId', event.target.value)} aria-invalid={marked('profile.processId')} disabled={processes.length === 0}>
              <option value="">{processes.length === 0 ? '该车间暂无工序' : '未指定'}</option>
              {processes.map(item => <option value={item.id} key={item.id}>{item.name}</option>)}
            </select>
            {errorFor('profile.processId') && <small className="eq-form-error">{errorFor('profile.processId')}</small>}
          </label>
        </div>

        <h3 className="eq-section-title">技术参数</h3>
        <div className="eq-params-editor">
          {form.parameters.length === 0 && <p className="eq-param-empty">还没有技术参数。需要记录温度、压力、转速时点下面的按钮添加。</p>}
          {form.parameters.map((item, index) => <div key={item.uid}>
            <div className="eq-param-row">
              <input value={item.key} onChange={event => updateParam(item.uid, {key: event.target.value})}
                     placeholder="参数名 如 temperature" aria-label="参数名"
                     aria-invalid={fields[`profile.technicalParameters[${index}].key`] ? true : undefined} />
              <input value={item.label} onChange={event => updateParam(item.uid, {label: event.target.value})}
                     placeholder="显示名 如 熔体温度" aria-label="参数显示名" />
              <select value={item.valueType} onChange={event => updateParam(item.uid, {valueType: event.target.value as ParameterValueType, value: ''})} aria-label="值类型">
                {VALUE_TYPES.map(type => <option value={type.value} key={type.value}>{type.label}</option>)}
              </select>
              {item.valueType === 'BOOLEAN'
                ? <select value={item.value} onChange={event => updateParam(item.uid, {value: event.target.value})} aria-label="参数值">
                  <option value="">—</option><option value="true">是</option><option value="false">否</option>
                </select>
                : <input value={item.value} onChange={event => updateParam(item.uid, {value: event.target.value})} placeholder="值" aria-label="参数值" />}
              <input value={item.unitLabel} onChange={event => updateParam(item.uid, {unitLabel: event.target.value})} placeholder="单位" aria-label="单位" maxLength={32} />
              <button type="button" className="eq-param-remove" onClick={() => removeParam(item.uid)} aria-label="删除该参数">×</button>
            </div>
            {fields[`profile.technicalParameters[${index}].key`] && <small className="eq-form-error" style={{display: 'block', marginTop: 3}}>{fields[`profile.technicalParameters[${index}].key`]}</small>}
            {fields[`profile.technicalParameters[${index}].value`] && <small className="eq-form-error" style={{display: 'block', marginTop: 3}}>{fields[`profile.technicalParameters[${index}].value`]}</small>}
          </div>)}
          <button type="button" className="eq-add-param" onClick={addParam}>+ 添加技术参数</button>
        </div>

        <h3 className="eq-section-title">提交信息</h3>
        <div className="eq-form-row">
          <label className="eq-form-field">
            <span>操作人<em>*</em></span>
            <input value={form.operatorName} onChange={event => set('operatorName', event.target.value)} aria-invalid={marked('operatorName')} maxLength={100} />
            {errorFor('operatorName') ? <small className="eq-form-error">{errorFor('operatorName')}</small>
              : <small className="eq-form-hint">会记入变更记录的经手人</small>}
          </label>
          {mode === 'edit' && <label className="eq-form-field">
            <span>更正原因<em>*</em></span>
            <input value={form.reason} onChange={event => set('reason', event.target.value)} aria-invalid={marked('reason')} maxLength={1000} placeholder="说明这次改了什么、为什么改" />
            {errorFor('reason') && <small className="eq-form-error">{errorFor('reason')}</small>}
          </label>}
        </div>
      </div>

      <div className="eq-dialog-foot">
        <span className="eq-form-hint">请求编号 {requestId.slice(0, 8)}… · 重试不会重复写入</span>
        <span className="eq-spacer" />
        <button type="button" className="button" onClick={onClose} disabled={submitting}>取消</button>
        <button type="button" className="button" onClick={submit} disabled={submitting}>
          {submitting ? '保存中…' : mode === 'create' ? '保存并新增' : '保存更正'}
        </button>
      </div>
    </div>
  </div>;
}
