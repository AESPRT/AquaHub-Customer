# AquaHub Customer Data and Backend

## Local persistence

There is no Room database, DAO, migration, local repository, or DataStore. Firebase Android SDK caching supports offline snapshots, but cart, onboarding and saved addresses are in-memory. Do not add documentation or logic that assumes durable local customer state without implementing and testing it.

## Domain models

`domain/Models.kt` defines:

- `Money(Long centavos)` with non-negative checked addition/multiplication and PHP formatting.
- validated `GeoPoint` and Haversine distance.
- `CustomerProfile`, map-resolved `DeliveryAddress`, `LocationSuggestion`, and `ResolvedLocation`.
- `PublicStation` with business/station identity, location, online acceptance, hours, delivery radius/fee/preparation time.
- `PublicProduct`, `ProductType`, `CartLine`.
- `DeliveryMode`, cash `PaymentMethod`, shared `OrderStatus`.
- `CustomerOrder`, `CreateOrderRequest`, `CatalogSnapshot`, `AppResult`.

These are customer/public projections, not identical to AquaHub Room entities. See workspace shared contracts before changing them.

## Repositories

| Contract/implementation | Responsibility |
|---|---|
| `AuthRepository` / `FirebaseAuthRepository` | Firebase session, Google sign-in/out, profile observation/update. |
| `CatalogRepository` / `FirestoreCatalogRepository` | Live station discovery and selected-station products with cache metadata. |
| `LocationRepository` / `AndroidLocationRepository` | Fused device location, Places autocomplete/details, map reverse geocoding and Geocoder fallback. |
| `OrderRepository` / `FirebaseOrderRepository` | Callable create/cancel, live UID-scoped order mirrors, FCM device token registration. |

No HTTP base URL/client/interceptor/serializer/timeouts exist. Firebase SDK errors are mapped to `AppResult` or flow behavior. Avoid unchecked Firestore casts and ignored listener errors in new mapping.

## Active Functions package

Firebase configuration targets `functions/`, whose TypeScript source is `functions/src/index.ts` and runtime is Node 22. The separate `aquahub-functions/` scaffold is inactive.

### `createCustomerOrder`

- Region `asia-southeast1`; authentication and enforced/consumed App Check required.
- Requires an active `CUSTOMER` profile.
- Validates station/item identifiers, 1–20 distinct items, quantities 1–99, station activation/acceptance, product existence/availability, integer prices, delivery inputs/coordinate ranges and optional radius.
- Recalculates subtotal/fee/total from trusted documents.
- Uses `customerOrderRequests/{uid}_{idempotencyKey}` to return the prior result for duplicate requests.
- Transaction writes the station customer, owner-facing order (including delivery place ID/coordinates), customer mirror, request record and audit record; then notifies business member devices.
- Does **not** validate/decrement/reserve `stockQuantity`.

### `cancelCustomerOrder`

Requires auth/App Check and allows customer mirrors in `PENDING` or `ACCEPTED`. It validates ownership but can overwrite a newer owner-side status because it does not compare owner status/version atomically. Fixes require a shared transition/version contract.

### `mirrorCustomerOrderStatus`

A Firestore document-write trigger on the named database mirrors the station order to `users/{customerUid}/orders/{orderId}` and sends a status notification. Ensure new order fields are safe and necessary before copying the whole document into a customer-readable path.

## Rules and indexes

Customer rules allow active customer discovery of station documents/products, self profile/device/order-mirror access, business-member private access, and deny direct customer order/audit/request writes. Server SDK writes bypass client rules and must perform explicit authorization.

Rules tests in `firebase-tests/` cover customer discovery, private resource isolation, direct order denial, staff station boundaries, subscription authority, first-owner bootstrap, profile privilege-field protection, and owner tenant/station boundaries. Coverage does not replace callable business-logic tests.

`firestore.rules` and `firestore.indexes.json` are synchronized with the owner project. No custom index is currently required: implemented queries use automatic single-field indexes, and the unfiltered station collection-group query needs no manual index. This project's `firebase.json` is the canonical deployment configuration and targets the named `aquahub` database. The unified rules and index configuration were deployed on 2026-09-05. Keep both project copies byte-identical and rerun the shared rules suite before future deployment.

## Public-station compatibility

Customer mapping/backend expects delivery radius, fee centavos, preparation minutes and online-order acceptance. Owner documents do not reliably publish/manage all fields. Current defaults can mean zero radius/fee. Resolve via a versioned public contract or explicit migration/default behavior across both projects.

## Notifications and security

FCM tokens are stored under each user's device collection and Functions fans out messages. `onNewToken` registration alone does not guarantee the current token is registered after login. Runtime notification permission, foreground handling and order deep links are incomplete.

Never log or document Firebase configuration, App Check artifacts, full FCM tokens, auth tokens, or customer-private fields. Firestore rules/index configuration and the three active Functions were deployed on 2026-09-05. Monitoring/rate limiting and other production console setup remain `UNKNOWN` unless independently verified.
