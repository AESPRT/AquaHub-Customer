import test, { before, after } from 'node:test';
import fs from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';

let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'aquahub-rules-test',
    firestore: { rules: fs.readFileSync('../firestore.rules', 'utf8') }
  });
  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await db.doc('users/customer-a').set({ uid: 'customer-a', role: 'CUSTOMER', isActive: true });
    await db.doc('users/customer-b').set({ uid: 'customer-b', role: 'CUSTOMER', isActive: true });
    await db.doc('users/customer-a/orders/order-a').set({ customerUid: 'customer-a', requestedAt: 1 });
    await db.doc('businesses/business-a').set({ id: 'business-a', ownerUid: 'owner-a', isActive: true });
    await db.doc('businesses/business-a/members/owner-a').set({ uid: 'owner-a', role: 'OWNER', isActive: true, stationIds: [] });
    await db.doc('businesses/business-a/members/staff-a').set({ uid: 'staff-a', role: 'STAFF', isActive: true, stationIds: ['station-a'] });
    await db.doc('businesses/business-a/members/rider-a').set({ uid: 'rider-a', role: 'RIDER', isActive: true, stationIds: ['station-a'] });
    await db.doc('businesses/business-a/stations/station-a').set({ id: 'station-a', name: 'A' });
    await db.doc('businesses/business-a/stations/station-b').set({ id: 'station-b', name: 'B' });
    await db.doc('businesses/business-a/publicStations/station-a').set({ id: 'station-a', businessId: 'business-a', name: 'A', isActive: true });
    await db.doc('businesses/business-a/publicStations/station-b').set({ id: 'station-b', businessId: 'business-a', name: 'B', isActive: true });
    await db.doc('businesses/business-a/stations/station-a/products/product-a').set({ id: 'product-a', name: 'Water', isAvailable: true });
    await db.doc('businesses/business-a/publicStations/station-a/products/product-a').set({ id: 'product-a', productId: 'product-a', name: 'Water', priceCentavos: 3500, isAvailableForOrdering: true });
    await db.doc('businesses/business-a/stations/station-private/products/product-private').set({ id: 'product-private', name: 'Private owner product', isAvailable: true });
    await db.doc('businesses/business-a/stations/station-a/orders/order-a').set({ id: 'order-a', stationId: 'station-a', customerUid: 'customer-a' });
    await db.doc('businesses/business-a/stations/station-a/payments/payment-a').set({ stationId: 'station-a', amountCentavos: 3500 });
    await db.doc('businesses/business-a/subscription/current').set({
      status: 'ACTIVE',
      currentPeriodEnd: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000),
    });
  });
});
after(async () => env?.cleanup());

test('active customer can discover public stations and products', async () => {
  const db = env.authenticatedContext('customer-a').firestore();
  await assertSucceeds(db.collectionGroup('publicStations').get());
  await assertSucceeds(db.doc('businesses/business-a/publicStations/station-a/products/product-a').get());
  await assertFails(db.doc('businesses/business-a/stations/station-a/products/product-a').get());
  await assertFails(db.doc('businesses/business-a/stations/station-private/products/product-private').get());
});

test('unauthenticated users cannot browse public stations', async () => {
  await assertFails(env.unauthenticatedContext().firestore().collectionGroup('publicStations').get());
});

test('customer reads own mirror but not another customer mirror', async () => {
  await assertSucceeds(env.authenticatedContext('customer-a').firestore().doc('users/customer-a/orders/order-a').get());
  await assertFails(env.authenticatedContext('customer-b').firestore().doc('users/customer-a/orders/order-a').get());
});

test('customer cannot create or change station order directly', async () => {
  const db = env.authenticatedContext('customer-a').firestore();
  await assertFails(db.doc('businesses/business-a/stations/station-a/orders/forged').set({
    stationId: 'station-a', customerUid: 'customer-a', totalCentavos: 1, status: 'PENDING'
  }));
  await assertFails(db.doc('businesses/business-a/stations/station-a/orders/order-a').update({ status: 'COMPLETED' }));
});

test('customer cannot read owner data, payments, subscription, or staff', async () => {
  const db = env.authenticatedContext('customer-a').firestore();
  await assertFails(db.doc('businesses/business-a/stations/station-a').get());
  await assertSucceeds(db.doc('businesses/business-a/publicStations/station-a').get());
  await assertFails(db.doc('businesses/business-a').get());
  await assertFails(db.doc('businesses/business-a').get());
  await assertFails(db.doc('businesses/business-a/members/owner-a').get());
  await assertFails(db.doc('businesses/business-a/stations/station-a/payments/payment-a').get());
  await assertFails(db.doc('businesses/business-a/subscription/current').get());
});

