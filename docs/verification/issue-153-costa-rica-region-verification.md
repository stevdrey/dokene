# Issue 153 verification: Costa Rica (CR) in the customer phone regions

Scope: frontend only (`frontend/src/features/customers`). The backend already accepted `CR` through libphonenumber (ADR 0009) and `detectRegionFromE164` already mapped `+506`; only the selectable region list and the client-side rule were missing. Found in QA suite #119 (REG-03).

## Change

- `CustomerFormModal.tsx`: `SUPPORTED_REGIONS` gains `Costa Rica (+506)`; it feeds both the customer form and the phone search selector.
- `phoneValidation.ts`: `REGION_RULES.CR` (calling code 506, 8 national digits, message "Costa Rica requiere 8 dígitos").

## Automated

`npm test` in `frontend/`: all frontend tests pass (new: CR validation rule, search selector option, form submission with region `CR`). `npm run build` succeeds.

## Manual browser verification

Real stack from `./scripts/dev-env.sh up` (containers rebuilt with the change), OPERATOR in a synthetic workspace.

| Check | Result |
|---|---|
| Customer form lists "Costa Rica (+506)" and the search selector lists "CR (+506)" | PASS |
| Region CR + `123` shows the field-level error "El número ingresado no es válido para la región seleccionada (Costa Rica requiere 8 dígitos)." | PASS |
| Region CR + `8888 7766` creates the customer, stored as `+50688887766` | PASS |
| Search with region CR + `8888 7766` returns exactly that customer | PASS |
| Edit form of that customer preselects region CR with the E.164 number | PASS |
