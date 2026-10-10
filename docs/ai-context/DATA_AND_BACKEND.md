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
| `AuthRepository` / `SupabaseAuthRepository` | Supabase email/password and Google session, verification/reset, profile observation/update, and Supabase profile provisioning. |
| `CatalogRepository` / `SupabaseCatalogRepository` | Supabase public station discovery and selected-station products with bounded refresh metadata. |
| `LocationRepository` / `AndroidLocationRepository` | Fused device location, Places autocomplete/details, map reverse geocoding and Geocoder fallback. |
| `OrderRepository` / `SupabaseOrderRepository` | Supabase RPC create/cancel, authenticated order reads, and FCM device-token registration through the Supabase token registry. |

No HTTP base URL/client/interceptor/serializer/timeouts exist. Supabase errors are mapped to `AppResult` or flow behavior. Firebase compatibility code is retained only in the Functions documentation and migration history; avoid reintroducing Firestore business callers.

The Customer App declares an Android App Link for `https://aquahub.aesprt.com/s/{publicStationCode}` and continues parsing the legacy `order.aquahub.app` host for backward compatibility. MainActivity retains cold-start and warm-start intents; CustomerViewModel persists the pending destination in DataStore through email/Google registration and resolves it after authentication. Customers acquired through a station link see only that business's branches until they intentionally enter discovery.

The same QR URL has a browser fallback in `aquahub-website/order-app/`. If the Android app is not installed, Firebase Hosting serves the web ordering client at `/s/{publicStationCode}`. The browser flow is a guest consumer of public station/catalog projections and the `guest-order` Edge Function; it does not change Android order creation or use Firebase business data services. Its nullable `order_items.container_source` field is optional when Android reads order items.

## Active Functions package

Supabase configuration targets `supabase/functions/` and `supabase/migrations/`. The former Firebase Functions package and inactive scaffold were removed after their responsibilities moved to Supabase RPCs and Edge Functions. Firebase remains only as the FCM transport on Android.

### `createCustomerOrder`

- Region `asia-southeast1`; authentication and enforced/consumed App Check required.
- Requires an active `CUSTOMER` profile.
- Validates station/item identifiers, 1–20 distinct items, quantities 1–99, station activation/acceptance, product existence/availability, integer prices, delivery inputs/coordinate ranges and optional radius.
- Recalculates subtotal/fee/total from trusted documents.
- Uses `customerOrderRequests/{uid}_{idempotencyKey}` to return the prior result for duplicate requests.
- Transaction writes the station customer, owner-facing order (including delivery place ID/coordinates), customer mirror, request record and audit record; then notifies business member devices.
- Validates available `stockQuantity` and decrements it transactionally when the product carries a stock value.

> **Current source-of-truth (2026-10-04):** The sections below preserve the historical Firebase contract for migration/reference only. The active customer runtime uses Supabase Auth, Supabase PostgreSQL/RPCs, Supabase Storage where applicable, and Supabase Edge Functions. Firebase is retained only for FCM transport. Analytics and Crashlytics are owner-app observability services; they are not business data authorities. No active customer runtime path uses Firebase Auth, Firestore, Firebase Storage, Firebase Functions, or App Check.

### Historical Firebase contract — `cancelCustomerOrder`

Requires auth/App Check and allows customer mirrors in `PENDING` or `ACCEPTED`. It validates ownership and reads the station-side order status inside the transaction, so a stale customer mirror cannot authorize cancellation over a newer Owner transition.

### Historical Firebase contract — `mirrorCustomerOrderStatus`

A Firestore document-write trigger on the named database mirrors the station order to `users/{customerUid}/orders/{orderId}` and sends a status notification. Ensure new order fields are safe and necessary before copying the whole document into a customer-readable path.

## Historical Firebase rules and indexes

Customer rules allow active customer discovery of station documents/products, self profile/device/order-mirror access, business-member private access, and deny direct customer order/audit/request writes. Server SDK writes bypass client rules and must perform explicit authorization.

Rules tests in `firebase-tests/` cover customer discovery, private resource isolation, direct order denial, staff station boundaries, subscription authority, first-owner bootstrap, profile privilege-field protection, setup-complete validation, forged email-verification state, and owner tenant/station boundaries. Coverage does not replace callable business-logic tests.

`firestore.rules` and `firestore.indexes.json` are synchronized with the owner project. No custom index is currently required: implemented queries use automatic single-field indexes, and the unfiltered station collection-group query needs no manual index. This project's `firebase.json` is the canonical deployment configuration and targets the named `aquahub` database. The staff-access hardening was rules-tested and deployed on 2026-09-12; staff can read assigned stations/orders and riders needed for order fulfillment, while management collections remain owner/manager-only. Keep both project copies byte-identical and rerun the shared rules suite before future deployment.

The former Firebase billing callables and Firestore/App Check permissions are historical deployment notes. Current Play billing uses Supabase Edge Functions (`verify-play-purchase` and `play-rtdn`) and server-side Supabase state; no Firebase callable or Firestore permission is required by the active customer runtime.

## Public-station compatibility

Customer mapping/backend expects delivery radius, fee centavos, preparation minutes and online-order acceptance. Owner documents do not reliably publish/manage all fields. Current defaults can mean zero radius/fee. Resolve via a versioned public contract or explicit migration/default behavior across both projects.

## Notifications and security

FCM remains the transport. Device tokens are registered against Supabase profile identity in `device_tokens`; Supabase notification outbox/Edge Functions resolve recipients and send through FCM. `onNewToken` registration alone does not guarantee the current token is registered after login.

Never log or document Firebase configuration, App Check artifacts, full FCM tokens, auth tokens, or customer-private fields. Supabase Auth providers and Edge Function secrets are configured outside source control. Firebase App Check is not part of the active customer runtime.
