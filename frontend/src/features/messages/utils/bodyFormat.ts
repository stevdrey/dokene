// ABOUTME: Mirrors the backend's template-parameter format rule (TemplateParameterValues) so the compose can say why a
// ABOUTME: body would be refused before submit. Usability only: the server still validates every body.

// Control characters (C0, DEL, C1), line and paragraph separators, format characters other than the zero-width
// (non-)joiners and the emoji tag block, and a run of five spaces.
const UNSENDABLE = new RegExp(
  '[\\p{Cc}\\u2028\\u2029]|(?![\\u200C\\u200D\\u{E0020}-\\u{E007F}])\\p{Cf}| {5}',
  'u'
);

/** True when WhatsApp would refuse the text as a template parameter (line breaks, tabs, hidden characters, 5 spaces). */
export function hasUnsendableFormat(text: string): boolean {
  return UNSENDABLE.test(text);
}
