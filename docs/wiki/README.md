# Dokene Wiki source

This directory is the **canonical editable source** for the Dokene GitHub Wiki.

Why keep the source here instead of editing Wiki pages independently?

- documentation changes can be reviewed in pull requests;
- architecture documentation evolves with code;
- Git history stays visible in the main project;
- contributors and coding agents have one canonical location to update;
- the published Wiki does not drift from repository documentation.

## Pages

- `Home.md`
- `Product-Vision.md`
- `Architecture.md`
- `Security-Model.md`
- `Multi-Tenancy-and-Data.md`
- `AI-and-Automation.md`
- `Messaging-and-Integrations.md`
- `Development-Model.md`
- `Roadmap.md`
- `_Sidebar.md`

## Updating documentation

Edit the applicable file in `docs/wiki/` through the normal branch/PR workflow.

If the change establishes or reverses a durable architecture decision, also add/update an ADR under `docs/adr/`. Accepted ADRs are authoritative for the scope they govern.

The GitHub Wiki is a publication surface. Avoid editing its generated pages independently, because those changes may be replaced by the next synchronization from this directory.

## Wiki publishing

`.github/workflows/publish-wiki.yml` synchronizes this directory to the repository Wiki.

GitHub may require the Wiki to be initialized once through the repository UI before the `.wiki.git` remote accepts pushes. After that one-time initialization, normal documentation updates should flow from `docs/wiki/`.
