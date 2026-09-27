import test, { after, before } from 'node:test';
import fs from 'node:fs';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'aquahub-rules-test',
    storage: { rules: fs.readFileSync('../storage.rules', 'utf8') },
  });
});

after(async () => {
  await env?.clearStorage();
  await env?.cleanup();
});

const image = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
const pathFor = ownerUid => `owner-media/${ownerUid}/stations/1/products/test.jpg`;

test('owner can upload an allowed image inside their own media path', async () => {
  const storage = env.authenticatedContext('owner-a').storage();
  await assertSucceeds(storage.ref(pathFor('owner-a')).put(image, { contentType: 'image/jpeg' }));
});

test('owner can upload GCash and Maya QR images but not arbitrary categories', async () => {
  const storage = env.authenticatedContext('owner-a').storage();
  await assertSucceeds(storage.ref('owner-media/owner-a/stations/1/payment-qr-gcash/qr.png').put(image, { contentType: 'image/png' }));
  await assertSucceeds(storage.ref('owner-media/owner-a/stations/1/payment-qr-maya/qr.png').put(image, { contentType: 'image/png' }));
  await assertFails(storage.ref('owner-media/owner-a/stations/1/private/secret.png').put(image, { contentType: 'image/png' }));
});

test('another authenticated user cannot overwrite owner media', async () => {
  const storage = env.authenticatedContext('owner-b').storage();
  await assertFails(storage.ref(pathFor('owner-a')).put(image, { contentType: 'image/jpeg' }));
});

test('unauthenticated upload is denied', async () => {
  const storage = env.unauthenticatedContext().storage();
  await assertFails(storage.ref(pathFor('owner-a')).put(image, { contentType: 'image/jpeg' }));
});

test('non-image content and unsupported image types are denied', async () => {
  const storage = env.authenticatedContext('owner-a').storage();
  await assertFails(storage.ref(`${pathFor('owner-a')}.txt`).put(image, { contentType: 'text/plain' }));
  await assertFails(storage.ref(`${pathFor('owner-a')}.gif`).put(image, { contentType: 'image/gif' }));
});

test('authenticated customers can read images but unauthenticated users cannot', async () => {
  const ownerStorage = env.authenticatedContext('owner-a').storage();
  const imageRef = ownerStorage.ref(pathFor('owner-a'));
  await assertSucceeds(imageRef.put(image, { contentType: 'image/jpeg' }));
  const customerStorage = env.authenticatedContext('customer-a').storage();
  await assertSucceeds(customerStorage.ref(pathFor('owner-a')).getDownloadURL());
  const unauthenticatedStorage = env.unauthenticatedContext().storage();
  await assertFails(unauthenticatedStorage.ref(pathFor('owner-a')).getDownloadURL());
});

test('images larger than 5 MB are denied', async () => {
  const storage = env.authenticatedContext('owner-a').storage();
  const oversizedImage = new Uint8Array((5 * 1024 * 1024) + 1);
  await assertFails(
    storage.ref(`owner-media/owner-a/stations/1/products/oversized.jpg`)
      .put(oversizedImage, { contentType: 'image/jpeg' })
  );
});

test('only the owning account can delete an uploaded image', async () => {
  const path = 'owner-media/owner-a/stations/1/station/delete-test.jpg';
  const ownerStorage = env.authenticatedContext('owner-a').storage();
  await assertSucceeds(ownerStorage.ref(path).put(image, { contentType: 'image/jpeg' }));
  const otherStorage = env.authenticatedContext('owner-b').storage();
  await assertFails(otherStorage.ref(path).delete());
  await assertSucceeds(ownerStorage.ref(path).delete());
});
