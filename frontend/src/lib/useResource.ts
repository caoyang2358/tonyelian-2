import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiResult } from './api';
export interface Resource<T> { data: T | null; error: string | null; loading: boolean; timestamp: string | null; reload: () => void }
export function useResource<T>(load: (signal: AbortSignal) => Promise<ApiResult<T>>): Resource<T> {
  const [state, setState] = useState<Omit<Resource<T>, 'reload'>>({data: null, error: null, loading: true, timestamp: null});
  const [version, setVersion] = useState(0), request = useRef(0);
  const reload = useCallback(() => setVersion(value => value + 1), []);
  useEffect(() => {
    const controller = new AbortController(), token = ++request.current;
    setState(previous => ({...previous, loading: true, error: null}));
    void load(controller.signal).then(result => {
      if (!controller.signal.aborted && token === request.current) setState({data: result.data, timestamp: result.timestamp, loading: false, error: null});
    }).catch((error: unknown) => {
      if (!controller.signal.aborted && token === request.current) setState(previous => ({...previous, loading: false, error: error instanceof Error ? error.message : '读取失败，请重试。'}));
    });
    return () => controller.abort();
  }, [load, version]);
  return {...state, reload};
}
