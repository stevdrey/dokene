// ABOUTME: Tests the settle intent hook, including the race where a click lands before the settle render's effect runs.
// ABOUTME: Arming from a layout effect runs before that commit's passive effects, which reproduces the race deterministically.
import { render } from '@testing-library/react';
import { useLayoutEffect } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { useSettleIntent } from './useSettleIntent';

interface HarnessProps {
  busy: boolean;
  onSettle: (intent: string) => void;
  /** Armed in a layout effect of this commit, so before the passive effects of the same commit run. */
  armInCommit?: { intent: string | null };
}

function Harness({ busy, onSettle, armInCommit }: HarnessProps) {
  const arm = useSettleIntent(busy, onSettle);
  useLayoutEffect(() => {
    if (armInCommit) arm(armInCommit.intent);
  }, [arm, armInCommit]);
  return null;
}

describe('useSettleIntent', () => {
  it('hands the intent back once the read it started settles', () => {
    const onSettle = vi.fn();
    const { rerender } = render(<Harness busy={false} onSettle={onSettle} armInCommit={{ intent: 'more' }} />);
    expect(onSettle).not.toHaveBeenCalled();
    rerender(<Harness busy={true} onSettle={onSettle} />);
    expect(onSettle).not.toHaveBeenCalled();
    rerender(<Harness busy={false} onSettle={onSettle} />);
    expect(onSettle).toHaveBeenCalledExactlyOnceWith('more');
  });

  it('keeps an intent set before the effect of a settle render runs for the read that intent starts', () => {
    const onSettle = vi.fn();
    const { rerender } = render(<Harness busy={true} onSettle={onSettle} />);
    // The previous read settles and the click lands before that render's passive effect has run.
    rerender(<Harness busy={false} onSettle={onSettle} armInCommit={{ intent: 'more' }} />);
    expect(onSettle).not.toHaveBeenCalled();
    rerender(<Harness busy={true} onSettle={onSettle} />);
    rerender(<Harness busy={false} onSettle={onSettle} />);
    expect(onSettle).toHaveBeenCalledExactlyOnceWith('more');
  });

  it('settles an intent once and never one that was cleared', () => {
    const onSettle = vi.fn();
    const { rerender } = render(<Harness busy={false} onSettle={onSettle} armInCommit={{ intent: 'more' }} />);
    rerender(<Harness busy={true} onSettle={onSettle} />);
    rerender(<Harness busy={false} onSettle={onSettle} />);
    rerender(<Harness busy={true} onSettle={onSettle} />);
    rerender(<Harness busy={false} onSettle={onSettle} />);
    expect(onSettle).toHaveBeenCalledExactlyOnceWith('more');
    rerender(<Harness busy={false} onSettle={onSettle} armInCommit={{ intent: null }} />);
    rerender(<Harness busy={true} onSettle={onSettle} />);
    rerender(<Harness busy={false} onSettle={onSettle} />);
    expect(onSettle).toHaveBeenCalledTimes(1);
  });
});
