import type { Feed, FeedEvent, Severity } from './api';
import { h, icon } from './dom';

const KIND_ICON: Record<string, string> = {
  task_started: 'play',
  task_done: 'check',
  task_failed: 'x',
  task_stopped: 'stop',
  task_declined: 'x',
  confirmed: 'check',
  declined: 'x',
  private: 'lock',
  blocked: 'shield',
  scam_blocked: 'shield',
  risky_screen: 'eye',
  screen_explained: 'eye',
  medicine_read: 'pill',
  share_sent: 'check',
  share_declined: 'x',
  viewer_added: 'user',
  sharing_stopped: 'user',
};

/** Events whose detail is the senior's decision; shown as an ink stamp, the page's signature. */
const DECISION_KINDS = new Set(['confirmed', 'declined', 'share_sent', 'share_declined', 'task_declined']);

const DAY_MS = 86_400_000;

export function clock(at: number) {
  return new Date(at * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

export function ago(at: number) {
  const s = Math.max(0, Date.now() / 1000 - at);
  if (s < 90) return 'just now';
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} days ago`;
}

function dayLabel(d: Date) {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const diff = Math.round((start.getTime() - new Date(d).setHours(0, 0, 0, 0)) / DAY_MS);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Yesterday';
  return d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'short' });
}

function groupByDay(events: FeedEvent[]) {
  const groups: { label: string; events: FeedEvent[] }[] = [];
  for (const e of events) {
    const label = dayLabel(new Date(e.at * 1000));
    const last = groups.at(-1);
    if (last && last.label === label) last.events.push(e);
    else groups.push({ label, events: [e] });
  }
  return groups;
}

function marker(kind: string, severity: Severity) {
  return h('span', { class: `marker sev-${severity}` }, icon(KIND_ICON[kind] ?? 'flag'));
}

function stamp(text: string, severity: Severity, at: number) {
  const tone = severity === 'confirmed' ? 'yes' : 'no';
  return h('span', { class: `stamp stamp-${tone}` }, icon(tone === 'yes' ? 'check' : 'x', 'ic sm'), `${text} · ${clock(at)}`);
}

export function eventRow(e: FeedEvent) {
  const isDecision = DECISION_KINDS.has(e.kind);
  return h(
    'li',
    { class: 'event' },
    h('time', { class: 'time', datetime: new Date(e.at * 1000).toISOString() }, clock(e.at)),
    marker(e.kind, e.severity),
    h(
      'div',
      { class: 'body' },
      h('p', { class: 'title' }, e.title),
      isDecision && e.detail ? stamp(e.detail, e.severity, e.at) : null,
      e.kind === 'private' ? h('span', { class: 'stamp stamp-private' }, icon('lock', 'ic sm'), 'Private step') : null,
      !isDecision && e.detail ? h('p', { class: 'detail' }, e.detail) : null,
    ),
  );
}

function summary(events: FeedEvent[]) {
  const count = (pred: (e: FeedEvent) => boolean) => events.filter(pred).length;
  const tasks = count((e) => e.kind === 'task_started');
  const yes = count((e) => e.kind === 'confirmed' || e.kind === 'share_sent');
  const no = count((e) => e.kind === 'declined' || e.kind === 'share_declined' || e.kind === 'task_declined');
  const priv = count((e) => e.kind === 'private');
  const parts = [
    tasks ? `${tasks} task${tasks === 1 ? '' : 's'}` : '',
    yes ? `${yes} yes` : '',
    no ? `${no} no` : '',
    priv ? `${priv} private step${priv === 1 ? '' : 's'}` : '',
  ].filter(Boolean);
  return parts.join(' · ');
}

/** Alerts from the last 7 days, newest first: what a family member may want to act on. */
function alerts(events: FeedEvent[]) {
  const since = Date.now() / 1000 - 7 * 86400;
  return events.filter((e) => e.severity === 'alert' && e.at >= since);
}

export function header(feed: Feed, extra?: Node) {
  const online = Date.now() / 1000 - feed.last_seen < 15 * 60;
  const name = feed.senior_name || 'Your family member';
  return h(
    'header',
    { class: 'bar' },
    h(
      'div',
      { class: 'bar-inner' },
      h('img', { src: '/logo.svg', alt: '', width: '36', height: '36' }),
      h('div', { class: 'bar-title' }, h('strong', {}, `${name}'s NextStep`), h('span', { class: 'status' }, h('span', { class: `dot ${online ? 'on' : 'off'}` }), online ? `Active ${ago(feed.last_seen)}` : `Last active ${ago(feed.last_seen)}`)),
      extra ?? null,
    ),
  );
}

export function timeline(feed: Feed) {
  const name = feed.senior_name || 'They';
  const wrap = h('main', { class: 'feed' });
  const urgent = alerts(feed.events);
  if (urgent.length) {
    wrap.append(
      h(
        'section',
        { class: 'alerts', 'aria-label': 'Needs a look' },
        h('h2', { class: 'section-title' }, 'Needs a look'),
        ...urgent.map((e) =>
          h('article', { class: 'alert' }, icon(KIND_ICON[e.kind] ?? 'flag', 'ic lg'), h('div', {}, h('p', { class: 'title' }, `${e.title} · ${clock(e.at)}`), e.detail ? h('p', { class: 'detail' }, e.detail) : null)),
        ),
      ),
    );
  }

  if (!feed.events.length) {
    wrap.append(
      h('section', { class: 'empty' }, h('h2', {}, 'Nothing yet'), h('p', {}, `When ${name} asks NextStep for help, each step and every yes or no will appear here.`)),
    );
    return wrap;
  }

  for (const g of groupByDay(feed.events)) {
    wrap.append(
      h(
        'section',
        { class: 'day' },
        h('div', { class: 'day-head' }, h('h2', { class: 'section-title' }, g.label), h('span', { class: 'day-sum' }, summary(g.events))),
        h('ol', { class: 'events' }, ...g.events.map(eventRow)),
      ),
    );
  }
  return wrap;
}

export function privacyNote(name: string) {
  return h(
    'footer',
    { class: 'privacy' },
    icon('lock'),
    h('p', {}, `You see what NextStep did and what ${name} decided. You never see ${name}'s screen, photos, messages, passwords, OTPs or PINs.`),
  );
}
