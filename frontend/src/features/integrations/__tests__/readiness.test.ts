// ABOUTME: Tests the readiness rule that turns the two server reads into the header badge.
// ABOUTME: Only server fields may produce ready; loading, absent and failed reads have their own outcomes.
import integrationJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/integration.json?raw';
import templatesJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/templates.json?raw';
import { describe, expect, it } from 'vitest';
import type { RecordState, TemplatesState } from '../hooks/useIntegrationStatus';
import type { IntegrationResponse, TemplateResponse } from '../types';
import { readiness } from '../utils/readiness';

const integration = JSON.parse(integrationJson);
const templates = JSON.parse(templatesJson);

const loadedRecord = (record: IntegrationResponse): RecordState => ({ kind: 'loaded', record, etag: `"${record.version}"` });
const loadedTemplates = (...items: TemplateResponse[]): TemplatesState => ({ kind: 'loaded', items });
const ready: IntegrationResponse = integration.response.valid.outboundAndWebhookReady;
const sendable: TemplateResponse = templates.response.valid.approvedAndSendable;
const unverified: TemplateResponse = templates.response.valid.mappedButUnverified;

describe('readiness', () => {
  it('is ready only when every server fact holds and one template is sendable', () => {
    expect(readiness(loadedRecord(ready), loadedTemplates(unverified, sendable))).toEqual({ kind: 'ready' });
  });

  it('lists every missing fact in the fixed order', () => {
    const notReady: IntegrationResponse = integration.response.valid.disabledWithoutDisplayNumber;

    expect(readiness(loadedRecord(notReady), loadedTemplates(unverified))).toEqual({
      kind: 'notReady',
      missing: ['enabled', 'outbound', 'ownership', 'templates']
    });
  });

  it('names only the facts that are missing', () => {
    const record: IntegrationResponse = integration.response.valid.enabledWithDisplayNumber;

    expect(readiness(loadedRecord(record), loadedTemplates(sendable))).toEqual({
      kind: 'notReady',
      missing: ['outbound', 'ownership']
    });
  });

  it('counts an empty template list as no sendable template', () => {
    expect(readiness(loadedRecord(ready), loadedTemplates())).toEqual({ kind: 'notReady', missing: ['templates'] });
  });

  it.each<[string, RecordState, TemplatesState]>([
    ['the record is loading', { kind: 'loading' }, loadedTemplates(sendable)],
    ['the templates are loading', loadedRecord(ready), { kind: 'loading' }]
  ])('is checking while %s', (_label, record, list) => {
    expect(readiness(record, list)).toEqual({ kind: 'checking' });
  });

  it.each<[string, RecordState, TemplatesState]>([
    ['the templates read failed', loadedRecord(ready), { kind: 'error', message: 'x' }],
    ['the record is absent', { kind: 'absent' }, loadedTemplates(sendable)],
    ['the record is absent and templates still load', { kind: 'absent' }, { kind: 'loading' }],
    ['the record read failed', { kind: 'error', message: 'x' }, loadedTemplates(sendable)]
  ])('is undetermined when %s', (_label, record, list) => {
    expect(readiness(record, list)).toEqual({ kind: 'undetermined' });
  });
});
