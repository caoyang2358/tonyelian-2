import { useEffect, useRef, useState } from 'react';
import type { PointerEvent } from 'react';
import type { PendingPageProps } from '../../components/PendingPage';
import { ApiError, api, fetchApi } from '../../lib/api';

type Kind = 'EQUIPMENT' | 'SENSOR';
interface Candidate { id: string; name: string; workshopId: string; kind: Kind; code?: string; responsiblePerson?: string | null; enabled?: boolean }
interface Node { nodeId: string; kind: Kind; objectId: string; xPercent: string; yPercent: string }
interface Edge { edgeId: string; fromNodeId: string; toNodeId: string; label: string | null }
interface Reading { parameterId: string; value: string | null; valueText: string | null; unitCode: string | null; collectTime: string | null; qualityStatus: string | null; limitStatus: string | null; monitoringStatus: string; dataSource: string | null }
interface Layout {
  workshopId: string; version: number; updatedAt: string | null; nodes: Node[]; edges: Edge[];
  processSteps: string[]; latestBySensorId?: Record<string, Reading[]>;
}
interface Workshop { id: string; name: string }
const prefix = '/api/v1';
const read = <T,>(path: string, signal?: AbortSignal) =>
  fetchApi(prefix + path, value => value as T, signal).then(result => result.data);
const message = (error: unknown) => error instanceof Error ? error.message : '请求失败。';

async function listItems<T>(path: string, signal: AbortSignal): Promise<T[]> {
  const items: T[] = [];
  for (let page = 1; ; page++) {
    const separator = path.includes('?') ? '&' : '?';
    const result = await read<{items: T[]; total?: number}>(path + separator + 'page=' + page + '&pageSize=100', signal);
    items.push(...result.items);
    if (!result.items.length || result.total === undefined || items.length >= result.total) return items;
  }
}

export function NetworkPage({back}: PendingPageProps) {
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [equipment, setEquipment] = useState<Candidate[]>([]);
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const {signal} = controller;
    setNotice('');
    setLoading(true);
    void Promise.allSettled([
      api.equipment(signal),
      read<{workshops: {workshopId?: string; id?: string; name?: string; workshopName?: string}[]}>('/reference-data', signal),
    ]).then(([devices, references]) => {
      if (signal.aborted) return;
      const rows = devices.status === 'fulfilled' ? devices.value.data : [];
      setLoading(false);
      setEquipment(rows.map(row => ({...row, kind: 'EQUIPMENT'})));
      const options = references.status === 'fulfilled'
        ? references.value.workshops.map(row => ({id: String(row.workshopId ?? row.id), name: row.name ?? row.workshopName ?? ''}))
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
      <WorkshopLayout workshopId={workshop.id} equipment={equipment} openEquipment={back}/>
    </section>)}
  </section>;
}

