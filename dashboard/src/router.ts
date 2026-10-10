type View = (params: URLSearchParams) => Node | Promise<Node>;

const routes: Record<string, View> = {};
let cleanup: (() => void) | null = null;

export function route(path: string, view: View) {
  routes[path] = view;
}

/** Views can register teardown (e.g. stop polling) for when the user navigates away. */
export function onLeave(fn: () => void) {
  cleanup = fn;
}

let renderSeq = 0;

export async function render() {
  cleanup?.();
  cleanup = null;
  const seq = ++renderSeq;
  const view = routes[location.pathname] ?? routes['/'];
  const node = await view(new URLSearchParams(location.search));
  // A newer navigation happened while this (async) view was loading: drop the stale result.
  if (seq === renderSeq) document.getElementById('app')!.replaceChildren(node);
}

export function navigate(path: string) {
  history.pushState(null, '', path);
  window.scrollTo(0, 0);
  void render();
}

/** Same-origin <a data-link> clicks stay in the app. */
export function startRouter() {
  document.addEventListener('click', (e) => {
    const a = (e.target as Element).closest('a[data-link]') as HTMLAnchorElement | null;
    if (a && a.origin === location.origin && !e.metaKey && !e.ctrlKey) {
      e.preventDefault();
      navigate(a.pathname + a.search);
    }
  });
  window.addEventListener('popstate', () => void render());
  void render();
}
