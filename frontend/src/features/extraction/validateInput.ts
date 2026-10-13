// Client-side checks that mirror the backend's 400s, so obvious mistakes are caught before upload.

export function validateSchema(text: string): string | null {
  if (!text.trim()) return 'Schema is required.';
  try {
    const parsed = JSON.parse(text);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return 'Schema must be a JSON object.';
    return null;
  } catch (e) {
    return `Schema is not valid JSON: ${(e as Error).message}`;
  }
}

export function validateRules(text: string): string | null {
  if (!text.trim()) return null;
  try {
    const parsed = JSON.parse(text);
    if (!Array.isArray(parsed) || !parsed.every((r) => typeof r === 'string')) {
      return 'Rules must be a JSON array of strings.';
    }
    return null;
  } catch (e) {
    return `Rules are not valid JSON: ${(e as Error).message}`;
  }
}
