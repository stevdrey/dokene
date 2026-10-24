// ABOUTME: Ties the intent a control set when it started a read to that read, and hands it back once the read settles.
// ABOUTME: An effect left pending from an earlier render, even one where a previous read settled, can never take it.
import { useCallback, useEffect, useEffectEvent, useRef } from 'react';

/**
 * Returns `arm`, which a click handler calls with its intent (or `null`) right before it starts a read. The intent is
 * only armed by a commit that shows `busy`, and only an armed intent reaches `onSettle` when `busy` clears again, so
 * an idle effect that React runs after the click (it was still pending from a render before it) leaves it alone.
 */
export function useSettleIntent<T>(busy: boolean, onSettle: (intent: T) => void): (intent: T | null) => void {
  const pending = useRef<T | null>(null);
  const armed = useRef<T | null>(null);
  const settle = useEffectEvent(onSettle);

  useEffect(() => {
    if (busy) {
      armed.current = pending.current;
      pending.current = null;
      return;
    }
    const intent = armed.current;
    armed.current = null;
    if (intent !== null) settle(intent);
  }, [busy]);

  return useCallback((intent: T | null) => {
    pending.current = intent;
  }, []);
}
