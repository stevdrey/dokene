import React from 'react';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
  isLoading?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      variant = 'secondary',
      size = 'md',
      isLoading = false,
      disabled,
      children,
      style,
      className,
      ...props
    },
    ref
  ) => {
    let bg = 'var(--color-surface)';
    let color = 'var(--color-text-main)';
    let border = '1px solid var(--color-outline-subtle)';

    if (variant === 'primary') {
      bg = 'var(--color-primary)';
      color = 'var(--color-on-primary)';
      border = '1px solid transparent';
    } else if (variant === 'secondary') {
      bg = 'var(--color-surface-inset)';
      color = 'var(--color-text-main)';
      border = '1px solid var(--color-outline)';
    } else if (variant === 'danger') {
      bg = 'var(--color-error-bg)';
      color = 'var(--color-error-text)';
      border = '1px solid var(--color-error-border)';
    } else if (variant === 'ghost') {
      bg = 'transparent';
      color = 'var(--color-text-supporting)';
      border = '1px solid transparent';
    }

    const minHeight = '44px';
    const padding = size === 'sm' ? 'var(--space-8) var(--space-12)' : 'var(--space-8) var(--space-16)';

    return (
      <button
        ref={ref}
        disabled={disabled || isLoading}
        className={`interactive-target ${className || ''}`}
        style={{
          backgroundColor: bg,
          color,
          border,
          borderRadius: 'var(--radius-md)',
          padding,
          minHeight,
          fontWeight: 500,
          fontSize: size === 'sm' ? 'var(--font-size-meta)' : 'var(--font-size-dense)',
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 'var(--space-8)',
          transition: 'background-color 0.15s ease, opacity 0.15s ease',
          ...style
        }}
        {...props}
      >
        {isLoading ? (
          <span>Cargando...</span>
        ) : (
          children
        )}
      </button>
    );
  }
);

Button.displayName = 'Button';
