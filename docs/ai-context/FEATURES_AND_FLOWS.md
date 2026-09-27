# AquaHub Customer Features and Flows

## Feature map

| Feature | Status | Entry/source | Data source and notes |
|---|---|---|---|
| Splash/onboarding | PARTIAL | `ui/CustomerApp.kt`, `feature/onboarding/` | Onboarding completion is not persisted. |
| Email/Google authentication and profile setup | IMPLEMENTED | `feature/auth/`, `data/auth/`, `feature/profile/` | Firebase Auth + `users/{uid}`; email verification, password reset, delivery profile completion, and authoritative setup routing. |
| Station QR acquisition/linking | IMPLEMENTED | App Links, `StationQrScannerActivity`, `StationLinkRequiredScreen`, `CustomerViewModel` | Profile-complete customers without a link are gated on QR scanning. A link resolves a single public projection; Profile can intentionally replace it by scanning another station. |
| Single-station catalog | IMPLEMENTED | `feature/home/`, catalog/location repos | Home and products observe only `businesses/{preferredBusinessId}/publicStations/{preferredStationId}`. There is no marketplace/discovery path; delivery address location tools remain available. |
| Station details/products | IMPLEMENTED with caveats | `feature/station/` | Live products are read only for server-published public stations and filtered by remote availability; public station contract defaults are incomplete. |
| Product type/size/quantity | IMPLEMENTED | station/components/domain | Four shared types, free-form size label, quantity 1–99. |
| Product promotions | IMPLEMENTED | catalog/product/cart UI + `createCustomerOrder` | Sanitized Business/Pro percentage, fixed-price, and quantity-break offers display with sale pricing; cart previews use integer-centavo math and the callable independently recalculates and snapshots the authoritative discount. |
| Cart | IMPLEMENTED, volatile | `feature/cart/`, `CustomerViewModel` | Single-station in-memory cart; process death loses it. |
| Station-configured checkout | IMPLEMENTED | `feature/checkout/`, order repo | Delivery/pickup, address/note, cash/COD/GCash/Maya, wallet account/QR display and server-validated totals. Wallet settlement remains manual. |
| Scheduled checkout | MOCK | checkout composable | Date/time controls do not enter `CreateOrderRequest`; backend timestamps remain null. |
| Saved addresses | IMPLEMENTED locally | profile/checkout + Room | Places/map-selected address, place ID and coordinates are reused at checkout; records are UID-scoped in Room and cleared on deletion/account switch. |
| Order creation/idempotency | IMPLEMENTED | callable `createCustomerOrder` | Trusted catalog totals, stock validation/decrement, request record, and client-side duplicate-submit guard. |
| Orders/history/reorder | IMPLEMENTED with caveats | orders screens/repo | Live user mirror; reorder assumes historical products available until server validation. |
| Cancellation | IMPLEMENTED WITH LIMITS | callable `cancelCustomerOrder` | PENDING/accepted only; station-side order status is authoritative inside the transaction. |
| Status tracking | PARTIAL | `OrderTrackingScreen`, timeline | Live status mirror; no rider coordinates/map and generic rider card. |
| Notifications | PARTIAL | messaging service + Functions | Token refresh/trigger sends; current token, permission, foreground and deep-link flows incomplete. |
| Offline behavior | PARTIAL | Firebase SDK + Room | Catalog/order listeners expose cache metadata; profile and addresses have UID-scoped Room caches. Cart and onboarding remain volatile by design. |
| Payment provider | NOT IMPLEMENTED | — | GCash/Maya details and QR are manual transfer instructions; no provider API confirmation. |
| Favorites/ratings/reviews | NOT IMPLEMENTED | — | No model, repository or screen flow. |
| Support | MOCK/PARTIAL | profile FAQ | Static FAQ dialog; no service/contact workflow. |

## End-to-end customer flow

```text
Launch
→ in-memory splash
→ onboarding when unsigned and not completed this process
→ email verification/profile completion when required
→ mandatory station QR link
→ focused Home for that exact station
→ Station detail and live product catalog
→ cart scoped to one station (cleared only after explicit station-switch confirmation)
→ checkout (delivery/pickup + station-enabled payment method + address/note)
→ createCustomerOrder callable
→ customer order mirror
→ order status timeline/history
```

The root onboarding/auth states are outside `CustomerNavGraph`; the NavHost begins at Home after sign-in.

## Discovery rules

`FirestoreCatalogRepository` listens to a collection group of station documents and excludes explicitly inactive stations. It maps online acceptance/open state and delivery defaults. The ViewModel applies text search, local-time opening-hours filtering (including overnight schedules), manual-open overrides, and distance ordering from the customer's selected/GPS location. This is not a geospatial query and can become expensive at scale.

Every customer must link one station through a QR/link/referral before entering the main app. The catalog performs a direct document observation for exactly the stored business/station pair, and the order callable rejects a different pair even if a modified client submits it. Scanning another valid QR from Profile intentionally replaces the one active link.

`AndroidLocationRepository` uses Google Places autocomplete/details when configured, with Android Geocoder fallback. Home, checkout and saved-address input share the map picker. GPS selections are reverse-geocoded, and map/Places selections retain the exact point used for distance ordering and delivery.

Product listeners read a selected station's catalog only after the station is present in the server-managed public projection. Availability field compatibility has fallbacks. Stock remains server-authoritative; the public customer model intentionally does not expose stock quantity.

## Checkout/order rules

`CreateOrderRequest` requires business/station IDs, non-empty lines, quantities 1–99, a map-resolved delivery address/coordinate pair for DELIVERY, and a note up to 500 characters. The client sends product IDs/quantities, delivery address/place ID/coordinates, delivery mode, payment method, note and idempotency key.

The callable re-reads station/products, validates and decrements stock where configured, calculates prices and delivery fee, writes station customer/order, customer mirror, idempotency request and audit in a transaction, then notifies business members. Delivery radius defaults can reject deliveries because owner configuration is incomplete. Pickup avoids address-radius validation.

## Cancellation and tracking

Customer cancellation is offered for pending/accepted orders. The callable transaction validates the customer mirror identity, then reads the station-side order as the authoritative status before updating both records and restoring reserved stock where applicable.

The Firestore order trigger copies owner order changes into the UID-scoped mirror and sends an FCM status message. Tracking is status/timestamp based only. The current rider display is generic and `CustomerOrder` does not carry rider identity/location.

## Requirements differences

The customer implementation plan core server-authoritative order loop, manual map location, email/Google authentication, profile completion, account deletion, and unified rules/index configuration are present. The latest Functions cancellation hardening is built locally but redeployment is pending Firebase CLI re-authentication. Scheduling remains cosmetic by contract; inventory reservation, full notification/deep-link handling, live rider tracking, ratings/favorites and online payment are not implemented.
