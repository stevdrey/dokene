// ABOUTME: Tests the integration edit form in isolation: fields, client validation, focus and the disable confirmation.
// ABOUTME: Request bodies are compared with the frozen integration.json request fixtures.
import integrationJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/integration.json?raw';
import { act, fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { IntegrationForm } from '../components/IntegrationForm';
import type { IntegrationResponse } from '../types';

const integration = JSON.parse(integrationJson);
const stored: IntegrationResponse = integration.response.valid.enabledWithDisplayNumber;

function renderForm(overrides: Partial<ComponentProps<typeof IntegrationForm>> = {}) {
  const props: ComponentProps<typeof IntegrationForm> = {
    initial: stored,
    submitting: false,
    autoFocus: true,
    stale: null,
    onSubmit: vi.fn(),
    onCancel: vi.fn(),
    ...overrides
  };
  const view = render(<IntegrationForm {...props} />);
  return { ...view, props };
}

function form(): HTMLFormElement {
  const element = document.querySelector('form');
  if (!element) throw new Error('form not rendered');
  return element;
}

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

async function flushFrames() {
  await act(async () => {
    await new Promise((resolve) => requestAnimationFrame(() => resolve(undefined)));
  });
}

describe('IntegrationForm', () => {
  it('starts from the stored record and moves focus to the first field', () => {
    renderForm();

    expect(screen.getByLabelText('Identificador del número')).toHaveValue(stored.phoneNumberId);
    expect(screen.getByLabelText('Cuenta de WhatsApp Business')).toHaveValue(stored.businessAccountId);
    expect(screen.getByLabelText('Número visible')).toHaveValue('+50600000011');
    expect(screen.getByLabelText('Referencia de credencial')).toHaveValue('synthetic-wa-token');
    expect(screen.getByLabelText('Integración habilitada')).toBeChecked();
    expect(document.activeElement).toBe(screen.getByLabelText('Identificador del número'));
  });

  it('starts empty and unticked in create mode, without stealing focus when asked not to', () => {
    renderForm({ initial: null, autoFocus: false, onCancel: undefined });

    expect(screen.getByLabelText('Identificador del número')).toHaveValue('');
    expect(screen.getByLabelText('Número visible')).toHaveValue('');
    expect(screen.getByLabelText('Integración habilitada')).not.toBeChecked();
    expect(document.activeElement).toBe(document.body);
    expect(screen.queryByRole('button', { name: 'Cancelar' })).toBeNull();
  });

  it('shows the ownership proof hint and the reference hint', () => {
    renderForm();

    expect(
      screen.getByText(
        'Cambiar el número, la cuenta o la referencia, o deshabilitar la integración, exige comprobar la propiedad de nuevo.'
      )
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Referencia de credencial')).toHaveAccessibleDescription(
      'Nombre de la referencia, nunca el token.'
    );
  });

  it('resaves the stored record unchanged with the fixture body', () => {
    const { props } = renderForm();

    fireEvent.submit(form());

    expect(props.onSubmit).toHaveBeenCalledWith(integration.request.valid.enabledWithDisplayNumber);
    expect(JSON.stringify(vi.mocked(props.onSubmit).mock.calls[0][0])).toBe(
      JSON.stringify(integration.request.valid.enabledWithDisplayNumber)
    );
  });

  it('trims values and omits an empty display number', () => {
    const { props } = renderForm({ initial: null, onCancel: undefined });
    const fixture = integration.request.valid.disabledWithoutDisplayNumber;

    type('Identificador del número', ` ${fixture.phoneNumberId} `);
    type('Cuenta de WhatsApp Business', fixture.businessAccountId);
    type('Número visible', '   ');
    type('Referencia de credencial', `${fixture.secretRef} `);
    fireEvent.submit(form());

    expect(JSON.stringify(vi.mocked(props.onSubmit).mock.calls[0][0])).toBe(JSON.stringify(fixture));
  });

  it.each([
    ['Identificador del número', '1234', 'Debe tener entre 5 y 32 dígitos.'],
    ['Cuenta de WhatsApp Business', '20000000000000A', 'Debe tener entre 5 y 32 dígitos.'],
    ['Número visible', '50600000011', 'Escribe el número en formato internacional, por ejemplo +50688888888.'],
    ['Número visible', '+0600000011', 'Escribe el número en formato internacional, por ejemplo +50688888888.'],
    ['Referencia de credencial', 'env:DOKENE_WA_TOKEN', 'Solo letras, números, guion y guion bajo, hasta 128 caracteres.'],
    ['Referencia de credencial', 'a'.repeat(129), 'Solo letras, números, guion y guion bajo, hasta 128 caracteres.']
  ])('refuses %s = %s, focusing the field and sending nothing', (label, value, message) => {
    const { props } = renderForm();

    type(label, value);
    fireEvent.submit(form());

    const field = screen.getByLabelText(label);
    expect(props.onSubmit).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(field);
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveAccessibleDescription(expect.stringContaining(message));
  });

  it('reports only the first invalid field', () => {
    renderForm();

    type('Identificador del número', '');
    type('Referencia de credencial', '');
    fireEvent.submit(form());

    expect(screen.getAllByText('Debe tener entre 5 y 32 dígitos.')).toHaveLength(1);
    expect(screen.queryByText('Solo letras, números, guion y guion bajo, hasta 128 caracteres.')).toBeNull();
    expect(document.activeElement).toBe(screen.getByLabelText('Identificador del número'));
  });

  it('shows a formatted preview of the display number without judging it', () => {
    renderForm();

    type('Número visible', '+50688888888');

    expect(screen.getByText(/Vista previa:/)).toHaveTextContent('Vista previa: +506 8888 8888');
  });

  it('disables the submit and shows Guardando… while a request is in flight', () => {
    const { props } = renderForm({ submitting: true });

    const submit = screen.getByRole('button', { name: 'Guardando…' });
    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute('type', 'submit');
    fireEvent.submit(form());
    expect(props.onSubmit).not.toHaveBeenCalled();
  });

  it('asks before disabling a stored enabled integration; Escape sends nothing and refocuses submit', async () => {
    const { props } = renderForm();

    fireEvent.click(screen.getByLabelText('Integración habilitada'));
    fireEvent.submit(form());

    expect(screen.getByRole('dialog', { name: 'Deshabilitar la integración' })).toBeInTheDocument();
    expect(
      screen.getByText(
        'Mientras esté deshabilitada no se enviará ningún mensaje y habrá que comprobar la propiedad del número de nuevo al habilitarla.'
      )
    ).toBeInTheDocument();
    await flushFrames();

    fireEvent.keyDown(window, { key: 'Escape' });

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(props.onSubmit).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Guardar' }));
  });

  it('sends the disabling request only after Confirmar', () => {
    const { props } = renderForm();

    fireEvent.click(screen.getByLabelText('Integración habilitada'));
    fireEvent.submit(form());
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar' }));

    expect(props.onSubmit).toHaveBeenCalledTimes(1);
    expect(props.onSubmit).toHaveBeenCalledWith({ ...integration.request.valid.enabledWithDisplayNumber, enabled: false });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('enables without a confirmation', () => {
    const { props } = renderForm({ initial: integration.response.valid.disabledWithoutDisplayNumber });

    fireEvent.click(screen.getByLabelText('Integración habilitada'));
    fireEvent.submit(form());

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(props.onSubmit).toHaveBeenCalledWith({ ...integration.request.valid.disabledWithoutDisplayNumber, enabled: true });
  });

  it('calls onCancel from Cancelar', () => {
    const { props } = renderForm();

    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));

    expect(props.onCancel).toHaveBeenCalledTimes(1);
  });

  it('lists the current server values beside the form after a stale save, keeping typed values', () => {
    const current = { ...integration.response.valid.outboundAndWebhookReady, version: 4, displayPhoneNumber: null };
    const { rerender, props } = renderForm();
    type('Referencia de credencial', 'mi-referencia');

    rerender(<IntegrationForm {...props} stale={{ current, rereadError: null, onRetry: vi.fn() }} />);

    const values = screen.getByRole('region', { name: 'Valores actuales en el servidor' });
    expect(values).toHaveTextContent('Sin número visible');
    expect(values).toHaveTextContent('synthetic-wa-token');
    expect(screen.getByLabelText('Referencia de credencial')).toHaveValue('mi-referencia');
  });

  it('shows the re read error with its own retry in place of the current values', () => {
    const onRetry = vi.fn();
    renderForm({ stale: { current: null, rereadError: 'No se pudo leer.', onRetry } });

    const values = screen.getByRole('region', { name: 'Valores actuales en el servidor' });
    expect(values).toHaveTextContent('No se pudo leer.');
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('renders no credential field', () => {
    renderForm();

    for (const input of Array.from(document.querySelectorAll('input'))) {
      expect(input.type).not.toBe('password');
      const label = input.labels?.[0]?.textContent ?? '';
      const described = `${input.name} ${label} ${input.autocomplete}`;
      expect(described).not.toMatch(/token|password|contraseña|valor secreto|secret value/i);
    }
  });
});
