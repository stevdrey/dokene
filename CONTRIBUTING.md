# Contributing to Dokene

Thanks for contributing.

## Development principles

1. Preserve tenant isolation and security invariants.
2. Prefer small, reviewable changes.
3. Keep domain logic independent from concrete AI and messaging providers.
4. Add tests for security-sensitive behavior and cross-tenant access.
5. Never commit secrets or real customer data.
6. Update ADRs when introducing architectural decisions.

## Pull requests

A PR should explain:

- what changes,
- why it is needed,
- security impact,
- tenant-isolation impact,
- tests performed.

## License

By contributing, you agree that your contributions are licensed under AGPL-3.0-or-later unless otherwise explicitly agreed.
