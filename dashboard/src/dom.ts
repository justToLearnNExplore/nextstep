/** Tiny element builder. Text is always set via text nodes, never innerHTML: titles contain user words. */
type Child = Node | string | null | undefined | false;
type Attrs = Record<string, string | boolean | undefined | ((e: Event) => void)>;

export function h(tag: string, attrs: Attrs = {}, ...children: Child[]): HTMLElement {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === false) continue;
    if (typeof v === 'function') el.addEventListener(k.replace(/^on/, '').toLowerCase(), v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const c of children) if (c) el.append(c);
  return el;
}

/** Inline SVG icon from the sprite in icons.ts. */
export function icon(name: string, cls = 'ic'): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', cls);
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', `#i-${name}`);
  svg.append(use);
  return svg;
}

export function mount(view: Node) {
  const app = document.getElementById('app')!;
  app.replaceChildren(view);
  window.scrollTo(0, 0);
}
