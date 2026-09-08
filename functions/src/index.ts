import { getApp, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore, Timestamp, Transaction } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onDocumentWritten } from "firebase-functions/v2/firestore";

initializeApp();

const REGION = "asia-southeast1";
const DATABASE = "aquahub";
const db = getFirestore(getApp(), DATABASE);

type RequestedItem = { productId: string; quantity: number };
type DeliveryAddress = { label?: string; addressLine?: string; placeId?: string; latitude?: number; longitude?: number };

function requiredString(value: unknown, field: string, max = 200): string {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > max) {
    throw new HttpsError("invalid-argument", `${field} is invalid.`);
  }
  return value.trim();
}

function epochMillis(value: unknown): number {
  if (value instanceof Timestamp) return value.toMillis();
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function nullableEpochMillis(value: unknown): number | null {
  return value == null ? null : epochMillis(value);
}

function hasActiveSubscription(data: FirebaseFirestore.DocumentData | undefined): boolean {
  if (!data) return false;
  const now = Date.now();
  if (data.status === "TRIALING") return epochMillis(data.trialEndsAt) > now;
  return ["ACTIVE", "ACTIVE_UNTIL_PERIOD_END"].includes(data.status) &&
    epochMillis(data.currentPeriodEnd) > now;
}

function parseItems(value: unknown): RequestedItem[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > 20) {
    throw new HttpsError("invalid-argument", "An order must contain 1–20 items.");
  }
  const seen = new Set<string>();
  return value.map((raw) => {
    const item = raw as Record<string, unknown>;
    const productId = requiredString(item.productId, "productId", 160);
    const quantity = item.quantity;
    if (!Number.isInteger(quantity) || (quantity as number) < 1 || (quantity as number) > 99) {
      throw new HttpsError("invalid-argument", "Quantity must be between 1 and 99.");
    }
    if (seen.has(productId)) throw new HttpsError("invalid-argument", "Duplicate product lines are not allowed.");
    seen.add(productId);
    return { productId, quantity: quantity as number };
  });
}

