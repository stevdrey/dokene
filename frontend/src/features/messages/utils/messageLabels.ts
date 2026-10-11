// ABOUTME: The single label table for message enums and refusal codes, in Latin American Spanish.
// ABOUTME: Every function falls back to the raw name so an unknown backend value still renders.

import type { ActorKind, FailureCategory, MessageEventType, MessageStatus, SendAttemptOutcome } from '../types';

export type BadgeVariant = 'success' | 'warning' | 'error' | 'neutral' | 'archived';

const STATUS_LABELS: Readonly<Record<string, string>> = {
  PENDING_APPROVAL: 'Pendiente de aprobación',
  APPROVED: 'Aprobado',
  QUEUED: 'En cola',
  SENDING: 'Enviando',
  SENT: 'Enviado',
  DELIVERED: 'Entregado',
  READ: 'Leído',
  REJECTED: 'Rechazado',
  CANCELLED: 'Cancelado',
  FAILED: 'Fallido'
};

const STATUS_VARIANTS: Readonly<Record<string, BadgeVariant>> = {
  PENDING_APPROVAL: 'warning',
  QUEUED: 'warning',
  SENDING: 'warning',
  APPROVED: 'neutral',
  SENT: 'success',
  DELIVERED: 'success',
  READ: 'success',
  FAILED: 'error',
  REJECTED: 'archived',
  CANCELLED: 'archived'
};

const FAILURE_CATEGORY_LABELS: Readonly<Record<string, string>> = {
  INVALID_RECIPIENT: 'Destinatario inválido',
  TEMPLATE_REJECTED: 'Plantilla rechazada',
  RATE_LIMITED: 'Límite de envíos alcanzado',
  PROVIDER_UNAVAILABLE: 'Proveedor no disponible',
  AUTHENTICATION: 'Error de autenticación con el proveedor',
  POLICY_VIOLATION: 'Rechazado por política del proveedor',
  UNKNOWN: 'Error desconocido'
};

const ATTEMPT_OUTCOME_LABELS: Readonly<Record<string, string>> = {
  STARTED: 'Iniciado',
  ACCEPTED: 'Aceptado',
  FAILED_PERMANENT: 'Falló definitivamente',
  FAILED_TRANSIENT: 'Falló temporalmente',
  OUTCOME_UNKNOWN: 'Resultado desconocido'
};

const EVENT_TYPE_LABELS: Readonly<Record<string, string>> = {
  SUBMITTED: 'Enviado a aprobación',
  APPROVED: 'Aprobado',
  REJECTED: 'Rechazado',
  CANCELLED: 'Cancelado',
  SEND_REQUESTED: 'Envío solicitado',
  SEND_ATTEMPT_STARTED: 'Intento de envío iniciado',
  SEND_FAILED: 'Envío fallido',
  SEND_OUTCOME_UNKNOWN: 'Resultado del envío desconocido',
  DELIVERY_UPDATED: 'Estado de entrega actualizado'
};

const ACTOR_KIND_LABELS: Readonly<Record<string, string>> = {
  MEMBER: 'Miembro',
  SYSTEM: 'Sistema',
  PROVIDER: 'Proveedor'
};

const ERROR_CODE_SENTENCES: Readonly<Record<string, string>> = {
  FORBIDDEN: 'No tienes permiso para esta acción.',
  NOT_FOUND: 'Este mensaje ya no está disponible.',
  INVALID_INPUT: 'Revisa los datos e intenta de nuevo.',
  PROVIDER_UNAVAILABLE: 'El proveedor de mensajería no está disponible.',
  OUTBOUND_DISABLED_GLOBALLY: 'Los envíos están desactivados en la plataforma.',
  OUTBOUND_DISABLED_FOR_TENANT: 'Los envíos están desactivados para este espacio de trabajo.',
  INTEGRATION_DISABLED: 'La integración de mensajería está desactivada.',
  NO_CONTACT_CONSENT: 'El cliente no ha dado consentimiento para este canal.',
  DO_NOT_CONTACT: 'El cliente pidió no ser contactado.',
  CUSTOMER_ARCHIVED: 'El cliente está archivado.',
  FOLLOW_UP_INELIGIBLE: 'El cliente no está elegible para seguimiento ahora.',
  CONTACT_FREQUENCY_EXCEEDED: 'Ya se contactó a este cliente recientemente.',
  TEMPLATE_NOT_MAPPED: 'No hay una plantilla configurada para este tipo de mensaje.',
  BODY_REJECTED: 'El texto del mensaje no pasó la validación.',
  SEND_ATTEMPTS_EXHAUSTED: 'Se agotaron los intentos de envío.',
  CONTACT_NOT_OWNED: 'El teléfono ya no pertenece a este cliente.',
  MESSAGE_ALREADY_OPEN: 'Ya hay un mensaje abierto para este cliente.',
  IDEMPOTENCY_KEY_REUSED: 'Esta acción ya se registró con otros datos. Actualiza e intenta de nuevo.'
};

export const FORBIDDEN_SENTENCE = ERROR_CODE_SENTENCES.FORBIDDEN;
export const NOT_FOUND_SENTENCE = ERROR_CODE_SENTENCES.NOT_FOUND;
export const NETWORK_SENTENCE = 'No se pudo conectar. Intenta de nuevo.';
export const READ_ONLY_SENTENCE = 'Tu rol solo permite consultar mensajes.';
export const FROZEN_SENTENCE = 'El proveedor no confirmó el envío. Un administrador debe resolverlo.';
export const EMPTY_LIST_SENTENCE = 'No hay mensajes en esta vista.';

export function statusLabel(status: MessageStatus): string {
  return STATUS_LABELS[status] ?? status;
}

export function statusBadgeVariant(status: MessageStatus): BadgeVariant {
  return STATUS_VARIANTS[status] ?? 'neutral';
}

export function failureCategoryLabel(category: FailureCategory): string {
  return FAILURE_CATEGORY_LABELS[category] ?? category;
}

export function attemptOutcomeLabel(outcome: SendAttemptOutcome): string {
  return ATTEMPT_OUTCOME_LABELS[outcome] ?? outcome;
}

export function eventTypeLabel(eventType: MessageEventType): string {
  return EVENT_TYPE_LABELS[eventType] ?? eventType;
}

export function actorKindLabel(actorKind: ActorKind): string {
  return ACTOR_KIND_LABELS[actorKind] ?? actorKind;
}

export function originLabel(origin: string): string {
  if (origin === 'AI_DRAFT') return 'IA';
  if (origin === 'MANUAL') return 'Manual';
  return origin;
}

export function changedSentence(newStatus: MessageStatus): string {
  return `Este mensaje cambió: ahora está ${statusLabel(newStatus)}.`;
}

export function rateLimitedSentence(seconds: number): string {
  return `Se alcanzó el límite de envíos por minuto. Espera ${seconds} s.`;
}

/**
 * The sentence for a refusal code. `STALE_VERSION`, `INVALID_TRANSITION` and `RATE_LIMITED` are
 * handled by their own flows (reload alert, countdown) and are not expected here; they still get the
 * generic fallback so a caller that reaches this never renders nothing.
 */
export function errorCodeSentence(code: string): string {
  return ERROR_CODE_SENTENCES[code] ?? `No se pudo completar la acción (${code}).`;
}
