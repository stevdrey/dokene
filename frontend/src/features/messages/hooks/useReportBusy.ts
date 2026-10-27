// ABOUTME: Tells a host whether a view holds a request in flight or an unsure outcome, so it does not unmount the view
// ABOUTME: then: the outcome would be lost, and with it the retry on the same idempotency key. Reports false on unmount.
import { useEffect } from 'react';

export function useReportBusy(busy: boolean, onBusyChange?: (busy: boolean) => void): void {
  useEffect(() => {
    onBusyChange?.(busy);
  }, [busy, onBusyChange]);
  useEffect(() => () => onBusyChange?.(false), [onBusyChange]);
}
