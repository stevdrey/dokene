// ABOUTME: The outcome, guidance and sentence tables of the delivery history (spec 0012), in Latin American Spanish.
// ABOUTME: Provider acceptance never reads as delivered here, and no sentence ever offers a resend.
import type { BadgeVariant } from './messageLabels';

export type DeliveryOutcomeKind =
  | 'notSent'
  | 'queued'
  | 'sending'
  | 'uncertain'
  | 'accepted'
  | 'delivered'
  | 'read'
  | 'failed'
  | 'cancelled'
  | 'rejected'
  | 'unknown';

export interface DeliveryOutcome {
  outcome: DeliveryOutcomeKind;
  label: string;
  secondary: string | null;
  variant: BadgeVariant;
}

const UNCERTAIN_LINE =
  'El proveedor no confirmó el envío. No lo reenvíes; actualiza o pide a un administrador que lo resuelva.';

const OUTCOMES: Readonly<Record<string, DeliveryOutcome>> = {
  PENDING_APPROVAL: { outcome: 'notSent', label: 'Aún no enviado', secondary: 'Pendiente de aprobación', variant: 'warning' },
  APPROVED: { outcome: 'notSent', label: 'Aún no enviado', secondary: 'Aprobado, sin enviar', variant: 'neutral' },
  QUEUED: { outcome: 'queued', label: 'En cola', secondary: 'Esperando el envío al proveedor', variant: 'warning' },
  SENDING: {
    outcome: 'sending',
    label: 'Enviando al proveedor',
    secondary: 'Esperando la respuesta del proveedor',
    variant: 'warning'
  },
  SENT: {
    outcome: 'accepted',
    label: 'Aceptado por el proveedor',
    secondary: 'Aún sin confirmación de entrega',
    variant: 'neutral'
  },
  DELIVERED: { outcome: 'delivered', label: 'Entregado', secondary: 'Entregado al teléfono del cliente', variant: 'success' },
  READ: { outcome: 'read', label: 'Leído', secondary: 'El cliente abrió el mensaje', variant: 'success' },
  FAILED: { outcome: 'failed', label: 'Fallido', secondary: 'No se entregó al cliente.', variant: 'error' },
  CANCELLED: { outcome: 'cancelled', label: 'Cancelado', secondary: 'No se enviará al cliente', variant: 'archived' },
  REJECTED: { outcome: 'rejected', label: 'Rechazado', secondary: 'No se enviará al cliente', variant: 'archived' }
};

const UNCERTAIN: DeliveryOutcome = {
  outcome: 'uncertain',
  label: 'Resultado sin confirmar',
  secondary: UNCERTAIN_LINE,
  variant: 'error'
};

/** `status` is any string on purpose: a value the contract does not know yet renders raw instead of breaking the list. */
export function deliveryOutcome(status: string, outcomeUnknown: boolean): DeliveryOutcome {
  if (status === 'SENDING' && outcomeUnknown) return UNCERTAIN;
  return OUTCOMES[status] ?? { outcome: 'unknown', label: status, secondary: null, variant: 'neutral' };
}

const GUIDANCE: Readonly<Record<string, string>> = {
  INVALID_RECIPIENT:
    'El número no es válido para WhatsApp. Verifica el teléfono del cliente antes de redactar otro mensaje.',
  TEMPLATE_REJECTED:
    'El proveedor rechazó la plantilla. Pide a un administrador que revise las plantillas antes de redactar otro mensaje.',
  RATE_LIMITED: 'El proveedor limitó los envíos. Espera un rato antes de redactar otro mensaje.',
  PROVIDER_UNAVAILABLE:
    'El proveedor no estaba disponible. Revisa el estado de la integración antes de redactar otro mensaje.',
  AUTHENTICATION: 'La integración no pudo autenticarse con el proveedor. Pide a un administrador que revise la integración.',
  POLICY_VIOLATION: 'El proveedor rechazó el mensaje por su política. Revisa el texto antes de redactar otro.',
  UNKNOWN: 'No se conoce la causa. Revisa el historial del mensaje antes de volver a contactar al cliente.'
};

/** A missing or unlisted category gets the UNKNOWN guidance; the caller still labels the raw category. */
export function failureGuidance(category: string | null): string {
  return (category === null ? undefined : GUIDANCE[category]) ?? GUIDANCE.UNKNOWN;
}

export const HISTORY_COPY = {
  heading: 'Historial de mensajes',
  loading: 'Cargando mensajes del cliente…',
  empty: 'Este cliente no tiene mensajes todavía.',
  loadFailed: 'No se pudo cargar el historial.',
  moreFailed: 'No se pudieron cargar más mensajes.',
  moreKeepsFailing: 'Si sigue fallando, usa Actualizar historial.',
  refreshFailed: 'No se pudo actualizar el historial. Intenta de nuevo.',
  rowLoadFailed: 'No se pudo cargar el mensaje.',
  forbidden: 'No tienes permiso para ver estos mensajes.',
  unchanged: 'Sin cambios desde la última lectura.',
  noAutomaticResend: 'Este mensaje no se reenviará automáticamente.',
  contactRemoved: 'El teléfono ya no pertenece a este cliente.',
  refreshList: 'Actualizar historial',
  loadMore: 'Cargar más',
  refreshRow: 'Actualizar',
  retry: 'Reintentar',
  openInReview: 'Abrir en revisión',
  acceptedAt: 'Aceptado por el proveedor',
  deliveredAt: 'Entregado',
  readAt: 'Leído'
} as const;

/** With `sameLabel` the secondary line follows, so a change the label alone cannot show is still heard. */
export function outcomeChangedSentence(outcome: DeliveryOutcome, sameLabel = false): string {
  const sentence = `El estado cambió: ahora ${outcome.label}.`;
  if (!sameLabel || outcome.secondary === null) return sentence;
  return `${sentence} ${outcome.secondary.replace(/\.$/, '')}.`;
}

export function loadedMoreSentence(count: number): string {
  return `Se cargaron ${count} mensajes más.`;
}
