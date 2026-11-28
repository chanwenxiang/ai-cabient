import type { OpenApiRiskEventDto } from '@aicabinet/shared-types';

export function canDisposeRiskEvent(
  row: Pick<OpenApiRiskEventDto, 'dispositionStatus' | 'severity'>
) {
  return (
    (row.dispositionStatus ?? 'OPEN') === 'OPEN' &&
    ['INFO', 'WARN', 'BLOCK'].includes(row.severity ?? '')
  );
}

export function normalizeDispositionNote(value: string | null | undefined) {
  return (value ?? '').trim();
}

export function validDispositionNote(value: string | null | undefined) {
  const note = normalizeDispositionNote(value);
  return note.length > 0 && note.length <= 256;
}

export function parseMaxDisputes(value: string | number | null | undefined) {
  if (typeof value === 'number') return Number.isInteger(value) && value >= 1 ? value : null;
  const text = (value ?? '').trim();
  if (!/^[1-9]\d*$/.test(text)) return null;
  const parsed = Number(text);
  return Number.isSafeInteger(parsed) ? parsed : null;
}
