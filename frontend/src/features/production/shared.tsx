import {type ReactNode} from 'react';
import {ApiError} from '../../lib/api';

export interface SaveFeedback {
  ok: boolean;
  message: string;
  fields?: Record<string, string>;
}

export function SaveFeedback({feedback}: {feedback: SaveFeedback}) {
  return <div className={feedback.ok ? 'state-error' : 'state-error'}
    style={feedback.ok
      ? {borderColor: '#cfe0c6', background: '#f4f9ee', color: '#4c7034'}
      : undefined}>
    <p style={{margin: 0}}>{feedback.ok ? '✓ ' : '⚠ '}{feedback.message}</p>
    {formatFields(feedback.fields)}
  </div>;
}

export interface FieldSpec {
  name: string; label: string; required?: boolean;
  type?: 'text' | 'date' | 'datetime-local' | 'select';
  options?: [string, string][];
  placeholder?: string; defaultValue?: string;
}

/**
 * 生产记录各登记表单的通用行式表单。只负责采集与回显校验错误，
 * 业务校验全部交给服务端（版本、库存、超差、归属边界）。
 */
export function FormRow({fields, hint, busy, onSubmit, submitLabel = '保存'}: {
  fields: FieldSpec[];
  hint?: string;
  busy: boolean;
  onSubmit: (values: Record<string, string>) => void | Promise<void>;
  submitLabel?: string;
}) {
  return <form className="ops-form" style={{paddingTop: 8}} onSubmit={event => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const values: Record<string, string> = {};
    for (const field of fields) {
      const raw = String(data.get(field.name) ?? '').trim();
      if (field.required && !raw) return;
      values[field.name] = raw;
    }
    void onSubmit(values);
  }}>
    {hint && <p className="ops-form-context">{hint}</p>}
    <div className="ops-form-row">
      {fields.map(field => <label key={field.name} className="select-field"
        style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>{field.label}</span>
        {field.type === 'select'
          ? <select name={field.name} defaultValue={field.defaultValue ?? ''}>
            {field.options?.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
          : <input name={field.name} type={field.type ?? 'text'} placeholder={field.placeholder}
            defaultValue={field.defaultValue ?? ''}
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5,
              padding: '0 11px', minWidth: 150}}/>}
      </label>)}
    </div>
    <div className="ops-form-actions">
      <button type="submit" className="button button-primary" disabled={busy}>
        {busy ? '保存中…' : submitLabel}
      </button>
    </div>
  </form>;
}

/** 数量小数位提示。 */
export const quantityHint = '数量单位为吨，最多三位小数；金额单位为元，最多两位小数。';

export function workshopName(workshopId: string | null | undefined): string {
  if (workshopId === '1') return '车间1 · 铜杆';
  if (workshopId === '2') return '车间2 · 铜丝';
  return workshopId ?? '—';
}

export function formatFields(fields: Record<string, string> | undefined): ReactNode {
  if (!fields || !Object.keys(fields).length) return null;
  return <ul style={{margin: '6px 0 0', paddingLeft: 18, fontSize: 10}}>
    {Object.entries(fields).map(([field, message]) =>
      <li key={field}><strong>{field}</strong>：{message}</li>)}
  </ul>;
}

export function errorFeedback(error: unknown): SaveFeedback {
  if (error instanceof ApiError) {
    return {ok: false, message: error.message, fields: error.fields};
  }
  return {ok: false, message: error instanceof Error ? error.message : '保存失败，请稍后重试。'};
}
