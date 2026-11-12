import { useEffect, useRef, useState } from 'react';
import type { PointerEvent } from 'react';
import type { PendingPageProps } from '../../components/PendingPage';
import { ApiError, api, fetchApi } from '../../lib/api';
import type { Equipment, SensorSummary } from '../../lib/api';

type Kind = 'EQUIPMENT' | 'SENSOR';
interface Node { nodeId: string; kind: Kind; objectId: string; xPercent: string; yPercent: string }
interface Edge { edgeId: string; fromNodeId: string; toNodeId: string; label: string | null }
interface Reading { parameterId: string; value: string | null; valueText: string | null; unitCode: string | null; collectTime: string | null; qualityStatus: string | null; limitStatus: string | null; monitoringStatus: string; dataSource: string | null }
interface SensorOwner { equipmentId: string; sensorCode: string; name: string }
interface Layout {
  workshopId: string; version: number; updatedAt: string | null; nodes: Node[]; edges: Edge[];
  processSteps: string[]; latestBySensorId?: Record<string, Reading[]>;
  sensorOwners?: Record<string, SensorOwner>;
}
interface Workshop { id: string; name: string }
const prefix = '/api/v1';
const read = <T,>(path: string, signal?: AbortSignal) =>
  fetchApi(prefix + path, value => value as T, signal).then(result => result.data);
const message = (error: unknown) => error instanceof Error ? error.message : '请求失败。';

async function listEquipmentSensors(equipmentId: string, signal: AbortSignal): Promise<SensorSummary[]> {
  const items: SensorSummary[] = [];
  for (let page = 1; ; page++) {
    const {data: result} = await api.sensors({equipmentId, page, pageSize: 100}, signal);
    items.push(...result.items);
    if (!result.items.length || result.total === null || items.length >= result.total) return items;
  }
}

export function NetworkPage({back}: PendingPageProps) {
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [equipment, setEquipment] = useState<Equipment[]>([]);
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const {signal} = controller;
    setNotice('');
    setLoading(true);
    void Promise.allSettled([
      api.equipment({}, signal),
      api.referenceData(signal),
    ]).then(([devices, references]) => {
      if (signal.aborted) return;
      const rows = devices.status === 'fulfilled' ? devices.value.data.items : [];
      setLoading(false);
      setEquipment(rows);
      const options = references.status === 'fulfilled'
        ? references.value.data.workshops
        : [...new Map(rows.map(row => [row.workshopId, {id: row.workshopId, name: row.workshopName}])).values()];
      setWorkshops(options);
      if (references.status === 'rejected' || devices.status === 'rejected') {
        setNotice(!options.length ? '未能读取可用车间，请重试。'
          : devices.status === 'rejected' ? '设备清单暂不可用，可继续查看已保存的布局。'
          : '当前车间来自设备清单。');
      }
    });
    return () => controller.abort();
  }, [reload]);

  return <section className="panel network-panel">
    <header className="network-toolbar">
      <div><h2>车间示意图</h2><p>同页查看各车间的设备、测点和示意连线</p></div>
    </header>
    {loading && <p role="status">正在读取车间资料…</p>}
    {notice && <p className="network-notice" role="status">{notice} <button className="button button-small" type="button" onClick={() => setReload(value => value + 1)}>刷新资料</button></p>}
    {workshops.map(workshop => <section className="network-workshop" key={workshop.id} aria-label={workshop.name}>
      <h3 className="network-workshop-title">{workshop.name}</h3>
      <WorkshopLayout workshopId={workshop.id} equipment={equipment.filter(row => row.workshopId === workshop.id)} openEquipment={back}/>
    </section>)}
  </section>;
}

