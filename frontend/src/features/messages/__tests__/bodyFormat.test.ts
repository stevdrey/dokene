// ABOUTME: The compose's mirror of the backend template-parameter format rule (#417), tested against the cases the
// ABOUTME: backend test also reads (contracts/messaging/v1/body-format.json), so the two rules cannot drift.
import bodyFormatJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/body-format.json?raw';
import { describe, expect, it } from 'vitest';
import { hasUnsendableFormat } from '../utils/bodyFormat';

interface FormatCase {
  name: string;
  value: string;
}

const cases = JSON.parse(bodyFormatJson) as { refused: FormatCase[]; accepted: FormatCase[] };

describe('hasUnsendableFormat (shared body-format cases)', () => {
  it('has cases on both sides', () => {
    expect(cases.refused.length).toBeGreaterThan(5);
    expect(cases.accepted.length).toBeGreaterThan(3);
  });

  it.each(cases.refused.map((c) => [c.name, c.value]))('refuses %s', (_, value) => {
    expect(hasUnsendableFormat(value)).toBe(true);
  });

  it.each(cases.accepted.map((c) => [c.name, c.value]))('accepts %s', (_, value) => {
    expect(hasUnsendableFormat(value)).toBe(false);
  });
});
