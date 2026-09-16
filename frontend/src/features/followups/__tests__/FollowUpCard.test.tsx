import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { FollowUpCard } from '../components/FollowUpCard';
import { QueueItemResponse } from '../types';

const baseItem: QueueItemResponse = {
  customerId: 'cust-1',
  displayName: 'Valentina Morales',
  primaryPhone: '+56984521190',
  status: 'DUE',
  reasons: ['DUE_TODAY'],
  dueDate: '2026-09-15',
  timingSource: 'LAST_PURCHASE',
  policyVersion: 1,
  effectiveCadenceDays: 30,
  lastPurchaseAt: '2026-08-16T10:00:00Z',
  lastManualFollowUpDate: null,
  lastDismissedDate: null,
  evaluatedAt: '2026-09-15T12:00:00Z'
};

describe('FollowUpCard', () => {
  const onSelect = vi.fn();

  it('prioritizes timingSource over DUE_TODAY in reason explanation', () => {
    // 1. EXPLICIT_DATE
    const { rerender } = render(
      <FollowUpCard
        item={{
          ...baseItem,
          timingSource: 'EXPLICIT_DATE',
          reasons: ['DUE_TODAY']
        }}
        isSelected={false}
        onSelect={onSelect}
        timeZone="America/Santiago"
      />
    );
    expect(screen.getByText(/Se ha alcanzado la fecha programada específicamente/i)).toBeInTheDocument();

    // 2. LAST_PURCHASE
    rerender(
      <FollowUpCard
        item={{
          ...baseItem,
          timingSource: 'LAST_PURCHASE',
          reasons: ['DUE_TODAY']
        }}
        isSelected={false}
        onSelect={onSelect}
        timeZone="America/Santiago"
      />
    );
    expect(screen.getByText(/Ciclo de seguimiento tras la última compra registrada/i)).toBeInTheDocument();

    // 3. LAST_MANUAL_FOLLOW_UP
    rerender(
      <FollowUpCard
        item={{
          ...baseItem,
          timingSource: 'LAST_MANUAL_FOLLOW_UP',
          reasons: ['DUE_TODAY']
        }}
        isSelected={false}
        onSelect={onSelect}
        timeZone="America/Santiago"
      />
    );
    expect(screen.getByText(/Nuevo ciclo tras el último seguimiento registrado/i)).toBeInTheDocument();

    // 4. LAST_DISMISSAL
    rerender(
      <FollowUpCard
        item={{
          ...baseItem,
          timingSource: 'LAST_DISMISSAL',
          reasons: ['DUE_TODAY']
        }}
        isSelected={false}
        onSelect={onSelect}
        timeZone="America/Santiago"
      />
    );
    expect(screen.getByText(/Nuevo ciclo tras el descarte anterior/i)).toBeInTheDocument();

    // 5. SNOOZE
    rerender(
      <FollowUpCard
        item={{
          ...baseItem,
          timingSource: 'SNOOZE',
          reasons: ['DUE_TODAY']
        }}
        isSelected={false}
        onSelect={onSelect}
        timeZone="America/Santiago"
      />
    );
    expect(screen.getByText(/Fecha de postergación acordada cumplida/i)).toBeInTheDocument();
  });

  it('derives card dates and overdue status correctly under tenant timezone without false isDueToday', () => {
    // When tenant is on Sept 17, an item from Sept 16 with status OVERDUE must not claim "Hoy"
    const fixedInstant = new Date('2026-09-17T03:00:00Z');
    vi.useFakeTimers();
    vi.setSystemTime(fixedInstant);

    try {
      render(
        <FollowUpCard
          item={{
            ...baseItem,
            status: 'OVERDUE',
            dueDate: '2026-09-16',
            reasons: ['OVERDUE'],
            timingSource: 'LAST_PURCHASE'
          }}
          isSelected={false}
          onSelect={onSelect}
          timeZone="America/Santiago"
        />
      );

      // Must show Vencido pill
      expect(screen.getByText(/Vencido/i)).toBeInTheDocument();
      // Must NOT display "Sugerido: Hoy"
      expect(screen.queryByText(/Sugerido: Hoy/i)).not.toBeInTheDocument();
      // Must display Fecha: 16 sept.
      expect(screen.getByText(/Fecha: 16 sept/i)).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
