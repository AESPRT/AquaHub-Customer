# AquaHub Customer Booking & Ordering APK — Implementation Plan

## 1. Objective

Create a separate Android APK for customers who want to find a nearby AquaHub water station, browse its live product catalog, place an order, and track the order status.

The customer APK and the existing AquaHub owner APK will use the same Firebase project and Firestore database. Customer orders must appear in the owner app's Orders screen as new `PENDING` orders for the selected station.

The two apps must share the Firebase contract and domain rules, but must not share unrestricted access. The customer app is a separate client with customer-scoped permissions; it is not another owner/staff client.

## 2. Existing architecture to preserve

The owner app currently uses:

- Firebase Authentication for Google and phone/password identities.
- Firestore paths under `businesses/{businessId}`.
- Station-scoped operational data under `businesses/{businessId}/stations/{stationRemoteId}`.
- `products`, `orders`, `customers`, `customerAddresses`, `riders`, `containerInventory`, `containerMovements`, and `payments` subcollections.
- Versioned WorkManager push/pull synchronization into Room.
- `FirestoreOrderDto` with order items, totals, payment fields, delivery address, schedule, status, and timestamps.
- Owner order lifecycle statuses: `PENDING → ACCEPTED → PREPARING → READY_FOR_PICKUP / RIDER_ASSIGNED → OUT_FOR_DELIVERY → DELIVERED → COMPLETED`, plus rejection/cancellation paths.

The customer project should reuse shared DTOs, enums, validation, money representation (centavos), and status-transition definitions through a small shared Kotlin module where practical. It must not reuse owner-only repositories or owner Room tables without customer isolation.

## 3. Recommended project structure

### Option A — Same repository, separate application module (recommended)

Add a new Gradle application module:

```text
/:app                 existing owner/station APK (AquaHub)
/:customer            customer APK (AquaHub Customer)
/:shared              Firebase DTOs, enums, validation, order contract, geo utilities
```

Use separate:

- `applicationId` (for example `com.aesprt.aquahub.customer`).
- Firebase Android app registration and SHA fingerprints.
- launcher icon, app name, signing configuration, and release track.
- analytics/crash reporting keys if separate reporting is desired.

### Option B — Separate repository

If release cadence or team ownership requires it, publish the shared Firebase contract as a private Gradle module. Do not copy DTOs manually between repositories; drift would cause order-sync failures.

## 4. Firebase data model

### 4.1 Customer identity

Use Firebase Auth. Supported first release providers:

- Google sign-in.
- Email/password or phone/password, depending on the final customer onboarding decision.

Create a customer profile at:

```text
users/{customerUid}
```

Suggested fields:

```text
uid
displayName
email
phone
photoUrl
role: CUSTOMER
isActive
createdAt
updatedAt
```

Do not place customer passwords, payment secrets, or owner business data in Firestore documents.

### 4.2 Public station directory

Customers need a safe catalog query without reading private business data. Add a public projection:

```text
publicStations/{stationRemoteId}
```

Suggested fields:

```text
stationId
businessId
name
logoPath
phone
address
barangay
city
province
latitude
longitude
geohash
isAcceptingOrders
openingTime
closingTime
deliveryRadiusKm
estimatedPreparationMinutes
updatedAt
```

Only public fields belong here. Never expose owner email, subscription state, internal inventory, staff records, customer lists, or payment records.

The owner app should publish/update this projection when a station is created or its public profile changes. A trusted Cloud Function should also reconcile `isAcceptingOrders` with station hours, pause state, and operational availability.

### 4.3 Customer-facing product catalog

Do not expose the entire owner product document if it contains internal stock or cost data. Add a customer-safe projection:

```text
publicStations/{stationRemoteId}/products/{productId}
```

Suggested fields:

```text
productId
name
productType
sizeLabel
description
imagePath
priceCentavos
isAvailableForOrdering
updatedAt
```

The owner app remains the source for product management. A trusted backend publishes only products marked available for customer ordering.

### 4.4 Customer orders

Orders should ultimately be stored where the owner app already reads them:

```text
businesses/{businessId}/stations/{stationRemoteId}/orders/{orderId}
```

Extend the shared order contract with:

```text
source: CUSTOMER_APP | OWNER_APP | WALK_IN
customerUid
customerProfileId
idempotencyKey
requestedAt
acceptedAt
rejectedAt
customerCancellationReason
orderVersion
```

The selected station and business IDs must be resolved by the backend, not trusted from arbitrary client input.

The existing owner app should receive the order through its existing Firestore pull/sync path. If realtime responsiveness is required, add a station-scoped listener or FCM notification while retaining the durable sync queue as the recovery path.

