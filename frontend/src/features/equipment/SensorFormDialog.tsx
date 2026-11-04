import { useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { SensorDetail } from '../../lib/api';
import './equipment.css';

/**
 * 新增 / 修改测点的表单弹层（S1-P03/P04）。
 *
 * <p>两处刻意的不对称：</p>
 * <ul>
 *   <li>编码在新建后可改，但只要该测点已有原始采集记录，输入框就锁死并说明原因——
 *       原始表按 {@code sensor_code} 关联，改编码等于把历史数据剪断。</li>
 *   <li>停用做成勾选框而不是删除按钮：契约没有删除接口，停用是唯一安全的退出方式，
 *       历史数据、事件与工单都保留。</li>
 * </ul>
 *
 * <p>不提供"采集来源"字段：来源由现场对接产生，页面填一个值会让"待配置"变成假象。</p>
 */

interface Draft {
  sensorCode: string;
  name: string;
  sensorType: string;
  location: string;
  purpose: string;
  enabled: boolean;
  reason: string;
}

const EMPTY: Draft = {
  sensorCode: '', name: '', sensorType: '', location: '', purpose: '', enabled: true, reason: '',
};

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

function rememberedOperator(): string {
  try { return window.localStorage.getItem('eq-operator-name')?.trim() || '设备管理员'; }
  catch { return '设备管理员'; }
}

export function SensorFormDialog({sensor, equipmentId, onClose, onSaved}: {
  sensor: SensorDetail | null;
  equipmentId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const editing = sensor !== null;
  const codeLocked = editing && sensor.hasHistory;
  const [draft, setDraft] = useState<Draft>(() => editing
    ? {
      sensorCode: sensor.sensorCode, name: sensor.name,
      sensorType: sensor.sensorType ?? '', location: sensor.location ?? '',
      purpose: sensor.purpose ?? '', enabled: sensor.enabled, reason: '',
    }
    : EMPTY);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    const sensorCode = draft.sensorCode.trim();
    const name = draft.name.trim();
    if (!sensorCode) { setError('请填写测点编码。'); return; }
    if (!name) { setError('请填写测点名称。'); return; }
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(sensorCode)) {
      setError('测点编码只能包含字母、数字、点、横线与下划线，且以字母或数字开头。');
      return;
    }

    setSaving(true);
    setError(null);
    const values = {
      sensorCode, name, equipmentId,
      sensorType: draft.sensorType.trim() || null,
      location: draft.location.trim() || null,
      purpose: draft.purpose.trim() || null,
      enabled: draft.enabled,
    };
    try {
      if (editing) {
        await api.updateSensor(sensor.sensorId, values, {
          requestId: newRequestId(), operatorName: rememberedOperator(),
          expectedVersion: sensor.version, reason: draft.reason.trim() || null,
        });
      } else {
        await api.createSensor(values, {requestId: newRequestId(), operatorName: rememberedOperator()});
      }
      onSaved();
    } catch (failure: unknown) {
      setError(failure instanceof ApiError ? failure.message : '保存失败，请稍后重试。');
    } finally {
      setSaving(false);
    }
  }

  return <div className="eq-overlay" role="presentation"
              onMouseDown={event => { if (event.target === event.currentTarget && !saving) onClose(); }}>
    <div className="eq-dialog-wrap" role="dialog" aria-modal="true" aria-label={editing ? '修改测点' : '新增测点'}>
      <div className="eq-dialog-head">
        <h2>{editing ? '修改测点' : '新增测点'}</h2>
        <p>{editing
          ? `测点 ID ${sensor.sensorId} · 版本 ${sensor.version}`
          : '保存后测点为「待配置」：还需补参数与采集映射才算接通'}</p>
      </div>

      <div className="eq-dialog-body">
        <div className="eq-form-row">
          <label className="eq-form-field">
            <span>测点编码<em>*</em></span>
            <input value={draft.sensorCode} maxLength={128} autoFocus disabled={codeLocked}
                   onChange={event => setDraft({...draft, sensorCode: event.target.value})}
                   placeholder="如 TEMP-011" />
          </label>
          <label className="eq-form-field">
            <span>测点名称<em>*</em></span>
            <input value={draft.name} maxLength={100}
                   onChange={event => setDraft({...draft, name: event.target.value})}
                   placeholder="如 炉壳温度" />
          </label>
        </div>
        {codeLocked && <p className="eq-form-hint">
          该测点已有原始采集记录，编码不可修改；如需换编码，请停用本测点后另建一个。
        </p>}

        <div className="eq-form-row">
          <label className="eq-form-field">
            <span>测点类型</span>
            <input value={draft.sensorType} maxLength={50}
                   onChange={event => setDraft({...draft, sensorType: event.target.value})}
                   placeholder="如 温度、压力、速度" />
          </label>
          <label className="eq-form-field">
            <span>安装位置</span>
            <input value={draft.location} maxLength={255}
                   onChange={event => setDraft({...draft, location: event.target.value})}
                   placeholder="如 东侧炉壳" />
          </label>
        </div>

        <div className="eq-form-row is-full">
          <label className="eq-form-field">
            <span>用途</span>
            <input value={draft.purpose} maxLength={255}
                   onChange={event => setDraft({...draft, purpose: event.target.value})}
                   placeholder="没确认就留空，不用参数名反推" />
          </label>
        </div>

        <label className="eq-sensor-switch">
          <input type="checkbox" checked={draft.enabled}
                 onChange={event => setDraft({...draft, enabled: event.target.checked})} />
          <span>启用（取消勾选即停用；停用保留全部历史数据与事件）</span>
        </label>

        {editing && <div className="eq-form-row is-full">
          <label className="eq-form-field">
            <span>修改原因</span>
            <input value={draft.reason} maxLength={1000}
                   onChange={event => setDraft({...draft, reason: event.target.value})}
                   placeholder="留档用，可留空" />
          </label>
        </div>}

        {error && <p className="eq-form-error">{error}</p>}
      </div>

      <div className="eq-dialog-foot">
        <span className="eq-spacer" />
        <button type="button" className="button" disabled={saving} onClick={onClose}>取消</button>
        <button type="button" className="button is-primary" disabled={saving}
                onClick={() => void submit()}>
          {saving ? '保存中…' : (editing ? '保存修改' : '新增测点')}
        </button>
      </div>
    </div>
  </div>;
}