test('staff station isolation remains enforced', async () => {
  const db = env.authenticatedContext('staff-a').firestore();
  await assertSucceeds(db.doc('businesses/business-a/stations/station-a').get());
  await assertFails(db.doc('businesses/business-a/stations/station-b').get());
});

test('staff and rider can advance an assigned station order', async () => {
  const staffDb = env.authenticatedContext('staff-a').firestore();
  await assertSucceeds(staffDb.doc('businesses/business-a/stations/station-a/orders/order-a').update({
    status: 'ACCEPTED',
  }));

  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc('businesses/business-a/stations/station-a/orders/order-a').update({
      status: 'OUT_FOR_DELIVERY',
    });
  });

  const riderDb = env.authenticatedContext('rider-a').firestore();
  await assertSucceeds(riderDb.doc('businesses/business-a/stations/station-a/orders/order-a').update({
    status: 'DELIVERED',
  }));
});

test('rider can sync operational counters but cannot edit rider identity', async () => {
  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc('businesses/business-a/stations/station-a/riders/rider-a').set({
      id: 'rider-a', stationId: 'station-a', name: 'Rider A', activeOrderCount: 1,
    });
  });
  const db = env.authenticatedContext('rider-a').firestore();
  await assertSucceeds(db.doc('businesses/business-a/stations/station-a/riders/rider-a').update({
    activeOrderCount: 0,
  }));
  await assertFails(db.doc('businesses/business-a/stations/station-a/riders/rider-a').update({
    name: 'Forged rider',
  }));
});

test('owner can process customer order but cannot alter subscription authority', async () => {
  const db = env.authenticatedContext('owner-a').firestore();
  await assertSucceeds(db.doc('businesses/business-a/stations/station-a/orders/order-a').update({
    id: 'order-a', stationId: 'station-a', customerUid: 'customer-a', status: 'ACCEPTED'
  }));
  await assertFails(db.doc('businesses/business-a/subscription/current').set({ status: 'EXPIRED' }));
});

test('product promotions require Business or Pro and valid pricing', async () => {
  const subscriptionRef = 'businesses/business-a/subscription/current';
  const productRef = 'businesses/business-a/stations/station-a/products/promo-product';
  const activeUntil = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc(subscriptionRef).set({
      status: 'ACTIVE', planCode: 'BUSINESS', currentPeriodEnd: activeUntil,
    });
  });
  const ownerDb = env.authenticatedContext('owner-a').firestore();
  const validPromotion = {
    id: 'promo-product', stationId: 'station-a', name: 'Promo refill',
    priceCentavos: 4000, stockQuantity: 10, isAvailable: true, isActive: true,
    promotionLabel: 'Weekend saver', promotionType: 'PERCENTAGE',
    promotionPercentBps: 1000, promotionalPriceCentavos: null,
    promotionMinimumQuantity: 1, promotionStartsAt: Date.now(),
    promotionEndsAt: null, promotionIsActive: true,
  };
  await assertSucceeds(ownerDb.doc(productRef).set(validPromotion));
  await assertFails(ownerDb.doc(productRef).set({
    ...validPromotion, promotionPercentBps: 9500,
  }));

  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc(subscriptionRef).set({
      status: 'ACTIVE', planCode: 'STARTER', currentPeriodEnd: activeUntil,
    });
  });
  await assertFails(ownerDb.doc(productRef).set(validPromotion));
  await assertSucceeds(ownerDb.doc(productRef).set({
    ...validPromotion,
    promotionLabel: null,
    promotionType: null,
    promotionPercentBps: null,
    promotionMinimumQuantity: null,
    promotionStartsAt: null,
    promotionIsActive: false,
  }));
  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc(subscriptionRef).set({
      status: 'ACTIVE', planCode: 'BUSINESS', currentPeriodEnd: activeUntil,
    });
  });
});

