// ABOUTME: The compose's mirror of the backend template-parameter format rule (#417).
import { describe, expect, it } from 'vitest';
import { hasUnsendableFormat } from '../utils/bodyFormat';

describe('hasUnsendableFormat', () => {
  it.each([
    ['line feed', 'a\nb'],
    ['carriage return', 'a\rb'],
    ['tab', 'a\tb'],
    ['five spaces', 'a     b'],
    ['line separator', 'a b'],
    ['paragraph separator', 'a b'],
    ['zero-width space', 'a​b'],
    ['bidi override', 'a‮b'],
    ['C1 control', 'a\u0085b']
  ])('refuses a %s', (_, text) => {
    expect(hasUnsendableFormat(text)).toBe(true);
  });

  it.each([
    ['accents and ñ', 'Hola Ñandú, ¿cómo estás?'],
    ['emoji with zero-width joiner', 'Equipo 👩‍💻 listo'],
    ['subdivision flag (tag block)', '🏴\u{E0067}\u{E0062}\u{E0073}\u{E0063}\u{E0074}\u{E007F}'],
    ['four spaces', 'a    b'],
    ['HTML-like text', '<b>no es negrita</b> &amp;']
  ])('accepts %s', (_, text) => {
    expect(hasUnsendableFormat(text)).toBe(false);
  });
});
