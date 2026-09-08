# AquaHub Customer Architecture

## Runtime structure

`AquaHubCustomerApplication` initializes App Check and Koin. `MainActivity` applies `AquaHubCustomerTheme` and hosts `CustomerApp`. The app shell chooses splash/onboarding/auth/main content from `CustomerUiState`; the signed-in main shell owns one navigation controller and bottom bar.

```text
Compose screen
  ↕ CustomerUiState / method calls
CustomerViewModel
  ↓ repository interfaces
FirebaseAuthRepository | FirestoreCatalogRepository
FirebaseOrderRepository | AndroidLocationRepository
  ↔ Firebase SDK / Fused Location

Order mutations
FirebaseOrderRepository → callable Function → Firestore transaction
```

This is MVVM-style but highly centralized. There is no separate use-case/domain-service layer. Add small, tested domain operations before making `CustomerViewModel` more monolithic; do not move trusted server validation into the client.

## State management

`CustomerViewModel` exposes a combined immutable `CustomerUiState`. It owns authentication/profile, discovery query/location, station/product listeners, cart, checkout inputs, addresses and customer-order listener jobs.

Listener/job lifetime must follow Firebase user and selected station. Cancel prior catalog/order collections when identity or station changes. Avoid duplicate collectors and stale results after logout. Cart should clear on station change because lines belong to one station.

Current volatile state:

- onboarding completion is process-memory only;
- cart is process-memory only;
- saved addresses are UI-state only;
- checkout scheduling values are local composable state and never reach the ViewModel/request.

## Dependency injection

`di/CustomerModule.kt` registers singleton `AuthRepository`, `CatalogRepository`, `LocationRepository`, and `OrderRepository` implementations plus `CustomerViewModel`. Keep Firebase object provisioning consistent with `FirebaseConfig`/the SDK and use constructor injection. Do not instantiate repositories in screens.

## Authentication and authorization

The app supports Google sign-in through Firebase Auth. `users/{uid}` stores/updates a customer profile. Backend order functions require an active authenticated customer role and App Check. Firestore rules limit customer writes and user-scoped mirrors/devices.

Role strings are not a business-membership substitute. Customer-side profile creation can choose from allowed role strings under current rules, but owner data access still requires membership. Review this boundary for any authorization work.

## Data/error behavior

Catalog snapshots carry data, cache metadata and an optional error. Orders/profile listeners are less consistent: some errors are ignored and unchecked Firestore casts inside tolerant mapping can silently omit a malformed order. Correctness-sensitive work should expose a visible/retryable failure and retain last known good state deliberately.

Firestore listeners give live updates and Firebase's default offline cache, but there is no explicit cache policy, Room database, paging or account cache-clearing layer. User order paths are UID-scoped; public station/product cache can be stale.

## Architectural decisions

- Compose/Material 3 and a single signed-in NavHost.
- Central ViewModel and repository abstractions for a small app.
- Firebase live listeners instead of an owner-style local database/outbox.
- Callable Functions as the trusted mutation boundary.
- Integer-centavo domain money with checked arithmetic.
- Debug/Play Integrity App Check by build type.

