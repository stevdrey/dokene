// ABOUTME: The single label and sentence table for the message review component, in Latin American Spanish.
// ABOUTME: Every enum lookup falls back to the raw name so an unknown backend value still renders as text.
import type {
  FailureCategory,
  MessageActorKind,
  MessageEventType,
  MessageStatus,
  SendAttemptOutcome
} from '../types';

/** Main's httpClient does not expose Retry-After, so the spec fixes the wait. */
export const RATE_LIMIT_SECONDS = 60;

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
  SENT: 'Enviado al proveedor',
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
  IDEMPOTENCY_KEY_REUSED: 'Esta acción ya se registró con otros datos. Actualiza e intenta de nuevo.',
  APPROVAL_INVALIDATED: 'La aprobación ya no es válida. Revisa el mensaje de nuevo.',
  RATE_LIMITED: 'Hay demasiadas solicitudes. Espera un momento e intenta de nuevo.'
};

export const COPY = {
  forbidden: ERROR_CODE_SENTENCES.FORBIDDEN,
  notFound: ERROR_CODE_SENTENCES.NOT_FOUND,
  readOnly: 'Tu rol solo permite consultar mensajes.',
  sessionEnded: 'Tu sesión terminó. Vuelve a iniciar sesión.',
  loading: 'Cargando mensaje…',
  loadFailed: 'No se pudo cargar el mensaje.',
  eventsLoading: 'Cargando historial…',
  eventsFailed: 'No se pudo cargar el historial del mensaje.',
  eventsEmpty: 'Sin eventos registrados todavía.',
  refreshFailed: 'No se pudo actualizar el mensaje. Intenta de nuevo.',
  frozen: 'El proveedor no confirmó el envío. Un administrador debe resolverlo.',
  contactRemoved:
    'El teléfono ya no pertenece a este cliente. Este mensaje no puede avanzar; cancélalo y vuelve a redactarlo con el número correcto.',
  submitConfirm:
    'Se enviará a aprobación exactamente el texto mostrado. Nada se envía al cliente hasta que alguien lo apruebe y lo envíe.',
  submitted: 'Mensaje enviado a aprobación. Aún no se ha enviado al cliente.',
  composeStale:
    'La situación del cliente cambió desde que se redactó este mensaje. Actualiza el seguimiento antes de enviarlo a aprobación.',
  correctionStale: 'La situación del cliente cambió. Vuelve a redactar el mensaje desde el seguimiento.',
  sendConfirm: 'Se enviará exactamente el texto mostrado arriba.',
  correctConfirm:
    'Corregir cancela este mensaje y abre una copia editable. El nuevo mensaje necesitará una nueva aprobación.',
  versionMoved: 'Este mensaje cambió. Revísalo de nuevo antes de continuar.',
  approved: 'Mensaje aprobado. Aún no se ha enviado.',
  rejected: 'Mensaje rechazado. No se enviará al cliente.',
  cancelled: 'Mensaje cancelado. No se enviará al cliente.',
  mayHaveCompleted: 'La acción pudo haberse completado. Revisa el estado actual.',
  unconfirmed: 'No se pudo confirmar la acción.',
  actionFailed: 'No se pudo completar la acción.',
  sendLimitReached: 'Se alcanzó el límite de envíos.',
  unchanged: 'Sin cambios desde la última lectura.',
  policyUpdatedTextKept: 'El seguimiento se actualizó. Tu texto se conserva; revísalo antes de enviarlo a aprobación.',
  policyUpdatedDraft: 'El seguimiento se actualizó. El borrador mostrado corresponde a la situación actual.',
  correctionContactRemoved:
    'El teléfono ya no pertenece a este cliente. Redacta un mensaje nuevo con el número correcto.'
} as const;

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

export function eventTypeLabel(type: MessageEventType): string {
  return EVENT_TYPE_LABELS[type] ?? type;
}

export function actorKindLabel(actorKind: MessageActorKind): string {
  return ACTOR_KIND_LABELS[actorKind] ?? actorKind;
}

export function originLabel(origin: string): string {
  if (origin === 'AI_DRAFT') return 'Borrador de IA';
  if (origin === 'MANUAL') return 'Manual';
  return origin;
}

export function revisionLabel(version: number): string {
  return `Revisión ${version}`;
}

export function changedSentence(status: MessageStatus): string {
  return `Este mensaje cambió: ahora está ${statusLabel(status)}.`;
}

/** A replayed submit returns the message as it is now, which may be well past PENDING_APPROVAL. */
export function submittedSentence(status: MessageStatus): string {
  return status === 'PENDING_APPROVAL'
    ? COPY.submitted
    : `Este mensaje ya se había enviado a aprobación. Estado actual: ${statusLabel(status)}.`;
}

/** A replayed approval returns the message as it is now, which another operator may have sent or cancelled. */
export function approvedSentence(status: MessageStatus): string {
  return status === 'APPROVED' ? COPY.approved : `Este mensaje ya se había aprobado. Estado actual: ${statusLabel(status)}.`;
}

export function sendResultSentence(status: MessageStatus): string {
  return `Envío procesado. Estado actual: ${statusLabel(status)}.`;
}

export function waitSentence(seconds: number): string {
  return `Espera ${seconds} s.`;
}

export function rateLimitedSentence(seconds: number): string {
  return `${COPY.sendLimitReached} ${waitSentence(seconds)}`;
}

/** The problem code appears only as a parenthesised fallback for a code this table does not name. */
export function errorCodeSentence(code: string): string {
  return ERROR_CODE_SENTENCES[code] ?? `No se pudo completar la acción (${code}).`;
}
