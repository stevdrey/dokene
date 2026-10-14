import enumsJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/enums.json?raw';
import integrationJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/integration.json?raw';
import messagesJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/messages.json?raw';
import problemsJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/problems.json?raw';
import requestsJson from '../../../../../backend/src/test/resources/contracts/messaging/v1/requests.json?raw';
import { describe, expect, it } from 'vitest';
import type { IntegrationRequest, IntegrationResponse } from '../../integrations/types';
import { SEMANTIC_ACTIONS, SEMANTIC_TEMPLATE_INTENTS } from '../../followups/types';
import {
  APPROVAL_DECISIONS,
  FAILURE_CATEGORIES,
  MESSAGE_ACTOR_KINDS,
  MESSAGE_CHANNELS,
  MESSAGE_EVENT_TYPES,
  MESSAGE_NOTE_MAX_LENGTH,
  MESSAGE_ORIGINS,
  MESSAGE_STATUSES,
  MESSAGING_ERROR_CODES,
  RESOLUTION_OUTCOMES,
  SEND_ATTEMPT_OUTCOMES,
  parseMessagingProblem,
  type ApprovalRequest,
  type ApprovalSummary,
  type AttemptSummary,
  type CancellationRequest,
  type MessageEventItem,
  type MessageEventsResponse,
  type MessageListItem,
  type MessagePageResponse,
  type MessageResponse,
  type MessagingProblem,
  type ResolutionRequest,
  type SubmitMessageRequest
} from '../types';

// Consumer side of the frozen messaging v1 contract (ADR 0024 §1). Shape<T> derives the rule each member must use
// from its TypeScript type (kind, enum values, nullability and optionality), so tsc fails when a DTO member is added,
// removed, retyped or changes nullability without its rule; the runtime check then pins every fixture to the rules.
type Rule =
  | { kind: 'string' | 'uuid' | 'instant' | 'integer' | 'boolean'; nullable?: boolean; optional?: boolean; maxLength?: number }
  | { kind: 'enum'; values: readonly string[]; nullable?: boolean; optional?: boolean }
  | { kind: 'object'; shape: Record<string, Rule>; nullable?: boolean; optional?: boolean }
  | { kind: 'array'; item: Rule; nullable?: boolean; optional?: boolean };

type KindFor<V> = [V] extends [boolean]
  ? { kind: 'boolean' }
  : [V] extends [number]
    ? { kind: 'integer' }
    : [V] extends [readonly (infer E)[]]
      ? { kind: 'array'; item: RuleFor<E, false> }
      : [V] extends [string]
        ? string extends V
          ? { kind: 'string' | 'uuid' | 'instant'; maxLength?: number }
          : { kind: 'enum'; values: readonly V[] }
        : [V] extends [object]
          ? { kind: 'object'; shape: Shape<V> }
          : never;
type RuleFor<V, Optional extends boolean> = KindFor<NonNullable<V>> &
  (null extends V ? { nullable: true } : { nullable?: false }) &
  (Optional extends true ? { optional: true } : { optional?: false });
type OptionalKeys<T> = { [K in keyof T]-?: object extends Pick<T, K> ? K : never }[keyof T];
type Shape<T> = { [K in keyof T]-?: RuleFor<T[K], K extends OptionalKeys<T> ? true : false> };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;
const uuid = { kind: 'uuid' } as const;
const instant = { kind: 'instant' } as const;
const nullableInstant = { kind: 'instant', nullable: true } as const;
const integer = { kind: 'integer' } as const;
const bool = { kind: 'boolean' } as const;
const text = { kind: 'string' } as const;
const enumOf = <const V extends string>(values: readonly V[]) => ({ kind: 'enum', values }) as const;
const nullableEnumOf = <const V extends string>(values: readonly V[]) =>
  ({ kind: 'enum', values, nullable: true }) as const;
const note = { kind: 'string', nullable: true, optional: true, maxLength: MESSAGE_NOTE_MAX_LENGTH } as const;