## 5. Secure order creation design

### Preferred design — callable Cloud Function

Implement `createCustomerOrder` as a callable HTTPS Cloud Function or equivalent trusted API.

Request:

```json
{
  "stationId": "station-remote-id",
  "items": [
    { "productId": "product-remote-id", "quantity": 2 }
  ],
  "deliveryAddress": {
    "label": "Home",
    "addressLine": "...",
    "latitude": 8.45,
    "longitude": 124.63
  },
  "deliveryMode": "DELIVERY",
  "paymentMethod": "CASH_ON_DELIVERY",
  "scheduledDeliveryStart": null,
  "scheduledDeliveryEnd": null,
  "customerNote": "...",
  "idempotencyKey": "client-generated-uuid"
}
```

Backend responsibilities:

1. Verify Firebase Auth and `role == CUSTOMER`.
2. Load the station from the public directory and verify it is active, open, and accepting orders.
3. Verify the station belongs to a valid business.
4. Load every product from the customer-safe catalog or trusted owner product record.
5. Verify product availability and stock policy.
6. Recalculate unit prices, subtotal, delivery fee, discount, and total on the server.
7. Validate delivery distance/radius and required address fields.
8. Enforce idempotency using `customerUid + idempotencyKey`.
9. Create or update the customer profile in the selected station's customer collection without creating duplicates.
10. Create the station order with `source = CUSTOMER_APP`, `status = PENDING`, and immutable financial snapshots.
11. Write an audit event and send an FCM notification to the station owner/staff.

The function must be transactional or use a transaction/outbox pattern so duplicate taps, retries, and poor connectivity cannot create duplicate orders.

### Alternative design — restricted direct Firestore write

Only consider this if Cloud Functions cannot be deployed. Add a `CUSTOMER` role and rules that allow a customer to create only a pending order whose `customerUid == request.auth.uid`, while forbidding client-controlled totals, status, station/business IDs, and product prices. A backend trigger must still recalculate/approve the order before the owner app treats it as valid. The callable function remains safer and is the release recommendation.

## 6. Nearest-station selection

### Location permission and fallback

1. Request `ACCESS_COARSE_LOCATION` first; request fine location only when needed.
2. Explain why location is needed before showing the Android permission prompt.
3. If permission is denied, allow manual city/barangay/address search.
4. Never block browsing entirely because GPS is unavailable.
5. Do not store continuous background location; use a one-time location for station discovery and the customer-selected delivery address for the order.

### Query strategy

Firestore has no native “nearest document” query. Use one of these approaches:

1. Geohash bounding-box query on `publicStations`, followed by client Haversine sorting.
2. A callable `findNearestStations` function that performs geospatial filtering and returns a bounded result set.

Recommended first release:

- Query several geohash bounds around the user location.
- Filter `isAcceptingOrders == true`.
- Calculate Haversine distance locally.
- Sort by distance, then availability/estimated preparation time.
- Display the nearest 5–10 stations.
- Show distance, estimated delivery range, open/closed state, and “accepting orders” state.

Do not select a station solely by city name. Validate the delivery address against the selected station's configured delivery radius before order creation.

## 7. Customer APK screens and UX

### Authentication

- Welcome screen with Google sign-in and the chosen credential option.
- First-login profile completion: name, phone, and default address.
- Clear error states for cancelled Google sign-in, offline mode, disabled account, and missing profile.
- Do not ask Google users for an AquaHub password.

### Home / station discovery

- Current location summary and permission state.
- “Find nearby stations” action.
- Manual location search fallback.
- Station cards with distance, status, hours, delivery estimate, and rating placeholder only if a real rating system exists.
- Pull-to-refresh for station/catalog data.

### Station detail

- Station identity and address.
- Operating hours and current order acceptance state.
- Product categories: refills, new containers, container deposits/returns, and other station-defined products.
- Product price, size, availability, and quantity selector.
- No static product data in production; demo mode may use seeded data explicitly labeled as demo.

### Cart and checkout

- Cart grouped by product and size.
- Server-calculated total shown after validation.
- Delivery or pickup selection.
- Address selection/map pin/manual address.
- Optional schedule window only when supported by the station.
- Payment method initially limited to cash/COD unless a real payment provider is integrated.
- Final confirmation with station name, items, fees, total, and expected timing.
- Disable repeated submission while a request is in flight.

### Order tracking

