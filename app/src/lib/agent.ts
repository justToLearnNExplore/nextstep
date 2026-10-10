import Constants from 'expo-constants';
import { useEffect, useSyncExternalStore } from 'react';

import NextStepAgent, { type AgentLogEntry, type Heard, type NextStepConfig } from '../../modules/nextstep-agent';

import type { StringKey } from '../i18n';

export { NextStepAgent };

/** The sentence to show/speak when listening didn't produce text. */
export function heardErrorKey(error: Heard['error']): StringKey {
  switch (error) {
    case 'mic_silent':
      return 'micSilent';
    case 'mic_unavailable':
    case 'permission':
      return 'micUnavailable';
    case 'network':
      return 'networkError';
    case 'busy':
      return 'aiBusy';
    default:
      return 'didNotHear';
  }
}

export const API_BASE_URL: string =
  (Constants.expoConfig?.extra?.apiBaseUrl as string | undefined) ?? 'http://10.0.2.2:8080';

export function configureAgent(config: NextStepConfig) {
  NextStepAgent.configure({ apiBaseUrl: API_BASE_URL, ...config });
}

// ---- action log: what NextStep saw, planned and did (kept in memory, newest first) ----

const MAX_LOG = 200;
let log: AgentLogEntry[] = [];
const listeners = new Set<() => void>();

function push(entry: AgentLogEntry) {
  log = [entry, ...log].slice(0, MAX_LOG);
  listeners.forEach((l) => l());
}

/** Mount once (root layout) to start collecting native agent events. */
export function useAgentEventCollector() {
  useEffect(() => {
    const sub = NextStepAgent.addListener('onAgentEvent', (e) => {
      if (e.type !== 'agent') return;
      try {
        const p = JSON.parse(e.payload);
        push({ at: Date.now(), kind: p.kind, taskId: p.task_id ?? null, data: p.data ?? {} });
      } catch {
        // malformed event: ignore
      }
    });
    return () => sub.remove();
  }, []);
}

export function useAgentLog(): AgentLogEntry[] {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    () => log,
  );
}
