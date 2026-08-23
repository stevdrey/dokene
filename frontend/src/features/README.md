# Frontend feature boundaries

The frontend is organized by product feature rather than by technical layer.

Planned feature areas:

- `auth`
- `customers`
- `followups`
- `messages`
- `templates`
- `integrations`
- `settings`

Shared components should be extracted only when they are genuinely shared. API contracts should be generated or validated against the backend OpenAPI definition rather than duplicated manually.
