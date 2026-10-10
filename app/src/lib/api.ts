import { API_BASE_URL } from './agent';
import { authHeaders } from './identity';

async function request<T>(method: 'GET' | 'POST' | 'PUT', path: string, body?: unknown, timeoutMs = 45_000): Promise<T> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', ...(await authHeaders()) },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: ctrl.signal,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return (await res.json()) as T;
  } finally {
    clearTimeout(timer);
  }
}

/** JSON calls to the NextStep backend, identified as this senior. Throw on non-2xx. */
export const post = <T>(path: string, body: unknown, timeoutMs?: number) => request<T>('POST', path, body, timeoutMs);
export const put = <T>(path: string, body: unknown) => request<T>('PUT', path, body);
export const get = <T>(path: string) => request<T>('GET', path);

export type MedicineInfo = {
  readable: boolean;
  name: string | null;
  generic: string | null;
  form: string | null;
  expiry: string | null;
  expired: boolean | null;
  say_to_user: string;
  caption: string;
};

export type Profile = { display_name: string; language: string; viewers: string[] };
export type Invite = { code: string; join_url: string; expires_at: number };
