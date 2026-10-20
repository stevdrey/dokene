// ABOUTME: Tests the read only template availability section against the frozen templates fixtures.
// ABOUTME: Covers the count line, the badge rule per item, and the section's own loading, error and retry.
import templatesJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/templates.json?raw';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { intentLabel } from '@/features/followups/utils/aiLabels';
import { TemplateAvailability } from '../components/TemplateAvailability';
import type { TemplateResponse } from '../types';

const templates = JSON.parse(templatesJson);
const valid: Record<string, TemplateResponse> = templates.response.valid;

function rows(): HTMLElement[] {
  return within(screen.getByRole('list')).getAllByRole('listitem');
}

describe('TemplateAvailability', () => {
  it('counts sendable items and lists them in the order returned', () => {
    const items = [valid.approvedAndSendable, valid.mappedButUnverified, valid.approvalSchemaMismatch, valid.unmapped];
    render(<TemplateAvailability state={{ kind: 'loaded', items }} onRetry={vi.fn()} />);

    expect(screen.getByRole('heading', { name: 'Plantillas de mensaje' })).toBeInTheDocument();
    expect(screen.getByText('1 de 4 plantillas listas para enviar')).toBeInTheDocument();
    expect(rows().map((row) => row.querySelector('.integration-templates__intent')?.textContent)).toEqual(
      items.map((item) => intentLabel(item.templateIntent))
    );
  });

  it.each<[string, TemplateResponse, string, string | null]>([
    ['a sendable item', valid.approvedAndSendable, 'Lista', null],
    ['a mapped item awaiting verification', valid.mappedButUnverified, 'Pendiente de aprobación', 'UNVERIFIED'],
    ['a pending item', { ...valid.mappedButUnverified, approvalStatus: 'PENDING' }, 'Pendiente de aprobación', 'PENDING'],
    ['a schema mismatch', valid.approvalSchemaMismatch, 'No disponible', 'SCHEMA_MISMATCH'],
    ['an approved item that is not sendable', { ...valid.approvedAndSendable, sendable: false }, 'No disponible', 'APPROVED'],
    ['a rejected item', { ...valid.approvedAndSendable, sendable: false, approvalStatus: 'REJECTED' }, 'No disponible', 'REJECTED'],
    ['a mapped but disabled item', { ...valid.approvedAndSendable, enabled: false, sendable: false }, 'Deshabilitada', null],
    ['an unmapped slot', valid.unmapped, 'Sin asignar', null]
  ])('labels %s', (_label, item, badge, providerStatus) => {
    render(<TemplateAvailability state={{ kind: 'loaded', items: [item] }} onRetry={vi.fn()} />);

    const row = rows()[0];
    expect(within(row).getByText(badge)).toBeInTheDocument();
    const titled = row.querySelector('[title]');
    if (providerStatus === null) {
      expect(titled).toBeNull();
    } else {
      expect(titled).toHaveAttribute('title', providerStatus);
      expect(within(row).getByText(`Estado del proveedor: ${providerStatus}`)).toHaveClass('sr-only');
    }
  });

  it('shows the one slot list fixture with nothing ready', () => {
    render(<TemplateAvailability state={{ kind: 'loaded', items: templates.list.valid.oneSlotPerIntent.items }} onRetry={vi.fn()} />);

    expect(screen.getByText('0 de 1 plantillas listas para enviar')).toBeInTheDocument();
    expect(within(rows()[0]).getByText('Sin asignar')).toBeInTheDocument();
  });

  it('offers nothing editable', () => {
    render(<TemplateAvailability state={{ kind: 'loaded', items: Object.values(valid) }} onRetry={vi.fn()} />);

    expect(screen.queryAllByRole('textbox')).toHaveLength(0);
    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
  });

  it('marks its region busy while loading', () => {
    render(<TemplateAvailability state={{ kind: 'loading' }} onRetry={vi.fn()} />);

    expect(screen.getByText('Cargando plantillas…').closest('[aria-busy="true"]')).not.toBeNull();
  });

  it('shows its own error and retries only through its button', () => {
    const onRetry = vi.fn();
    render(<TemplateAvailability state={{ kind: 'error', message: 'No se pudo leer.' }} onRetry={onRetry} />);

    expect(screen.getByText('No se pudo leer.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
