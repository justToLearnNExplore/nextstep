export type AgentEventKind =
  | 'plan'
  | 'step'
  | 'action'
  | 'decision'
  | 'explain'
  | 'scam_check'
  | 'open_app'
  | 'error';

/** Raw event from the native agent. `payload` is a JSON string. */
export type NativeAgentEvent = { type: 'agent' | 'service'; payload: string };

export type AgentLogEntry = {
  at: number;
  kind: AgentEventKind;
  taskId: string | null;
  data: Record<string, unknown>;
};

export type NextStepConfig = {
  apiBaseUrl?: string;
  language?: string;
  /** Firebase ID token + refresh data, so the native agent can call the backend on its own. */
  authToken?: string;
  refreshToken?: string;
  tokenExpiresAt?: number;
  firebaseApiKey?: string;
  /** Dev-only identity when Firebase is not configured. */
  deviceId?: string;
  scamCheckEnabled?: boolean;
  overlayEnabled?: boolean;
  /** Overlay strings + yes/no words for the active language (from src/i18n/locales). */
  labels?: Record<string, string | string[]>;
};

export type NextStepAgentEvents = {
  onAgentEvent: (event: NativeAgentEvent) => void;
};

/** Result of one listen. `error` is null when `text` was heard. */
export type HeardError = 'silence' | 'mic_silent' | 'mic_unavailable' | 'permission' | 'network' | 'busy' | 'cancelled';
export type Heard = { text: string | null; error: HeardError | null };
