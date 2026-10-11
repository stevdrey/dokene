// ABOUTME: Parses the strong numeric ETag the backend sends on versioned resources.
// ABOUTME: Shared so every feature reads a version the same strict way and never invents one.

const STRONG_NUMERIC_ETAG = /^"(0|[1-9][0-9]*)"$/;

/**
 * Returns the version carried by a strong numeric ETag such as `"7"`, or null when the header is
 * missing, weak (`W/"7"`), unquoted or not a safe integer. Callers decide what null means for them:
 * followups treats it as an invalid AI response, messages as a detail that cannot be acted on.
 */
export function parseStrongNumericEtag(etag: string | null): number | null {
  if (etag === null || !STRONG_NUMERIC_ETAG.test(etag)) {
    return null;
  }
  const version = Number(etag.slice(1, -1));
  return Number.isSafeInteger(version) ? version : null;
}
