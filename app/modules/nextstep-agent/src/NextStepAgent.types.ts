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
  authToken?: string;
  scamCheckEnabled?: boolean;
  overlayEnabled?: boolean;
  /** Overlay strings + yes/no words for the active language (from src/i18n/locales). */
  labels?: Record<string, string | string[]>;
};

export type NextStepAgentEvents = {
  onAgentEvent: (event: NativeAgentEvent) => void;
};