function WorkshopLayout({workshopId, equipment, openEquipment}: {
  workshopId: string; equipment: Equipment[]; openEquipment: () => void;
}) {
  const path = '/network-layouts/' + encodeURIComponent(workshopId);
  const [saved, setSaved] = useState<Layout>();
  const [draft, setDraft] = useState<Layout>();
  const [sensorList, setSensorList] = useState<{equipmentId: string; items: SensorSummary[]; error?: string}>();
  const [sensorCandidate, setSensorCandidate] = useState('');
  const [readingNotice, setReadingNotice] = useState('');
  const [status, setStatus] = useState('');
  const [operator, setOperator] = useState('');
  const [reason, setReason] = useState('');
  const [candidate, setCandidate] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [selected, setSelected] = useState<Node>();
  const [details, setDetails] = useState<{lines: string[]; hasReadings: boolean}>({lines: [], hasReadings: false});
  const [busy, setBusy] = useState(false);
  const [versionConflict, setVersionConflict] = useState(false);
  const [showSaved, setShowSaved] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const retry = useRef<{payload: string; requestId: string} | null>(null);
  const canvas = useRef<HTMLDivElement>(null);
  const drag = useRef<{id: string; startX: number; startY: number; x: number; y: number; moved: boolean} | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const {signal} = controller;
    setReadingNotice('');
    void read<Layout>(path + '?includeLatest=true', signal).catch(error => {
      if (signal.aborted || !(error instanceof ApiError) || error.status !== 503) throw error;
      setReadingNotice('测点最新值暂时无法读取。');
      return read<Layout>(path, signal);
    }).then(layout => {
      if (signal.aborted) return;
      setSaved(layout);
    }).catch(error => { if (!signal.aborted) setStatus(message(error)); });
    return () => controller.abort();
  }, [path, refresh]);

  const selectedEquipmentId = selected?.kind === 'EQUIPMENT' ? selected.objectId : undefined;
  useEffect(() => {
    setSensorCandidate('');
    setSensorList(undefined);
    if (!selectedEquipmentId) return;
    const controller = new AbortController();
    const {signal} = controller;
    void listEquipmentSensors(selectedEquipmentId, signal).then(items => {
      if (!signal.aborted) setSensorList({equipmentId: selectedEquipmentId,
        items: items.filter(row => row.equipmentId === selectedEquipmentId && row.workshopId === workshopId)});
    }).catch(error => {
      if (!signal.aborted) setSensorList({equipmentId: selectedEquipmentId, items: [], error: message(error)});
    });
    return () => controller.abort();
  }, [selectedEquipmentId, workshopId, refresh]);

  useEffect(() => {
    setDetails({lines: [], hasReadings: false});
    if (!selected || selected.kind !== 'SENSOR') return;
    const controller = new AbortController();
    const {signal} = controller;
    const load = async () => {
      const {data: info} = await api.sensorDetail(selected.objectId, signal);
      const lines = [info.purpose, info.dataSource].filter((value): value is string => Boolean(value));
      const parameters = info.parameters;
      const values = await Promise.allSettled(parameters.map(parameter =>
        api.sensorLatest(parameter.parameterId, signal)));
      return {hasReadings: true, lines: [...lines, ...parameters.map((parameter, index) => {
        const result = values[index];
        return parameter.name + ' · ' + (result.status === 'fulfilled' ? readingText(result.value.data) : '最新值暂不可用');
      })]};
    };
    void load().then(lines => { if (!signal.aborted) setDetails(lines); })
      .catch(error => { if (!signal.aborted) setDetails({lines: [message(error)], hasReadings: false}); });
    return () => controller.abort();
  }, [selected]);
  const layout = showSaved ? saved : draft ?? saved;
  if (!layout) return <p role="status">{status || '正在加载布局…'} {status && <button onClick={() => {setStatus(''); setRefresh(value => value + 1);}}>重试</button>}</p>;
  const equipmentById = new Map(equipment.map(row => [row.id, row]));
  const nodesById = new Map(layout.nodes.map(node => [node.nodeId, node]));
  const equipmentNodes = layout.nodes.filter(node => node.kind === 'EQUIPMENT');
  const equipmentNodeById = new Map<string, Node>();
  const repeatedEquipmentIds = new Set<string>();
  for (const node of equipmentNodes) {
    if (equipmentNodeById.has(node.objectId)) repeatedEquipmentIds.add(node.objectId);
    else equipmentNodeById.set(node.objectId, node);
  }
  const sensorOwner = (node: Node) => layout.sensorOwners?.[node.objectId];
  const parentNode = (sensor: Node) => equipmentNodeById.get(sensorOwner(sensor)?.equipmentId ?? '');
  const sensorsByNode = new Map<string, Node[]>();
  const orphanSensors: Node[] = [];
  for (const node of layout.nodes) {
    if (node.kind !== 'SENSOR') continue;
    const owner = parentNode(node);
    if (owner) sensorsByNode.set(owner.nodeId, [...(sensorsByNode.get(owner.nodeId) ?? []), node]);
    else orphanSensors.push(node);
  }
  const equipmentName = (id: string) => {
    const row = equipmentById.get(id);
    return row ? row.name + '（' + row.code + '）' : '设备 ' + id;
  };
  const title = (node: Node) => {
    if (node.kind === 'EQUIPMENT') return equipmentName(node.objectId);
    const sensor = sensorOwner(node);
    return sensor ? sensor.name + '（' + sensor.sensorCode + '）' : '测点 ' + node.objectId;
  };
  const anchor = (node: Node) => node.kind === 'SENSOR' ? parentNode(node) ?? node : node;
  const endpointName = (node: Node) => {
    const position = anchor(node);
    return title(node) + ' · ' + position.xPercent + '%, ' + position.yPercent + '%';
  };
  const selectedEquipment = selectedEquipmentId ? equipmentById.get(selectedEquipmentId) : undefined;
  const selectedSensorList = sensorList?.equipmentId === selectedEquipmentId ? sensorList : undefined;
  const equipmentCandidates = equipment.filter(row => !equipmentNodeById.has(row.id));
  const blockedLayout = repeatedEquipmentIds.size > 0 || orphanSensors.length > 0;
  const addEquipment = () => {
    const item = equipmentCandidates.find(row => row.id === candidate);
    if (!draft || !item) return;
    const columns = Math.max(1, Math.floor((canvas.current?.clientWidth ?? 600) / 180));
    const columnWidth = 100 / columns;
    const position = Array.from({length: columns * 4}, (_, index) => ({
      x: ((index % columns) + 0.5) * columnWidth, y: 16 + Math.floor(index / columns) * 23,
    })).find(({x, y}) => equipmentNodes.every(node =>
      Math.abs(Number(node.xPercent) - x) >= columnWidth * 0.85 || Math.abs(Number(node.yPercent) - y) >= 20));
    const node: Node = {nodeId: crypto.randomUUID(), kind: 'EQUIPMENT', objectId: item.id,
      xPercent: position?.x.toFixed(3) ?? '50', yPercent: String(position?.y ?? 50)};
    setDraft({...draft, nodes: [...draft.nodes, node]});
    setCandidate('');
    setSelected(node);
  };
  const addSensor = () => {
    const item = selectedSensorList?.items.find(row => row.sensorId === sensorCandidate);
    if (!draft || !selected || !selectedEquipment || !item) return;
    const owner = equipmentNodeById.get(item.equipmentId);
    if (!owner) return;
    const node: Node = {nodeId: crypto.randomUUID(), kind: 'SENSOR', objectId: item.sensorId,
      xPercent: owner.xPercent, yPercent: owner.yPercent};
    setDraft({...draft, nodes: [...draft.nodes, node], sensorOwners: {
      ...draft.sensorOwners, [item.sensorId]: {equipmentId: item.equipmentId, sensorCode: item.sensorCode, name: item.name},
    }});
    setSensorCandidate('');
  };
  const removeNode = (node: Node) => {
    if (!draft) return;
    if (node.kind === 'EQUIPMENT' && !repeatedEquipmentIds.has(node.objectId) && sensorsByNode.has(node.nodeId)) {
      setStatus('请先移除这台设备下的测点标记，再移除设备。');
      return;
    }
    const attachedEdges = draft.edges.filter(edge => edge.fromNodeId === node.nodeId || edge.toNodeId === node.nodeId);
    if (attachedEdges.length && !window.confirm('移除「' + title(node) + '」会同时移除 ' + attachedEdges.length + ' 条关联连线。确定移除？')) return;
    setDraft({...draft, nodes: draft.nodes.filter(item => item.nodeId !== node.nodeId),
      edges: draft.edges.filter(edge => !attachedEdges.includes(edge))});
    if (selected?.nodeId === node.nodeId) setSelected(undefined);
    if (from === node.nodeId) setFrom('');
    if (to === node.nodeId) setTo('');
    setStatus('');
  };
  const move = (event: PointerEvent<HTMLButtonElement>) => {
    if (!drag.current || !canvas.current || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    const {startX, startY, x, y, id} = drag.current;
    if (Math.hypot(event.clientX - startX, event.clientY - startY) < 3 && !drag.current.moved) return;
    drag.current.moved = true;
    const bounds = canvas.current.getBoundingClientRect();
    const percent = (value: number) => Math.min(100, Math.max(0, value)).toFixed(3);
    const xPercent = percent(x + 100 * (event.clientX - startX) / bounds.width);
    const yPercent = percent(y + 100 * (event.clientY - startY) / bounds.height);
    const attached = sensorsByNode.get(id) ?? [];
    setDraft(current => current && {...current, nodes: current.nodes.map(node =>
      node.nodeId === id || attached.some(sensor => sensor.nodeId === node.nodeId) ? {...node, xPercent, yPercent} : node)});
  };
  const save = async () => {
    if (!draft || !operator.trim() || busy || versionConflict || blockedLayout) return;
    const content = {operatorName: operator.trim(), expectedVersion: draft.version, reason: reason.trim() || null, nodes: draft.nodes, edges: draft.edges};
    const payload = JSON.stringify(content);
    if (retry.current?.payload !== payload) retry.current = {payload, requestId: crypto.randomUUID()};
    setBusy(true);
    try {
      const response = await fetchApi(prefix + path, value => value as Layout, undefined, (input, init) =>
        fetch(input, {...init, method: 'PUT', headers: {'Content-Type': 'application/json', Accept: 'application/json'},
          body: JSON.stringify({...content, requestId: retry.current?.requestId})}));
      setSaved(response.data);
      setDraft(undefined);
      retry.current = null;
      setReason('');
      setRefresh(value => value + 1);
      setStatus('布局已保存。');
    } catch (error) {
      const conflict = error instanceof ApiError && error.code === 'VERSION_CONFLICT';
      setVersionConflict(conflict);
      setStatus(conflict ? '其他人已保存了新布局。当前草稿仍保留，不能直接覆盖。' : message(error));
    } finally {
      setBusy(false);
    }
  };

  const loadSaved = async () => {
    setBusy(true);
    try {
      setSaved(await read<Layout>(path));
      setSelected(undefined);
      setShowSaved(true);
      setStatus('正在查看他人已保存的布局。你的草稿仍保留。');
    } catch (error) {
      setStatus(message(error));
    } finally {
      setBusy(false);
    }
  };

  return <>
    <div className="network-process"><strong>工艺顺序</strong>
      {layout.processSteps.length ? <ol>{layout.processSteps.map(step => <li key={step}>{step}</li>)}</ol> : <span>暂无工艺信息</span>}
    </div>
    <div className="network-toolbar network-map-toolbar">
      <div><strong>车间布局</strong><p>{layout.version ? '版本 ' + layout.version : '尚未保存布局'}</p></div>
      <div className="network-map-actions">
        <span className="network-count">{layout.nodes.length} 个节点 · {layout.edges.length} 条连线</span>
        {draft && <span className="network-edit-badge">{showSaved ? '已保存的布局' : '正在编辑'}</span>}
        {!draft && <button type="button" className="button button-primary" onClick={() => {setDraft(layout); setStatus('');}}>编辑布局</button>}
      </div>
    </div>
    {status && <p className="network-notice" role="status">{status}</p>}
    {repeatedEquipmentIds.size > 0 && <p className="network-notice" role="status">
      此布局有重复设备标记：{[...repeatedEquipmentIds].map(equipmentName).join('、')}。请保留每台设备的一个标记，并核对相关连线。
    </p>}
    {orphanSensors.length > 0 && <p className="network-notice" role="status">
      待归属测点：{orphanSensors.map(node => {
        const owner = sensorOwner(node);
        return title(node) + (owner ? ' → ' + equipmentName(owner.equipmentId) : '（所属设备未找到）');
      }).join('、')}。请添加对应设备或移除这些标记。
    </p>}
    <div className="network-diagram">
    <div className="network-board">
    <div className="network-legend"><span>设备</span><span>测点</span><span>示意连线</span></div>

    <div className="network-canvas" ref={canvas}>
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        {layout.edges.map(edge => {
          const a = nodesById.get(edge.fromNodeId);
          const b = nodesById.get(edge.toNodeId);
          if (!a || !b || orphanSensors.includes(a) || orphanSensors.includes(b)) return null;
          const start = anchor(a);
          const end = anchor(b);
          return <line key={edge.edgeId} x1={start.xPercent} y1={start.yPercent} x2={end.xPercent} y2={end.yPercent}/>;
        })}
      </svg>
      {equipmentNodes.map(node => <div className="network-device-group" key={node.nodeId}
        data-selected={selected?.nodeId === node.nodeId || sensorsByNode.get(node.nodeId)?.some(sensor => sensor.nodeId === selected?.nodeId)}
        style={{left: node.xPercent + '%', top: node.yPercent + '%'}}>
      <button type="button" className="network-node"
        aria-pressed={selected?.nodeId === node.nodeId} data-editing={Boolean(draft) && !busy && !showSaved}
        onPointerDown={event => {
          if (!draft || busy || showSaved || event.button !== 0) return;
          drag.current = {id: node.nodeId, startX: event.clientX, startY: event.clientY,
            x: Number(node.xPercent), y: Number(node.yPercent), moved: false};
          event.currentTarget.setPointerCapture(event.pointerId);
        }} onPointerMove={move} onPointerCancel={() => {drag.current = null;}}
        onClick={event => {
          if (event.detail === 0 || !drag.current?.moved) setSelected(node);
          drag.current = null;
        }}>{title(node)}</button>
        {sensorsByNode.has(node.nodeId) && <div className="network-device-sensors" aria-label={title(node) + '的测点'}>
          {sensorsByNode.get(node.nodeId)?.map(sensor => <button key={sensor.nodeId} type="button" className="network-node"
            data-kind="SENSOR" aria-pressed={selected?.nodeId === sensor.nodeId} onClick={() => setSelected(sensor)}>
            {title(sensor)}
          </button>)}
        </div>}
      </div>)}
      {!equipmentNodes.length && <div className="network-empty"><strong>此车间暂无设备节点</strong><span>{draft ? '从下方选择设备，添加到画布。' : '点击编辑布局，添加设备并绘制连线。'}</span></div>}
    </div>
    </div>
    <aside aria-label="节点详情" className="network-inspector">
      <div className="network-toolbar"><h3>节点详情</h3>
        {selected && <button className="button button-small" type="button" onClick={() => setSelected(undefined)}>取消选择</button>}
      </div>
      {!selected ? <p>在画布中选择设备，查看资料和所属测点。</p> : <>
      <span className="network-node-type">{selected.kind === 'SENSOR' ? '测点' : '设备'}</span>
      <h4>{title(selected)}</h4>
      {selectedEquipment && <p>负责人 {selectedEquipment.responsiblePerson ?? '待补充'}</p>}
      {selected.kind === 'EQUIPMENT' && <>
        <button className="button button-small" type="button" onClick={openEquipment}>进入设备管理</button>
        <h4>所属测点</h4>
        {!selectedSensorList ? <p role="status">正在读取测点…</p>
          : selectedSensorList.error ? <p role="status">{selectedSensorList.error}</p>
          : selectedSensorList.items.length ? (draft && !showSaved && selectedEquipment
            ? <div className="network-sensor-picker">
              <label>可添加的测点 <select value={sensorCandidate} onChange={event => setSensorCandidate(event.target.value)} disabled={busy}>
                <option value="">请选择</option>
                {selectedSensorList.items.map(sensor => <option key={sensor.sensorId} value={sensor.sensorId}>
                  {sensor.name}（{sensor.sensorCode}）
                </option>)}
              </select></label>
              <button className="button button-small" type="button" disabled={busy || !sensorCandidate} onClick={addSensor}>添加测点</button>
            </div>
            : <ul className="network-sensor-list">{selectedSensorList.items.map(sensor =>
              <li key={sensor.sensorId}>{sensor.name}（{sensor.sensorCode}）</li>)}</ul>)
          : <p>此设备暂无测点。</p>}
      </>}
      {selected.kind === 'SENSOR' && sensorOwner(selected) &&
        <p>所属设备 {equipmentName(sensorOwner(selected)!.equipmentId)}</p>}
      {details.lines.map((line, index) => <div key={index}>{line}</div>)}
      {selected.kind === 'SENSOR' && !details.hasReadings && layout.latestBySensorId?.[selected.objectId]?.map(value =>
        <div key={value.parameterId}>参数 {value.parameterId} · {readingText(value)}</div>)}
      </>}
      {readingNotice && <p className="network-availability">{readingNotice}</p>}
    </aside>
    </div>
    {layout.edges.length > 0 && (!draft || showSaved) && <ul aria-label="示意连线">{layout.edges.map(edge => <li key={edge.edgeId}>
      {title(nodesById.get(edge.fromNodeId)!)} → {title(nodesById.get(edge.toNodeId)!)}
    </li>)}</ul>}
    {showSaved && <button className="button" type="button" onClick={() => {
      setShowSaved(false); setSelected(undefined); setStatus('已回到草稿。请核对差异，或取消编辑后以已保存的布局为起点。');
    }}>返回我的草稿</button>}
    {draft && !showSaved ? <fieldset disabled={busy} className="network-editor">
      <legend>编辑布局</legend>
      <div className="network-edit-section">
      <h3>设备和测点</h3>
      <p>拖动设备可调整位置。选中设备后，在右侧查看和添加所属测点。</p>
      <label>待添加设备 <select value={candidate} onChange={event => setCandidate(event.target.value)} disabled={!equipmentCandidates.length}>
        <option value="">{equipmentCandidates.length ? '请选择' : '暂无可添加设备'}</option>
        {equipmentCandidates.map(item => <option key={item.id} value={item.id}>{equipmentName(item.id)}</option>)}
      </select></label>
      <button className="button button-small" type="button" onClick={addEquipment} disabled={!candidate}>添加设备</button>
      <div className="network-node-list">{draft.nodes.map(node => <div key={node.nodeId}>
        <span>{title(node)} <small>{node.kind === 'EQUIPMENT' ? node.xPercent + '%, ' + node.yPercent + '%'
          : parentNode(node) ? '所属 ' + equipmentName(sensorOwner(node)!.equipmentId) : '待归属'}</small></span>
        <button className="button button-small" type="button" onClick={() => removeNode(node)}>移除</button>
      </div>)}</div>
      </div>
      <div className="network-edit-section">
      <h3>连线</h3>
      {([[from, setFrom, '起点'], [to, setTo, '终点']] as const).map(([value, setter, label]) =>
        <label key={label}>{label} <select value={value} onChange={event => setter(event.target.value)}>
          <option value="">请选择</option>
          {draft.nodes.map(node => <option key={node.nodeId} value={node.nodeId}>{endpointName(node)}</option>)}
        </select></label>)}
      <button className="button button-small" type="button" disabled={from === to || !draft.nodes.some(node => node.nodeId === from) ||
        !draft.nodes.some(node => node.nodeId === to)} onClick={() => {
          setDraft({...draft, edges: [...draft.edges, {edgeId: crypto.randomUUID(), fromNodeId: from, toNodeId: to, label: null}]});
        }}>添加连线</button>
      {draft.edges.map(edge => <div className="network-edge-row" key={edge.edgeId}>
        {endpointName(nodesById.get(edge.fromNodeId)!)} → {endpointName(nodesById.get(edge.toNodeId)!)}
        <button className="button button-small" type="button" onClick={() => setDraft({...draft, edges: draft.edges.filter(item => item.edgeId !== edge.edgeId)})}>删除连线</button>
      </div>)}
      </div>
      <div className="network-edit-section network-save-section">
      <h3>保存变更</h3>
      <label>操作人 <input value={operator} maxLength={100} onChange={event => setOperator(event.target.value)}/></label>
      <label>调整原因（选填） <input value={reason} maxLength={1000} onChange={event => setReason(event.target.value)}/></label>
      <button className="button button-primary" type="button" disabled={!operator.trim() || versionConflict || blockedLayout} onClick={() => void save()}>保存布局</button>
      <button className="button" type="button" onClick={() => {setDraft(undefined); setSelected(undefined); setStatus(''); setVersionConflict(false); retry.current = null;}}>取消编辑</button>
      {versionConflict && <button className="button button-small" type="button" onClick={() => void loadSaved()}>查看他人保存的布局</button>}
      {saved && saved.version !== draft.version && <p role="status">草稿基于版本 {draft.version}，已保存版本为 {saved.version}。</p>}
      </div>
    </fieldset> : null}
  </>;
}

function readingText(value: Reading) {
  const reading = value.value ?? value.valueText;
  return [reading === null ? '待数据' : reading, value.unitCode, value.collectTime, value.qualityStatus, value.limitStatus, value.monitoringStatus, value.dataSource]
    .filter(Boolean).join(' · ');
}
