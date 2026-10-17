import { h, icon } from '../dom';
import { demoFeed } from '../demo';
import { eventRow } from '../timeline';
import { navigate } from '../router';
import { session } from '../api';

const APK_URL = 'https://github.com/justToLearnNExplore/nextstep/releases/latest/download/NextStep.apk';
const VIDEOS = [
  { label: '4-minute demo', url: 'https://youtu.be/HlGCqERawWU' },
  { label: 'All features (full walkthrough)', url: 'https://youtu.be/MHEeDlpe5HM' },
];

/** The app itself: download, demo videos and the three install steps. */
function appSection() {
  return h(
    'section',
    { class: 'app-hero', 'aria-labelledby': 'app-title' },
    h('p', { class: 'eyebrow' }, 'NextStep for Android'),
    h('h1', { id: 'app-title' }, 'An AI agent that uses your phone for you.'),
    h(
      'p',
      { class: 'lede' },
      'Say what you need, in English, Hindi or Kannada. NextStep does it inside real apps like Blinkit, Uber and WhatsApp, and asks before anything important.',
    ),
    h(
      'a',
      { href: APK_URL, class: 'btn download', download: 'NextStep.apk' },
      icon('download', 'ic'),
      h('span', {}, h('strong', {}, 'Download NextStep for Android'), h('small', {}, 'APK · Android 10 or newer · about 110 MB')),
    ),
    h(
      'div',
      { class: 'videos' },
      ...VIDEOS.map((v) => h('a', { href: v.url, class: 'btn video', target: '_blank', rel: 'noopener' }, icon('play', 'ic sm'), v.label)),
    ),
    h('p', { class: 'fine' }, 'Tip: the videos are best watched at 0.75× speed.'),
    h(
      'ol',
      { class: 'install' },
      h('li', {}, h('strong', {}, 'Download and open the APK.'), h('span', {}, ' Allow "Install unknown apps" if Android asks.')),
      h('li', {}, h('strong', {}, 'Open NextStep and say your name.'), h('span', {}, ' That is your account: no password, no OTP.')),
      h('li', {}, h('strong', {}, 'Turn on the NextStep helper'), h('span', {}, ' (Settings → Accessibility) and allow the microphone. Then tap and speak.')),
    ),
  );
}

export function landing() {
  const saved = session.get();
  const sample = demoFeed().events.filter((e) => ['confirmed', 'private', 'scam_blocked', 'share_sent'].includes(e.kind));

  const codeInput = h('input', {
    id: 'code',
    name: 'code',
    inputmode: 'text',
    autocomplete: 'off',
    autocapitalize: 'characters',
    placeholder: 'ABCD-2345',
    'aria-describedby': 'code-help',
  }) as HTMLInputElement;

  return h(
    'div',
    { class: 'landing' },
    h(
      'header',
      { class: 'top' },
      h('a', { href: '/', 'data-link': true, class: 'brand' }, h('img', { src: '/logo.svg', alt: '', width: '32', height: '32' }), 'NextStep'),
      saved ? h('a', { href: '/feed', 'data-link': true, class: 'btn ghost' }, `Open ${saved.seniorName || 'family'} timeline`) : null,
    ),
    appSection(),
    h(
      'section',
      { class: 'hero' },
      h(
        'div',
        { class: 'hero-copy' },
        h('p', { class: 'eyebrow' }, 'Family view'),
        h('h2', { class: 'hero-title' }, 'See how NextStep helps your parent, without seeing anything private.'),
        h('p', { class: 'lede' }, 'NextStep does phone tasks for older adults by voice, and stops to ask them before anything important. Here you see every task and every yes or no they gave.'),
        h(
          'div',
          { class: 'cta' },
          h('a', { href: '/demo', 'data-link': true, class: 'btn primary' }, 'See a demo', icon('arrow', 'ic sm')),
        ),
      ),
      h('div', { class: 'hero-sample', 'aria-label': 'Example timeline' }, h('p', { class: 'sample-label' }, 'Kamala, today'), h('ol', { class: 'events' }, ...sample.map(eventRow))),
    ),
    h(
      'section',
      { class: 'join-card' },
      h('h2', {}, 'Got a link or code from your parent?'),
      h('p', { id: 'code-help' }, 'Open the WhatsApp link they sent, or type the code here. No sign-up needed.'),
      h(
        'form',
        {
          class: 'inline-form',
          onsubmit: (e: Event) => {
            e.preventDefault();
            const code = codeInput.value.trim();
            if (code) navigate(`/join?code=${encodeURIComponent(code)}`);
          },
        },
        codeInput,
        h('button', { class: 'btn primary', type: 'submit' }, 'Continue'),
      ),
    ),
    h(
      'section',
      { class: 'how' },
      h('h2', {}, 'How family sharing works'),
      h(
        'ol',
        { class: 'steps' },
        h('li', {}, h('strong', {}, 'Your parent taps "Share with family"'), h('span', {}, 'in NextStep. It sends you a link on WhatsApp.')),
        h('li', {}, h('strong', {}, 'You open the link'), h('span', {}, 'and type your name, so they know who can see.')),
        h('li', {}, h('strong', {}, 'You see their timeline'), h('span', {}, 'until they tap "Stop sharing". They stay in control.')),
      ),
    ),
    h(
      'section',
      { class: 'sees' },
      h('div', { class: 'col yes' }, h('h3', {}, icon('eye', 'ic sm'), 'You see'), h('ul', {}, h('li', {}, 'Tasks NextStep did, like orders or calls'), h('li', {}, 'Every yes or no your parent gave'), h('li', {}, 'Scam messages that were stopped'), h('li', {}, 'Medicine labels read and shared'))),
      h('div', { class: 'col no' }, h('h3', {}, icon('lock', 'ic sm'), 'You never see'), h('ul', {}, h('li', {}, 'Their screen, photos or messages'), h('li', {}, 'Passwords, OTPs, PINs or card details'), h('li', {}, 'Anything after they stop sharing'))),
    ),
    h('footer', { class: 'foot' }, 'NextStep · Team Northstar · Built on Google Cloud with Gemini'),
  );
}
