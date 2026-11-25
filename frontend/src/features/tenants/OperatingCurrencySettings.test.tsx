// ABOUTME: Tests the operating currency setting (issue 452): it reads the value with its ETag, saves under If-Match,
// ABOUTME: reloads after a 409, refuses a malformed response, and stays read only for roles without TENANT_UPDATE.
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../api/apiClient';
import { narrowOperatingCurrency, OperatingCurrencySettings } from './OperatingCurrencySettings';

const SUPPORTED = ['CAD', 'CRC', 'USD'];

function json(body: unknown, status = 200, etag?: string): Response {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (etag) headers.ETag = etag;
  return new Response(status === 409 ? '' : JSON.stringify(body), { status, headers });
}

function setting(operatingCurrency: string | null) {
  return { operatingCurrency, supportedCurrencies: SUPPORTED };
}

describe('OperatingCurrencySettings', () => {
  beforeEach(() => {
    apiClient.setCsrfToken('csrf-1');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows an unset currency and saves the chosen one under the read ETag', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_url, init) => {
      if (init?.method === 'PUT') return json(setting('USD'), 200, '"4"');
      return json(setting(null), 200, '"3"');
    });
    render(<OperatingCurrencySettings workspace={{ tenantId: 't-1', role: 'ADMIN' }} />);

    const select = await screen.findByLabelText('Moneda');
    expect(select).toHaveValue('');
    expect(screen.getByRole('button', { name: /Guardar moneda/ })).toBeDisabled();

    fireEvent.change(select, { target: { value: 'USD' } });
    fireEvent.click(screen.getByRole('button', { name: /Guardar moneda/ }));

    expect(await screen.findByText('Moneda guardada.')).toBeInTheDocument();
    const put = fetchSpy.mock.calls.find(([, init]) => init?.method === 'PUT');
    expect(put?.[0]).toBe('/api/operating-currency');
    expect(put?.[1]?.body).toBe(JSON.stringify({ operatingCurrency: 'USD' }));
    expect((put?.[1]?.headers as Record<string, string>)['If-Match']).toBe('"3"');
    expect(select).toHaveValue('USD');
  });

  it('clears the currency with null and reloads the current value after a conflict', async () => {
    let reads = 0;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_url, init) => {
      if (init?.method === 'PUT') return json(null, 409);
      reads += 1;
      return reads === 1 ? json(setting('USD'), 200, '"2"') : json(setting('CRC'), 200, '"5"');
    });
    render(<OperatingCurrencySettings workspace={{ tenantId: 't-1', role: 'OWNER' }} />);

    const select = await screen.findByLabelText('Moneda');
    fireEvent.change(select, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: /Guardar moneda/ }));

    expect(await screen.findByText('La moneda cambió en otra sesión. Se cargó el valor actual.')).toBeInTheDocument();
    const put = fetchSpy.mock.calls.find(([, init]) => init?.method === 'PUT');
    expect(put?.[1]?.body).toBe(JSON.stringify({ operatingCurrency: null }));
    await waitFor(() => expect(select).toHaveValue('CRC'));
  });

  it('is read only for a role without TENANT_UPDATE', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(setting('CAD'), 200, '"1"'));
    render(<OperatingCurrencySettings workspace={{ tenantId: 't-1', role: 'OPERATOR' }} />);

    const select = await screen.findByLabelText('Moneda');
    expect(select).toHaveValue('CAD');
    expect(select).toBeDisabled();
    expect(screen.queryByRole('button', { name: /Guardar moneda/ })).toBeNull();
  });

  it('shows an error instead of a malformed setting', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ operatingCurrency: 'AUD', supportedCurrencies: SUPPORTED }, 200, '"1"'));
    render(<OperatingCurrencySettings workspace={{ tenantId: 't-1', role: 'ADMIN' }} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('no tiene el formato esperado');
  });

  it('narrows only well formed responses with a strong ETag', () => {
    expect(narrowOperatingCurrency({ data: { ...setting('USD'), extra: 1 }, etag: '"0"' })).toEqual({
      operatingCurrency: 'USD',
      supportedCurrencies: SUPPORTED,
      etag: '"0"'
    });
    for (const [data, etag] of [
      [setting('USD'), null],
      [setting('USD'), 'W/"1"'],
      [setting('usd'), '"1"'],
      [{ operatingCurrency: null, supportedCurrencies: ['us'] }, '"1"'],
      [{ operatingCurrency: null }, '"1"'],
      [[], '"1"'],
      ['USD', '"1"']
    ] as const) {
      expect(() => narrowOperatingCurrency({ data, etag })).toThrow();
    }
  });
});
