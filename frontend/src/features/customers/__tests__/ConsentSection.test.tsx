import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ConsentSection } from '@/features/customers/components/ConsentSection';
import { customerApi } from '@/features/customers/api/customerApi';

describe('ConsentSection', () => {
  const mockPhones = [
    { id: 'phone-1', e164: '+56984521190', primary: true }
  ];

  const mockPolicy = {
    customerId: 'cust-1',
    version: 3,
    doNotContact: false,
    doNotContactSource: null,
    doNotContactChangedAt: null,
    consents: [
      {
        contactId: 'phone-1',
        channel: 'WHATSAPP' as const,
        status: 'GRANTED' as const,
        source: 'CUSTOMER_WRITTEN' as const,
        changedAt: '2024-09-01T10:00:00Z'
      }
    ]
  };

  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(customerApi, 'getContactPolicy').mockResolvedValue({
      policy: mockPolicy,
      version: 3
    });
  });

  it('renders consent card with WhatsApp status and source', async () => {
    render(<ConsentSection customerId="cust-1" phones={mockPhones} isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 2, name: /Consentimiento/i })).toBeInTheDocument();
    });

    expect(screen.getByText('Activo / Concedido')).toBeInTheDocument();
    expect(screen.getByText(/Escrito por el cliente/i)).toBeInTheDocument();
  });

  it('allows changing consent status with mandatory source selection', async () => {
    vi.spyOn(customerApi, 'changeConsent').mockResolvedValue({
      ...mockPolicy,
      version: 4,
      consents: [
        {
          contactId: 'phone-1',
          channel: 'WHATSAPP',
          status: 'REVOKED',
          source: 'CUSTOMER_VERBAL',
          changedAt: new Date().toISOString()
        }
      ]
    });

    render(<ConsentSection customerId="cust-1" phones={mockPhones} isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Gestionar consentimiento/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Gestionar consentimiento/i }));

    expect(screen.getByRole('dialog', { name: /Gestionar consentimiento/i })).toBeInTheDocument();

    // Select Revoked
    fireEvent.click(screen.getByLabelText(/Revocado/i));

    // Submit
    fireEvent.click(screen.getByRole('button', { name: /Guardar consentimiento/i }));

    await waitFor(() => {
      expect(customerApi.changeConsent).toHaveBeenCalledWith(
        'cust-1',
        'phone-1',
        'WHATSAPP',
        3,
        'REVOKED',
        'CUSTOMER_VERBAL'
      );
    });
  });

  it('allows toggling protocol No contactar with source confirmation', async () => {
    vi.spyOn(customerApi, 'changeDoNotContact').mockResolvedValue({
      ...mockPolicy,
      version: 4,
      doNotContact: true,
      doNotContactSource: 'CUSTOMER_VERBAL',
      doNotContactChangedAt: new Date().toISOString()
    });

    render(<ConsentSection customerId="cust-1" phones={mockPhones} isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Marcar No contactar/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Marcar No contactar/i }));

    expect(screen.getByRole('dialog', { name: /Marcar como No contactar/i })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Confirmar restricción/i }));

    await waitFor(() => {
      expect(customerApi.changeDoNotContact).toHaveBeenCalledWith(
        'cust-1',
        3,
        true,
        'CUSTOMER_VERBAL'
      );
    });
  });

  it('renders audit history modal with correct labels for DO_NOT_CONTACT_CHANGED', async () => {
    vi.spyOn(customerApi, 'getContactPolicyHistory').mockResolvedValue({
      events: [
        {
          id: 'evt-1',
          type: 'DO_NOT_CONTACT_CHANGED',
          contactId: '',
          channel: 'WHATSAPP',
          consentStatus: null as any,
          doNotContact: true,
          source: 'CUSTOMER_VERBAL',
          occurredAt: '2025-01-14T10:00:00Z',
          actorId: 'act-1',
          membershipId: 'mem-1',
          policyVersion: 2
        }
      ],
      nextCursor: null
    });

    render(<ConsentSection customerId="cust-1" phones={mockPhones} isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Historial/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Historial/i }));

    await waitFor(() => {
      expect(screen.getByRole('dialog', { name: /Historial de consentimientos y políticas/i })).toBeInTheDocument();
    });

    expect(screen.getByText('Protocolo No contactar')).toBeInTheDocument();
    expect(screen.getByText(/Restricción activada \(No contactar\)/i)).toBeInTheDocument();
    expect(screen.queryByText(/Desconocido \(No solicitado\)/i)).not.toBeInTheDocument();
  });

  it('renders multiple contacts independently with their own WhatsApp consent status', async () => {
    const multiPhones = [
      { id: 'phone-1', e164: '+56984521190', primary: true },
      { id: 'phone-2', e164: '+56912345678', primary: false }
    ];

    const multiPolicy = {
      customerId: 'cust-1',
      version: 5,
      doNotContact: false,
      doNotContactSource: null,
      doNotContactChangedAt: null,
      consents: [
        {
          contactId: 'phone-1',
          channel: 'WHATSAPP' as const,
          status: 'REVOKED' as const,
          source: 'CUSTOMER_VERBAL' as const,
          changedAt: '2025-01-10T10:00:00Z'
        },
        {
          contactId: 'phone-2',
          channel: 'WHATSAPP' as const,
          status: 'GRANTED' as const,
          source: 'CUSTOMER_WRITTEN' as const,
          changedAt: '2025-01-11T12:00:00Z'
        }
      ]
    };

    vi.spyOn(customerApi, 'getContactPolicy').mockResolvedValue({
      policy: multiPolicy,
      version: 5
    });

    render(<ConsentSection customerId="cust-1" phones={multiPhones} isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('+56984521190')).toBeInTheDocument();
      expect(screen.getByText('+56912345678')).toBeInTheDocument();
    });

    expect(screen.getByText('Revocado')).toBeInTheDocument();
    expect(screen.getByText('Activo / Concedido')).toBeInTheDocument();
  });

  it('allows managing consent for a secondary contact and passes correct contactId to changeConsent', async () => {
    const multiPhones = [
      { id: 'phone-1', e164: '+56984521190', primary: true },
      { id: 'phone-2', e164: '+56912345678', primary: false }
    ];

    const multiPolicy = {
      customerId: 'cust-1',
      version: 5,
      doNotContact: false,
      doNotContactSource: null,
      doNotContactChangedAt: null,
      consents: [
        {
          contactId: 'phone-1',
          channel: 'WHATSAPP' as const,
          status: 'REVOKED' as const,
          source: 'CUSTOMER_VERBAL' as const,
          changedAt: '2025-01-10T10:00:00Z'
        },
        {
          contactId: 'phone-2',
          channel: 'WHATSAPP' as const,
          status: 'GRANTED' as const,
          source: 'CUSTOMER_WRITTEN' as const,
          changedAt: '2025-01-11T12:00:00Z'
        }
      ]
    };

    vi.spyOn(customerApi, 'getContactPolicy').mockResolvedValue({
      policy: multiPolicy,
      version: 5
    });

    vi.spyOn(customerApi, 'changeConsent').mockResolvedValue({
      ...multiPolicy,
      version: 6,
      consents: [
        multiPolicy.consents[0],
        {
          contactId: 'phone-2',
          channel: 'WHATSAPP',
          status: 'REVOKED',
          source: 'CUSTOMER_VERBAL',
          changedAt: new Date().toISOString()
        }
      ]
    });

    render(<ConsentSection customerId="cust-1" phones={multiPhones} isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText('+56912345678')).toBeInTheDocument();
    });

    const manageButtons = screen.getAllByRole('button', { name: /Gestionar consentimiento/i });
    expect(manageButtons.length).toBe(2);

    // Click manage for the secondary phone (second button)
    fireEvent.click(manageButtons[1]);

    expect(screen.getByRole('dialog', { name: /Gestionar consentimiento/i })).toBeInTheDocument();
    expect(screen.getByText(/Teléfono: \+56912345678/i)).toBeInTheDocument();

    // Select Revocado
    fireEvent.click(screen.getByLabelText(/Revocado/i));
    fireEvent.click(screen.getByRole('button', { name: /Guardar consentimiento/i }));

    await waitFor(() => {
      expect(customerApi.changeConsent).toHaveBeenCalledWith(
        'cust-1',
        'phone-2',
        'WHATSAPP',
        5,
        'REVOKED',
        'CUSTOMER_VERBAL'
      );
    });
  });

  it('ensures customer-wide Do Not Contact overrides individual phone consent status', async () => {
    const multiPhones = [
      { id: 'phone-1', e164: '+56984521190', primary: true }
    ];

    const dncPolicy = {
      customerId: 'cust-1',
      version: 7,
      doNotContact: true,
      doNotContactSource: 'CUSTOMER_VERBAL' as const,
      doNotContactChangedAt: '2025-01-12T10:00:00Z',
      consents: [
        {
          contactId: 'phone-1',
          channel: 'WHATSAPP' as const,
          status: 'GRANTED' as const,
          source: 'CUSTOMER_WRITTEN' as const,
          changedAt: '2025-01-11T12:00:00Z'
        }
      ]
    };

    vi.spyOn(customerApi, 'getContactPolicy').mockResolvedValue({
      policy: dncPolicy,
      version: 7
    });

    render(<ConsentSection customerId="cust-1" phones={multiPhones} isArchived={false} />);

    await waitFor(() => {
      expect(screen.getByText(/Protocolo.*No contactar.*ACTIVADO/i)).toBeInTheDocument();
    });

    expect(screen.getByText(/Este cliente tiene restringido cualquier contacto directo/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Desactivar No contactar/i })).toBeInTheDocument();
  });
});
