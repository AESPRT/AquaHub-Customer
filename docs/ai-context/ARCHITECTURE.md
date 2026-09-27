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
- cart is process-memory only and is protected by an explicit confirmation before switching stations;
- pending external station links are stored in DataStore until they resolve or the user signs out;
- checkout scheduling values are local composable state and never reach the ViewModel/request.

Durable local state:

- the Room customer profile cache includes setup-completion, verification, and primary delivery coordinates;
- saved addresses are stored in a UID-scoped Room table;
- appearance mode is stored in DataStore.

These local caches are cleared after successful cloud account deletion and isolated when the Firebase UID changes.

## Dependency injection

`di/CustomerModule.kt` registers singleton `AuthRepository`, `CatalogRepository`, `LocationRepository`, and `OrderRepository` implementations plus `CustomerViewModel`. Keep Firebase object provisioning consistent with `FirebaseConfig`/the SDK and use constructor injection. Do not instantiate repositories in screens.

## Authentication and authorization

The app supports email/password and Google sign-in through Firebase Auth, including email verification, password reset, and deletion reauthentication. `users/{uid}` stores/updates a customer profile with authoritative `setupComplete` and delivery coordinates. Backend order functions require an active authenticated customer role and App Check. Firestore rules limit customer writes and user-scoped mirrors/devices.

Role strings are not a business-membership substitute. Customer-side profile creation can choose from allowed role strings under current rules, but owner data access still requires membership. Review this boundary for any authorization work.

## Data/error behavior

Catalog snapshots carry data, cache metadata and an optional error. Order listeners retain the last good snapshot, expose cache metadata and surface listener or malformed-document errors with a retry action; unchecked mapping still skips invalid records but now reports that condition visibly.

Firestore listeners give live updates and Firebase offline cache. The explicit Room profile/address cache is UID-scoped and cleared on account switches/deletion as appropriate; user order paths are UID-scoped and public station/product cache can be stale.

## Architectural decisions

- Compose/Material 3 and a single signed-in NavHost.
- Central ViewModel and repository abstractions for a small app.
- Firebase live listeners instead of an owner-style local database/outbox.
- Callable Functions as the trusted mutation boundary.
- Integer-centavo domain money with checked arithmetic.
- Debug/Play Integrity App Check by build type.
