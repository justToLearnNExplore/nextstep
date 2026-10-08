import { API_BASE_URL } from './agent';

/** JSON POST to the NextStep backend. Throws on non-2xx with a short message. */
export async function post<T>(path: string, body: unknown, timeoutMs = 45_000): Promise<T> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(`${API_BASE_URL}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: ctrl.signal,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return (await res.json()) as T;
  } finally {
    clearTimeout(timer);
  }
}

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