- Pending/accepted/preparing/ready/out-for-delivery/delivered/completed timeline.
- Owner rejection/cancellation reason when supplied.
- Customer cancellation rules based on order status.
- Retry-safe refresh and offline “last updated” display.
- FCM notification deep-links to the order detail screen.

### Account and history

- Current profile and saved addresses.
- Order history scoped to the signed-in customer UID.
- Sign out clears only customer-local cache and active session state.
- Account deletion/export workflow should be planned before production launch.

## 8. Owner APK changes

1. Add a clearly labeled “Online customer orders” source/filter in Orders.
2. Display customer name, phone, delivery address, source, requested time, and customer notes.
3. Keep customer orders in `PENDING` until owner/staff accepts or rejects them.
4. Add accept/reject actions with validation and optional rejection reason.
5. Preserve the existing order state machine and prevent illegal transitions.
6. Show customer order notifications with FCM plus durable sync fallback.
7. Add station-level “Accepting online orders” toggle and optional delivery radius.
8. Publish public station/product projections whenever station/product data changes.
9. When a customer UID is present, merge into the station customer record by UID/normalized phone rather than creating a duplicate customer.
10. Never allow owner screens to display orders from another business or unassigned station.

## 9. Firestore security rules and backend authorization

Rules must distinguish these actors:

- `OWNER` and `MANAGER`: owner-app business operations, subject to station assignment and server-side plan enforcement.
- `STAFF`: only assigned station operations.
- `CUSTOMER`: own profile, own order history, and callable-order creation only.
- Backend service account: trusted projections, order creation, billing, and reconciliation.

Required rule properties:

- Customer profile reads/writes are limited to `request.auth.uid`.
- Customer order reads require `resource.data.customerUid == request.auth.uid`.
- Customers cannot read owner customer lists, inventory, payments, subscriptions, or staff records.
- Customers cannot update order totals, station IDs, business IDs, product prices, or order status.
- Customers may only request cancellation through a callable function or a tightly constrained status transition.
- Public station/product projections expose only approved fields.
- Subscription documents remain server-only, as already required by the owner app rules.
- All writes validate tenant ID, station ID, immutable IDs, timestamps, status transitions, non-negative quantities, and money fields.

Add emulator tests for:

- Customer can read public stations/products.
- Customer cannot read another customer's order.
- Customer cannot read owner/staff/inventory/payment/subscription data.
- Customer cannot create an order by direct Firestore write.
- Callable order creation accepts valid input and rejects forged totals/product IDs.
- Staff/manager station isolation remains intact.
- Cross-business reads/writes fail.

## 10. Offline, retry, and duplicate-order behavior

Customer ordering should be online-first because station/product availability and totals must be current.

When offline:

- Allow browsing cached public stations/products with a visible stale-data indicator.
- Disable final order submission or queue a clearly labeled “awaiting connection” request.
- Generate a client idempotency UUID before submission.
- Retry the same idempotency key after timeout; never generate a new order ID for a retry.
- Show a recoverable error and preserve the cart.

Owner app synchronization must treat backend-created customer orders as remote creates and must not re-upload them as duplicate local creates.

## 11. Notifications and operational flow

### New order

1. Customer submits order.
2. Backend writes the order under the selected station.
3. Backend sends FCM to authorized station devices.
4. Owner app refreshes/pulls the order and shows a new-order badge.
5. Owner accepts/rejects.

### Status update

1. Owner app changes status through the existing validated transition path.
2. Backend or a Firestore trigger sends FCM to the customer UID.
3. Customer app updates from the notification payload and a server read.

FCM payloads must contain IDs and minimal display metadata only. Do not put sensitive customer addresses or payment information in notification bodies.

## 12. Implementation phases

### Phase 0 — Contract and environment

- Create the customer application module and Firebase Android app registration.
- Define shared DTOs/enums and version the order contract.
- Add separate package name, SHA fingerprints, signing, app label, and launcher assets.
- Decide whether Cloud Functions live in this repository or a separate backend repository.

**Exit criteria:** customer APK launches, authenticates in a Firebase test project, and cannot access owner-only screens/data.

### Phase 1 — Public station/product projections

- Add `publicStations` and public product projections.
- Add owner-app publisher/reconciliation code.
- Add geohash generation and indexed queries.
- Add Firestore rules and emulator tests for public/private boundaries.

**Exit criteria:** customer app can list only active public stations and public products; no private fields are exposed.

### Phase 2 — Customer authentication/profile

- Implement Google sign-in and selected credential provider.
- Create/update `users/{uid}` with `CUSTOMER` role.
- Add profile completion and saved addresses.
- Add account-switch/logout cache clearing.