function distanceKm(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const rad = (degree: number) => degree * Math.PI / 180;
  const lat = rad(bLat - aLat);
  const lng = rad(bLng - aLng);
  const value = Math.sin(lat / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(lng / 2) ** 2;
  return 6371.0088 * 2 * Math.asin(Math.sqrt(value));
}

export const ensureOwnerTrial = onCall(
  { region: REGION },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in before starting a trial.");

    const data = request.data as Record<string, unknown>;
    const planCode = requiredString(data.planCode, "planCode", 20).toUpperCase();
    const billingCycle = requiredString(data.billingCycle, "billingCycle", 20).toUpperCase();
    if (!["STARTER", "BUSINESS", "PRO"].includes(planCode)) {
      throw new HttpsError("invalid-argument", "That plan is not available.");
    }
    if (!["MONTHLY", "ANNUAL"].includes(billingCycle)) {
      throw new HttpsError("invalid-argument", "That billing cycle is not available.");
    }

    // The owner app calls this after creating the business. Deriving the
    // business from ownerUid prevents a client from choosing another tenant.
    const businesses = await db.collection("businesses")
      .where("ownerUid", "==", uid)
      .limit(2)
      .get();
    if (businesses.size !== 1) {
      throw new HttpsError("failed-precondition", "No unique owner business is ready for trial setup.");
    }
    const business = businesses.docs[0];
    const businessId = business.id;
    const member = await db.doc(`businesses/${businessId}/members/${uid}`).get();
    if (!member.exists || member.get("isActive") !== true || member.get("role") !== "OWNER") {
      throw new HttpsError("permission-denied", "Only the business owner can start the trial.");
    }

    const subscriptionRef = db.doc(`businesses/${businessId}/subscription/current`);
    const existing = await subscriptionRef.get();
    if (existing.exists) {
      const current = existing.data() ?? {};
      return {
        businessId,
        planCode: current.planCode ?? "STARTER",
        status: current.status ?? "EXPIRED",
        billingCycle: current.billingCycle ?? "MONTHLY",
        startedAt: epochMillis(current.startedAt),
        currentPeriodStart: epochMillis(current.currentPeriodStart),
        currentPeriodEnd: epochMillis(current.currentPeriodEnd),
        trialStartedAt: nullableEpochMillis(current.trialStartedAt),
        trialEndsAt: nullableEpochMillis(current.trialEndsAt),
        gracePeriodEndsAt: nullableEpochMillis(current.gracePeriodEndsAt),
        cancelledAt: nullableEpochMillis(current.cancelledAt),
        expiresAt: nullableEpochMillis(current.expiresAt),
        autoRenew: current.autoRenew === true,
        cancelAtPeriodEnd: current.cancelAtPeriodEnd === true,
        currency: current.currency ?? "PHP",
        createdAt: epochMillis(current.createdAt),
      };
    }

    const now = Date.now();
    const trialEndsAt = now + 14 * 24 * 60 * 60 * 1000;
    const payload = {
      id: "current",
      businessId,
      planCode,
      status: "TRIALING",
      billingCycle,
      startedAt: now,
      currentPeriodStart: now,
      trialStartedAt: Timestamp.fromMillis(now),
      trialEndsAt: Timestamp.fromMillis(trialEndsAt),
      currentPeriodEnd: Timestamp.fromMillis(trialEndsAt),
      // A trial has no grace period. A paid period may add one later through
      // a verified billing provider.
      gracePeriodEndsAt: null,
      cancelledAt: null,
      expiresAt: null,
      autoRenew: false,
      cancelAtPeriodEnd: false,
      currency: "PHP",
      createdAt: now,
      clientUpdatedAt: now,
      version: 1,
      updatedAt: FieldValue.serverTimestamp(),
    };
    await subscriptionRef.create(payload);
    return {
      businessId,
      planCode,
      status: "TRIALING",
      billingCycle,
      startedAt: now,
      currentPeriodStart: now,
      currentPeriodEnd: trialEndsAt,
      trialStartedAt: now,
      trialEndsAt,
      gracePeriodEndsAt: null,
      cancelledAt: null,
      expiresAt: null,
      autoRenew: false,
      cancelAtPeriodEnd: false,
      currency: "PHP",
      createdAt: now,
    };
  },
);

