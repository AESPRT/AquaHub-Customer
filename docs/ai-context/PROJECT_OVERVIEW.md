# AquaHub Customer Project Overview

## Purpose and identity

AquaHubCustomer is the customer-facing Android app for discovering water stations, browsing products, placing cash delivery/pickup orders, and following order status. It also contains the active Firebase callable/trigger backend. Namespace/application ID: `com.aesprt.aquahub_customer`. The Android build has one `:app` module.

## Actual stack

- Kotlin, Android SDK 37/min 26, Java 11, Compose and Material 3.
- Navigation Compose, Koin, coroutines, `StateFlow`.
- Firebase Auth, named Firestore database `aquahub`, Firebase Functions, FCM and App Check.
- Fused Location; no Google Map screen or Room database.
- Firebase SDK disk cache provides implicit offline reads; application session/cart/address state is memory-only.
- Active Functions use TypeScript/Node 22 in `functions/`.
- No Retrofit/OkHttp REST layer and no online-payment SDK.

## Architecture summary

This is a compact MVVM-style architecture:

```text
Compose UI → CustomerViewModel → repository interface → Firebase/location implementation
```

`CustomerViewModel` is a central feature-spanning state holder for session, profile, location, discovery, catalog, cart, checkout and orders. There is no use-case layer or local database. Koin registers four singleton repositories and one ViewModel in `CustomerModule`.

Catalog and orders use Firestore callback flows/live listeners. Auth uses Google/Firebase. Mutating orders uses callable Functions rather than client Firestore writes.

## Primary source map

| Concern | Source |
|---|---|
| Application/App Check/DI | `app/src/main/java/com/aesprt/aquahub_customer/AquaHubCustomerApplication.kt` |
| Session shell | `ui/CustomerApp.kt` |
| State/actions | `ui/CustomerViewModel.kt` |
| Domain and contracts | `domain/Models.kt`, `domain/Repositories.kt` |
| Firebase auth/catalog/orders | `data/auth/`, `data/catalog/`, `data/order/` |
| Location | `data/location/` |
| Navigation | `ui/navigation/` |
| Screens/UI system | `ui/feature/`, `ui/components/`, `ui/theme/` |
| Backend | `functions/src/index.ts` |
| Rules/tests | `firestore.rules`, `firestore.indexes.json`, `firebase-tests/` |

## Configuration and boundaries

Firebase build configuration is provided by `google-services.json`; never document its values. Debug builds install debug App Check and non-debug builds use Play Integrity. Callable Functions enforce and consume App Check tokens. Actual console registration, deployment and production signing state are `UNKNOWN` from repository source.

The manifest declares internet/network/location/notification permissions and FCM service. Backup is disabled. There are debug/release build types, no flavors, no source-controlled production signing setup, and release optimization is currently disabled.

## Status at a glance

The core Firebase ordering loop exists locally, but delivery configuration/stock integrity have cross-app gaps. Scheduling, saved addresses, notification UX and live delivery tracking are incomplete or cosmetic. See `FEATURES_AND_FLOWS.md` and workspace `KNOWN_RISKS_AND_STATUS.md`.