test('operating expenses require Business or Pro and remain owner-manager private', async () => {
  const subscriptionRef = 'businesses/business-a/subscription/current';
  const expenseRef = 'businesses/business-a/stations/station-a/expenses/expense-a';
  const activeUntil = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  const validExpense = {
    id: 'expense-a',
    stationId: 'station-a',
    amountCentavos: 245050,
    category: 'ELECTRICITY',
    description: 'Monthly power bill',
    vendor: 'Electric utility',
    paymentMethod: 'GCASH',
    referenceNumber: 'REF-1001',
    notes: null,
    incurredAt: Date.now(),
    createdAt: Date.now(),
    clientUpdatedAt: Date.now(),
    isActive: true,
    deletedAt: null,
  };

  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc(subscriptionRef).set({
      status: 'ACTIVE', planCode: 'BUSINESS', currentPeriodEnd: activeUntil,
    });
  });
  const ownerDb = env.authenticatedContext('owner-a').firestore();
  await assertSucceeds(ownerDb.doc(expenseRef).set(validExpense));
  await assertFails(ownerDb.doc(expenseRef).set({ ...validExpense, amountCentavos: 0 }));
  await assertFails(ownerDb.doc(expenseRef).set({ ...validExpense, stationId: 'station-b' }));
  await assertFails(env.authenticatedContext('staff-a').firestore().doc(expenseRef).get());
  await assertFails(env.authenticatedContext('customer-a').firestore().doc(expenseRef).get());

  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc(subscriptionRef).set({
      status: 'ACTIVE', planCode: 'STARTER', currentPeriodEnd: activeUntil,
    });
  });
  await assertFails(ownerDb.doc('businesses/business-a/stations/station-a/expenses/expense-starter').set({
    ...validExpense, id: 'expense-starter',
  }));
  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc(subscriptionRef).set({
      status: 'ACTIVE', planCode: 'BUSINESS', currentPeriodEnd: activeUntil,
    });
  });
});

test('owner can sync supported in-person payment methods only', async () => {
  const db = env.authenticatedContext('owner-a').firestore();
  for (const paymentMethod of ['CASH', 'CASH_ON_DELIVERY', 'GCASH', 'MAYA']) {
    await assertSucceeds(db.doc(`businesses/business-a/stations/station-a/payments/${paymentMethod}`).set({
      stationId: 'station-a',
      orderId: 'order-a',
      amountCentavos: 3500,
      paymentMethod,
      status: 'PAID',
    }));
  }
  await assertFails(db.doc('businesses/business-a/stations/station-a/payments/unsupported').set({
    stationId: 'station-a',
    orderId: 'order-a',
    amountCentavos: 3500,
    paymentMethod: 'ONLINE_PAYMENT',
    status: 'PAID',
  }));
  await assertFails(db.doc('businesses/business-a/stations/station-a/payments/card-disabled').set({
    stationId: 'station-a',
    orderId: 'order-a',
    amountCentavos: 3500,
    paymentMethod: 'CARD',
    status: 'PAID',
  }));
  await assertSucceeds(db.doc('businesses/business-a/stations/station-a/orders/order-a').update({
    paymentMethod: 'GCASH',
    paymentStatus: 'PAID',
  }));
  await assertFails(env.authenticatedContext('staff-a').firestore()
    .doc('businesses/business-a/stations/station-a/orders/order-a').update({
      paymentMethod: 'CASH',
      paymentStatus: 'PAID',
    }));
});

test('expired trial owner cannot read or mutate operational data', async () => {
  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc('businesses/business-a/subscription/current').set({
      status: 'TRIALING',
      trialEndsAt: new Date(Date.now() - 1),
    });
  });
  const db = env.authenticatedContext('owner-a').firestore();
  await assertFails(db.doc('businesses/business-a/stations/station-a').get());
  await assertFails(db.doc('businesses/business-a/stations/station-a/orders/order-a').update({ status: 'COMPLETED' }));
  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc('businesses/business-a/subscription/current').set({
      status: 'ACTIVE',
      currentPeriodEnd: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000),
    });
  });
});

test('new owner can bootstrap membership, profile, station, and station resources', async () => {
  const uid = 'owner-bootstrap';
  const businessId = 'business-owner-bootstrap';
  const stationId = 'station-owner-bootstrap';
  const db = env.authenticatedContext(uid).firestore();

  await assertSucceeds(db.doc(`businesses/${businessId}`).set({
    id: businessId,
    ownerUid: uid,
    isActive: true,
  }));

  // AquaHub checks this document before it creates it. The missing-document
  // read must succeed for the first-owner bootstrap to continue.
  await assertSucceeds(db.doc(`businesses/${businessId}/members/${uid}`).get());
  await assertSucceeds(db.doc(`businesses/${businessId}/members/${uid}`).set({
    uid,
    role: 'OWNER',
    isActive: true,
    stationIds: [],
  }));
  await assertSucceeds(db.doc(`users/${uid}`).set({
    uid,
    role: 'OWNER',
    isActive: true,
    activeBusinessId: businessId,
    displayName: 'Bootstrap Owner',
  }));
  await assertSucceeds(db.doc(`businesses/${businessId}/stations/${stationId}`).set({
    id: stationId,
    businessId,
    name: 'Bootstrap Station',
    isActive: true,
  }));
  await assertSucceeds(db.doc(`businesses/${businessId}/stations/${stationId}/products/product-bootstrap`).set({
    id: 'product-bootstrap',
    stationId,
    name: 'Refill',
    priceCentavos: 3500,
    isAvailable: true,
  }));
});

