import type {
  ActionGateRejectionReason,
  AiUnavailableReason,
  NoDraftReason,
  NoRecommendationReason,
  SemanticAction,
  SemanticTemplateIntent
} from '@/features/followups/types';

// Values arrive from the network, so every lookup accepts `string` and falls back
// to a neutral label instead of trusting the closed enum at runtime.

const ACTION_LABELS: Record<SemanticAction, string> = {
  REPEAT_PURCHASE_FOLLOW_UP: 'Seguimiento por recompra',
  GENERAL_CHECK_IN: 'Contacto de cortesía',
  RELATED_PRODUCT_OFFER: 'Sugerencia de producto relacionado',
  DORMANT_REENGAGEMENT: 'Reactivación de cliente inactivo',
  SEASONAL_GREETING: 'Saludo de temporada'
};

const INTENT_LABELS: Record<SemanticTemplateIntent, string> = {
  GENERAL_FOLLOW_UP: 'Seguimiento general',
  REPEAT_PURCHASE: 'Recompra',
  RELATED_PRODUCT: 'Producto relacionado',
  SEASONAL_EVENT: 'Temporada',
  DORMANT_CUSTOMER: 'Cliente inactivo'
};

const NO_RECOMMENDATION_REASON_LABELS: Record<NoRecommendationReason, string> = {
  INSUFFICIENT_HISTORY: 'el historial de compras es insuficiente',
  RECENTLY_CONTACTED: 'el cliente fue contactado recientemente',
  NO_RELEVANT_OFFER: 'no hay una oferta relevante para este cliente',
  UNCERTAIN_INTENT: 'la intención del cliente no es clara',
  MANUAL_REVIEW_REQUIRED: 'el caso requiere revisión manual'
};

const NO_DRAFT_REASON_LABELS: Record<NoDraftReason, string> = {
  INSUFFICIENT_HISTORY: 'el historial de compras es insuficiente',
  UNSUPPORTED_ACTION: 'esta acción no admite un borrador automático',
  MISSING_TRUSTED_FACTS: 'faltan datos verificados del negocio',
  SAFETY_VIOLATION: 'el texto generado no cumplió las reglas de seguridad',
  MANUAL_REVIEW_REQUIRED: 'el caso requiere revisión manual'
};

const INELIGIBLE_REASON_LABELS: Partial<Record<ActionGateRejectionReason, string>> = {
  DO_NOT_CONTACT: 'el cliente pidió no ser contactado',
  NO_CONTACT_CONSENT: 'el cliente no tiene consentimiento de contacto',
  CUSTOMER_ARCHIVED: 'el cliente está archivado',
  FOLLOW_UP_INELIGIBLE: 'el seguimiento ya no está pendiente',
  DISALLOWED_ACTION: 'la acción solicitada no está permitida'
};

function lookup<K extends string>(table: Record<K, string>, key: string | null | undefined): string | undefined {
  return key !== null && key !== undefined && Object.prototype.hasOwnProperty.call(table, key)
    ? table[key as K]
    : undefined;
}

export function actionLabel(action: string): string {
  return lookup(ACTION_LABELS, action) ?? 'Acción no reconocida';
}

export function intentLabel(intent: string): string {
  return lookup(INTENT_LABELS, intent) ?? 'Intención no reconocida';
}

export function noRecommendationReasonLabel(reason: string | null | undefined): string {
  return lookup(NO_RECOMMENDATION_REASON_LABELS, reason) ?? 'el asistente no encontró una acción adecuada';
}

export function noDraftReasonLabel(reason: string | null | undefined): string {
  return lookup(NO_DRAFT_REASON_LABELS, reason) ?? 'el asistente no pudo redactar un mensaje adecuado';
}

export function ineligibleReasonLabel(reason: string | null | undefined): string {
  return (
    lookup(INELIGIBLE_REASON_LABELS as Record<string, string>, reason) ??
    'el cliente no cumple las condiciones para sugerir un contacto'
  );
}

export const AI_MANUAL_FALLBACK = 'Puedes continuar con el seguimiento manual.';

export function unavailableMessage(reason: AiUnavailableReason | string | null, retryable: boolean): string {
  if (reason === 'NOT_AVAILABLE') {
    return `El asistente IA no está habilitado para este espacio de trabajo. ${AI_MANUAL_FALLBACK}`;
  }
  if (retryable) {
    return `El asistente IA no está disponible en este momento. ${AI_MANUAL_FALLBACK} También puedes reintentar.`;
  }
  return `El asistente IA no pudo generar una sugerencia utilizable. ${AI_MANUAL_FALLBACK}`;
}

export function confidenceLabel(confidence: number): string {
  const clamped = Number.isFinite(confidence) ? Math.min(1, Math.max(0, confidence)) : 0;
  return `${Math.round(clamped * 100)}\u00a0%`;
}