const approvalShape: Shape<ApprovalSummary> = {
  decision: enumOf(APPROVAL_DECISIONS),
  decidedAt: instant,
  decidedByMembershipId: uuid
};
const attemptShape: Shape<AttemptSummary> = {
  attemptNumber: integer,
  startedAt: instant,
  outcome: enumOf(SEND_ATTEMPT_OUTCOMES),
  failureCategory: nullableEnumOf(FAILURE_CATEGORIES)
};
const messageShape: Shape<MessageResponse> = {
  messageId: uuid,
  customerId: uuid,
  contactId: uuid,
  contactRemoved: bool,
  recipientPhone: text,
  channel: enumOf(MESSAGE_CHANNELS),
  status: enumOf(MESSAGE_STATUSES),
  outcomeUnknown: bool,
  failureCategory: nullableEnumOf(FAILURE_CATEGORIES),
  action: enumOf(SEMANTIC_ACTIONS),
  templateIntent: enumOf(SEMANTIC_TEMPLATE_INTENTS),
  locale: text,
  origin: enumOf(MESSAGE_ORIGINS),
  body: text,
  sourcePolicyVersion: integer,
  approval: { kind: 'object', shape: approvalShape, nullable: true },
  attempts: { kind: 'array', item: { kind: 'object', shape: attemptShape } },
  providerMessageId: { kind: 'string', nullable: true },
  sentAt: nullableInstant,
  deliveredAt: nullableInstant,
  readAt: nullableInstant,
  createdByMembershipId: uuid,
  createdAt: instant,
  updatedAt: instant,
  version: integer
};
const listItemShape: Shape<MessageListItem> = {
  messageId: uuid,
  customerId: uuid,
  contactRemoved: bool,
  status: enumOf(MESSAGE_STATUSES),
  outcomeUnknown: bool,
  action: enumOf(SEMANTIC_ACTIONS),
  templateIntent: enumOf(SEMANTIC_TEMPLATE_INTENTS),
  origin: enumOf(MESSAGE_ORIGINS),
  createdAt: instant,
  updatedAt: instant,
  version: integer
};
const pageShape: Shape<MessagePageResponse> = {
  items: { kind: 'array', item: { kind: 'object', shape: listItemShape } },
  nextCursor: { kind: 'string', nullable: true }
};
const eventShape: Shape<MessageEventItem> = {
  sequenceNumber: integer,
  type: enumOf(MESSAGE_EVENT_TYPES),
  statusFrom: nullableEnumOf(MESSAGE_STATUSES),
  statusTo: enumOf(MESSAGE_STATUSES),
  applied: bool,
  actorKind: enumOf(MESSAGE_ACTOR_KINDS),
  failureCategory: nullableEnumOf(FAILURE_CATEGORIES),
  attemptNumber: { kind: 'integer', nullable: true },
  occurredAt: instant
};
const eventsShape: Shape<MessageEventsResponse> = {
  messageId: uuid,
  events: { kind: 'array', item: { kind: 'object', shape: eventShape } }
};
const problemShape: Shape<MessagingProblem> = {
  type: enumOf(['about:blank']),
  title: text,
  status: integer,
  code: enumOf(MESSAGING_ERROR_CODES)
};
const integrationRequestShape: Shape<IntegrationRequest> = {
  phoneNumberId: text,
  businessAccountId: text,
  displayPhoneNumber: { kind: 'string', nullable: true, optional: true },
  enabled: bool,
  secretRef: text
};
const integrationResponseShape: Shape<IntegrationResponse> = {
  enabled: bool,
  phoneNumberId: text,
  businessAccountId: text,
  displayPhoneNumber: { kind: 'string', nullable: true },
  secretRef: text,
  version: integer
};
const requestShapes = {
  submit: {
    contactId: uuid,
    action: enumOf(SEMANTIC_ACTIONS),
    templateIntent: enumOf(SEMANTIC_TEMPLATE_INTENTS),
    body: text,
    locale: text,
    origin: enumOf(MESSAGE_ORIGINS)
  } satisfies Shape<SubmitMessageRequest>,
  approval: { decision: enumOf(APPROVAL_DECISIONS), note } satisfies Shape<ApprovalRequest>,
  cancellation: { note } satisfies Shape<CancellationRequest>,
  resolution: { outcome: enumOf(RESOLUTION_OUTCOMES), note } satisfies Shape<ResolutionRequest>
};

/** Returns the path of every violation; an empty list means the value matches the shape exactly. */
function violations(value: unknown, shape: Record<string, Rule>, path = '$'): string[] {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return [`${path} is not an object`];
  }
  const record = value as Record<string, unknown>;
  const extra = Object.keys(record)
    .filter((key) => !(key in shape))
    .map((key) => `${path}.${key} is not in the contract`);
  const checked = Object.entries(shape).flatMap(([key, rule]) => {
    if (!(key in record)) {
      return 'optional' in rule && rule.optional ? [] : [`${path}.${key} is missing`];
    }
    return check(record[key], rule, `${path}.${key}`);
  });
  return [...extra, ...checked];
}

function check(value: unknown, rule: Rule, path: string): string[] {
  if (value === null) {
    return 'nullable' in rule && rule.nullable ? [] : [`${path} is null`];
  }
  switch (rule.kind) {
    case 'string':
      return typeof value === 'string' && value.length <= (rule.maxLength ?? Infinity) ? [] : [`${path} is not a valid string`];
    case 'uuid':
      return typeof value === 'string' && UUID.test(value) ? [] : [`${path} is not a UUID`];
    case 'instant':
      return typeof value === 'string' && INSTANT.test(value) ? [] : [`${path} is not a UTC instant`];
    case 'integer':
      return Number.isInteger(value) ? [] : [`${path} is not an integer`];
    case 'boolean':
      return typeof value === 'boolean' ? [] : [`${path} is not a boolean`];
    case 'enum':
      return typeof value === 'string' && rule.values.includes(value) ? [] : [`${path} is not a known value`];
    case 'object':
      return violations(value, rule.shape, path);
    case 'array':
      return Array.isArray(value)
        ? value.flatMap((item, index) => check(item, rule.item, `${path}[${index}]`))
        : [`${path} is not an array`];
  }
}

