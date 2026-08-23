# ADR 0001: Monorepo and Modular Monolith

## Status
Accepted

## Decision
Dokene will begin as a monorepo containing independently buildable `backend/` and `frontend/` applications. The backend will use a modular-monolith architecture rather than microservices.

## Rationale
The product is early-stage and benefits from atomic cross-stack changes, a single issue/PR workflow, simpler local development, and lower operational complexity. Module boundaries remain explicit so that components can be extracted later if independent scaling or release cycles justify it.

## Consequences
- Backend and frontend remain deployable independently.
- Domain modules must not bypass declared boundaries.
- Architectural decisions are documented in ADRs.
- Microservices require a new ADR and evidence of a real boundary or operational need.
