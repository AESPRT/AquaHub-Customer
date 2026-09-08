# AquaHub Customer Navigation

Sources: `ui/CustomerApp.kt`, `ui/navigation/CustomerDestinations.kt`, and `CustomerNavGraph.kt`.

## App-level state routing

```text
process launch
→ Splash
→ Onboarding (unsigned and not completed in this process)
→ Auth
→ Signed-in main scaffold / NavHost
```

Splash/onboarding/auth are conditional composables in `CustomerApp`, not destinations in the signed-in NavHost despite constants existing for them. Onboarding completion is not persisted; a signed-out fresh process shows it again.

## Signed-in graph

```text
Home
├── Station
│   └── Cart
│       └── Checkout
│           └── order_tracking/{orderId}
├── order_tracking/{orderId}
└── reorder → Cart

Bottom roots: Home | Orders | Cart | Profile (labelled Account)
Orders → order_tracking/{orderId} or reorder → Cart
```

| Route | Arguments/state dependency |
|---|---|
| `home` | Start; discovery and recent/reorder data. |
| `station` | Selected station is held in ViewModel state, not route arguments. |
| `cart` | In-memory cart and selected station. |
| `checkout` | In-memory cart, station, delivery/address state. |
| `orders` | Live customer mirror list. |
| `order_tracking/{orderId}` | String remote order ID; resolves from currently loaded `state.orders`. |
| `profile` | Customer profile/settings/FAQ. |

## Navigation constraints and debt

- Station, cart and checkout depend on volatile ViewModel state. Process recreation/deep linking cannot reconstruct them independently.
- Tracking shows an “Order Not Found” state while the listener catches up; the route does not fetch one order directly.
- `popUpTo(Home)` is used after placement and Home-reset actions; verify back-stack outcomes when adding intermediate routes.
- The app defines fade transitions around 220 ms and uses the signed-in bottom scaffold for root navigation.
- No Android deep-link intent filters or URI route contract exist. Notifications cannot reliably open a specific order.
- Centralize all new route strings/builders in `CustomerDestinations`; pass IDs rather than serialized domain objects.

