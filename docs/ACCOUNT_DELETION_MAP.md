# AquaHub account deletion map

This map reflects the collections and local stores currently used by AquaHub
Customer and Owner. Deletion is initiated by an authenticated callable; the
UID is always taken from `request.auth.uid`.

| Data location | UID relationship | Action | Reason |
| --- | --- | --- | --- |
| `users/{uid}` and its subcollections | Document key | Delete recursively | Profile, devices, private customer order copies, and preferences are user-owned. |
| `users/{uid}/orders/{orderId}` | Path owner | Delete | Private customer cache/copy. |
| `businesses/{businessId}/stations/{stationId}/orders/{orderId}` | `customerUid` | Retain and anonymize | Preserves station sales, fulfillment, and reports without direct customer identity. |
| Customer order `address`, coordinates, phone, note, name | Customer fields | Remove/anonymize | No longer needed after account deletion. |
| `customerOrderRequests/{uid}_{idempotencyKey}` | `uid` field | Delete | Idempotency records are customer-owned and no longer useful after account deletion. |
| `auditEvents/{eventId}` | `uid` field | Retain and anonymize UID | Keeps operational audit history without retaining the deleted account identifier. |
| `businesses/{businessId}/stations/{stationId}/customers/{customerId}` | `customerUid` | Anonymize and deactivate | Keeps operational customer ledger integrity while removing personal identity. |
| Owner `businesses/{businessId}` and stations | `ownerUid` / membership | Archive/deactivate when current owner | Prevents new orders while retaining business history; active station orders are rejected with a system reason first. Shared ownership must be reviewed before reactivation. |
| Owner subscription record | `businessId` | Mark cancelled/account-deleted | Server state is retained for billing/audit; Google Play cancellation remains external. |
| Firebase Storage | No current usage found | None currently | Both audited apps have no Storage references. Re-audit before adding uploads. |
| FCM device tokens | `users/{uid}/devices` | Deleted with user subtree | Prevents notifications to a deleted account. |
| Room / DataStore | Local account scope | Clear after cloud success | Prevents same-device account leakage; failed cloud deletion leaves local data for retry. |

The current Owner schema has one authoritative `ownerUid` and one `activeBusinessId`. The callable derives every business owned by the deleting UID, including the compatibility fallback. If another active OWNER/MANAGER or declared co-owner exists, it removes only the deleting membership, preserves the tenant, clears ownerUid, and marks ownershipTransferRequired; sole-owned tenants are archived.

Completed business orders are intentionally not blindly cascaded. The callable
retains their station-side operational record but strips customer-specific
fields. Deletion is only complete after the callable succeeds and the client
clears its local account cache.