function WorkshopLayout({workshopId, equipment, openEquipment}: {
  workshopId: string; equipment: Candidate[]; openEquipment: () => void;
}) {
  const path = '/network-layouts/' + encodeURIComponent(workshopId);
  const [saved, setSaved] = useState<Layout>();
  const [draft, setDraft] = useState<Layout>();
  const [sensors, setSensors] = useState<Candidate[]>([]);
  const [sensorNotice, setSensorNotice] = useState('');
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
    void listItems<{sensorId: string; name: string}>('/sensors?workshopId=' + encodeURIComponent(workshopId), signal)
      .then(rows => {
        if (signal.aborted) return;
        setSensors(rows.map(row => ({id: String(row.sensorId), name: row.name, workshopId, kind: 'SENSOR'})));
        setSensorNotice('');
      }).catch(() => {
        if (!signal.aborted) setSensorNotice('测点列表暂不可用，可继续编辑已读取的布局。');
      });
    return () => controller.abort();
  }, [path, workshopId, refresh]);

  useEffect(() => {
    setDetails({lines: [], hasReadings: false});
    if (!selected) return;
    const controller = new AbortController();
    const {signal} = controller;
    const objectId = encodeURIComponent(selected.objectId);
    const sensor = selected.kind === 'SENSOR';
    const load = async () => {
      const info = await read<{name?: string; code?: string; sensorCode?: string; purpose?: string; dataSource?: string}>(
        (sensor ? '/sensors/' : '/equipment/') + objectId, signal);
      const lines = [info.name, info.code ?? info.sensorCode, info.purpose, info.dataSource].filter((value): value is string => Boolean(value));
      if (!sensor) return {lines, hasReadings: false};
      const parameters = await listItems<{parameterId: string; name: string}>('/sensors/' + objectId + '/parameters', signal);
      const values = await Promise.allSettled(parameters.map(parameter =>
        read<Reading>('/sensor-parameters/' + encodeURIComponent(parameter.parameterId) + '/latest', signal)));
      return {hasReadings: true, lines: [...lines, ...parameters.map((parameter, index) => {
        const result = values[index];
        return parameter.name + ' · ' + (result.status === 'fulfilled' ? readingText(result.value) : '最新值暂不可用');
      })]};
    };
    void load().then(lines => { if (!signal.aborted) setDetails(lines); })
      .catch(error => { if (!signal.aborted) setDetails({lines: [message(error)], hasReadings: false}); });
    return () => controller.abort();
  }, [selected]);



  const layout = showSaved ? saved : draft ?? saved;
  if (!layout) return <p role="status">{status || '正在加载布局…'} {status && <button onClick={() => {setStatus(''); setRefresh(value => value + 1);}}>重试</button>}</p>;
  const candidates = [...equipment.filter(row => row.workshopId === workshopId), ...sensors];
  const title = (node: Node) => candidates.find(row => row.kind === node.kind && row.id === node.objectId)?.name
    ?? (node.kind === 'SENSOR' ? '测点 ' : '设备 ') + node.objectId;
  const endpointName = (node: Node) => title(node) + ' · ' + node.xPercent + '%, ' + node.yPercent + '%';
  const selectedEquipment = selected?.kind === 'EQUIPMENT'
    ? equipment.find(row => row.id === selected.objectId) : undefined;
  const add = () => {
    const item = candidates.find(row => row.kind + ':' + row.id === candidate);
    if (!draft || !item) return;
    const position = Array.from({length: 24}, (_, index) => ({
      x: 12 + (index % 6) * 15, y: 18 + Math.floor(index / 6) * 21,
    })).find(({x, y}) => draft.nodes.every(node =>
      Math.abs(Number(node.xPercent) - x) >= 12 || Math.abs(Number(node.yPercent) - y) >= 14));
    setDraft({...draft, nodes: [...draft.nodes, {
      nodeId: crypto.randomUUID(), kind: item.kind, objectId: item.id, xPercent: String(position?.x ?? 50), yPercent: String(position?.y ?? 50),
    }]});

  };
  const move = (event: PointerEvent<HTMLButtonElement>) => {
    if (!drag.current || !canvas.current || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    const {startX, startY, x, y, id} = drag.current;
    if (Math.hypot(event.clientX - startX, event.clientY - startY) < 3 && !drag.current.moved) return;
    drag.current.moved = true;
    const bounds = canvas.current.getBoundingClientRect();
    const percent = (value: number) => Math.min(100, Math.max(0, value)).toFixed(3);
    setDraft(current => current && {...current, nodes: current.nodes.map(node => node.nodeId === id
      ? {...node, xPercent: percent(x + 100 * (event.clientX - startX) / bounds.width),
        yPercent: percent(y + 100 * (event.clientY - startY) / bounds.height)} : node)});
  };
  const save = async () => {
    if (!draft || !operator.trim() || busy || versionConflict) return;
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
    <div className="network-diagram">
    <div className="network-board">
    <div className="network-legend"><span>设备</span><span>测点</span><span>示意连线</span></div>

    <div className="network-canvas" ref={canvas}>
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        {layout.edges.map(edge => {
          const a = layout.nodes.find(node => node.nodeId === edge.fromNodeId);
          const b = layout.nodes.find(node => node.nodeId === edge.toNodeId);
          return a && b && <line key={edge.edgeId} x1={a.xPercent} y1={a.yPercent} x2={b.xPercent} y2={b.yPercent}/>;
        })}
      </svg>
      {layout.nodes.map(node => <button key={node.nodeId} type="button" className="network-node"
        aria-pressed={selected?.nodeId === node.nodeId} data-kind={node.kind} data-editing={Boolean(draft) && !busy && !showSaved} style={{left: node.xPercent + '%', top: node.yPercent + '%'}}
        onPointerDown={event => {
          if (!draft || busy || showSaved || event.button !== 0) return;
          drag.current = {id: node.nodeId, startX: event.clientX, startY: event.clientY,
            x: Number(node.xPercent), y: Number(node.yPercent), moved: false};
          event.currentTarget.setPointerCapture(event.pointerId);
        }} onPointerMove={move} onPointerCancel={() => {drag.current = null;}}
        onClick={event => {
          if (event.detail === 0 || !drag.current?.moved) setSelected(node);
          drag.current = null;
        }}>{title(node)}</button>)}
      {!layout.nodes.length && <div className="network-empty"><strong>此车间暂无布局</strong><span>{draft ? '从下方选择设备或测点，添加到画布。' : '点击编辑布局，添加设备和测点并绘制连线。'}</span></div>}
    </div>
    </div>
    <aside aria-label="节点详情" className="network-inspector">
      <div className="network-toolbar"><h3>节点详情</h3>
        {selected && <button className="button button-small" type="button" onClick={() => setSelected(undefined)}>取消选择</button>}
      </div>
      {!selected ? <p>在画布中选择设备或测点，查看对应资料。</p> : <>
      <span className="network-node-type">{selected.kind === 'SENSOR' ? '测点' : '设备'}</span>
      <h4>{title(selected)}</h4>
      {selectedEquipment && <p>编码 {selectedEquipment.code} · 负责人 {selectedEquipment.responsiblePerson ?? '待补充'}</p>}
      {selected.kind === 'EQUIPMENT' && <button className="button button-small" type="button" onClick={openEquipment}>进入设备管理</button>}
      {details.lines.map((line, index) => <div key={index}>{line}</div>)}
      {selected.kind === 'SENSOR' && !details.hasReadings && layout.latestBySensorId?.[selected.objectId]?.map(value =>
        <div key={value.parameterId}>参数 {value.parameterId} · {readingText(value)}</div>)}
      </>}
      {sensorNotice && <p className="network-availability">{sensorNotice}</p>}
      {readingNotice && <p className="network-availability">{readingNotice}</p>}
    </aside>
    </div>
    {layout.edges.length > 0 && !draft && <ul aria-label="示意连线">{layout.edges.map(edge => <li key={edge.edgeId}>
      {title(layout.nodes.find(node => node.nodeId === edge.fromNodeId)!)} → {title(layout.nodes.find(node => node.nodeId === edge.toNodeId)!)}
    </li>)}</ul>}
    {showSaved && <button className="button" type="button" onClick={() => {
      setShowSaved(false); setSelected(undefined); setStatus('已回到草稿。请核对差异，或取消编辑后以已保存的布局为起点。');
    }}>返回我的草稿</button>}
    {draft && !showSaved ? <fieldset disabled={busy} className="network-editor">
      <legend>编辑布局</legend>
      <div className="network-edit-section">
      <h3>节点</h3>
      <p>添加设备或测点后，可直接在画布上拖动定位。</p>
      <label>候选节点 <select value={candidate} onChange={event => setCandidate(event.target.value)}>
        <option value="">请选择</option>
        {candidates.map(item => <option key={item.kind + ':' + item.id} value={item.kind + ':' + item.id}>{item.kind === 'EQUIPMENT' ? '设备' : '测点'} · {item.name}</option>)}
      </select></label>
      <button className="button button-small" type="button" onClick={add} disabled={!candidate}>添加节点</button>
      <div className="network-node-list">{draft.nodes.map(node => <div key={node.nodeId}>
        <span>{title(node)} <small>{node.xPercent}%, {node.yPercent}%</small></span>
        <button className="button button-small" type="button" onClick={() => {
          setDraft({...draft, nodes: draft.nodes.filter(item => item.nodeId !== node.nodeId),
            edges: draft.edges.filter(edge => edge.fromNodeId !== node.nodeId && edge.toNodeId !== node.nodeId)});
          if (selected?.nodeId === node.nodeId) setSelected(undefined);
        }}>移除</button>
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
        {title(draft.nodes.find(node => node.nodeId === edge.fromNodeId)!)} → {title(draft.nodes.find(node => node.nodeId === edge.toNodeId)!)}
        <button className="button button-small" type="button" onClick={() => setDraft({...draft, edges: draft.edges.filter(item => item.edgeId !== edge.edgeId)})}>删除连线</button>
      </div>)}
      </div>
      <div className="network-edit-section network-save-section">
      <h3>保存变更</h3>
      <label>操作人 <input value={operator} maxLength={100} onChange={event => setOperator(event.target.value)}/></label>
      <label>调整原因（选填） <input value={reason} maxLength={1000} onChange={event => setReason(event.target.value)}/></label>
      <button className="button button-primary" type="button" disabled={!operator.trim() || versionConflict} onClick={() => void save()}>保存布局</button>
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
