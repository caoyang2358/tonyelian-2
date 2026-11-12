import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../../components/Icon';
import { MaintenanceApiError, inspectionApi, repairOrderApi } from '../../lib/maintenanceApi';
import type { InspectionDetail, InspectionItem, InspectionWriteFields } from '../../lib/maintenanceApi';
import { formatTime, faultCategoryLabels, inspectionResultLabels, label, localInputValue, orderStatusLabels } from './labels';
import { EquipmentSelect, Pager, PhotoPicker, PhotoSection, Tag, uploadPhotos, useOperatorName } from './shared';
import type { RepairHandoff } from './shared';

type Feedback = {kind: 'ok' | 'error'; text: string} | null;

function emptyFields(operatorName: string, equipmentId = ''): InspectionWriteFields {
  return {operatorName, equipmentId, inspectedAt: localInputValue(), inspectorName: operatorName, content: '', result: 'NORMAL', description: ''};
}

export function InspectionsTab({onRepairRequest}: {onRepairRequest?: (handoff: RepairHandoff) => void}) {
  const [result, setResult] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [items, setItems] = useState<InspectionItem[] | null>(null);
  const [total, setTotal] = useState(0);
  const [listError, setListError] = useState<string | null>(null);
  const [listLoading, setListLoading] = useState(true);

  const [detail, setDetail] = useState<InspectionDetail | null>(null);

  const [operator, setOperator] = useOperatorName();
  const [createFields, setCreateFields] = useState<InspectionWriteFields>(() => emptyFields(''));
  const [createPhotos, setCreatePhotos] = useState<File[]>([]);
  const [createFeedback, setCreateFeedback] = useState<Feedback>(null);
  const [creating, setCreating] = useState(false);
  const [createWithOrder, setCreateWithOrder] = useState(false);
  const [createFaultCategory, setCreateFaultCategory] = useState('');

  const [updateId, setUpdateId] = useState('');
  const [updateVersion, setUpdateVersion] = useState(0);
  const [updateReason, setUpdateReason] = useState('录入笔误更正');
  const [updateFields, setUpdateFields] = useState<InspectionWriteFields>(() => emptyFields(''));
  const [updateFeedback, setUpdateFeedback] = useState<Feedback>(null);
  const [updating, setUpdating] = useState(false);
  const [photoRefresh, setPhotoRefresh] = useState(0);

  // 首次拿到已记住的经办人后回填一次，避免覆盖用户正在输入的内容
  useEffect(() => { if (operator) setCreateFields(fields => fields.operatorName ? fields : {...fields, operatorName: operator, inspectorName: operator}); }, [operator]);

  const loadList = useCallback(async (targetPage = page, targetSize = pageSize) => {
    setListLoading(true);
    try {
      const data = await inspectionApi.list({result: result || undefined}, targetPage, targetSize);
      if (data.items.length === 0 && targetPage > 1 && data.total > 0) {
        const lastPage = Math.max(1, Math.ceil(data.total / targetSize));
        if (lastPage !== targetPage) { setPage(lastPage); return; }
      }
      setItems(data.items);
      setTotal(data.total);
      setListError(null);
    } catch (error) {
      setListError(error instanceof MaintenanceApiError ? error.message : '读取失败，请重试。');
    } finally {
      setListLoading(false);
    }
  }, [result, page, pageSize]);

  useEffect(() => { void loadList(); }, [loadList]);

  const openDetail = useCallback(async (inspectionId: string) => {
    try {
      const data = await inspectionApi.detail(inspectionId);
      setDetail(data);
      setUpdateId(data.inspectionId);
      setUpdateVersion(data.version);
      setUpdateFields({
        operatorName: data.operatorName, equipmentId: data.equipmentId,
        inspectedAt: data.inspectedAt.slice(0, 16), inspectorName: data.inspectorName,
        content: data.content, result: data.result, description: data.description ?? '',
      });
    } catch (error) {
      setUpdateFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '读取详情失败，请重试。'});
    }
  }, []);

  const submitCreate = useCallback(async () => {
    if (!createFields.equipmentId) { setCreateFeedback({kind: 'error', text: '请选择巡检设备。'}); return; }
    if (!createFields.operatorName.trim() || !createFields.inspectorName.trim()) { setCreateFeedback({kind: 'error', text: '请填写经办人与巡检人。'}); return; }
    if (!createFields.content.trim()) { setCreateFeedback({kind: 'error', text: '请填写巡检内容。'}); return; }
    setCreating(true);
    try {
      setOperator(createFields.operatorName);
      const data = await inspectionApi.create(createFields);
      // 现场照片依赖巡检编号，只能等记录落库后再上传；上传失败不回滚已登记的巡检，只提示补传。
      const photos = createPhotos.length
        ? await uploadPhotos('INSPECTION', data.inspectionId, createFields.operatorName.trim(), createPhotos)
        : {uploaded: 0, failed: []};
      // 判定异常且勾选了"同时报修"时，顺手建一张工单；建单失败不回滚巡检，只提示去详情重试。
      let orderNote = '';
      let orderFailed = false;
      if (data.result === 'ABNORMAL' && createWithOrder) {
        try {
          const order = await repairOrderApi.create({
            sourceType: 'INSPECTION', sourceId: data.inspectionId, equipmentId: createFields.equipmentId,
            operatorName: createFields.operatorName.trim(), faultCategory: createFaultCategory || null,
            description: `${createFields.content.trim()}${createFields.description.trim() ? `｜${createFields.description.trim()}` : ''}`,
            reporterName: createFields.inspectorName.trim(),
          });
          orderNote = ` 已同步生成维修工单 ${order.orderNo}，当前「待提交」，需到维修工单页签提交送审。`;
        } catch (error) {
          orderFailed = true;
          orderNote = ` 但维修工单未生成：${error instanceof MaintenanceApiError ? error.message : '接口异常'}，`
            + '可在下方详情点「以此巡检报修」重试。';
        }
      }
      setCreateFeedback({
        kind: photos.failed.length || orderFailed ? 'error' : 'ok',
        text: `巡检记录 ${data.inspectionId} 已登记，结果「${label(inspectionResultLabels, data.result)}」`
          + (photos.uploaded ? `，已上传 ${photos.uploaded} 张现场照片。` : '。')
          + (photos.failed.length ? ` 有 ${photos.failed.length} 张未上传成功：${photos.failed.join('；')}，可在下方"更正巡检"里补传。` : '')
          + orderNote,
      });
      setCreatePhotos([]);
      setCreateWithOrder(false);
      setCreateFaultCategory('');
      setCreateFields(emptyFields(createFields.operatorName, createFields.equipmentId));
      await loadList();
      await openDetail(data.inspectionId);
    } catch (error) {
      setCreateFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '登记失败，请重试。'});
    } finally {
      setCreating(false);
    }
  }, [createFields, createPhotos, createWithOrder, createFaultCategory, loadList, setOperator, openDetail]);

  const submitUpdate = useCallback(async () => {
    if (!updateId) { setUpdateFeedback({kind: 'error', text: '请先在列表中选择要更正的巡检记录。'}); return; }
    if (!updateReason.trim()) { setUpdateFeedback({kind: 'error', text: '更正必须填写原因，便于追溯。'}); return; }
    if (!updateFields.content.trim()) { setUpdateFeedback({kind: 'error', text: '巡检内容为必填（整份保存语义，留空会被拒绝）。'}); return; }
    setUpdating(true);
    try {
      setOperator(updateFields.operatorName);
      await inspectionApi.update(updateId, updateVersion, updateReason.trim(), updateFields);
      setUpdateFeedback({kind: 'ok', text: `巡检记录 ${updateId} 已更正。`});
      setPhotoRefresh(value => value + 1);
      await Promise.all([loadList(), openDetail(updateId)]);
    } catch (error) {
      if (error instanceof MaintenanceApiError && error.code === 'VERSION_CONFLICT') {
        setUpdateFeedback({kind: 'error', text: `${error.message} 该记录刚被他人更正，请重新选择后再提交。`});
        await loadList();
      } else {
        setUpdateFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '更正失败，请重试。'});
      }
    } finally {
      setUpdating(false);
    }
  }, [updateId, updateVersion, updateReason, updateFields, loadList, openDetail, setOperator]);

  return <>
    <section className="panel" aria-label="巡检记录列表">
      <div className="panel-heading"><div><h2>巡检记录<span className="panel-count">{items ? `${total} 条` : '等待读取'}</span></h2><p>判为异常可同步报修</p></div></div>
      <div className="filters">
        <label className="select-field"><span>巡检结果</span>
          <select value={result} onChange={event => {setResult(event.target.value); setPage(1);}}>
            <option value="">全部结果</option>
            {Object.entries(inspectionResultLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
          </select></label>
        <button className="reset-filter" type="button" onClick={() => {setResult(''); setPage(1);}}>清除筛选</button>
        <button className="button" type="button" onClick={() => void loadList(page, pageSize)} disabled={listLoading}>{listLoading ? '刷新中…' : '刷新'}</button>
      </div>
      {listError ? <div className="state-error" role="alert"><div className="state-icon"><Icon name="alert" size={24} /></div><div><h3>暂时无法读取巡检记录</h3><p>{listError}</p></div><button className="button" type="button" onClick={() => void loadList()} disabled={listLoading}>重试读取</button></div>
        : listLoading && !items ? <div className="loading-state" role="status"><span className="spinner" /><span>正在读取巡检记录…</span></div>
        : items && items.length === 0 ? <div className="empty-state" role="status"><div className="empty-icon"><Icon name="operations" size={30} /></div><h3>当前没有巡检记录</h3><p>可在下方登记第一条巡检。</p></div>
        : items && <div className="table-region" role="region" aria-label="巡检记录表" tabIndex={0}><table>
          <thead><tr><th className="number-column">编号</th><th>设备</th><th>巡检时间</th><th>巡检人</th><th>结果</th><th>内容</th><th>现场照片</th><th>工单进度</th></tr></thead>
          <tbody>{items.map(item => <tr key={item.inspectionId} className={updateId === item.inspectionId ? 'ops-selected' : ''} onClick={() => void openDetail(item.inspectionId)}>
            <td className="row-number">{item.inspectionId}</td>
            <td>{item.equipmentName}</td>
            <td>{formatTime(item.inspectedAt)}</td>
            <td>{item.inspectorName}</td>
            <td><span className={`ops-tag ${item.result === 'ABNORMAL' ? 'ops-tag-red' : 'ops-tag-green'}`}>{label(inspectionResultLabels, item.result)}</span></td>
            <td className="ops-cell-text">{item.content}</td>
            <td>{item.photoCount > 0 ? `${item.photoCount} 张` : <span className="missing-value">无</span>}</td>
            <td>{item.repairOrderStatuses?.length
              ? <><Tag value={item.repairOrderStatuses[0]} map={orderStatusLabels} />
                  {item.repairOrderStatuses.length > 1 && <span className="missing-value"> 等{item.repairOrderStatuses.length}张</span>}</>
              : item.result === 'ABNORMAL'
                ? <span className="ops-tag ops-tag-amber">待报修</span>
                : <span className="missing-value">—</span>}</td>
          </tr>)}</tbody>
        </table></div>}
      {items && items.length > 0 && <Pager page={page} pageSize={pageSize} total={total} unit="条"
        onPage={next => setPage(next)} onPageSize={size => {setPageSize(size); setPage(1);}} />}
      <div className="table-footer"><span role="status">点击行查看详情并预填更正表单</span><span><Icon name="clock" size={14} />{items ? `共 ${total} 条` : '等待读取'}</span></div>
    </section>

    <div className="ops-two-column">
      <section className="panel" aria-label="登记巡检">
        <div className="panel-heading"><div><h2>登记巡检</h2></div></div>
        <div className="ops-form">
          <div className="ops-form-row">
            <EquipmentSelect label="设备" value={createFields.equipmentId} onChange={next => setCreateFields({...createFields, equipmentId: next})} />
            <label className="select-field"><span>巡检时间</span>
              <input type="datetime-local" value={createFields.inspectedAt} onChange={event => setCreateFields({...createFields, inspectedAt: event.target.value})} /></label>
          </div>
          <div className="ops-form-row">
            <label className="select-field"><span>巡检人</span>
              <input value={createFields.inspectorName} onChange={event => setCreateFields({...createFields, inspectorName: event.target.value})} size={8} placeholder="请填写姓名" /></label>
            <label className="select-field"><span>结果</span>
              <select value={createFields.result} onChange={event => setCreateFields({...createFields, result: event.target.value})}>
                {Object.entries(inspectionResultLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
              </select></label>
          </div>
          <label className="ops-text-field"><span>巡检内容（必填）</span>
            <textarea rows={2} value={createFields.content} onChange={event => setCreateFields({...createFields, content: event.target.value})} placeholder="例如：炉体轴承部位温度偏高，伴有异响" /></label>
          <label className="ops-text-field"><span>补充说明（选填）</span>
            <textarea rows={2} value={createFields.description} onChange={event => setCreateFields({...createFields, description: event.target.value})} placeholder="例如：轴承可能缺油，建议安排检修" /></label>
          {createFields.result === 'ABNORMAL' && <div className="ops-check-row">
            <label className="ops-check">
              <input type="checkbox" checked={createWithOrder} onChange={event => setCreateWithOrder(event.target.checked)} />
              <span>同时提交维修工单</span>
            </label>
            {createWithOrder ? <>
              <select value={createFaultCategory} aria-label="故障类别"
                onChange={event => setCreateFaultCategory(event.target.value)}>
                <option value="">故障类别（可选）</option>
                {Object.entries(faultCategoryLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
              </select>
              <span className="ops-check-note">工单从「待提交」开始，需到维修工单页签提交送审</span>
            </> : <span className="ops-check-note">不勾选则只留巡检记录，之后可在下方详情点「以此巡检报修」</span>}
          </div>}
          <PhotoPicker files={createPhotos} onChange={setCreatePhotos} disabled={creating} />
          <div className="ops-form-actions">
            <button className="button button-primary" type="button" onClick={() => void submitCreate()} disabled={creating}>{creating ? '登记中…' : '登记巡检'}</button>
          </div>
          {createFeedback && <p className={createFeedback.kind === 'ok' ? 'ops-feedback-ok' : 'ops-feedback-error'} role={createFeedback.kind === 'ok' ? 'status' : 'alert'}>{createFeedback.text}</p>}
        </div>
      </section>

      <section className="panel" aria-label="更正巡检">
        <div className="panel-heading"><div><h2>更正巡检</h2><p>整份保存；原值留痕、来源快照不被改写</p></div></div>
        {updateId ? <div className="ops-form">
          <p className="ops-form-context">记录 <strong>#{updateId}</strong>{detail?.operatorName ? ` · 原登记人 ${detail.operatorName}` : ''}</p>
          <div className="ops-form-row">
            <label className="select-field"><span>巡检时间</span>
              <input type="datetime-local" value={updateFields.inspectedAt} onChange={event => setUpdateFields({...updateFields, inspectedAt: event.target.value})} /></label>
            <label className="select-field"><span>巡检人</span>
              <input value={updateFields.inspectorName} onChange={event => setUpdateFields({...updateFields, inspectorName: event.target.value})} size={8} /></label>
            <label className="select-field"><span>结果</span>
              <select value={updateFields.result} onChange={event => setUpdateFields({...updateFields, result: event.target.value})}>
                {Object.entries(inspectionResultLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
              </select></label>
          </div>
          <label className="ops-text-field"><span>巡检内容（必填）</span>
            <textarea rows={2} value={updateFields.content} onChange={event => setUpdateFields({...updateFields, content: event.target.value})} /></label>
          <label className="ops-text-field"><span>补充说明</span>
            <textarea rows={2} value={updateFields.description} onChange={event => setUpdateFields({...updateFields, description: event.target.value})} /></label>
          <PhotoSection ownerType="INSPECTION" ownerId={updateId} operatorName={updateFields.operatorName || operator}
            refreshKey={photoRefresh} emptyHint="该记录还没有现场照片，可在此补传。" />
          <div className="ops-form-row">
            <label className="ops-text-field ops-grow"><span>更正原因（必填）</span>
              <input value={updateReason} onChange={event => setUpdateReason(event.target.value)} /></label>
            <label className="select-field"><span>经办人</span>
              <input value={updateFields.operatorName} onChange={event => setUpdateFields({...updateFields, operatorName: event.target.value})} size={8} placeholder="请填写姓名" /></label>
          </div>
          <div className="ops-form-actions">
            <button className="button button-primary" type="button" onClick={() => void submitUpdate()} disabled={updating}>{updating ? '提交中…' : '提交更正'}</button>
            {onRepairRequest && detail?.result === 'ABNORMAL' && <button className="button" type="button"
              onClick={() => onRepairRequest({
                sourceType: 'INSPECTION', sourceId: detail.inspectionId, equipmentId: detail.equipmentId,
                summary: `巡检 #${detail.inspectionId}｜${detail.equipmentName}｜${detail.content}`,
              })}>
              以此巡检报修
            </button>}
          </div>
          {updateFeedback && <p className={updateFeedback.kind === 'ok' ? 'ops-feedback-ok' : 'ops-feedback-error'} role={updateFeedback.kind === 'ok' ? 'status' : 'alert'}>{updateFeedback.text}</p>}
        </div>
        : <div className="empty-state"><div className="empty-icon"><Icon name="operations" size={30} /></div><h3>尚未选择记录</h3><p>在上方列表点击任意一条巡检记录。</p></div>}
      </section>
    </div>
  </>;
}
