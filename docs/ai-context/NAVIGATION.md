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

External ordering links are accepted by `MainActivity` and held as a pending station destination in DataStore until the signed-in profile is ready, so a process recreation does not lose the QR flow. The link is then resolved to a public station projection and the normal Home → Station → Cart flow continues.

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
| station-link gate | Profile-complete customers without a preferred business/station must scan a station QR before main navigation is available. |
| `home` | Focused dashboard for the one linked station plus recent/reorder data. |
| `station` | Selected station is held in ViewModel state, not route arguments. |
| `cart` | In-memory cart and selected station. |
| `checkout` | In-memory cart, station, delivery/address state. |
| `orders` | Live customer mirror list. |
| `order_tracking/{orderId}` | String remote order ID; resolves from currently loaded `state.orders`. |
| `profile` | Customer profile/settings/FAQ. |

## Navigation constraints and debt

- Station, cart and checkout depend on ViewModel state; a cart is intentionally not persisted, while pending external station links are persisted until consumed.
- Tracking receives newly created orders immediately and then reconciles with the live mirror listener; older order IDs still depend on the currently loaded mirror list.
- `popUpTo(Home)` is used after placement and Home-reset actions; verify back-stack outcomes when adding intermediate routes.
- The app defines fade transitions around 220 ms and uses the signed-in bottom scaffold for root navigation.
- Station ordering App Links are handled by `MainActivity` for `aquahub.aesprt.com/s/{code}` and the legacy ordering host; order-notification deep links remain incomplete.
- Centralize all new route strings/builders in `CustomerDestinations`; pass IDs rather than serialized domain objects.
