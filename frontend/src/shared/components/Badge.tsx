import React from 'react';

interface BadgeProps {
  variant?: 'success' | 'warning' | 'error' | 'neutral' | 'archived';
  children: React.ReactNode;
}

export function Badge({ variant = 'neutral', children }: BadgeProps) {
  let bg = 'var(--color-surface-container)';
  let color = 'var(--color-text-supporting)';
  let border = 'none';

  if (variant === 'success') {
    bg = 'var(--color-surface-selected)';
    color = 'var(--color-brand)';
    border = '1px solid var(--color-surface-low)';
  } else if (variant === 'warning') {
    bg = 'var(--color-warning-bg)';
    color = 'var(--color-warning-text)';
    border = '1px solid var(--color-warning-border)';
  } else if (variant === 'error') {
    bg = 'var(--color-error-bg)';
    color = 'var(--color-error-text)';
    border = '1px solid var(--color-error-border)';
  } else if (variant === 'archived') {
    bg = 'var(--color-surface-inset)';
    color = 'var(--color-text-muted)';
    border = '1px solid var(--color-outline)';
  }

  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '4px',
        padding: '2px var(--space-8)',
        borderRadius: 'var(--radius-full)',
        backgroundColor: bg,
        color,
        border,
        fontSize: 'var(--font-size-meta)',
        lineHeight: 'var(--line-height-meta)',
        fontWeight: 500
      }}
    >
      {children}
    </span>
  );
}
