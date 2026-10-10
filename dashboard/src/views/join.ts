import { ApiError, join, session } from '../api';
import { h } from '../dom';
import { navigate } from '../router';

export function joinView(params: URLSearchParams) {
  const code = params.get('code') ?? '';
  const nameInput = h('input', { id: 'viewer', name: 'viewer', autocomplete: 'name', placeholder: 'Ravi', required: true, maxlength: '40' }) as HTMLInputElement;
  const codeInput = h('input', { id: 'code', name: 'code', value: code, autocomplete: 'off', autocapitalize: 'characters', required: true }) as HTMLInputElement;
  const error = h('p', { class: 'error', role: 'alert' });
  const button = h('button', { class: 'btn primary', type: 'submit' }, 'Open timeline') as HTMLButtonElement;

  const submit = async (e: Event) => {
    e.preventDefault();
    error.textContent = '';
    if (!nameInput.value.trim()) {
      error.textContent = 'Type your name first.';
      nameInput.focus();
      return;
    }
    button.disabled = true;
    button.textContent = 'Opening…';
    try {
      const r = await join(codeInput.value, nameInput.value.trim());
      session.set({ token: r.viewer_token, seniorName: r.senior_name });
      navigate('/feed');
    } catch (err) {
      error.textContent = err instanceof ApiError ? err.message : 'Could not connect. Check your internet and try again.';
      button.disabled = false;
      button.textContent = 'Open timeline';
    }
  };

  return h(
    'div',
    { class: 'narrow' },
    h('a', { href: '/', 'data-link': true, class: 'brand' }, h('img', { src: '/logo.svg', alt: '', width: '32', height: '32' }), 'NextStep'),
    h('h1', {}, 'You were invited to a NextStep timeline'),
    h('p', { class: 'lede' }, 'Add your name so your family member can see who is viewing. No account or password.'),
    h(
      'form',
      { class: 'stack-form', onsubmit: submit, novalidate: true },
      h('label', { for: 'viewer' }, 'Your name'),
      nameInput,
      // A code from the WhatsApp link is used as-is; only ask for it when typed by hand.
      code ? h('p', { class: 'fine' }, `Invite code ${code}`) : h('label', { for: 'code' }, 'Invite code'),
      code ? null : codeInput,
      error,
      button,
    ),
    h('p', { class: 'fine' }, 'Links work once and expire after 15 minutes. If it has expired, ask for a new one.'),
  );
}