type Named = Record<string, unknown>;
const enums = JSON.parse(enumsJson) as Record<string, string[]>;
const messages = JSON.parse(messagesJson) as { details: Named; pages: Named; events: Named };
const requests = JSON.parse(requestsJson) as Record<keyof typeof requestShapes, { valid: Named; invalid: Named }>;
const integration = JSON.parse(integrationJson) as {
  request: { valid: Named; invalid: Named };
  response: { valid: Named };
};
const problems = JSON.parse(problemsJson) as { valid: Named; invalid: Named };

describe('messaging v1 contract fixtures', () => {
  it('declares exactly the shared enum values', () => {
    expect(enums).toEqual({
      MessageStatus: [...MESSAGE_STATUSES],
      MessageChannel: [...MESSAGE_CHANNELS],
      MessageOrigin: [...MESSAGE_ORIGINS],
      ApprovalDecision: [...APPROVAL_DECISIONS],
      ResolutionOutcome: [...RESOLUTION_OUTCOMES],
      SendAttemptOutcome: [...SEND_ATTEMPT_OUTCOMES],
      FailureCategory: [...FAILURE_CATEGORIES],
      MessageEventType: [...MESSAGE_EVENT_TYPES],
      MessageActorKind: [...MESSAGE_ACTOR_KINDS],
      MessagingErrorCode: [...MESSAGING_ERROR_CODES],
      SemanticAction: [...SEMANTIC_ACTIONS],
      SemanticTemplateIntent: [...SEMANTIC_TEMPLATE_INTENTS]
    });
  });

  it.each(Object.entries(messages.details))('detail %s matches MessageResponse', (_name, fixture) => {
    expect(violations(fixture, messageShape)).toEqual([]);
  });

  it('details cover every status and every provider outcome', () => {
    const details = Object.values(messages.details) as MessageResponse[];
    expect(new Set(details.map((message) => message.status))).toEqual(new Set(MESSAGE_STATUSES));
    const outcomes = new Set(details.flatMap((message) => message.attempts.map((attempt) => attempt.outcome)));
    expect([...outcomes].sort()).toEqual(['ACCEPTED', 'FAILED_PERMANENT', 'FAILED_TRANSIENT', 'OUTCOME_UNKNOWN']);
  });

  it.each(Object.entries(messages.pages))('page %s matches MessagePageResponse', (_name, fixture) => {
    expect(violations(fixture, pageShape)).toEqual([]);
  });

  it.each(Object.entries(messages.events))('event history %s matches MessageEventsResponse', (_name, fixture) => {
    expect(violations(fixture, eventsShape)).toEqual([]);
  });

  describe.each(Object.keys(requestShapes) as (keyof typeof requestShapes)[])('%s request', (operation) => {
    const shape = requestShapes[operation] as Record<string, Rule>;

    it.each(Object.entries(requests[operation].valid))('accepts %s', (_name, fixture) => {
      expect(violations(fixture, shape)).toEqual([]);
    });

    it.each(Object.entries(requests[operation].invalid))('rejects %s', (_name, fixture) => {
      expect(violations(fixture, shape)).not.toEqual([]);
    });
  });

  it('has one problem per error code and parses each one', () => {
    expect(Object.keys(problems.valid).sort()).toEqual([...MESSAGING_ERROR_CODES].sort());
    for (const [code, fixture] of Object.entries(problems.valid)) {
      expect(violations(fixture, problemShape)).toEqual([]);
      expect(parseMessagingProblem(fixture)?.code).toBe(code);
    }
  });

  it.each(Object.entries(problems.invalid))('refuses invalid problem %s', (_name, fixture) => {
    expect(parseMessagingProblem(fixture)).toBeNull();
  });

  describe('integration', () => {
    it.each(Object.entries(integration.request.valid))('accepts request %s', (_name, fixture) => {
      expect(violations(fixture, integrationRequestShape)).toEqual([]);
    });

    it.each(Object.entries(integration.request.invalid))('rejects request %s', (_name, fixture) => {
      expect(violations(fixture, integrationRequestShape)).not.toEqual([]);
    });

    it.each(Object.entries(integration.response.valid))('response %s matches IntegrationResponse', (_name, fixture) => {
      expect(violations(fixture, integrationResponseShape)).toEqual([]);
    });
  });
});
