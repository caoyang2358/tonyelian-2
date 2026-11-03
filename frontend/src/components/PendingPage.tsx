import { Icon } from './Icon';
import type { Page } from './Icon';

export interface PendingPageProps {
  item: {id: Exclude<Page, 'equipment'>; label: string; description: string; features: string[]};
  back: () => void;
}

export function PendingPage({item, back}: PendingPageProps) {
  return <section className="panel pending-panel"><div className="pending-art" aria-hidden="true"><div className="pending-orbit" /><div className="pending-glyph"><Icon name={item.id} size={36} /></div><span className="pending-spark" /></div><span className="section-kicker">功能建设计划</span><h2>{item.label}待开发</h2><p className="pending-description">{item.description}。本次工程初始化尚未实现这一业务模块。</p><div className="pending-features">{item.features.map((feature, index) => <div key={feature}><span>{String(index + 1).padStart(2, '0')}</span>{feature}</div>)}</div><button className="button button-primary" type="button" onClick={back}>查看已接入的设备<Icon name="arrow" size={16} /></button><p className="pending-note">当前可用：设备基础信息只读查询</p></section>;
}
