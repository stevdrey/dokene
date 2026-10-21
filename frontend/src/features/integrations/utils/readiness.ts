// ABOUTME: Derives the integration readiness badge from the two server reads, never from client input.
// ABOUTME: Ready needs enabled, outbound on, a recorded ownership proof and one sendable template.
import type { RecordState, TemplatesState } from '../hooks/useIntegrationStatus';

export type Missing = 'enabled' | 'outbound' | 'ownership' | 'templates';

export type Readiness =
  | { kind: 'checking' }
  | { kind: 'undetermined' }
  | { kind: 'ready' }
  | { kind: 'notReady'; missing: Missing[] };

export function readiness(record: RecordState, templates: TemplatesState): Readiness {
  if (record.kind === 'absent' || record.kind === 'error' || record.kind === 'sessionEnded') {
    return { kind: 'undetermined' };
  }
  if (record.kind === 'loading' || templates.kind === 'loading') return { kind: 'checking' };
  if (templates.kind === 'error') return { kind: 'undetermined' };

  const { enabled, outboundEnabled, webhookVerified } = record.record;
  const missing: Missing[] = [];
  if (!enabled) missing.push('enabled');
  if (!outboundEnabled) missing.push('outbound');
  if (!webhookVerified) missing.push('ownership');
  if (!templates.items.some((item) => item.sendable)) missing.push('templates');
  return missing.length === 0 ? { kind: 'ready' } : { kind: 'notReady', missing };
}
