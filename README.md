# AquaHub Customer

Customer booking and ordering APK for the AquaHub Firebase project. It uses the same named Firestore database (`aquahub`), nested business station paths, and customer-owned order mirrors.

## Implemented

- Google/Firebase Authentication with a profile at `users/{uid}`.
- Public station discovery with GPS permission and manual search fallback.
- Distance ranking, station availability, operating hours, and preparation estimates.
- Live product catalogs from `businesses/{businessId}/stations/{stationId}/products`.
- Cart, delivery/pickup checkout, exact centavo money arithmetic, and idempotent submission.
- Customer order history, status timeline, cancellation, stale-cache indicators, and FCM token registration.
- Koin DI, MVVM, repository boundaries, StateFlow, and Compose navigation.
- Trusted Cloud Functions for order creation/cancellation, owner notifications, and customer status mirrors.
- Firestore rules preserving existing OWNER/MANAGER/STAFF station isolation while adding CUSTOMER isolation.

## Firebase connection

The Android app is configured for package `com.aesprt.aquahub_customer` and requires `app/google-services.json` for live authentication and data.

The customer app reads:

```text
businesses/{businessId}/stations/{stationId}
businesses/{businessId}/stations/{stationId}/products/{productId}
users/{customerUid}/orders/{orderId}
```

Trusted Functions write the owner-compatible record to:

```text
businesses/{businessId}/stations/{stationId}/orders/{orderId}
```

The order includes the existing AquaHub snapshots/totals/status fields plus `source = CUSTOMER_APP`, `customerUid`, `customerProfileId`, `idempotencyKey`, `requestedAt`, and `orderVersion`.

## Local verification

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
cd functions && npm install && npm run build
cd ../firebase-tests && npm install && npm test
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Staging deployment

Deploy to a staging Firebase project first. The configuration targets the named Firestore database `aquahub` and Functions region `asia-southeast1`.

```bash
firebase use <staging-project-id>
firebase deploy --only firestore,functions
```

Before production:

1. Enable Google sign-in for the customer Firebase Android app and add debug/release/Play SHA-1 and SHA-256 fingerprints.
2. Enable App Check; callable order functions enforce it.
3. Confirm the owner app is using the accompanying rules and can read the extended order fields.
4. Add owner UI controls for `isAcceptingOnlineOrders`, `deliveryRadiusKm`, `deliveryFeeCentavos`, and customer-order filtering.
5. Pilot cross-business, cross-station, duplicate-tap, offline/retry, and notification-loss scenarios.

No Firebase rules or Functions were deployed automatically by this implementation.
