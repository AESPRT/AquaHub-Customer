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
    await db.doc('businesses/business-a/stations/station-a').set({ id: 'station-a', name: 'A' });
    await db.doc('businesses/business-a/stations/station-b').set({ id: 'station-b', name: 'B' });
    await db.doc('businesses/business-a/stations/station-a/products/product-a').set({ id: 'product-a', name: 'Water', isAvailable: true });
    await db.doc('businesses/business-a/stations/station-a/orders/order-a').set({ id: 'order-a', stationId: 'station-a', customerUid: 'customer-a' });
    await db.doc('businesses/business-a/stations/station-a/payments/payment-a').set({ stationId: 'station-a', amountCentavos: 3500 });
    await db.doc('businesses/business-a/subscription/current').set({
      status: 'ACTIVE',
      currentPeriodEnd: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000),
    });
  });
});
after(async () => env?.cleanup());

test('active customer can discover nested stations and products', async () => {
  const db = env.authenticatedContext('customer-a').firestore();
  await assertSucceeds(db.collectionGroup('stations').get());
  await assertSucceeds(db.doc('businesses/business-a/stations/station-a/products/product-a').get());
});

test('unauthenticated users cannot browse nested stations', async () => {
  await assertFails(env.unauthenticatedContext().firestore().collectionGroup('stations').get());
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
  await assertSucceeds(db.doc('businesses/business-a/stations/station-a').get());
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

test('owner can process customer order but cannot alter subscription authority', async () => {
  const db = env.authenticatedContext('owner-a').firestore();
  await assertSucceeds(db.doc('businesses/business-a/stations/station-a/orders/order-a').update({
    id: 'order-a', stationId: 'station-a', customerUid: 'customer-a', status: 'ACCEPTED'
  }));
  await assertFails(db.doc('businesses/business-a/subscription/current').set({ status: 'EXPIRED' }));
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
});
