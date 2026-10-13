# Self-hosted fonts

The SPA is served under a strict Content-Security-Policy (`font-src 'self'`, [ADR 0023](../../../docs/adr/0023-spa-and-bff-response-security-headers.md)), so fonts are vendored here instead of loaded from a CDN.

| File | Source | License | sha256 |
| --- | --- | --- | --- |
| `inter-variable.woff2` | Inter v4.1, `web/InterVariable.woff2` from https://github.com/rsms/inter/releases/tag/v4.1 | SIL OFL 1.1 (`Inter-OFL.txt`) | `693b77d4f32ee9b8bfc995589b5fad5e99adf2832738661f5402f9978429a8e3` |
| `material-symbols-outlined.woff2` | Google Material Symbols Outlined | Apache 2.0 | n/a |