export const createCustomerOrder = onCall(
  { region: REGION, enforceAppCheck: true, consumeAppCheckToken: true },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in before ordering.");

    const data = request.data as Record<string, unknown>;
    const businessId = requiredString(data.businessId, "businessId", 160);
    const stationId = requiredString(data.stationId, "stationId", 160);
    const idempotencyKey = requiredString(data.idempotencyKey, "idempotencyKey", 100);
    const subscription = await db.doc(`businesses/${businessId}/subscription/current`).get();
    if (!hasActiveSubscription(subscription.data())) {
      throw new HttpsError("failed-precondition", "This station's mobile ordering is unavailable until the business has an active plan.");
    }
    const items = parseItems(data.items);
    const deliveryMode = data.deliveryMode === "PICKUP" ? "PICKUP" : "DELIVERY";
    const paymentMethod = deliveryMode === "DELIVERY" ? "CASH_ON_DELIVERY" : "CASH";
    const customerNote = typeof data.customerNote === "string" ? data.customerNote.trim().slice(0, 500) : null;
    const rawAcceptedBusinessRulesVersion = data.acceptedBusinessRulesVersion;
    const acceptedBusinessRulesVersion: number | null =
      rawAcceptedBusinessRulesVersion === undefined || rawAcceptedBusinessRulesVersion === null
        ? null
        : typeof rawAcceptedBusinessRulesVersion === "number"
          ? rawAcceptedBusinessRulesVersion
          : Number.NaN;
    if (acceptedBusinessRulesVersion !== null &&
        (!Number.isSafeInteger(acceptedBusinessRulesVersion) || acceptedBusinessRulesVersion < 0)) {
      throw new HttpsError("invalid-argument", "acceptedBusinessRulesVersion is invalid.");
    }
    const address = (data.deliveryAddress ?? null) as DeliveryAddress | null;
    if (deliveryMode === "DELIVERY") requiredString(address?.addressLine, "deliveryAddress.addressLine", 500);
    const hasDeliveryCoordinates = address?.latitude !== undefined || address?.longitude !== undefined;
    if (deliveryMode === "DELIVERY" && hasDeliveryCoordinates &&
        (typeof address?.latitude !== "number" || !Number.isFinite(address.latitude) || address.latitude < -90 || address.latitude > 90 ||
         typeof address.longitude !== "number" || !Number.isFinite(address.longitude) || address.longitude < -180 || address.longitude > 180)) {
      throw new HttpsError("invalid-argument", "Delivery coordinates are invalid.");
    }
    const deliveryLatitude = deliveryMode === "DELIVERY" && typeof address?.latitude === "number" ? address.latitude : null;
    const deliveryLongitude = deliveryMode === "DELIVERY" && typeof address?.longitude === "number" ? address.longitude : null;
    const deliveryPlaceId = deliveryMode === "DELIVERY" && typeof address?.placeId === "string"
      ? address.placeId.trim().slice(0, 300) || null
      : null;

    const userRef = db.doc(`users/${uid}`);
    const stationRef = db.doc(`businesses/${businessId}/stations/${stationId}`);
    const customerRef = db.doc(`businesses/${businessId}/stations/${stationId}/customers/customer-${uid}`);
    const requestRef = db.doc(`customerOrderRequests/${uid}_${idempotencyKey}`);
    const now = Date.now();

    const result = await db.runTransaction(async (tx: Transaction) => {
      const duplicate = await tx.get(requestRef);
      if (duplicate.exists) {
        return { orderId: duplicate.get("orderId") as string, duplicate: true };
      }

      const existingOrdersQuery = db.collection(`businesses/${businessId}/stations/${stationId}/orders`)
        .where("customerUid", "==", uid);
      const [user, station, customerDoc, existingOrdersSnap] = await Promise.all([
        tx.get(userRef),
        tx.get(stationRef),
        tx.get(customerRef),
        tx.get(existingOrdersQuery)
      ]);
      if (!user.exists || user.get("role") !== "CUSTOMER" || user.get("isActive") !== true) {
        throw new HttpsError("permission-denied", "This account cannot place customer orders.");
      }
      const stationIsActive = station.exists && station.get("isActive") !== false && station.get("deletedAt") == null;
      const stationAcceptsOrders = station.get("isAcceptingOnlineOrders") === true ||
        station.get("isAcceptingOrders") === true || station.get("isOpen") === true;
      if (!stationIsActive || !stationAcceptsOrders) {
        throw new HttpsError("failed-precondition", "This station is not accepting orders.");
      }

      const businessRulesText = typeof station.get("businessRulesText") === "string"
        ? (station.get("businessRulesText") as string).trim()
        : "";
      const businessRulesVersion = Number(station.get("businessRulesVersion") ?? 0);
      if (!Number.isSafeInteger(businessRulesVersion) || businessRulesVersion < 0) {
        throw new HttpsError("internal", "Station rules version is invalid.");
      }
      if (businessRulesText.length === 0) {
        throw new HttpsError("failed-precondition", "This station has not published its rules yet.");
      }
      if (acceptedBusinessRulesVersion !== businessRulesVersion) {
        throw new HttpsError("failed-precondition", "Read and accept this station's current rules before placing the order.");
      }

      const trustedProducts = await Promise.all(items.map((item) => tx.get(stationRef.collection("products").doc(item.productId))));
      const lines = trustedProducts.map((product, index) => {
        const available = product.get("isAvailableForOrdering") ?? product.get("isAvailable") ?? product.get("isActive") ?? true;
        if (!product.exists || available !== true) {
          throw new HttpsError("failed-precondition", "A selected product is no longer available.");
        }
        const unitPrice = product.get("priceCentavos");
        if (!Number.isSafeInteger(unitPrice) || unitPrice < 0) throw new HttpsError("internal", "Product price is invalid.");
        const quantity = items[index].quantity;
        const stockQuantity = product.get("stockQuantity");
        if (typeof stockQuantity === "number" && stockQuantity < quantity) {
          throw new HttpsError("failed-precondition", `Insufficient stock for ${product.get("name") ?? "a selected product"}.`);
        }
        return {
          id: `${idempotencyKey}-${index}`,
          productId: product.id,
          productNameSnapshot: product.get("name") as string,
          productTypeSnapshot: (product.get("productType") as string) || "OTHER",
          sizeLabelSnapshot: (product.get("sizeLabel") as string) || "",
          unitPriceCentavos: unitPrice,
          quantity,
          subtotalCentavos: unitPrice * quantity,
        };
      });

      for (const line of lines) {
        const prodDoc = trustedProducts.find((p) => p.id === line.productId);
        if (prodDoc && typeof prodDoc.get("stockQuantity") === "number") {
          tx.update(stationRef.collection("products").doc(line.productId), {
            stockQuantity: FieldValue.increment(-line.quantity),
            clientUpdatedAt: now,
            updatedAt: FieldValue.serverTimestamp(),
          });
        }
      }

      const subtotalCentavos = lines.reduce((sum, line) => sum + line.subtotalCentavos, 0);
      const deliveryFeeCentavos = deliveryMode === "DELIVERY" ? Number(station.get("deliveryFeeCentavos") ?? 0) : 0;
      if (!Number.isSafeInteger(deliveryFeeCentavos) || deliveryFeeCentavos < 0) throw new HttpsError("internal", "Delivery fee is invalid.");

      if (deliveryMode === "DELIVERY" && deliveryLatitude !== null && deliveryLongitude !== null) {
        const stationLocation = station.get("location");
        const stationLat = station.get("latitude") ?? station.get("lat") ?? stationLocation?.latitude;
        const stationLng = station.get("longitude") ?? station.get("lng") ?? stationLocation?.longitude;
        const radius = Number(station.get("deliveryRadiusKm") ?? 0);
        if (radius > 0 && typeof stationLat === "number" && typeof stationLng === "number" &&
            distanceKm(stationLat, stationLng, deliveryLatitude, deliveryLongitude) > radius) {
          throw new HttpsError("out-of-range", "The delivery address is outside this station's delivery area.");
        }
      }

      const orderRef = db.collection(`businesses/${businessId}/stations/${stationId}/orders`).doc();
      const mirrorRef = userRef.collection("orders").doc(orderRef.id);
      const orderNumber = `AH-${now.toString().slice(-8)}`;
      const deliveryAddress = deliveryMode === "PICKUP" ? "Station pickup" : requiredString(address?.addressLine, "deliveryAddress.addressLine", 500);
      const order = {
        id: orderRef.id,
        clientOperationId: idempotencyKey,
        idempotencyKey,
        source: "CUSTOMER_APP",
        customerUid: uid,
        customerProfileId: customerRef.id,
        stationId,
        businessId,
        stationName: station.get("name") as string,
        orderNumber,
        customerId: customerRef.id,
        customerName: (user.get("displayName") as string) || "Customer",
        customerPhone: (user.get("phone") as string) || "",
        riderId: null,
        riderName: null,
        deliveryAddressId: null,
        deliveryAddress,
        deliveryPlaceId,
        deliveryLatitude,
        deliveryLongitude,
        deliveryMode,
        items: lines,
        subtotalCentavos,
        deliveryFeeCentavos,
        discountCentavos: 0,
        totalCentavos: subtotalCentavos + deliveryFeeCentavos,
        paymentMethod,
        paymentStatus: "PENDING",
        status: "PENDING",
        scheduledDeliveryStart: null,
        scheduledDeliveryEnd: null,
        customerNote,
        businessRulesVersionAccepted: businessRulesText.length > 0 ? acceptedBusinessRulesVersion : null,
        stationNote: null,
        rejectionReason: null,
        requestedAt: now,
        createdAt: now,
        acceptedAt: null,
        readyAt: null,
        assignedAt: null,
        outForDeliveryAt: null,
        deliveredAt: null,
        completedAt: null,
        cancelledAt: null,
        clientUpdatedAt: now,
        updatedAt: FieldValue.serverTimestamp(),
        orderVersion: 1,
        version: 1,
        deletedAt: null,
      };

      const nonCancelledOrders = existingOrdersSnap.docs.filter((d) => !["CANCELLED", "REJECTED"].includes(d.get("status") as string));
      const totalOrders = nonCancelledOrders.length + 1;
      let totalSpentCentavos = subtotalCentavos + deliveryFeeCentavos;
      for (const d of nonCancelledOrders) {
        totalSpentCentavos += Number(d.get("totalCentavos") ?? 0);
      }

      if (totalOrders >= 5) {
        tx.set(customerRef, {
          id: customerRef.id,
          stationId,
          customerUid: uid,
          name: order.customerName,
          phone: order.customerPhone,
          normalizedPhone: String(order.customerPhone).replace(/\D/g, ""),
          email: user.get("email") ?? null,
          totalOrders,
          totalSpentCentavos,
          isActive: true,
          clientUpdatedAt: now,
          updatedAt: FieldValue.serverTimestamp(),
          createdAt: customerDoc.exists && customerDoc.get("createdAt") ? customerDoc.get("createdAt") : now,
        }, { merge: true });
      } else if (customerDoc.exists) {
        tx.delete(customerRef);
      }
      tx.create(orderRef, order);
      tx.create(mirrorRef, { ...order, businessId });
      tx.create(requestRef, { uid, idempotencyKey, orderId: orderRef.id, businessId, stationId, createdAt: now });
      tx.create(db.collection("auditEvents").doc(), { type: "CUSTOMER_ORDER_CREATED", uid, businessId, stationId, orderId: orderRef.id, createdAt: now });
      return { orderId: orderRef.id, duplicate: false, businessId, stationId };
    });

    if (!result.duplicate && result.businessId) {
      await notifyStationMembers(result.businessId, result.stationId!, result.orderId).catch(() => undefined);
    }
    return { orderId: result.orderId, duplicate: result.duplicate };
  },
);

