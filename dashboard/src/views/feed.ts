import { ApiError, type Feed, fetchFeed, session } from '../api';
import { demoFeed } from '../demo';
import { h } from '../dom';
import { navigate, onLeave } from '../router';
import { header, privacyNote, timeline } from '../timeline';

const POLL_MS = 20_000;

function page(feed: Feed, opts: { demo: boolean; onForget?: () => void }) {
  const actions = opts.demo
    ? h('a', { href: '/', 'data-link': true, class: 'btn on-dark' }, 'Exit demo')
    : h('button', { class: 'btn on-dark', type: 'button', onclick: opts.onForget }, 'Stop viewing here');
  return h(
    'div',
    { class: 'feed-page' },
    opts.demo ? h('div', { class: 'ribbon', role: 'note' }, 'Demo with sample data. A real timeline appears after your parent shares it with you.') : null,
    header(feed, actions),
    timeline(feed),
    privacyNote(feed.senior_name || 'your family member'),
  );
}

/** No (or revoked) access on this device. */
function notLinked(seniorName?: string) {
  return h(
    'div',
    { class: 'narrow' },
    h('h1', {}, seniorName ? 'Sharing has stopped' : 'No timeline on this device'),
    h(
      'p',
      { class: 'lede' },
      seniorName
        ? `${seniorName} stopped sharing this timeline, or this link is no longer valid. Ask them to send a new link.`
        : 'Open the WhatsApp link your family member sent you, or type their code on the start page.',
    ),
    h('a', { href: '/', 'data-link': true, class: 'btn primary' }, 'Go to start'),
  );
}

export function demoView() {
  return page(demoFeed(), { demo: true });
}

export async function feedView() {
  const saved = session.get();
  if (!saved) return notLinked();
  const container = h('div', { 'aria-live': 'polite' });
  const forget = () => {
    session.clear();
    navigate('/');
  };

  let timer: ReturnType<typeof setInterval> | undefined;
  let revoked = false;

  const load = async () => {
    try {
      const feed = await fetchFeed(saved.token);
      container.replaceChildren(page(feed, { demo: false, onForget: forget }));
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        revoked = true;
        session.clear();
        clearInterval(timer);
        container.replaceChildren(notLinked(saved.seniorName));
      } else if (!container.firstChild) {
        container.replaceChildren(h('div', { class: 'narrow' }, h('p', { class: 'lede' }, 'Could not load the timeline. Retrying…')));
      }
    }
  };

  container.replaceChildren(h('div', { class: 'narrow' }, h('p', { class: 'lede' }, 'Loading…')));
  await load();
  if (revoked) return container;
  // Poll only while the tab is visible: family members leave this open on their phone.
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') void load();
  }, POLL_MS);
  const onVisible = () => document.visibilityState === 'visible' && !revoked && void load();
  document.addEventListener('visibilitychange', onVisible);
  onLeave(() => {
    clearInterval(timer);
    document.removeEventListener('visibilitychange', onVisible);
  });
  return container;
}