**Exit criteria:** two customer accounts cannot see each other's profile, addresses, or cached orders.

### Phase 3 — Station discovery and cart

- Implement permission UX, current-location lookup, manual fallback, nearest-station sorting, and station detail.
- Implement dynamic product catalog and cart validation.
- Add loading, empty, stale, permission-denied, and station-closed states.

**Exit criteria:** a customer can select the nearest accepting station and build a cart from live catalog data.

### Phase 4 — Secure order backend

- Implement callable `createCustomerOrder`.
- Add server-side product, price, stock, delivery-radius, total, and idempotency validation.
- Write orders using the existing station order path and shared DTO fields.
- Add backend tests for duplicate submissions and forged payloads.

**Exit criteria:** valid customer orders are created exactly once and appear under the selected station; forged direct writes fail.

### Phase 5 — Owner-app order intake

- Add customer-order source/filter and notification badge.
- Verify remote order mapping into Room and existing order list/detail screens.
- Add accept/reject reason and customer contact/address display.
- Verify status updates are legal and customer-visible.

**Exit criteria:** owner staff can accept/reject/process a customer order and the customer sees every status transition.

### Phase 6 — Notifications, history, and resilience

- Add FCM token registration and notification deep links.
- Add customer order history/detail.
- Add retry/idempotency recovery, pull-to-refresh, stale-cache indicators, and analytics.
- Add crash/error telemetry without logging phone numbers, addresses, tokens, or credentials.

**Exit criteria:** notification loss or temporary offline conditions do not lose or duplicate orders.

### Phase 7 — Pilot and production rollout

- Deploy rules/functions to a staging Firebase project first.
- Test with multiple businesses, stations, customers, devices, and station roles.
- Validate App Check, rate limits, abuse controls, FCM delivery, and Firestore indexes.
- Run a small pilot with real station operators.
- Release customer APK independently from owner APK while keeping the shared contract backward compatible.

**Exit criteria:** security tests pass, duplicate-order rate is zero in retry tests, and operators can process orders without manual database intervention.

## 13. Testing strategy

### Unit tests

- Haversine distance and geohash bounds.
- Station ranking and radius filtering.
- Cart quantity/price calculations.
- Order status transition rules.
- Idempotency-key handling.
- Address validation and delivery/pickup rules.
- Customer-to-station customer merge logic.

### Integration/emulator tests

- Auth/profile creation.
- Public projection reads.
- Callable order creation.
- Duplicate request retries.
- Cross-customer and cross-business isolation.
- Owner-app remote order pull and Room mapping.
- FCM token registration and notification deep links.

### Manual device matrix

- Android versions supported by the owner APK.
- GPS enabled/disabled.
- Location permission granted/denied/approximate.
- Slow network, airplane mode, process death, and retry after timeout.
- Google account already registered, new Google account, and account switching.
- Station closed, paused, outside delivery radius, and out-of-stock product.

## 14. Observability and abuse controls

- Log only request IDs, station IDs, and outcome categories in backend logs.
- Add rate limits per customer UID/IP/device for station discovery and order creation.
- Alert on repeated failed order attempts, forged payloads, and duplicate idempotency keys.
- Track order creation success, rejection reasons, latency, notification delivery, and sync recovery.
- Keep an immutable audit event for order creation and status transitions.

## 15. Acceptance checklist

- [ ] Separate customer APK/package/signing configuration exists.
- [ ] Customer can sign in with Google without an AquaHub password.
- [ ] Customer can discover nearest stations by GPS or manual location.
- [ ] Only accepting stations/products are shown.
- [ ] Customer totals are calculated and validated by trusted backend code.
- [ ] Duplicate taps/retries create one order only.
- [ ] New order appears in the correct station's owner Orders screen as `PENDING`.
- [ ] Owner status changes appear in customer tracking.
- [ ] Customer cannot access another customer's data or owner data.
- [ ] Station/business isolation passes emulator tests.
- [ ] Product and station changes are reflected dynamically; demo data remains explicitly isolated.
- [ ] Offline, permission, closed-station, out-of-stock, and backend-error states are user-friendly.
- [ ] FCM is supplementary; pull/sync can recover missed notifications.
- [ ] Production rules, indexes, App Check, functions, and monitoring are deployed and tested.

## 16. Important implementation constraint

The existing owner APK rules intentionally make subscription documents server-authoritative. The customer APK must follow the same trust model: it may request an order, but it must not be trusted to set prices, stock, subscription entitlements, station ownership, or order status. A shared Firebase database is safe only when the customer surface is backed by customer-specific rules and trusted server-side order creation.
