import { HttpErrorResponse } from '@angular/common/http';

type AnyRecord = Record<string, unknown>;

export type ApiValidationError = {
  field: string;
  message: string;
  rejectedValue?: string | null;
};

function isRecord(value: unknown): value is AnyRecord {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function asString(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function extractPayload(error: unknown): AnyRecord | null {
  if (!(error instanceof HttpErrorResponse)) return null;
  return isRecord(error.error) ? error.error : null;
}

export function apiValidationErrors(error: unknown): ApiValidationError[] {
  const payload = extractPayload(error);
  if (!payload) return [];
  const raw = payload['validationErrors'];
  if (!Array.isArray(raw)) return [];

  const out: ApiValidationError[] = [];
  for (const item of raw) {
    if (!isRecord(item)) continue;
    const field = asString(item['field']) ?? '';
    const message = asString(item['message']) ?? '';
    if (!message) continue;
    const rejectedValue = asString(item['rejectedValue']) ?? undefined;
    out.push({ field, message, rejectedValue });
  }
  return out;
}

export function apiErrorMessage(error: unknown, fallback: string): string {
  const payload = extractPayload(error);
  if (payload) {
    const message = asString(payload['message']);
    if (message) return message;

    const validation = apiValidationErrors(error);
    if (validation.length > 0) {
      return validation[0].message;
    }
  }

  if (error instanceof HttpErrorResponse) {
    if (typeof error.error === 'string') {
      const raw = error.error.trim();
      if (raw) return raw;
    }
    if (error.status === 0) {
      return 'Network error. Check your connection and try again.';
    }
  }

  if (error instanceof Error) {
    const message = error.message.trim();
    if (message) return message;
  }

  return fallback;
}
