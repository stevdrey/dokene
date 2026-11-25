// ABOUTME: Shows and changes the workspace operating currency (issue 452) over GET/PUT /api/operating-currency.
// ABOUTME: Owners and admins change it under the read ETag; other roles see it read only. Unset is the safe default.
import React, { useCallback, useEffect, useId, useState } from 'react';
import { ApiError, httpClient } from '@/shared/api/httpClient';
import { Button } from '@/shared/components/Button';

const PATH = '/api/operating-currency';
const CURRENCY_CODE = /^[A-Z]{3}$/;

export interface OperatingCurrencySetting {
  operatingCurrency: string | null;
  supportedCurrencies: string[];
  etag: string;
}

export class InvalidOperatingCurrencyResponseError extends Error {
  constructor() {
    super('La respuesta del servidor sobre la moneda no tiene el formato esperado.');
    this.name = 'InvalidOperatingCurrencyResponseError';
  }
}

/** Keeps only the two contract members and refuses any other shape or a missing strong ETag. */
export function narrowOperatingCurrency(response: { data: unknown; etag: string | null }): OperatingCurrencySetting {
  const body = response.data;
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw new InvalidOperatingCurrencyResponseError();
  }
  const { operatingCurrency, supportedCurrencies } = body as Record<string, unknown>;
  const supported =
    Array.isArray(supportedCurrencies) &&
    supportedCurrencies.every((code) => typeof code === 'string' && CURRENCY_CODE.test(code));
  const current =
    operatingCurrency === null ||
    (typeof operatingCurrency === 'string' && supported && supportedCurrencies.includes(operatingCurrency));
  if (!supported || !current || response.etag === null || !/^"(0|[1-9][0-9]*)"$/.test(response.etag)) {
    throw new InvalidOperatingCurrencyResponseError();
  }
  return {
    operatingCurrency: operatingCurrency as string | null,
    supportedCurrencies: [...(supportedCurrencies as string[])],
    etag: response.etag
  };
}

export const operatingCurrencyApi = {
  async read(signal?: AbortSignal): Promise<OperatingCurrencySetting> {
    return narrowOperatingCurrency(await httpClient.request<unknown>(PATH, { signal }));
  },

  async change(operatingCurrency: string | null, etag: string): Promise<OperatingCurrencySetting> {
    return narrowOperatingCurrency(
      await httpClient.request<unknown>(PATH, { method: 'PUT', body: { operatingCurrency }, ifMatch: etag })
    );
  }
};

/** Mirrors TENANT_UPDATE (OWNER, ADMIN). */
export function canChangeOperatingCurrency(role?: string | null): boolean {
  return role === 'OWNER' || role === 'ADMIN' || role === 'TENANT_ADMIN';
}

type LoadState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; setting: OperatingCurrencySetting };

const UNSET = '';

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message ? error.message : 'No se pudo cargar la moneda.';
}

export const OperatingCurrencySettings: React.FC<{ workspace: { tenantId: string; role: string | null } }> = ({
  workspace
}) => <OperatingCurrencyView key={workspace.tenantId} role={workspace.role} />;

const OperatingCurrencyView: React.FC<{ role: string | null }> = ({ role }) => {
  const selectId = useId();
  const [state, setState] = useState<LoadState>({ kind: 'loading' });
  const [selected, setSelected] = useState(UNSET);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<{ error: boolean; text: string } | null>(null);
  const manages = canChangeOperatingCurrency(role);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const setting = await operatingCurrencyApi.read(signal);
      setState({ kind: 'ready', setting });
      setSelected(setting.operatingCurrency ?? UNSET);
    } catch (error) {
      if (signal?.aborted) return;
      setState({ kind: 'error', message: errorMessage(error) });
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  if (state.kind === 'loading') {
    return <p role="status">Cargando la moneda del espacio…</p>;
  }
  if (state.kind === 'error') {
    return (
      <section aria-label="Moneda de operación">
        <p role="alert">{state.message}</p>
        <Button onClick={() => void load()}>Reintentar</Button>
      </section>
    );
  }

  const { setting } = state;
  const current = setting.operatingCurrency ?? UNSET;

  const save = async () => {
    setSaving(true);
    setNotice(null);
    try {
      const next = await operatingCurrencyApi.change(selected === UNSET ? null : selected, setting.etag);
      setState({ kind: 'ready', setting: next });
      setSelected(next.operatingCurrency ?? UNSET);
      setNotice({ error: false, text: 'Moneda guardada.' });
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        await load();
        setNotice({ error: true, text: 'La moneda cambió en otra sesión. Se cargó el valor actual.' });
      } else {
        setNotice({ error: true, text: errorMessage(error) });
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <section aria-label="Moneda de operación" style={{ marginBottom: 'var(--space-6, 24px)' }}>
      <h2 style={{ fontSize: 'var(--font-size-title, 1.125rem)' }}>Moneda de operación</h2>
      <p style={{ fontSize: 'var(--font-size-secondary)', color: 'var(--color-text-muted)' }}>
        La moneda en la que este espacio cotiza precios. Sin definir, los mensajes se revisan como si pudieran citar
        cualquier moneda.
      </p>
      <label htmlFor={selectId}>Moneda</label>{' '}
      <select
        id={selectId}
        value={selected}
        disabled={!manages || saving}
        onChange={(event) => setSelected(event.target.value)}
      >
        <option value={UNSET}>Sin definir</option>
        {setting.supportedCurrencies.map((code) => (
          <option key={code} value={code}>
            {code}
          </option>
        ))}
      </select>{' '}
      {manages ? (
        <Button variant="primary" onClick={() => void save()} disabled={selected === current} isLoading={saving}>
          Guardar moneda
        </Button>
      ) : (
        <p style={{ fontSize: 'var(--font-size-secondary)' }}>Solo propietarios y administradores pueden cambiarla.</p>
      )}
      {notice && (
        <p role={notice.error ? 'alert' : 'status'} aria-live="polite">
          {notice.text}
        </p>
      )}
    </section>
  );
};
