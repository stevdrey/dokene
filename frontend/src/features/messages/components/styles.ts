// ABOUTME: Inline style objects shared by the Mensajes components, all values from tokens.css.
// ABOUTME: Mirrors the card, alert and meta treatments the followups and customers pages use.

import type React from 'react';

export const cardStyle: React.CSSProperties = {
  backgroundColor: 'var(--color-surface)',
  borderRadius: 'var(--radius-lg)',
  border: '1px solid var(--color-outline-subtle)',
  padding: 'var(--space-16)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'var(--space-12)'
};

export const sectionHeadingStyle: React.CSSProperties = {
  fontSize: 'var(--font-size-component-heading)',
  lineHeight: 'var(--line-height-component-heading)',
  fontWeight: 600,
  color: 'var(--color-text-main)',
  margin: 0
};

export const metaStyle: React.CSSProperties = {
  fontSize: 'var(--font-size-meta)',
  lineHeight: 'var(--line-height-meta)',
  color: 'var(--color-text-muted)'
};

export const secondaryTextStyle: React.CSSProperties = {
  fontSize: 'var(--font-size-secondary)',
  lineHeight: 'var(--line-height-secondary)',
  color: 'var(--color-text-supporting)'
};

export const warningAlertStyle: React.CSSProperties = {
  backgroundColor: 'var(--color-warning-bg)',
  color: 'var(--color-warning-text)',
  border: '1px solid var(--color-warning-border)',
  borderRadius: 'var(--radius-md)',
  padding: 'var(--space-12)',
  fontSize: 'var(--font-size-secondary)',
  lineHeight: 'var(--line-height-secondary)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'var(--space-8)'
};

export const errorAlertStyle: React.CSSProperties = {
  ...warningAlertStyle,
  backgroundColor: 'var(--color-error-bg)',
  color: 'var(--color-error-text)',
  border: '1px solid var(--color-error-border)'
};

export const statusLineStyle: React.CSSProperties = {
  backgroundColor: 'var(--color-surface-container-low)',
  color: 'var(--color-brand)',
  border: '1px solid var(--color-surface-low)',
  borderRadius: 'var(--radius-md)',
  padding: 'var(--space-12)',
  fontSize: 'var(--font-size-secondary)',
  lineHeight: 'var(--line-height-secondary)'
};

export const actionsRowStyle: React.CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 'var(--space-8)',
  alignItems: 'center'
};

export const textareaStyle: React.CSSProperties = {
  width: '100%',
  boxSizing: 'border-box',
  minHeight: '88px',
  padding: 'var(--space-12)',
  border: '1px solid var(--color-outline)',
  borderRadius: 'var(--radius-md)',
  font: 'inherit',
  fontSize: 'var(--font-size-secondary)',
  color: 'var(--color-text-main)',
  backgroundColor: 'var(--color-surface)',
  resize: 'vertical'
};

export const definitionListStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'max-content 1fr',
  columnGap: 'var(--space-16)',
  rowGap: 'var(--space-4)',
  margin: 0,
  fontSize: 'var(--font-size-secondary)',
  lineHeight: 'var(--line-height-secondary)'
};

export const definitionTermStyle: React.CSSProperties = {
  color: 'var(--color-text-muted)',
  fontWeight: 500
};

export const definitionValueStyle: React.CSSProperties = {
  margin: 0,
  color: 'var(--color-text-main)'
};
