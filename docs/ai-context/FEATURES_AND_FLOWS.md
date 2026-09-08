# AquaHub Customer Features and Flows

## Feature map

| Feature | Status | Entry/source | Data source and notes |
|---|---|---|---|
| Splash/onboarding | PARTIAL | `ui/CustomerApp.kt`, `feature/onboarding/` | Onboarding completion is not persisted. |
| Google authentication/profile | IMPLEMENTED | `feature/auth/`, `data/auth/` | Firebase Auth + `users/{uid}`; no phone/email-password UI. |
| GPS station discovery/search | IMPLEMENTED with scaling caveat | `feature/home/`, catalog/location repos | Places autocomplete, GPS reverse geocoding, map pin, working open-now/nearest filters; station query remains unbounded and client-side. |
| Station details/products | IMPLEMENTED with caveats | `feature/station/` | Live products filtered by remote availability; public station contract defaults are incomplete. |
| Product type/size/quantity | IMPLEMENTED | station/components/domain | Four shared types, free-form size label, quantity 1–99. |
| Cart | IMPLEMENTED, volatile | `feature/cart/`, `CustomerViewModel` | Single-station in-memory cart; process death loses it. |
| Cash checkout | IMPLEMENTED | `feature/checkout/`, order repo | Delivery/pickup, address/note, cash/COD, server totals. |
| Scheduled checkout | MOCK | checkout composable | Date/time controls do not enter `CreateOrderRequest`; backend timestamps remain null. |
| Saved addresses | PARTIAL | profile/checkout state | Places/map-selected address, place ID and coordinates are reused at checkout; list is still in-memory only. |
| Order creation/idempotency | IMPLEMENTED with stock gap | callable `createCustomerOrder` | Trusted catalog totals and request record; no stock check/reservation. |
| Orders/history/reorder | IMPLEMENTED with caveats | orders screens/repo | Live user mirror; reorder assumes historical products available until server validation. |
| Cancellation | PARTIAL/RISKY | callable `cancelCustomerOrder` | Pending/accepted only; race can overwrite newer owner status. |
| Status tracking | PARTIAL | `OrderTrackingScreen`, timeline | Live status mirror; no rider coordinates/map and generic rider card. |
| Notifications | PARTIAL | messaging service + Functions | Token refresh/trigger sends; current token, permission, foreground and deep-link flows incomplete. |
| Offline behavior | PARTIAL | Firebase SDK | Cached listeners only; no explicit local store or persisted cart/address. |
| Payment provider | NOT IMPLEMENTED | — | Cash only; no PayMongo/online payment. |
| Favorites/ratings/reviews | NOT IMPLEMENTED | — | No model, repository or screen flow. |
| Support | MOCK/PARTIAL | profile FAQ | Static FAQ dialog; no service/contact workflow. |

## End-to-end customer flow

```text
Launch
→ in-memory splash
→ onboarding when unsigned and not completed this process
→ Google authentication
→ Home station discovery (GPS + Firestore)
→ Station detail and live product catalog
→ in-memory cart
→ checkout (delivery/pickup + cash + address/note)
→ createCustomerOrder callable
→ customer order mirror
→ order status timeline/history
```

The root onboarding/auth states are outside `CustomerNavGraph`; the NavHost begins at Home after sign-in.

## Discovery rules

`FirestoreCatalogRepository` listens to a collection group of station documents and excludes explicitly inactive stations. It maps online acceptance/open state and delivery defaults. The ViewModel applies text search, local-time opening-hours filtering (including overnight schedules), and distance ordering from the customer's selected/GPS location. This is not a geospatial query and can become expensive at scale.

`AndroidLocationRepository` uses Google Places autocomplete/details when configured, with Android Geocoder fallback. Home, checkout and saved-address input share the map picker. GPS selections are reverse-geocoded, and map/Places selections retain the exact point used for distance ordering and delivery.

Product listeners read a selected station's catalog. Availability field compatibility has fallbacks. There is no stock quantity in the public customer model, so client UI cannot prevent overselling.

## Checkout/order rules

`CreateOrderRequest` requires business/station IDs, non-empty lines, quantities 1–99, a map-resolved delivery address/coordinate pair for DELIVERY, and a note up to 500 characters. The client sends product IDs/quantities, delivery address/place ID/coordinates, delivery mode, payment method, note and idempotency key.

The callable re-reads station/products, calculates prices and delivery fee, writes station customer/order, customer mirror, idempotency request and audit in a transaction, then notifies business members. It validates availability but not stock. Delivery radius defaults can reject deliveries because owner configuration is incomplete. Pickup avoids address-radius validation.

## Cancellation and tracking

Customer cancellation is offered for pending/accepted mirrors. The callable transaction does not atomically validate owner order status/version before setting it cancelled, so concurrent owner processing is unsafe. Keep UI rules and server transition enforcement separate.

The Firestore order trigger copies owner order changes into the UID-scoped mirror and sends an FCM status message. Tracking is status/timestamp based only. The current rider display is generic and `CustomerOrder` does not carry rider identity/location.

## Requirements differences

The customer implementation plan's core server-authoritative order loop and manual map location are present. The active Functions and unified rules/index configuration were deployed on 2026-09-05. Persistent saved addresses, actual scheduling, inventory safety, full notification handling, live rider tracking, ratings/favorites and online payment are not complete.
