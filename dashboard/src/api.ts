export type Severity = 'routine' | 'confirmed' | 'declined' | 'private' | 'alert' | 'done' | 'failed';

export type FeedEvent = {
  id: string;
  at: number; // epoch seconds
  kind: string;
  severity: Severity;
  title: string;
  detail: string;
};

export type Feed = {
  senior_name: string;
  language: string;
  last_seen: number;
  events: FeedEvent[];
};

const TOKEN_KEY = 'nextstep.viewer';

type Saved = { token: string; seniorName: string };

export const session = {
  get(): Saved | null {
    try {
      return JSON.parse(localStorage.getItem(TOKEN_KEY) ?? 'null') as Saved | null;
    } catch {
      return null;
    }
  },
  set(s: Saved) {
    try {
      localStorage.setItem(TOKEN_KEY, JSON.stringify(s));
    } catch {
      /* private mode: the session lasts for this tab only */
    }
  },
  clear() {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* ignore */
    }
  },
};

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init.headers ?? {}) },
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { detail?: string };
    throw new ApiError(res.status, body.detail ?? `Request failed (${res.status})`);
  }
  return (await res.json()) as T;
}

export function join(code: string, viewerName: string) {
  return call<{ viewer_token: string; senior_name: string }>('/v1/family/join', {
    method: 'POST',
    body: JSON.stringify({ code, viewer_name: viewerName }),
  });
}

export function fetchFeed(token: string) {
  return call<Feed>('/v1/family/feed?limit=150', { headers: { 'X-Viewer-Token': token } });
}
