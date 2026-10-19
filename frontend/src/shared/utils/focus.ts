/**
 * True when keyboard focus has no meaningful target: it fell back to the body (or nowhere)
 * because the focused element was removed, or it still points at a detached node.
 */
export function isFocusLost(): boolean {
  const active = document.activeElement;
  return !active || active === document.body || !active.isConnected;
}
