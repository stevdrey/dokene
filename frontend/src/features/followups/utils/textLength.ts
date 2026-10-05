// The backend bounds drafts in Unicode code points, while String#length and
// String#slice work in UTF-16 units. These helpers keep the UI aligned with the
// backend and never split a surrogate pair.

export function countCodePoints(text: string): number {
  let count = 0;
  for (const _ of text) {
    count += 1;
  }
  return count;
}

export function truncateToCodePoints(text: string, max: number): string {
  if (max <= 0) return '';
  let count = 0;
  let end = 0;
  for (const char of text) {
    if (count === max) return text.slice(0, end);
    count += 1;
    end += char.length;
  }
  return text;
}
