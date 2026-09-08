# AquaHub Customer App Agent Guide

AquaHubCustomer is the customer ordering Android app and owns the active Firebase Functions package. Read `docs/ai-context/PROJECT_OVERVIEW.md`, then the relevant focused document.

## Architecture route

- App/session shell: `app/src/main/java/com/aesprt/aquahub_customer/ui/CustomerApp.kt`.
- Navigation: `ui/navigation/`.
- Screens/components/theme: `ui/feature/`, `ui/components/`, `ui/theme/`.
- Central state/events: `ui/CustomerViewModel.kt`.
- Domain models/contracts: `domain/Models.kt`, `domain/Repositories.kt`.
- Firebase/location repositories: `data/`; Koin: `di/CustomerModule.kt`.
- Active callable/trigger backend: `functions/src/index.ts`.
- Authorization/indexes: `firestore.rules`, `firestore.indexes.json`; rules tests: `firebase-tests/`.

## Change rules

1. Customer order creation/cancellation remains server-authoritative. Do not add direct client writes to station orders.
2. Preserve Firebase Auth, App Check, membership/customer authorization, server-side totals and idempotency.
3. Use integer centavos and exact shared enum values at every boundary.
4. Any Station/Product/Customer/Address/Order/Delivery/Payment/status change requires inspection of AquaHub, Functions, and both rules/index files.
5. Surface listener/parsing/connectivity failures; do not silently present missing remote records as an empty success.
6. Cart, saved addresses and onboarding are currently in-memory. Do not describe them as persisted or rely on them surviving process death.
7. Checkout date/time UI is not wired to the request. Do not extend cosmetic scheduling without an end-to-end contract.
8. Reuse the blue/cyan customer design system and established Home → Station → Cart → Checkout → Tracking flow.
9. `aquahub-functions/` is not the deployed source under current Firebase configuration; avoid editing it instead of `functions/`.
10. Never expose Firebase configuration, tokens, customer data, or secret values.

## Verification

Run `./gradlew testDebugUnitTest assembleDebug` from this directory. For Functions changes, run the package build/tests/lint scripts that exist in `functions/package.json`. For access/path/query changes, run the Firebase rules tests. Emulator/deployment verification is separate and must not be assumed.

Deeper context:

- `docs/ai-context/ARCHITECTURE.md`
- `docs/ai-context/FEATURES_AND_FLOWS.md`
- `docs/ai-context/NAVIGATION.md`
- `docs/ai-context/DATA_AND_BACKEND.md`
- workspace `../docs/ai-context/SHARED_DOMAIN_AND_CONTRACTS.md`