test('legacy owner profile bootstrap remains compatible with existing app versions', async () => {
  const uid = 'owner-legacy';
  const businessId = 'business-owner-legacy';
  const db = env.authenticatedContext(uid).firestore();

  await assertSucceeds(db.doc(`businesses/${businessId}`).set({
    id: businessId,
    ownerUid: uid,
    isActive: true,
  }));
  await assertSucceeds(db.doc(`users/${uid}`).set({
    uid,
    activeBusinessId: businessId,
    displayName: 'Legacy Owner',
  }));
});

test('profile role and activation cannot be changed by the client', async () => {
  const db = env.authenticatedContext('customer-a').firestore();
  await assertSucceeds(db.doc('users/customer-a').update({ displayName: 'Customer A' }));
  await assertFails(db.doc('users/customer-a').update({ role: 'OWNER' }));
  await assertFails(db.doc('users/customer-a').update({ isActive: false }));
  await assertFails(db.doc('users/customer-a').update({ activeBusinessId: 'business-a' }));
});

test('owner writes remain tenant- and station-bound', async () => {
  const db = env.authenticatedContext('owner-a').firestore();
  await assertFails(db.doc('businesses/business-a/stations/station-wrong').set({
    id: 'station-wrong',
    businessId: 'business-b',
    name: 'Wrong business',
  }));
  await assertFails(db.doc('businesses/business-a/stations/station-a/products/product-wrong').set({
    id: 'product-wrong',
    stationId: 'station-b',
    name: 'Wrong station',
  }));
  await assertFails(db.doc('businesses/business-b/stations/station-b/products/product-cross-tenant').set({
    id: 'product-cross-tenant',
    stationId: 'station-b',
    name: 'Cross tenant',
  }));

  await assertFails(db.doc('businesses/business-a/stations/station-a/products/product-a').update({
    stockQuantity: -1,
  }));
  await assertFails(db.doc('businesses/business-a/stations/station-a/orders/order-a').update({
    totalCentavos: 1,
  }));
  await assertFails(db.doc('businesses/business-a/stations/station-a/orders/order-a').update({
    stationId: 'station-b',
  }));

});

test("customer setupComplete requires phone and geocoded address", async () => {
  const db = env.authenticatedContext("customer-a").firestore();
  await assertFails(db.doc("users/customer-a").update({ setupComplete: true }));
  await assertFails(db.doc("users/customer-a").update({ address: "x".repeat(501) }));
  await assertSucceeds(db.doc("users/customer-a").update({
    phone: "09171234567",
    address: "AquaHub delivery address",
    latitude: 8.48,
    longitude: 124.65,
    setupComplete: true,
  }));
});


test("customer cannot forge verified or complete profile state", async () => {
  const db = env.authenticatedContext("customer-a").firestore();
  await assertFails(db.doc("users/customer-a").update({ emailVerified: true }));
  await assertFails(db.doc("users/customer-c").set({
    uid: "customer-c",
    role: "CUSTOMER",
    isActive: true,
    setupComplete: true,
  }));
});


test("profile and device payloads reject unknown or oversized fields", async () => {
  const db = env.authenticatedContext("customer-a").firestore();
  await assertFails(db.doc("users/customer-a").update({ isAdmin: true }));
  await assertSucceeds(db.doc("users/customer-a/devices/device-valid").set({
    token: "fcm-token", platform: "ANDROID", updatedAt: Date.now()
  }));
  await assertFails(db.doc("users/customer-a/devices/device-extra").set({
    token: "fcm-token-2", platform: "ANDROID", updatedAt: Date.now(), debug: true
  }));
  await assertFails(db.doc("users/customer-a/devices/device-large").set({
    token: "x".repeat(4097), platform: "ANDROID", updatedAt: Date.now()
  }));
  await assertFails(db.doc("users/customer-extra").set({
    uid: "customer-extra", role: "CUSTOMER", isActive: true, unexpected: "field"
  }));
});

test("manager membership updates remain typed and field-limited", async () => {
  const db = env.authenticatedContext("owner-a").firestore();
  await assertSucceeds(db.doc("businesses/business-a/members/staff-a").update({
    uid: "staff-a", role: "STAFF", isActive: true, stationIds: ["station-a"]
  }));
  await assertFails(db.doc("businesses/business-a/members/staff-a").update({
    uid: "staff-a", role: "STAFF", isActive: true, stationIds: ["station-a"], canGrantPremium: true
  }));
  await assertFails(db.doc("businesses/business-a/members/staff-a").update({
    uid: "staff-a", role: "STAFF", isActive: true, stationIds: "station-a"
  }));
});
