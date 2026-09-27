# AquaHub Customer Data and Backend

## Local persistence

Customer profile and saved-address caches use Room (`CustomerDatabase` v3) and are scoped by Firebase UID. Profile cache migration 2→3 adds nullable preferred business/station IDs, acquisition source, and acquisition time. Appearance mode uses a DataStore preference. Cart, onboarding completion, and checkout scheduling remain process-memory state by design; they must not be described as durable across process death. Cloud deletion clears Room and DataStore state only after the callable succeeds.

## Domain models

`domain/Models.kt` defines:

- `Money(Long centavos)` with non-negative checked addition/multiplication and PHP formatting.
- validated `GeoPoint` and Haversine distance.
- `CustomerProfile`, map-resolved `DeliveryAddress`, `LocationSuggestion`, and `ResolvedLocation`.
- `PublicStation` with business/station identity, location, online acceptance, hours, manual-open override, delivery radius/fee/preparation time.
- `PublicProduct`, `ProductType`, `CartLine`.
- `DeliveryMode`, cash `PaymentMethod`, shared `OrderStatus`.
- `CustomerOrder`, `CreateOrderRequest`, `CatalogSnapshot`, `AppResult`.
- `CustomerAcquisitionSource` and `PendingStationLink` model station-owned versus organic entry. Station links resolve by the public station code, then persist the preferred relationship through the server callable.

These are customer/public projections, not identical to AquaHub Room entities. See workspace shared contracts before changing them.

## Repositories

| Contract/implementation | Responsibility |
|---|---|
| `AuthRepository` / `FirebaseAuthRepository` | Firebase email/password and Google session, verification/reset, profile observation/update, reauthentication, and server-authoritative deletion. |
| `CatalogRepository` / `FirestoreCatalogRepository` | Live station discovery and selected-station products with cache metadata. |
| `LocationRepository` / `AndroidLocationRepository` | Fused device location, Places autocomplete/details, map reverse geocoding and Geocoder fallback. |
| `OrderRepository` / `FirebaseOrderRepository` | Callable create/cancel, live UID-scoped order mirrors, FCM device token registration. |

No HTTP base URL/client/interceptor/serializer/timeouts exist. Firebase SDK errors are mapped to `AppResult` or flow behavior. Avoid unchecked Firestore casts and ignored listener errors in new mapping.

The Customer App declares an Android App Link for `https://aquahub.aesprt.com/s/{publicStationCode}` and continues parsing the legacy `order.aquahub.app` host for backward compatibility. MainActivity retains cold-start and warm-start intents; CustomerViewModel persists the pending destination in DataStore through email/Google registration and resolves it after authentication. Customers acquired through a station link see only that business's branches until they intentionally enter discovery.

## Active Functions package

Firebase configuration targets `functions/`, whose TypeScript source is `functions/src/index.ts` and runtime is Node 22. The separate `aquahub-functions/` scaffold is inactive.

### `createCustomerOrder`

- Region `asia-southeast1`; authentication and enforced/consumed App Check required.
- Requires an active `CUSTOMER` profile.
- Validates station/item identifiers, 1–20 distinct items, quantities 1–99, station activation/acceptance, product existence/availability, integer prices, delivery inputs/coordinate ranges and optional radius.
- Recalculates subtotal/fee/total from trusted documents.
- Uses `customerOrderRequests/{uid}_{idempotencyKey}` to return the prior result for duplicate requests.
- Transaction writes the station customer, owner-facing order (including delivery place ID/coordinates), customer mirror, request record and audit record; then notifies business member devices.
- Validates available `stockQuantity` and decrements it transactionally when the product carries a stock value.

### `cancelCustomerOrder`

Requires auth/App Check and allows customer mirrors in `PENDING` or `ACCEPTED`. It validates ownership and reads the station-side order status inside the transaction, so a stale customer mirror cannot authorize cancellation over a newer Owner transition.

### `mirrorCustomerOrderStatus`

A Firestore document-write trigger on the named database mirrors the station order to `users/{customerUid}/orders/{orderId}` and sends a status notification. Ensure new order fields are safe and necessary before copying the whole document into a customer-readable path.

## Rules and indexes

Customer rules allow active customer discovery of station documents/products, self profile/device/order-mirror access, business-member private access, and deny direct customer order/audit/request writes. Server SDK writes bypass client rules and must perform explicit authorization.

Rules tests in `firebase-tests/` cover customer discovery, private resource isolation, direct order denial, staff station boundaries, subscription authority, first-owner bootstrap, profile privilege-field protection, setup-complete validation, forged email-verification state, and owner tenant/station boundaries. Coverage does not replace callable business-logic tests.

`firestore.rules` and `firestore.indexes.json` are synchronized with the owner project. No custom index is currently required: implemented queries use automatic single-field indexes, and the unfiltered station collection-group query needs no manual index. This project's `firebase.json` is the canonical deployment configuration and targets the named `aquahub` database. The staff-access hardening was rules-tested and deployed on 2026-09-12; staff can read assigned stations/orders and riders needed for order fulfillment, while management collections remain owner/manager-only. Keep both project copies byte-identical and rerun the shared rules suite before future deployment.

`verifyPlaySubscription` and `playSubscriptionNotifications` run as the dedicated `aquahub-play-billing@aquahub-506411.iam.gserviceaccount.com` identity, which must have the Play Console billing permissions plus Google Cloud IAM access to Firestore (`roles/datastore.user`) and App Check token verification (`roles/firebaseappcheck.tokenVerifier`). The service-account binding was deployed on 2026-09-16 after the default Compute service account returned HTTP 401 from the Android Publisher API. The callable logs the exact Play/Firestore response code, message, and debug details for failures; the Datastore role remains an external deployment prerequisite until confirmed in project IAM.

## Public-station compatibility

Customer mapping/backend expects delivery radius, fee centavos, preparation minutes and online-order acceptance. Owner documents do not reliably publish/manage all fields. Current defaults can mean zero radius/fee. Resolve via a versioned public contract or explicit migration/default behavior across both projects.

## Notifications and security

FCM tokens are stored under each user's device collection and Functions fans out messages. `onNewToken` registration alone does not guarantee the current token is registered after login. Runtime notification permission, foreground handling and order deep links are incomplete.

Never log or document Firebase configuration, App Check artifacts, full FCM tokens, auth tokens, or customer-private fields. Email/password and Google Auth providers are configured in `firebase.json` and deployed. The Customer debug App Check token is registered for the connected Samsung SM-A366B debug build; delete it after testing and never commit it. Release Play Integrity/SHA configuration and monitoring/rate limiting still require production-console verification.
