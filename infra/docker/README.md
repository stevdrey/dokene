# Docker infrastructure

Container definitions will live here as the application becomes deployable.

Security baseline for runtime images:

- non-root user,
- minimal base image,
- read-only filesystem where practical,
- no embedded secrets,
- dropped Linux capabilities unless explicitly required,
- separate build and runtime stages.

Local PostgreSQL is currently defined in the root `compose.yaml`.
