# AquaHub Customer Project Overview

## Purpose and identity

AquaHubCustomer is the customer-facing Android app for discovering water stations, browsing products, placing cash delivery/pickup orders, and following order status. It also contains the active Firebase callable/trigger backend. Namespace/application ID: `com.aesprt.aquahub_customer`. The Android build has one `:app` module.

## Actual stack

- Kotlin, Android SDK 37/min 26, Java 11, Compose and Material 3.
- Navigation Compose, Koin, coroutines, `StateFlow`.
- Supabase Auth/PostgREST/Edge Functions, Room cache, and Firebase Cloud Messaging for transport.
- Coil renders optional station and product image URLs projected by the owner app; existing icons remain the fallback.
- Fused Location and Google Maps/Places location picker; Room profile/address cache and DataStore appearance preference.
- Firebase SDK disk cache provides implicit offline reads; cart, onboarding, and checkout scheduling remain process-memory state.
- Trusted server operations use Supabase Edge Functions under the workspace `supabase/functions/`.
- No Retrofit/OkHttp REST layer and no online-payment SDK.

## Architecture summary

This is a compact MVVM-style architecture:

```text
Compose UI → CustomerViewModel → repository interface → Firebase/location implementation
```

`CustomerViewModel` is a central feature-spanning state holder for session, profile, location, discovery, catalog, cart, checkout and orders. Koin also registers the Room database/DAOs and ThemePreferences in `CustomerModule`; there is still no separate use-case layer. Keep trusted order validation in Functions.

Catalog and orders use Firestore callback flows/live listeners. Auth uses email/password and Google Firebase providers. Mutating orders uses callable Functions rather than client Firestore writes.

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
| Backend | `supabase/functions/` and `supabase/migrations/` |
| Rules/tests | `firestore.rules`, `firestore.indexes.json`, `firebase-tests/` |

## Configuration and boundaries

Firebase build configuration is retained only for FCM transport and must never be documented with its values. Supabase Auth providers and Edge Functions are the active authentication/business backend. Historical Firestore/Storage configuration remains available only for non-destructive data operations; it is not an Android runtime dependency.

The manifest declares internet/network/location/notification permissions and FCM service. Backup is disabled. There are debug/release build types, no flavors, no source-controlled production signing setup, and release optimization is currently disabled.

## Status at a glance

The core Firebase ordering loop remains server-authoritative and cross-app compatible. Profile completion, UID-scoped local caches, theme selection, email/Google auth, account deletion, and Owner sync wake-up paths are implemented. Scheduling remains cosmetic by contract, and live delivery tracking/payment providers are not implemented. See `FEATURES_AND_FLOWS.md` and workspace `KNOWN_RISKS_AND_STATUS.md`.
