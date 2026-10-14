import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

afterEach(() => {
  cleanup();
});

// jsdom has no matchMedia. Emulate width queries from window.innerWidth and notify 'change'
// listeners when a 'resize' event makes a query cross its boundary, as browsers do.
if (typeof window.matchMedia !== 'function') {
  type Listener = (event: MediaQueryListEvent) => void;
  const evaluate = (query: string) => {
    const width = window.innerWidth;
    const max = /\(max-width:\s*([\d.]+)px\)/.exec(query);
    const min = /\(min-width:\s*([\d.]+)px\)/.exec(query);
    return (!max || width <= Number(max[1])) && (!min || width >= Number(min[1]));
  };

  window.matchMedia = (query: string): MediaQueryList => {
    const listeners = new Set<Listener>();
    let lastMatches = evaluate(query);
    const onResize = () => {
      const next = evaluate(query);
      if (next === lastMatches) return;
      lastMatches = next;
      listeners.forEach((listener) => listener({ matches: next, media: query } as MediaQueryListEvent));
    };
    const add = (listener: Listener) => {
      if (listeners.size === 0) {
        lastMatches = evaluate(query);
        window.addEventListener('resize', onResize);
      }
      listeners.add(listener);
    };
    const remove = (listener: Listener) => {
      listeners.delete(listener);
      if (listeners.size === 0) window.removeEventListener('resize', onResize);
    };
    return {
      media: query,
      get matches() {
        return evaluate(query);
      },
      onchange: null,
      addEventListener: (_type: string, listener: Listener) => add(listener),
      removeEventListener: (_type: string, listener: Listener) => remove(listener),
      addListener: add,
      removeListener: remove,
      dispatchEvent: () => true
    } as unknown as MediaQueryList;
  };
}