export const cancelCustomerOrder = onCall(
  { region: REGION, enforceAppCheck: true },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in before cancelling.");
    const data = request.data as Record<string, unknown>;
    const orderId = requiredString(data.orderId, "orderId", 160);
    const reason = typeof data.reason === "string" ? data.reason.trim().slice(0, 300) : "Cancelled by customer";
    const mirrorRef = db.doc(`users/${uid}/orders/${orderId}`);
    await db.runTransaction(async (tx) => {
      const mirror = await tx.get(mirrorRef);
      if (!mirror.exists || mirror.get("customerUid") !== uid) throw new HttpsError("not-found", "Order not found.");
      if (!["PENDING", "ACCEPTED"].includes(mirror.get("status") as string)) {
        throw new HttpsError("failed-precondition", "This order can no longer be cancelled.");
      }
      const businessId = mirror.get("businessId") as string;
      const stationId = mirror.get("stationId") as string;
      const ownerRef = db.doc(`businesses/${businessId}/stations/${stationId}/orders/${orderId}`);
      const owner = await tx.get(ownerRef);
      if (!owner.exists || owner.get("customerUid") !== uid) throw new HttpsError("not-found", "Order not found.");
      const now = Date.now();
      const patch = {
        status: "CANCELLED",
        customerCancellationReason: reason,
        cancelledAt: now,
        clientUpdatedAt: now,
        updatedAt: FieldValue.serverTimestamp(),
        orderVersion: FieldValue.increment(1),
        version: FieldValue.increment(1)
      };
      tx.update(ownerRef, patch);
      tx.update(mirrorRef, patch);

      const orderItems = owner.get("items") as Array<{ productId: string; quantity: number }> | undefined;
      if (Array.isArray(orderItems)) {
        for (const it of orderItems) {
          if (it.productId && typeof it.quantity === "number" && it.quantity > 0) {
            const productRef = db.doc(`businesses/${businessId}/stations/${stationId}/products/${it.productId}`);
            tx.update(productRef, {
              stockQuantity: FieldValue.increment(it.quantity),
              clientUpdatedAt: now,
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
        }
      }

      tx.create(db.collection("auditEvents").doc(), { type: "CUSTOMER_ORDER_CANCELLED", uid, businessId, stationId, orderId, reason, createdAt: now });
    });
    return { orderId, status: "CANCELLED" };
  },
);

export const mirrorCustomerOrderStatus = onDocumentWritten(
  { region: REGION, database: DATABASE, document: "businesses/{businessId}/stations/{stationId}/orders/{orderId}" },
  async (event) => {
    const after = event.data?.after;
    const before = event.data?.before;
    const customerUid = after?.get("customerUid") as string | undefined;
    if (!after?.exists || !customerUid) return;
    const data = after.data()!;
    await db.doc(`users/${customerUid}/orders/${event.params.orderId}`).set({
      ...data,
      businessId: event.params.businessId,
      stationId: event.params.stationId,
    }, { merge: true });

    const oldStatus = before?.get("status") as string | undefined;
    const newStatus = data.status as string | undefined;
    if (newStatus && (newStatus === "REJECTED" || newStatus === "CANCELLED") &&
        oldStatus !== "REJECTED" && oldStatus !== "CANCELLED") {
      if (!data.customerCancellationReason && Array.isArray(data.items)) {
        const batch = db.batch();
        const now = Date.now();
        for (const it of data.items as Array<{ productId: string; quantity: number }>) {
          if (it.productId && typeof it.quantity === "number" && it.quantity > 0) {
            const productRef = db.doc(`businesses/${event.params.businessId}/stations/${event.params.stationId}/products/${it.productId}`);
            batch.update(productRef, {
              stockQuantity: FieldValue.increment(it.quantity),
              clientUpdatedAt: now,
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
        }
        await batch.commit().catch(() => undefined);
      }
    }

    if (event.data?.before.get("status") !== data.status) {
      const devices = await db.collection(`users/${customerUid}/devices`).get();
      const tokens = devices.docs.map((doc) => doc.get("token") as string).filter(Boolean).slice(0, 500);
      if (tokens.length) await getMessaging().sendEachForMulticast({
        tokens,
        notification: { title: "AquaHub order update", body: `Order ${data.orderNumber} is now ${String(data.status).toLowerCase().replaceAll("_", " ")}.` },
        data: { type: "ORDER_STATUS", orderId: event.params.orderId },
      });
    }
  },
);

async function notifyStationMembers(businessId: string, stationId: string, orderId: string): Promise<void> {
  const members = await db.collection(`businesses/${businessId}/members`).where("isActive", "==", true).get();
  const eligible = members.docs.filter((doc) => {
    const role = doc.get("role") as string;
    const stationIds = (doc.get("stationIds") as string[] | undefined) ?? [];
    return ["OWNER", "MANAGER"].includes(role) || (role === "STAFF" && stationIds.includes(stationId));
  });
  const deviceSnapshots = await Promise.all(eligible.map((member) => db.collection(`users/${member.id}/devices`).get()));
  const tokens = deviceSnapshots.flatMap((snapshot) => snapshot.docs.map((doc) => doc.get("token") as string)).filter(Boolean).slice(0, 500);
  if (!tokens.length) return;
  await getMessaging().sendEachForMulticast({
    tokens,
    notification: { title: "New online customer order", body: "A new customer order is waiting for confirmation." },
    data: { type: "NEW_CUSTOMER_ORDER", businessId, stationId, orderId },
  });
}
