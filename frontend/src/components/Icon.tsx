import type { ReactNode } from 'react';

export type Page = 'equipment' | 'operations' | 'knowledge' | 'network' | 'planning' | 'production' | 'statistics';
export type IconName = Page | 'arrow' | 'refresh' | 'search' | 'check' | 'alert' | 'database' | 'clock' | 'plus' | 'document' | 'edit';

export function Icon({name, size = 20}: {name: IconName; size?: number}) {
  const paths: Record<IconName, ReactNode> = {
    equipment: <><rect x="3" y="5" width="18" height="15" rx="3" /><path d="M7 5V2m10 3V2M7 10h10M7 15h5" /><circle cx="17" cy="15" r="1" /></>,
    operations: <><path d="m14 4 6 6M5 20l6-6m-6-2 7 7m2-15 3-2 5 5-2 3-6 6-5-5zM3 17l4 4" /></>,
    knowledge: <><path d="M12 5v16M3 4c4-1 6 0 9 2 3-2 5-3 9-2v15c-4-1-6 0-9 2-3-2-5-3-9-2z" /></>,
    network: <><rect x="8" y="2" width="8" height="6" rx="1" /><rect x="2" y="16" width="7" height="6" rx="1" /><rect x="15" y="16" width="7" height="6" rx="1" /><path d="M12 8v4H5v4m7-4h7v4" /></>,
    planning: <><rect x="3" y="4" width="18" height="18" rx="3" /><path d="M7 2v4m10-4v4M3 10h18m-14 4h3m4 0h3m-10 4h3" /></>,
    production: <><path d="M3 21V10l6 3V7l6 4V3h6v18zM3 21h18M7 17h1m4 0h1m4 0h1" /></>,
    statistics: <><path d="M3 3v18h18M7 16v-5m5 5V6m5 10V9" /></>,
    arrow: <path d="m9 5 7 7-7 7" />,
    refresh: <><path d="M20 7a8 8 0 1 0 0 10M20 3v5h-5" /></>,
    search: <><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 5 5" /></>,
    check: <path d="m5 12 4 4L19 6" />,
    alert: <><path d="m12 3 10 18H2zM12 9v5m0 3v.1" /></>,
    database: <><ellipse cx="12" cy="5" rx="9" ry="3" /><path d="M3 5v7c0 4 18 4 18 0V5M3 12v7c0 4 18 4 18 0v-7" /></>,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    plus: <><circle cx="12" cy="12" r="9" /><path d="M12 8v8m-4-4h8" /></>,
    document: <><path d="M6 3h8l4 4v14H6z" /><path d="M14 3v5h5M9 12h6m-6 4h6" /></>,
    edit: <><path d="M4 20h4L19 9l-4-4L4 16zM13 7l4 4" /><path d="M12 20h8" /></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}
