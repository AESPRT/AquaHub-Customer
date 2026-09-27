import { createHash } from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { getApp, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore, Timestamp, Transaction } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
import {
  acknowledgePlaySubscription,
  describePlayBillingError,
  playPurchaseTokenHash,
  PlayBillingNotConfiguredError,
  PlayBillingVerificationError,
  verifyPlaySubscription as verifyGooglePlaySubscription,
} from "./playBilling";

initializeApp();

const REGION = "asia-southeast1";
const DATABASE = "aquahub";
// Keep Play Publisher access on a dedicated runtime identity. The default
// Compute service account is not granted Play Console permissions reliably.
const PLAY_BILLING_SERVICE_ACCOUNT = "aquahub-play-billing@aquahub-506411.iam.gserviceaccount.com";
const db = getFirestore(getApp(), DATABASE);

function describeBackendError(error: unknown): {
  code: string | number | null;
  message: string;
  details: string | null;
} {
  const record = typeof error === "object" && error !== null
    ? error as { code?: unknown; message?: unknown; details?: unknown }
    : {};
  return {
    code: typeof record.code === "string" || typeof record.code === "number" ? record.code : null,
    message: typeof record.message === "string" ? record.message : String(error),
    details: typeof record.details === "string" ? record.details : null,
  };
}

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

function normalizeTrialIdentity(value: unknown, kind: "phone" | "email"): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim().toLowerCase();
  if (!normalized) return null;
  if (kind === "phone") {
    const digits = normalized.replace(/\D/g, "");
    if (digits.length < 10) return null;
    return digits.startsWith("0") ? `63${digits.slice(1)}` : digits;
  }
  return normalized;
}

function trialIdentityKeys(data: FirebaseFirestore.DocumentData | undefined): string[] {
  if (!data) return [];
  const identities = [
    ["phone", normalizeTrialIdentity(data.phone, "phone")],
    ["email", normalizeTrialIdentity(data.email, "email")],
  ] as const;
  return identities
    .filter((entry): entry is readonly ["phone" | "email", string] => entry[1] !== null)
    .map(([kind, value]) => createHash("sha256")
      .update(`aquahub-owner-trial-v1:${kind}:${value}`)
      .digest("hex"));
}

async function hasConsumedOwnerTrial(data: FirebaseFirestore.DocumentData | undefined): Promise<boolean> {
  const keys = trialIdentityKeys(data);
  if (keys.length === 0) return false;
  const records = await Promise.all(keys.map((key) => db.doc(`ownerTrialClaims/${key}`).get()));
  return records.some((record) => record.exists && record.get("trialConsumed") === true);
}

class PlaySubscriptionTokenOwnershipError extends Error {}

type VerifiedPlaySubscription = Awaited<ReturnType<typeof verifyGooglePlaySubscription>>;

async function persistVerifiedPlaySubscription(
  businessId: string,
  verified: VerifiedPlaySubscription,
  tokenHash: string,
  now: number,
): Promise<void> {
  const subscriptionRef = db.doc(`businesses/${businessId}/subscription/current`);
  const tokenRef = db.doc(`googlePlaySubscriptionTokens/${tokenHash}`);

  await db.runTransaction(async (transaction) => {
    const [existingToken, existingSubscription] = await Promise.all([
      transaction.get(tokenRef),
      transaction.get(subscriptionRef),
    ]);
    if (existingToken.exists && existingToken.get("businessId") !== businessId) {
      throw new PlaySubscriptionTokenOwnershipError("This Google Play purchase is already linked to another business.");
    }
    const existingData = existingSubscription.data() ?? {};
    const nextVersion = (Number(existingData.version) || 0) + 1;
    transaction.set(tokenRef, {
      businessId,
      packageName: "com.aesprt.aquahub",
      productId: verified.productId,
      basePlanId: verified.basePlanId,
      lastVerifiedAt: now,
      updatedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
    transaction.set(subscriptionRef, {
      id: "current",
      businessId,
      planCode: verified.planCode,
      status: verified.status,
      billingCycle: verified.billingCycle,
      startedAt: verified.currentPeriodStart,
      currentPeriodStart: verified.currentPeriodStart,
      currentPeriodEnd: Timestamp.fromMillis(verified.currentPeriodEnd),
      trialStartedAt: null,
      trialEndsAt: null,
      gracePeriodEndsAt: verified.status === "GRACE_PERIOD"
        ? Timestamp.fromMillis(verified.currentPeriodEnd) : null,
      cancelledAt: verified.cancelAtPeriodEnd ? now : null,
      expiresAt: verified.status === "EXPIRED" ? Timestamp.fromMillis(verified.currentPeriodEnd) : null,
      autoRenew: verified.autoRenew,
      cancelAtPeriodEnd: verified.cancelAtPeriodEnd,
      currency: "PHP",
      provider: "GOOGLE_PLAY",
      googlePlayProductId: verified.productId,
      googlePlayBasePlanId: verified.basePlanId,
      purchaseTokenHash: tokenHash,
      externalSubscriptionId: tokenHash,
      verifiedAt: now,
      verificationVersion: 1,
      version: nextVersion,
      clientUpdatedAt: now,
      updatedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
  });
}

function hasTrialHistory(data: FirebaseFirestore.DocumentData | undefined): boolean {
  if (!data) return false;
  return data.status === "TRIALING" || data.trialStartedAt != null || data.trialEndsAt != null;
}

function hasActiveSubscription(data: FirebaseFirestore.DocumentData | undefined): boolean {
  if (!data) return false;
  const now = Date.now();
  // FREE includes station-management features but not mobile-app ordering.
  // Keep this server-side gate aligned with the public station projection so
  // a stale client or mirror cannot place an online order on the free plan.
  if (data.planCode === "FREE") return false;
  if (data.status === "TRIALING") return epochMillis(data.trialEndsAt) > now;
  return ["ACTIVE", "ACTIVE_UNTIL_PERIOD_END"].includes(data.status) &&
    epochMillis(data.currentPeriodEnd) > now;
}

function hasPublicStationAccess(data: FirebaseFirestore.DocumentData | undefined): boolean {
  if (!data || data.planCode === "FREE") return false;
  if (data.status === "TRIALING") return epochMillis(data.trialEndsAt) > Date.now();
  return ["ACTIVE", "ACTIVE_UNTIL_PERIOD_END"].includes(data.status) &&
    epochMillis(data.currentPeriodEnd) > Date.now();
}

function hasPromotionAccess(data: FirebaseFirestore.DocumentData | undefined): boolean {
  if (!hasActiveSubscription(data)) return false;
  return ["BUSINESS", "PRO"].includes(String(data?.planCode ?? "").toUpperCase());
}

type AppliedPromotion = {
  label: string;
  type: "PERCENTAGE" | "FIXED_PRICE" | "QUANTITY_BREAK";
  unitPriceCentavos: number;
  minimumQuantity: number;
};

function appliedProductPromotion(
  source: FirebaseFirestore.DocumentData,
  regularPriceCentavos: number,
  quantity: number,
  promotionsAllowed: boolean,
  now: number,
): AppliedPromotion | null {
  if (!promotionsAllowed || source.promotionIsActive !== true) return null;
  const type = String(source.promotionType ?? "").toUpperCase();
  if (!["PERCENTAGE", "FIXED_PRICE", "QUANTITY_BREAK"].includes(type)) return null;
  const startsAt = epochMillis(source.promotionStartsAt);
  const endsAt = source.promotionEndsAt == null ? null : epochMillis(source.promotionEndsAt);
  const minimumQuantity = type === "QUANTITY_BREAK" ? Number(source.promotionMinimumQuantity ?? 2) : 1;
  if (startsAt > now || (endsAt !== null && endsAt <= now) ||
      !Number.isSafeInteger(minimumQuantity) || minimumQuantity < 1 || minimumQuantity > 99 ||
      quantity < minimumQuantity) return null;

  let unitPriceCentavos: number;
  if (type === "FIXED_PRICE") {
    unitPriceCentavos = Number(source.promotionalPriceCentavos);
    if (!Number.isSafeInteger(unitPriceCentavos) || unitPriceCentavos <= 0 || unitPriceCentavos >= regularPriceCentavos) return null;
  } else {
    const percentBps = Number(source.promotionPercentBps);
    if (!Number.isSafeInteger(percentBps) || percentBps < 100 || percentBps > 9000) return null;
    unitPriceCentavos = Math.round(regularPriceCentavos * (10000 - percentBps) / 10000);
  }
  return {
    label: typeof source.promotionLabel === "string" ? source.promotionLabel.trim().slice(0, 80) : "Special offer",
    type: type as AppliedPromotion["type"],
    unitPriceCentavos,
    minimumQuantity,
  };
}

const STAFF_PLAN_LIMITS: Record<string, number> = {
  STARTER: 2,
  BUSINESS: 10,
  PRO: 100,
};

function phoneAuthEmail(phone: string): string {
  const digits = phone.replace(/\D/g, "");
  if (digits.length < 10) throw new HttpsError("invalid-argument", "phone is invalid.");
  const canonical = digits.startsWith("0") ? `63${digits.slice(1)}` : digits.startsWith("63") ? digits : digits;
  return `phone-${canonical}@auth.aquahub.app`;
}

async function requireStaffManager(request: { auth?: { uid?: string } }): Promise<{
  uid: string;
  businessId: string;
  role: string;
  stationIds: string[];
  subscription: FirebaseFirestore.DocumentData;
}> {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in before managing staff accounts.");
  const user = await db.doc(`users/${uid}`).get();
  const businessId = user.get("activeBusinessId") as string | undefined;
  if (!businessId) throw new HttpsError("failed-precondition", "Your owner business is not ready.");
  const member = await db.doc(`businesses/${businessId}/members/${uid}`).get();
  const role = member.get("role") as string | undefined;
  if (!member.exists || member.get("isActive") !== true || !["OWNER", "MANAGER"].includes(role ?? "")) {
    throw new HttpsError("permission-denied", "Only an owner or manager can manage staff accounts.");
  }
  const subscriptionSnapshot = await db.doc(`businesses/${businessId}/subscription/current`).get();
  const subscription = subscriptionSnapshot.data();
  if (!hasActiveSubscription(subscription)) {
    throw new HttpsError("failed-precondition", "Staff accounts are available on paid plans only.");
  }
  return {
    uid,
    businessId,
    role: role as string,
    stationIds: (member.get("stationIds") as string[] | undefined) ?? [],
    subscription: subscription ?? {},
  };
}

async function getBusinessStationIds(businessId: string): Promise<string[]> {
  const snapshot = await db.collection(`businesses/${businessId}/stations`).get();
  return snapshot.docs.map((doc) => doc.id);
}

async function getManagedStaffAccount(caller: Awaited<ReturnType<typeof requireStaffManager>>, uid: string) {
  if (uid === caller.uid) {
    throw new HttpsError("failed-precondition", "You cannot manage your own staff membership.");
  }
  const memberRef = db.doc(`businesses/${caller.businessId}/members/${uid}`);
  const memberSnapshot = await memberRef.get();
  if (!memberSnapshot.exists || memberSnapshot.get("role") === "OWNER") {
    throw new HttpsError("not-found", "Staff account not found.");
  }
  const targetStationIds = (memberSnapshot.get("stationIds") as string[] | undefined) ?? [];
  if (caller.role !== "OWNER" && !targetStationIds.some((id) => caller.stationIds.includes(id))) {
    throw new HttpsError("permission-denied", "You can only manage staff assigned to your stations.");
  }
  return {
    memberRef,
    member: memberSnapshot,
    profileRef: db.doc(`users/${uid}`),
  };
}

async function findFleetRidersForPhone(
  businessId: string,
  stationIds: string[],
  phone: string,
): Promise<Array<{ ref: FirebaseFirestore.DocumentReference; data: FirebaseFirestore.DocumentData }>> {
  const normalizedPhone = normalizeTrialIdentity(phone, "phone");
  if (!normalizedPhone) return [];
  const snapshots = await Promise.all(stationIds.map((stationId) =>
    db.collection(`businesses/${businessId}/stations/${stationId}/riders`).get(),
  ));
  return snapshots.flatMap((snapshot) => snapshot.docs
    .filter((doc) => normalizeTrialIdentity(doc.get("phone"), "phone") === normalizedPhone)
    .map((doc) => ({ ref: doc.ref, data: doc.data() })));
}

async function deactivateFleetRiders(
  riders: Array<{ ref: FirebaseFirestore.DocumentReference }>,
  now: number,
): Promise<void> {
  if (!riders.length) return;
  const batch = db.batch();
  riders.forEach(({ ref }) => batch.update(ref, {
    isActive: false,
    isAvailable: false,
    isOnline: false,
    activeOrderCount: 0,
    updatedAt: now,
  }));
  await batch.commit();
}

export const listStaffAccounts = onCall(
  { region: REGION, enforceAppCheck: true, consumeAppCheckToken: true },
  async (request) => {
    const caller = await requireStaffManager(request);
    const members = await db.collection(`businesses/${caller.businessId}/members`).get();
    const rows = await Promise.all(members.docs
      .filter((member) => member.get("role") !== "OWNER")
      .filter((member) => caller.role === "OWNER" ||
        ((member.get("stationIds") as string[] | undefined) ?? []).some((id) => caller.stationIds.includes(id)))
      .map(async (member) => {
        const profile = await db.doc(`users/${member.id}`).get();
        return {
          uid: member.id,
          name: profile.get("displayName") ?? "Staff account",
          phone: profile.get("phone") ?? "",
          role: member.get("role") ?? "STAFF",
          isActive: member.get("isActive") === true,
          stationIds: member.get("stationIds") ?? [],
        };
      }));
    const planCode = String(caller.subscription.planCode ?? "").toUpperCase();
    return { staff: rows, planCode, staffLimit: STAFF_PLAN_LIMITS[planCode] ?? null };
  },
);

export const createStaffAccount = onCall(
  { region: REGION, enforceAppCheck: true, consumeAppCheckToken: true },
  async (request) => {
    const caller = await requireStaffManager(request);
    const data = request.data as Record<string, unknown>;
    const name = requiredString(data.name, "name", 120);
    const phone = requiredString(data.phone, "phone", 32);
    const password = requiredString(data.password, "password", 128);
    if (password.length < 8) throw new HttpsError("invalid-argument", "password must be at least 8 characters.");
    const requestedRole = requiredString(data.role ?? "STAFF", "role", 20).toUpperCase();
    if (!["STAFF", "MANAGER", "RIDER"].includes(requestedRole)) {
      throw new HttpsError("invalid-argument", "Only STAFF, MANAGER, or RIDER roles are available.");
    }
    if (requestedRole === "MANAGER" && caller.role !== "OWNER") {
      throw new HttpsError("permission-denied", "Only the owner can create a manager.");
    }
    const requestedStationIds = Array.isArray(data.stationIds)
      ? data.stationIds.filter((value): value is string => typeof value === "string" && value.trim().length > 0)
      : [];
    const accessibleStationIds = caller.role === "OWNER"
      ? await getBusinessStationIds(caller.businessId)
      : caller.stationIds;
    const stationIds = [...new Set(requestedStationIds.length > 0 ? requestedStationIds : accessibleStationIds)];
    if (stationIds.length === 0) throw new HttpsError("failed-precondition", "Create a station before adding staff.");
    if (stationIds.some((id) => !accessibleStationIds.includes(id))) {
      throw new HttpsError("permission-denied", "You can only assign stations you manage.");
    }

    const planCode = String(caller.subscription.planCode ?? "").toUpperCase();
    const limit = STAFF_PLAN_LIMITS[planCode];
    if (!limit) throw new HttpsError("failed-precondition", "Staff accounts are available on paid plans only.");
    const existingStaff = (await db.collection(`businesses/${caller.businessId}/members`).get()).docs
      .filter((member) => member.get("role") !== "OWNER" && member.get("isActive") === true).length;
    if (existingStaff >= limit) {
      throw new HttpsError("resource-exhausted", `Your ${planCode} plan supports up to ${limit} staff accounts.`);
    }

    let createdUid: string;
    try {
      const created = await getAuth().createUser({
        email: phoneAuthEmail(phone),
        password,
        displayName: name,
      });
      createdUid = created.uid;
    } catch (error) {
      const code = (error as { code?: string }).code;
      if (code === "auth/email-already-exists") {
        throw new HttpsError("already-exists", "This phone number already has an AquaHub account.");
      }
      throw new HttpsError("internal", "The staff account could not be created.");
    }

    const now = Date.now();
    const userRef = db.doc(`users/${createdUid}`);
    const memberRef = db.doc(`businesses/${caller.businessId}/members/${createdUid}`);
    const batch = db.batch();
    batch.set(userRef, {
      uid: createdUid,
      firstName: name.split(/\s+/)[0] ?? name,
      lastName: name.split(/\s+/).slice(1).join(" "),
      displayName: name,
      email: phoneAuthEmail(phone),
      phone,
      role: "OWNER",
      activeBusinessId: caller.businessId,
      isActive: true,
      createdAt: now,
      updatedAt: now,
    });
    batch.set(memberRef, {
      uid: createdUid,
      role: requestedRole,
      isActive: true,
      stationIds,
      createdAt: now,
      updatedAt: now,
    });
    try {
      await batch.commit();
    } catch (error) {
      // Do not leave an unusable Auth identity behind if the membership/profile
      // transaction fails after Auth creation.
      await getAuth().deleteUser(createdUid).catch(() => undefined);
      throw new HttpsError("internal", "The staff account could not be finished.");
    }
    return { uid: createdUid, name, phone, role: requestedRole, isActive: true, stationIds };
  },
);

export const updateStaffAccount = onCall(
  { region: REGION, enforceAppCheck: true, consumeAppCheckToken: true },
  async (request) => {
    const caller = await requireStaffManager(request);
    const data = request.data as Record<string, unknown>;
    const uid = requiredString(data.uid, "uid", 128);
    const name = requiredString(data.name, "name", 120);
    const phone = requiredString(data.phone, "phone", 32);
    const requestedRole = requiredString(data.role ?? "STAFF", "role", 20).toUpperCase();
    if (!["STAFF", "MANAGER", "RIDER"].includes(requestedRole)) {
      throw new HttpsError("invalid-argument", "Only STAFF, MANAGER, or RIDER roles are available.");
    }
    if (requestedRole === "MANAGER" && caller.role !== "OWNER") {
      throw new HttpsError("permission-denied", "Only the owner can assign the manager role.");
    }

    const target = await getManagedStaffAccount(caller, uid);
    const existingStationIds = (target.member.get("stationIds") as string[] | undefined) ?? [];
    const requestedStationIds = Array.isArray(data.stationIds)
      ? data.stationIds.filter((value): value is string => typeof value === "string" && value.trim().length > 0)
      : [];
    const accessibleStationIds = caller.role === "OWNER"
      ? await getBusinessStationIds(caller.businessId)
      : caller.stationIds;
    const stationIds = [...new Set(requestedStationIds.length > 0 ? requestedStationIds : existingStationIds)];
    if (stationIds.length === 0) throw new HttpsError("failed-precondition", "Assign at least one station to this account.");
    if (stationIds.some((id) => !accessibleStationIds.includes(id))) {
      throw new HttpsError("permission-denied", "You can only assign stations you manage.");
    }
    const previousProfile = await target.profileRef.get();
    const previousPhone = previousProfile.get("phone") as string | undefined;
    const previousName = previousProfile.get("displayName") as string | undefined;
    const previousEmail = previousProfile.get("email") as string | undefined;
    const previousRole = target.member.get("role") as string | undefined;
    const linkedRiders = previousRole === "RIDER"
      ? await findFleetRidersForPhone(caller.businessId, await getBusinessStationIds(caller.businessId), previousPhone ?? "")
      : [];
    const phoneChanged = (previousPhone ?? "") !== phone;
    const previousAuth = {
      displayName: previousName,
      email: previousEmail,
    };

    if (linkedRiders.some(({ data }) => Number(data.activeOrderCount ?? 0) > 0) &&
        (requestedRole !== "RIDER" || phoneChanged)) {
      throw new HttpsError("failed-precondition", "Reassign this rider's active orders before changing or removing the rider account.");
    }

    try {
      await getAuth().updateUser(uid, {
        displayName: name,
        ...(phoneChanged ? { email: phoneAuthEmail(phone) } : {}),
      });
    } catch (error) {
      const code = (error as { code?: string }).code;
      if (code === "auth/email-already-exists") {
        throw new HttpsError("already-exists", "This phone number already has an AquaHub account.");
      }
      throw new HttpsError("internal", "The staff account could not be updated.");
    }

    const now = Date.now();
    try {
      const batch = db.batch();
      batch.update(target.profileRef, {
        firstName: name.split(/\s+/)[0] ?? name,
        lastName: name.split(/\s+/).slice(1).join(" "),
        displayName: name,
        email: phoneAuthEmail(phone),
        phone,
        updatedAt: now,
      });
      batch.update(target.memberRef, { role: requestedRole, stationIds, updatedAt: now });
      await batch.commit();
    } catch (error) {
      // Restore the Auth identity if the profile/member update fails after the
      // Auth update succeeds, keeping the two records consistent.
      await getAuth().updateUser(uid, {
        displayName: previousAuth.displayName,
        ...(phoneChanged && previousAuth.email ? { email: previousAuth.email } : {}),
      }).catch(() => undefined);
      throw new HttpsError("internal", "The staff account could not be updated.");
    }

    if (previousRole === "RIDER") {
      if (requestedRole === "RIDER") {
        const riderBatch = db.batch();
        linkedRiders.forEach(({ ref }) => riderBatch.update(ref, {
          name,
          phone,
          updatedAt: now,
        }));
        if (linkedRiders.length) await riderBatch.commit();
      } else {
        await deactivateFleetRiders(linkedRiders, now);
      }
    }

    return { uid, name, phone, role: requestedRole, isActive: target.member.get("isActive") === true, stationIds };
  },
);

export const deleteStaffAccount = onCall(
  { region: REGION, enforceAppCheck: true, consumeAppCheckToken: true },
  async (request) => {
    const caller = await requireStaffManager(request);
    const data = request.data as Record<string, unknown>;
    const uid = requiredString(data.uid, "uid", 128);
    const target = await getManagedStaffAccount(caller, uid);
    const targetRole = target.member.get("role") as string | undefined;
    const previousProfile = await target.profileRef.get();
    const linkedRiders = targetRole === "RIDER"
      ? await findFleetRidersForPhone(caller.businessId, await getBusinessStationIds(caller.businessId), (previousProfile.get("phone") as string | undefined) ?? "")
      : [];
    if (linkedRiders.some(({ data }) => Number(data.activeOrderCount ?? 0) > 0)) {
      throw new HttpsError("failed-precondition", "Reassign this rider's active orders before removing the rider account.");
    }

    try {
      await getAuth().deleteUser(uid);
    } catch (error) {
      const code = (error as { code?: string }).code;
      if (code !== "auth/user-not-found") {
        throw new HttpsError("internal", "The staff account could not be removed.");
      }
    }

    try {
      const batch = db.batch();
      batch.delete(target.memberRef);
      batch.delete(target.profileRef);
      await batch.commit();
    } catch (error) {
      throw new HttpsError("internal", "The staff account login was removed, but the staff record could not be cleaned up.");
    }
    if (targetRole === "RIDER") {
      await deactivateFleetRiders(linkedRiders, Date.now());
    }
    return { uid, deleted: true };
  },
);

function stationTimeMinutes(value: unknown): number | null {
  if (typeof value !== "string") return null;
  const match = value.trim().match(/(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?/i);
  if (!match) return null;
  let hour = Number(match[1]);
  const minute = Number(match[2] ?? "0");
  const meridiem = match[3]?.toUpperCase();
  if (!Number.isInteger(hour) || !Number.isInteger(minute) || minute > 59) return null;
  if (meridiem) {
    if (hour < 1 || hour > 12) return null;
    if (meridiem === "AM" && hour === 12) hour = 0;
    if (meridiem === "PM" && hour !== 12) hour += 12;
  } else if (hour > 23) {
    return null;
  }
  return hour * 60 + minute;
}

function stationIsOpenAtCurrentTime(data: FirebaseFirestore.DocumentData): boolean {
  if (data.manualOpenOverride === true) return data.isOpen === true;
  const opening = stationTimeMinutes(data.openingTime);
  const closing = stationTimeMinutes(data.closingTime);
  if (opening === null || closing === null || opening === closing) return true;
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Manila", hour: "2-digit", minute: "2-digit", hour12: false,
  }).formatToParts(new Date());
  const hour = Number(parts.find((part) => part.type === "hour")?.value ?? 0);
  const minute = Number(parts.find((part) => part.type === "minute")?.value ?? 0);
  const current = hour * 60 + minute;
  return opening < closing
    ? current >= opening && current < closing
    : current >= opening || current < closing;
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
  { region: REGION, enforceAppCheck: true, consumeAppCheckToken: true },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in before starting a trial.");

    const data = request.data as Record<string, unknown>;
    const planCode = requiredString(data.planCode, "planCode", 20).toUpperCase();
    const billingCycle = requiredString(data.billingCycle, "billingCycle", 20).toUpperCase();
    if (!["FREE", "STARTER", "BUSINESS", "PRO"].includes(planCode)) {
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

    const userSnapshot = await db.doc(`users/${uid}`).get();
    if (!userSnapshot.exists) {
      throw new HttpsError("failed-precondition", "Owner profile is not ready for trial setup.");
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

    // A trial is an identity-level entitlement, not an Auth-UID entitlement.
    // The deletion callable leaves hashed claims behind, so deleting an
    // account and registering again cannot reset the trial allowance.
    const trialKeys = trialIdentityKeys(userSnapshot.data());
    if (planCode !== "FREE" && trialKeys.length === 0) {
      throw new HttpsError("failed-precondition", "A verified owner phone or recovery email is required for a free trial.");
    }
    if (planCode !== "FREE" && await hasConsumedOwnerTrial(userSnapshot.data())) {
      throw new HttpsError(
        "failed-precondition",
        "This owner identity has already used its free trial. Choose a paid plan without a trial or contact support.",
      );
    }

    const now = Date.now();
    const isFree = planCode === "FREE";
    const trialEndsAt = isFree ? null : now + 30 * 24 * 60 * 60 * 1000;
    const payload = {
      id: "current",
      businessId,
      planCode,
      status: isFree ? "ACTIVE" : "TRIALING",
      billingCycle: isFree ? "MONTHLY" : billingCycle,
      startedAt: now,
      currentPeriodStart: now,
      trialStartedAt: isFree ? null : Timestamp.fromMillis(now),
      trialEndsAt: isFree ? null : Timestamp.fromMillis(trialEndsAt as number),
      currentPeriodEnd: Timestamp.fromMillis(isFree ? now : trialEndsAt as number),
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
    const trialBatch = db.batch();
    trialBatch.create(subscriptionRef, payload);
    if (!isFree) {
      for (const key of trialKeys) {
        trialBatch.set(db.doc(`ownerTrialClaims/${key}`), {
          trialConsumed: true,
          sourceUid: uid,
          trialRecordedAt: now,
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
      }
    }
    await trialBatch.commit();
    return {
      businessId,
      planCode,
      status: isFree ? "ACTIVE" : "TRIALING",
      billingCycle: isFree ? "MONTHLY" : billingCycle,
      startedAt: now,
      currentPeriodStart: now,
      currentPeriodEnd: isFree ? now : trialEndsAt,
      trialStartedAt: isFree ? null : now,
      trialEndsAt: isFree ? null : trialEndsAt,
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

export const checkOwnerTrialEligibility = onCall(
  { region: REGION, enforceAppCheck: true, consumeAppCheckToken: true },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in before checking trial eligibility.");

    const userSnapshot = await db.doc(`users/${uid}`).get();
    if (!userSnapshot.exists) {
      throw new HttpsError("failed-precondition", "Owner profile is not ready for trial eligibility.");
    }

    return {
      trialConsumed: await hasConsumedOwnerTrial(userSnapshot.data()),
    };
  },
);

/**
 * Google Play is the payment system of record. The client supplies only a
 * product ID and token; plan, cycle, state, and expiry are derived here from
 * the Android Publisher API before the business subscription is updated.
 */
export const verifyPlaySubscription = onCall(
  {
    region: REGION,
    enforceAppCheck: true,
    consumeAppCheckToken: true,
    serviceAccount: PLAY_BILLING_SERVICE_ACCOUNT,
  },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in before verifying a purchase.");

    const data = request.data as Record<string, unknown>;
    const productId = requiredString(data.productId, "productId", 100);
    const purchaseToken = requiredString(data.purchaseToken, "purchaseToken", 4096);

    let verified;
    try {
      verified = await verifyGooglePlaySubscription(productId, purchaseToken);
    } catch (error) {
      if (error instanceof PlayBillingNotConfiguredError) {
        throw new HttpsError("failed-precondition", "Google Play billing is not configured yet.");
      }
      if (error instanceof PlayBillingVerificationError) {
        console.error("Google Play verification rejected", {
          productId,
          purchaseTokenHash: playPurchaseTokenHash(purchaseToken),
          httpStatus: error.httpStatus,
          googleErrorCode: error.googleErrorCode,
          googleStatus: error.googleStatus,
          debugMessage: error.debugMessage,
          retryable: error.retryable,
        });
        if (error.retryable) {
          throw new HttpsError(
            "unavailable",
            "Google Play verification is temporarily unavailable. Please retry.",
          );
        }
        throw new HttpsError(
          "failed-precondition",
          error.httpStatus
            ? `Google Play could not verify this purchase (HTTP ${error.httpStatus}).`
            : "Google Play could not verify this purchase.",
        );
      }
      throw new HttpsError("internal", "Purchase verification is temporarily unavailable.");
    }

    let businessId: string;
    try {
      const ownedBusinesses = await db.collection("businesses")
        .where("ownerUid", "==", uid)
        .limit(2)
        .get();
      if (ownedBusinesses.size !== 1) {
        throw new HttpsError("failed-precondition", "No unique owner business is ready for billing.");
      }
      businessId = ownedBusinesses.docs[0].id;
      const member = await db.doc(`businesses/${businessId}/members/${uid}`).get();
      if (!member.exists || member.get("isActive") !== true || member.get("role") !== "OWNER") {
        throw new HttpsError("permission-denied", "Only the business owner can activate billing.");
      }
    } catch (error) {
      if (error instanceof HttpsError) throw error;
      const details = describeBackendError(error);
      console.error("Google Play entitlement lookup failed", {
        serviceAccount: PLAY_BILLING_SERVICE_ACCOUNT,
        firestoreCode: details.code,
        firestoreMessage: details.message,
        firestoreDetails: details.details,
      });
      throw new HttpsError("internal", "Subscription entitlement storage is unavailable. Please retry.");
    }

    const now = Date.now();
    const tokenHash = playPurchaseTokenHash(purchaseToken);

    // Acknowledgement happens after verification but before the entitlement is
    // finalized. This prevents an unacknowledged Play purchase from looking
    // active in Firestore while Google is still able to cancel it.
    if (!verified.acknowledged) {
      try {
        await acknowledgePlaySubscription(verified.productId, purchaseToken);
      } catch (error) {
        const details = describePlayBillingError(error);
        console.error("Google Play acknowledgement failed", {
          productId: verified.productId,
          purchaseTokenHash: tokenHash,
          httpStatus: details.httpStatus,
          googleErrorCode: details.googleErrorCode,
          googleStatus: details.googleStatus,
          debugMessage: details.debugMessage,
          retryable: details.retryable,
        });
        throw new HttpsError(
          details.retryable ? "unavailable" : "internal",
          details.httpStatus
            ? `Purchase verified but acknowledgement failed (HTTP ${details.httpStatus}). Please retry.`
            : "Purchase verified but acknowledgement is pending. Please retry.",
        );
      }
    }

    try {
      await persistVerifiedPlaySubscription(businessId, verified, tokenHash, now);
    } catch (error) {
      if (error instanceof PlaySubscriptionTokenOwnershipError) {
        throw new HttpsError("permission-denied", "This Google Play purchase belongs to another business.");
      }
      const details = describeBackendError(error);
      console.error("Google Play entitlement persistence failed", {
        serviceAccount: PLAY_BILLING_SERVICE_ACCOUNT,
        purchaseTokenHash: tokenHash,
        firestoreCode: details.code,
        firestoreMessage: details.message,
        firestoreDetails: details.details,
      });
      throw new HttpsError("internal", "Subscription entitlement could not be synchronized. Please retry.");
    }

    return {
      businessId,
      planCode: verified.planCode,
      billingCycle: verified.billingCycle,
      status: verified.status,
      currentPeriodEnd: verified.currentPeriodEnd,
      verifiedAt: now,
    };
  },
);

type PlayRtdnPayload = {
  version?: string;
  packageName?: string;
  eventTimeMillis?: string;
  subscriptionNotification?: {
    version?: string;
    notificationType?: number;
    purchaseToken?: string;
    subscriptionId?: string;
  };
  testNotification?: { version?: string };
};

/**
 * Processes Google Play Real-time Developer Notifications. Play remains the
 * authority: the notification only identifies the purchase, then the current
 * subscription is fetched from Android Publisher before Firestore changes.
 * The topic name is configured in Play Console and intentionally kept in one
 * place for deploy-time setup.
 */
export const playSubscriptionNotifications = onMessagePublished(
  {
    topic: "play-subscription-notifications",
    region: REGION,
    serviceAccount: PLAY_BILLING_SERVICE_ACCOUNT,
  },
  async (event) => {
    const message = event.data.message;
    const payload = message.json as PlayRtdnPayload;
    if (payload.testNotification || !payload.subscriptionNotification) return;
    if (payload.packageName !== "com.aesprt.aquahub") return;

    const notification = payload.subscriptionNotification;
    const purchaseToken = notification.purchaseToken;
    const productId = notification.subscriptionId;
    if (!purchaseToken || !productId) return;

    const tokenHash = playPurchaseTokenHash(purchaseToken);
    const tokenRecord = await db.doc(`googlePlaySubscriptionTokens/${tokenHash}`).get();
    if (!tokenRecord.exists) {
      // A newly purchased token is normally first associated by the app's
      // verification call. Do not guess a business from a public RTDN.
      console.warn("Ignoring Play RTDN without a known purchase token", {
        messageId: message.messageId,
        notificationType: notification.notificationType,
      });
      return;
    }
    const businessId = tokenRecord.get("businessId") as string | undefined;
    if (!businessId) return;

    const eventRef = db.doc(`googlePlayBillingEvents/${message.messageId}`);
    const previousEvent = await eventRef.get();
    if (previousEvent.exists && previousEvent.get("processedAt") != null) return;

    let verified: VerifiedPlaySubscription;
    try {
      verified = await verifyGooglePlaySubscription(productId, purchaseToken);
    } catch (error) {
      // Throwing causes Pub/Sub to retry transient Play API failures instead
      // of permanently losing a renewal or cancellation update.
      throw error;
    }

    await persistVerifiedPlaySubscription(businessId, verified, tokenHash, Date.now());
    await eventRef.set({
      messageId: message.messageId,
      notificationType: notification.notificationType ?? null,
      purchaseTokenHash: tokenHash,
      businessId,
      processedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
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
      throw new HttpsError("failed-precondition", "This station's mobile ordering is unavailable on the current plan.");
    }
    const promotionsAllowed = hasPromotionAccess(subscription.data());
    const items = parseItems(data.items);
    const deliveryMode = data.deliveryMode === "PICKUP" ? "PICKUP" : "DELIVERY";
    const paymentMethod = (typeof data.paymentMethod === "string" && data.paymentMethod.trim().length > 0
      ? data.paymentMethod.trim().slice(0, 32)
      : deliveryMode === "DELIVERY" ? "CASH_ON_DELIVERY" : "CASH").toUpperCase();
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

      // Read the station order collection without a filtered query. The
      // customer aggregate below is derived from these documents, and using a
      // filtered query here would make order creation depend on a custom
      // single-field index being active in every target database.
      const existingOrdersQuery = db.collection(`businesses/${businessId}/stations/${stationId}/orders`);
      const [user, station, customerDoc, existingOrdersSnap] = await Promise.all([
        tx.get(userRef),
        tx.get(stationRef),
        tx.get(customerRef),
        tx.get(existingOrdersQuery)
      ]);
      if (!user.exists || user.get("role") !== "CUSTOMER" || user.get("isActive") !== true || user.get("setupComplete") !== true || !user.get("phone") || !user.get("address") || request.auth?.token.email_verified !== true) {
        throw new HttpsError("permission-denied", "This account cannot place customer orders.");
      }
      if (user.get("preferredBusinessId") !== businessId || user.get("preferredStationId") !== stationId) {
        throw new HttpsError(
          "permission-denied",
          "Scan this station's AquaHub QR code before placing an order.",
        );
      }
      const stationIsActive = station.exists && station.get("isActive") !== false && station.get("deletedAt") == null;
      const stationAcceptsOrders = (station.get("isAcceptingOnlineOrders") === true ||
        station.get("isAcceptingOrders") === true || station.get("isOpen") === true) &&
        station.get("isOpen") === true && stationIsOpenAtCurrentTime(station.data() ?? {});
      if (!stationIsActive || !stationAcceptsOrders) {
        throw new HttpsError("failed-precondition", "This station is not accepting orders.");
      }

      const allowedPaymentMethods = new Set<string>();
      if (deliveryMode === "DELIVERY" && station.get("codPaymentEnabled") !== false) {
        allowedPaymentMethods.add("CASH_ON_DELIVERY");
      }
      if (deliveryMode === "PICKUP" && station.get("cashPaymentEnabled") !== false) {
        allowedPaymentMethods.add("CASH");
      }
      if (station.get("gcashPaymentEnabled") === true) allowedPaymentMethods.add("GCASH");
      if (station.get("mayaPaymentEnabled") === true) allowedPaymentMethods.add("MAYA");
      if (!allowedPaymentMethods.has(paymentMethod)) {
        throw new HttpsError("failed-precondition", "That payment method is not enabled for this station and fulfilment option.");
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
        const regularUnitPrice = product.get("priceCentavos");
        if (!Number.isSafeInteger(regularUnitPrice) || regularUnitPrice < 0) throw new HttpsError("internal", "Product price is invalid.");
        const quantity = items[index].quantity;
        const promotion = appliedProductPromotion(product.data() ?? {}, regularUnitPrice, quantity, promotionsAllowed, now);
        const unitPrice = promotion?.unitPriceCentavos ?? regularUnitPrice;
        const stockQuantity = product.get("stockQuantity");
        if (typeof stockQuantity === "number" &&
            (!Number.isSafeInteger(stockQuantity) || stockQuantity < 0 || stockQuantity < quantity)) {
          throw new HttpsError("failed-precondition", `Insufficient stock for ${product.get("name") ?? "a selected product"}.`);
        }
        return {
          id: `${idempotencyKey}-${index}`,
          productId: product.id,
          productNameSnapshot: product.get("name") as string,
          productTypeSnapshot: (product.get("productType") as string) || "OTHER",
          sizeLabelSnapshot: (product.get("sizeLabel") as string) || "",
          unitPriceCentavos: unitPrice,
          regularUnitPriceCentavos: regularUnitPrice,
          quantity,
          subtotalCentavos: unitPrice * quantity,
          discountCentavos: (regularUnitPrice - unitPrice) * quantity,
          promotionLabel: promotion?.label ?? null,
          promotionType: promotion?.type ?? null,
          promotionMinimumQuantity: promotion?.minimumQuantity ?? null,
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

      const subtotalCentavos = lines.reduce((sum, line) => sum + line.regularUnitPriceCentavos * line.quantity, 0);
      const discountCentavos = lines.reduce((sum, line) => sum + line.discountCentavos, 0);
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
        discountCentavos,
        totalCentavos: subtotalCentavos - discountCentavos + deliveryFeeCentavos,
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

      const nonCancelledOrders = existingOrdersSnap.docs.filter((d) =>
        d.get("customerUid") === uid && !["CANCELLED", "REJECTED"].includes(d.get("status") as string),
      );
      const totalOrders = nonCancelledOrders.length + 1;
      let totalSpentCentavos = subtotalCentavos - discountCentavos + deliveryFeeCentavos;
      for (const d of nonCancelledOrders) {
        totalSpentCentavos += Number(d.get("totalCentavos") ?? 0);
      }

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
      tx.create(orderRef, order);
      tx.create(mirrorRef, { ...order, businessId });
      tx.create(requestRef, { uid, idempotencyKey, orderId: orderRef.id, businessId, stationId, createdAt: now });
      tx.create(db.collection("auditEvents").doc(), { type: "CUSTOMER_ORDER_CREATED", uid, businessId, stationId, orderId: orderRef.id, createdAt: now });
      return { orderId: orderRef.id, duplicate: false, businessId, stationId };
    });

    if (!result.duplicate && result.businessId) {
      await notifyStationMembers(result.businessId, result.stationId!, result.orderId)
        .catch((error) => console.error("Owner order notification failed", {
          businessId: result.businessId,
          stationId: result.stationId,
          orderId: result.orderId,
          error: error instanceof Error ? error.message : String(error),
        }));
    }
    return { orderId: result.orderId, duplicate: result.duplicate };
  },
);

export const setCustomerPreferredStation = onCall(
  { region: REGION, enforceAppCheck: true, consumeAppCheckToken: true },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in before choosing a water station.");
    const data = request.data as Record<string, unknown>;
    const businessId = requiredString(data.businessId, "businessId", 160);
    const stationId = requiredString(data.stationId, "stationId", 160);
    const requestedSource = typeof data.acquisitionSource === "string"
      ? data.acquisitionSource.trim().toUpperCase()
      : "UNKNOWN";
    const allowedSources = new Set(["STATION_QR", "STATION_LINK", "OWNER_REFERRAL"]);
    if (!allowedSources.has(requestedSource)) {
      throw new HttpsError("invalid-argument", "acquisitionSource is invalid.");
    }

    const userRef = db.doc(`users/${uid}`);
    const publicRef = db.doc(`businesses/${businessId}/publicStations/${stationId}`);
    const [user, publicStation] = await Promise.all([userRef.get(), publicRef.get()]);
    if (!user.exists || user.get("role") !== "CUSTOMER" || user.get("isActive") !== true) {
      throw new HttpsError("permission-denied", "This account cannot choose a water station.");
    }
    if (!publicStation.exists || publicStation.get("isActive") !== true || publicStation.get("businessId") !== businessId) {
      throw new HttpsError("not-found", "This water station is no longer available.");
    }

    const source = requestedSource;
    const acquiredAt = typeof user.get("acquiredAt") === "number" ? user.get("acquiredAt") : Date.now();
    await userRef.set({
      preferredBusinessId: businessId,
      preferredStationId: stationId,
      acquisitionSource: source,
      acquiredAt,
      updatedAt: Date.now(),
    }, { merge: true });
    return { businessId, stationId, acquisitionSource: source, acquiredAt };
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
      const businessId = mirror.get("businessId") as string;
      const stationId = mirror.get("stationId") as string;
      const ownerRef = db.doc(`businesses/${businessId}/stations/${stationId}/orders/${orderId}`);
      const owner = await tx.get(ownerRef);
      if (!owner.exists || owner.get("customerUid") !== uid) throw new HttpsError("not-found", "Order not found.");
      // The station order is authoritative. The customer mirror can be stale while an Owner transition is propagating.
      if (!["PENDING", "ACCEPTED"].includes(owner.get("status") as string)) {
        throw new HttpsError("failed-precondition", "This order can no longer be cancelled.");
      }
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
    const oldRiderId = before?.get("riderId") as string | undefined;
    const newRiderId = data.riderId as string | undefined;
    if (newRiderId && newRiderId !== oldRiderId) {
      await notifyAssignedRider(
        event.params.businessId,
        event.params.stationId,
        event.params.orderId,
        String(data.orderNumber ?? event.params.orderId),
        newRiderId,
      ).catch((error) => console.error("Rider assignment notification failed", {
        businessId: event.params.businessId,
        stationId: event.params.stationId,
        orderId: event.params.orderId,
        riderId: newRiderId,
        error: error instanceof Error ? error.message : String(error),
      }));
    }
    if (newStatus && (newStatus === "REJECTED" || newStatus === "CANCELLED") &&
        oldStatus !== "REJECTED" && oldStatus !== "CANCELLED") {
      if (!data.customerCancellationReason && Array.isArray(data.items)) {
        // The status mirror is delivered at-least-once. The compensation
        // record makes stock restoration idempotent, while throwing on any
        // failure lets the background trigger retry instead of silently
        // losing inventory after the order status has already changed.
        await restoreOrderInventoryOnce(
          event.params.businessId,
          event.params.stationId,
          event.params.orderId,
          data.items as Array<{ productId: string; quantity: number }>,
          newStatus,
        );
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

async function restoreOrderInventoryOnce(
  businessId: string,
  stationId: string,
  orderId: string,
  items: Array<{ productId: string; quantity: number }>,
  reason: string,
): Promise<void> {
  const quantities = new Map<string, number>();
  for (const item of items) {
    if (!item.productId || !Number.isSafeInteger(item.quantity) || item.quantity <= 0) {
      throw new Error(`Invalid inventory compensation item for order ${orderId}`);
    }
    quantities.set(item.productId, (quantities.get(item.productId) ?? 0) + item.quantity);
  }

  const compensationRef = db.doc(
    `businesses/${businessId}/stations/${stationId}/inventoryCompensations/${orderId}`,
  );
  const productRefs = [...quantities.keys()].map((productId) =>
    db.doc(`businesses/${businessId}/stations/${stationId}/products/${productId}`),
  );

  await db.runTransaction(async (tx) => {
    const compensation = await tx.get(compensationRef);
    if (compensation.exists) return;

    const products = [] as Array<{ ref: FirebaseFirestore.DocumentReference; snapshot: FirebaseFirestore.DocumentSnapshot }>;
    for (const ref of productRefs) {
      // Read every product before issuing any writes. This is required by
      // Firestore transactions and ensures the stock check is atomic.
      products.push({ ref, snapshot: await tx.get(ref) });
    }

    const now = Date.now();
    for (const product of products) {
      if (!product.snapshot.exists) {
        throw new Error(`Product ${product.ref.id} is missing for inventory compensation ${orderId}`);
      }
      const currentStock = product.snapshot.get("stockQuantity");
      const quantity = quantities.get(product.ref.id) ?? 0;
      if (!Number.isSafeInteger(currentStock) || currentStock < 0 || !Number.isSafeInteger(quantity) ||
          currentStock + quantity > Number.MAX_SAFE_INTEGER) {
        throw new Error(`Invalid stock value for product ${product.ref.id}`);
      }
      tx.update(product.ref, {
        stockQuantity: currentStock + quantity,
        clientUpdatedAt: now,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }

    tx.create(compensationRef, {
      orderId,
      businessId,
      stationId,
      reason,
      quantities: Object.fromEntries(quantities),
      createdAt: now,
    });
  });
}

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
    android: { priority: "high" },
    // Data-only delivery ensures the owner service runs in the background
    // and wakes the durable sync when a new order arrives.
    data: {
      type: "NEW_CUSTOMER_ORDER",
      title: "New online customer order",
      body: "A new customer order is waiting for confirmation.",
      businessId,
      stationId,
      orderId
    },
  });
}

async function notifyAssignedRider(
  businessId: string,
  stationId: string,
  orderId: string,
  orderNumber: string,
  riderId: string,
): Promise<void> {
  const riderSnapshot = await db.doc(
    `businesses/${businessId}/stations/${stationId}/riders/${riderId}`,
  ).get();
  const riderPhone = normalizeTrialIdentity(riderSnapshot.get("phone"), "phone");
  if (!riderPhone) return;

  const members = await db.collection(`businesses/${businessId}/members`)
    .where("role", "==", "RIDER")
    .where("isActive", "==", true)
    .get();
  const eligible: FirebaseFirestore.QueryDocumentSnapshot[] = [];
  await Promise.all(members.docs.map(async (member) => {
    const profile = await db.doc(`users/${member.id}`).get();
    if (normalizeTrialIdentity(profile.get("phone"), "phone") === riderPhone) {
      eligible.push(member);
    }
  }));
  const deviceSnapshots = await Promise.all(eligible.map((member) =>
    db.collection(`users/${member.id}/devices`).get(),
  ));
  const tokens = deviceSnapshots.flatMap((snapshot) =>
    snapshot.docs.map((doc) => doc.get("token") as string),
  ).filter(Boolean).slice(0, 500);
  if (!tokens.length) return;
  await getMessaging().sendEachForMulticast({
    tokens,
    android: { priority: "high" },
    data: {
      type: "RIDER_ASSIGNMENT",
      title: "New delivery assigned",
      body: `Order ${orderNumber} has been assigned to you.`,
      businessId,
      stationId,
      orderId,
      riderId,
    },
  });
}

async function syncPublicStationProjection(
  businessId: string,
  stationId: string,
  source: FirebaseFirestore.DocumentData | undefined,
  publicAccess: boolean,
  promotionsAllowed: boolean,
): Promise<void> {
  const publicRef = db.doc(`businesses/${businessId}/publicStations/${stationId}`);
  if (!publicAccess || !source) {
    await publicRef.delete();
    await syncPublicProductProjections(businessId, stationId, false, false);
    return;
  }

  const stringValue = (value: unknown, max: number): string | null =>
    typeof value === "string" && value.trim().length > 0 ? value.trim().slice(0, max) : null;
  const numberValue = (value: unknown, min: number, max: number): number | null =>
    typeof value === "number" && Number.isFinite(value) && value >= min && value <= max ? value : null;
  const integerValue = (value: unknown, min: number, max: number): number | null => {
    const number = numberValue(value, min, max);
    return number !== null && Number.isSafeInteger(number) ? number : null;
  };
  const inactive = source.isActive === false || source.deletedAt != null;
  // The owner-controlled open switch is authoritative. The acceptance flags
  // can be stale because they are maintained separately by older owner app
  // versions, so never publish them as accepting while the station is closed.
  const isOpen = !inactive && source.isOpen === true;
  // Owner clients historically stored online acceptance under
  // isAcceptingOnlineOrders, while the public/customer contract consumes
  // isAcceptingOrders. Treat either explicit flag as the same setting and
  // default legacy open stations to accepting orders when neither exists.
  const hasExplicitAcceptance = typeof source.isAcceptingOnlineOrders === "boolean" ||
    typeof source.isAcceptingOrders === "boolean";
  const acceptsOrders = source.isAcceptingOnlineOrders === true ||
    source.isAcceptingOrders === true ||
    (!hasExplicitAcceptance && isOpen);
  const isAcceptingOnlineOrders = isOpen && acceptsOrders;
  const isAcceptingOrders = isOpen && acceptsOrders;
  const address = (stringValue(source.address, 500) ??
    stringValue(source.addressLine, 500) ??
    [source.barangay, source.city, source.province]
      .map((value) => stringValue(value, 120))
      .filter((value): value is string => value !== null)
      .join(", ")) || null;

  // This projection is the only station shape exposed to customers. Never
  // copy owner/staff/internal fields from the operational station document.
  await publicRef.set({
    id: stationId,
    businessId,
    name: stringValue(source.name, 120) ?? "Water station",
    description: stringValue(source.description, 1000),
    phone: stringValue(source.phone, 32),
    address,
    barangay: stringValue(source.barangay, 120),
    city: stringValue(source.city, 120),
    province: stringValue(source.province, 120),
    placeId: stringValue(source.placeId, 256),
    latitude: numberValue(source.latitude ?? source.lat, -90, 90),
    longitude: numberValue(source.longitude ?? source.lng, -180, 180),
    logoPath: stringValue(source.logoPath, 2048),
    openingTime: stringValue(source.openingTime, 20),
    closingTime: stringValue(source.closingTime, 20),
    isActive: !inactive,
    isOpen,
    manualOpenOverride: source.manualOpenOverride === true,
    isAcceptingOnlineOrders,
    isAcceptingOrders,
    deliveryRadiusKm: numberValue(source.deliveryRadiusKm, 0, 500),
    deliveryFeeCentavos: integerValue(source.deliveryFeeCentavos, 0, 1000000000),
    estimatedPreparationMinutes: integerValue(source.estimatedPreparationMinutes, 0, 1440),
    businessRulesText: stringValue(source.businessRulesText, 2000),
    businessRulesVersion: integerValue(source.businessRulesVersion, 0, Number.MAX_SAFE_INTEGER),
    publicStationCode: stringValue(source.publicStationCode, 64),
    cashPaymentEnabled: source.cashPaymentEnabled !== false,
    codPaymentEnabled: source.codPaymentEnabled !== false,
    gcashPaymentEnabled: source.gcashPaymentEnabled === true,
    gcashAccountName: stringValue(source.gcashAccountName, 120),
    gcashAccountNumber: stringValue(source.gcashAccountNumber, 64),
    gcashQrImagePath: stringValue(source.gcashQrImagePath, 2048),
    mayaPaymentEnabled: source.mayaPaymentEnabled === true,
    mayaAccountName: stringValue(source.mayaAccountName, 120),
    mayaAccountNumber: stringValue(source.mayaAccountNumber, 64),
    mayaQrImagePath: stringValue(source.mayaQrImagePath, 2048),
    averageRating: numberValue(source.averageRating ?? source.rating, 0, 5),
    ratingCount: integerValue(source.ratingCount ?? source.totalReviews, 0, 1000000000),
    updatedAt: FieldValue.serverTimestamp(),
  }, { merge: false });
  await syncPublicProductProjections(businessId, stationId, true, promotionsAllowed);
}

async function syncPublicProductProjection(
  businessId: string,
  stationId: string,
  productId: string,
  source: FirebaseFirestore.DocumentData | undefined,
  publicAccess: boolean,
  promotionsAllowed: boolean,
): Promise<void> {
  const publicRef = db.doc(`businesses/${businessId}/publicStations/${stationId}/products/${productId}`);
  const available = source?.isAvailableForOrdering ?? source?.isAvailable ?? source?.isActive ?? true;
  const price = source?.priceCentavos;
  if (!publicAccess || !source || source.deletedAt != null || available !== true ||
      !Number.isSafeInteger(price) || price < 0) {
    await publicRef.delete();
    return;
  }

  const stringValue = (value: unknown, max: number): string =>
    typeof value === "string" ? value.trim().slice(0, max) : "";
  // Publish valid future campaigns too. The customer hides them until the
  // start time, while checkout always re-evaluates the current server time.
  const projectionEvaluationTime = Math.max(Date.now(), epochMillis(source.promotionStartsAt));
  const promotion = appliedProductPromotion(source, price, 99, promotionsAllowed, projectionEvaluationTime);
  await publicRef.set({
    id: productId,
    productId,
    name: stringValue(source.name, 160) || "Water product",
    productType: stringValue(source.productType ?? source.type, 40) || "OTHER",
    sizeLabel: stringValue(source.sizeLabel, 80),
    description: stringValue(source.description, 1000),
    imagePath: stringValue(source.imagePath, 2048) || null,
    priceCentavos: price,
    promotionLabel: promotion?.label ?? null,
    promotionType: promotion?.type ?? null,
    promotionPercentBps: promotion && Number.isSafeInteger(source.promotionPercentBps) ? source.promotionPercentBps : null,
    promotionalPriceCentavos: promotion?.type === "FIXED_PRICE" ? promotion.unitPriceCentavos : null,
    promotionMinimumQuantity: promotion?.minimumQuantity ?? null,
    promotionStartsAt: promotion ? epochMillis(source.promotionStartsAt) : null,
    promotionEndsAt: promotion && source.promotionEndsAt != null ? epochMillis(source.promotionEndsAt) : null,
    promotionIsActive: promotion !== null,
    isAvailableForOrdering: true,
    updatedAt: FieldValue.serverTimestamp(),
  }, { merge: false });
}

async function syncPublicProductProjections(
  businessId: string,
  stationId: string,
  publicAccess: boolean,
  promotionsAllowed: boolean,
): Promise<void> {
  const products = await db.collection(`businesses/${businessId}/stations/${stationId}/products`).get();
  await Promise.all(products.docs.map((product) => syncPublicProductProjection(
    businessId,
    stationId,
    product.id,
    product.data(),
    publicAccess,
    promotionsAllowed,
  )));
}

export const publishPublicStation = onDocumentWritten(
  { region: REGION, database: DATABASE, document: "businesses/{businessId}/stations/{stationId}" },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) {
      await syncPublicStationProjection(event.params.businessId, event.params.stationId, undefined, false, false);
      return;
    }
    const subscription = await db.doc(`businesses/${event.params.businessId}/subscription/current`).get();
    await syncPublicStationProjection(
      event.params.businessId,
      event.params.stationId,
      after.data(),
      hasPublicStationAccess(subscription.data()),
      hasPromotionAccess(subscription.data()),
    );
  },
);

export const publishPublicProduct = onDocumentWritten(
  { region: REGION, database: DATABASE, document: "businesses/{businessId}/stations/{stationId}/products/{productId}" },
  async (event) => {
    const publicStation = await db.doc(`businesses/${event.params.businessId}/publicStations/${event.params.stationId}`).get();
    const subscription = await db.doc(`businesses/${event.params.businessId}/subscription/current`).get();
    await syncPublicProductProjection(
      event.params.businessId,
      event.params.stationId,
      event.params.productId,
      event.data?.after?.exists ? event.data.after.data() : undefined,
      publicStation.exists && publicStation.get("isActive") === true,
      hasPromotionAccess(subscription.data()),
    );
  },
);

// Station creation can race subscription provisioning. Reconcile all branches
// when the account plan changes so a newly eligible paid/trial plan is exposed,
// while FREE or expired plans immediately lose their public projections.
export const syncPublicStationsForSubscription = onDocumentWritten(
  { region: REGION, database: DATABASE, document: "businesses/{businessId}/subscription/current" },
  async (event) => {
    const after = event.data?.after;
    const publicAccess = hasPublicStationAccess(after?.exists ? after.data() : undefined);
    const promotionsAllowed = hasPromotionAccess(after?.exists ? after.data() : undefined);
    const stations = await db.collection(`businesses/${event.params.businessId}/stations`).get();
    await Promise.all(stations.docs.map((station) => syncPublicStationProjection(
      event.params.businessId,
      station.id,
      station.data(),
      publicAccess,
      promotionsAllowed,
    )));
  },
);

export const deleteAquaHubAccount = onCall(
  { region: REGION, enforceAppCheck: true },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in before deleting your account.");

    // Account deletion is destructive; require a recent Firebase Auth event.
    const authTimeSeconds = Number(request.auth?.token?.auth_time ?? 0);
    if (!Number.isFinite(authTimeSeconds) || authTimeSeconds <= 0 ||
        Date.now() / 1000 - authTimeSeconds > 300) {
      throw new HttpsError("failed-precondition", "Recent sign-in required before deleting your account.");
    }

    const userRef = db.doc(`users/${uid}`);
    const user = await userRef.get();
    const role = user.get("role") as string | undefined;
    const activeBusinessId = user.get("activeBusinessId") as string | undefined;
    const now = Date.now();

    // Private customer order mirrors are removed. Station-owned orders remain
    // in sales history, but direct customer data is anonymized.
    const customerOrders = await db.collectionGroup("orders")
      .where("customerUid", "==", uid)
      .get();
    const batches: FirebaseFirestore.WriteBatch[] = [];
    let batch = db.batch();
    let writes = 0;
    let retainedSharedBusiness = false;
    const commitBatch = async () => {
      if (writes > 0) {
        batches.push(batch);
        batch = db.batch();
        writes = 0;
      }
    };
    for (const order of customerOrders.docs) {
      if (order.ref.path.startsWith(`users/${uid}/orders/`)) {
        batch.delete(order.ref);
      } else {
        batch.set(order.ref, {
          customerUid: null,
          customerProfileId: null,
          customerId: null,
          customerName: "Deleted User",
          customerPhone: "",
          deliveryAddress: "Address removed",
          deliveryPlaceId: null,
          deliveryLatitude: null,
          deliveryLongitude: null,
          customerNote: null,
          customerDeletedAt: now,
          updatedAt: FieldValue.serverTimestamp(),
          clientUpdatedAt: now,
        }, { merge: true });
      }
      writes++;
      if (writes >= 450) await commitBatch();
    }

    const orderRequests = await db.collection("customerOrderRequests").where("uid", "==", uid).get();
    for (const requestDoc of orderRequests.docs) {
      batch.delete(requestDoc.ref);
      writes++;
      if (writes >= 450) await commitBatch();
    }

    // Keep operational audit history, but remove the deleted account UID from
    // retained events so it cannot be used as a personal identifier.
    const auditEvents = await db.collection("auditEvents").where("uid", "==", uid).get();
    for (const event of auditEvents.docs) {
      batch.set(event.ref, { uid: null, accountDeletedAt: now, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      writes++;
      if (writes >= 450) await commitBatch();
    }

    const customerSnapshots = await db.collectionGroup("customers")
      .where("customerUid", "==", uid)
      .get();
    for (const snapshot of customerSnapshots.docs) {
      batch.set(snapshot.ref, {
        name: "Deleted User",
        phone: "",
        email: null,
        isActive: false,
        deletedAt: now,
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: true });
      writes++;
      if (writes >= 450) await commitBatch();
    }

    if (role === "OWNER") {
      // Derive all sole-owned businesses from the trusted ownerUid field. The
      // current Owner UI exposes one activeBusinessId, but deletion must not
      // leave an older owned tenant active. Membership alone never qualifies.
      const ownedBusinesses = await db.collection("businesses").where("ownerUid", "==", uid).get();
      const businessIds = new Set(ownedBusinesses.docs.map((doc) => doc.id));
      if (activeBusinessId && !businessIds.has(activeBusinessId)) {
        const activeBusiness = await db.collection("businesses").doc(activeBusinessId).get();
        if (activeBusiness.exists && activeBusiness.get("ownerUid") === uid) businessIds.add(activeBusinessId);
      }

      for (const businessId of businessIds) {
        const businessRef = db.collection("businesses").doc(businessId);
        const stations = await businessRef.collection("stations").get();
        const activeMembers = await businessRef.collection("members")
          .where("isActive", "==", true)
          .get();
        const remainingAdministrators = activeMembers.docs.some((member) =>
          member.id !== uid && ["OWNER", "MANAGER"].includes(member.get("role") as string));
        const businessSnapshot = ownedBusinesses.docs.find((doc) => doc.id === businessId);
        const declaredCoOwners = Array.isArray(businessSnapshot?.get("ownerUids")) &&
          (businessSnapshot?.get("ownerUids") as unknown[]).some((candidate) => candidate !== uid);
        const subscriptionRef = businessRef.collection("subscription").doc("current");
        const subscription = await subscriptionRef.get();
        if (hasTrialHistory(subscription.data())) {
          for (const key of trialIdentityKeys(user.data())) {
            batch.set(db.doc(`ownerTrialClaims/${key}`), {
              trialConsumed: true,
              sourceUid: uid,
              trialRecordedAt: now,
              accountDeletedAt: now,
              updatedAt: FieldValue.serverTimestamp(),
            }, { merge: true });
            writes++;
            if (writes >= 450) await commitBatch();
          }
        }
        if (remainingAdministrators || declaredCoOwners) {
          retainedSharedBusiness = true;
          // Preserve a tenant that still has an active administrator. The
          // current schema has one ownerUid, so clear the deleted identity and
          // mark the business for explicit ownership transfer.
          batch.set(businessRef, {
            ownerUid: null,
            ownershipTransferRequired: true,
            accountDeletedAt: now,
            updatedAt: FieldValue.serverTimestamp(),
          }, { merge: true });
          batch.set(businessRef.collection("members").doc(uid), {
            isActive: false,
            deletedAt: now,
            updatedAt: FieldValue.serverTimestamp(),
          }, { merge: true });
          writes += 2;
          continue;
        }
        batch.set(businessRef, {
          ownerUid: null,
          isActive: false,
          deletedAt: now,
          accountDeletedAt: now,
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        writes++;
        const memberRef = businessRef.collection("members").doc(uid);
        batch.set(memberRef, {
          isActive: false,
          deletedAt: now,
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        writes++;
        const activeOrderStatuses = ["PENDING", "ACCEPTED", "PREPARING", "READY_FOR_PICKUP", "RIDER_ASSIGNED", "OUT_FOR_DELIVERY"];
        for (const station of stations.docs) {
          const activeOrders = await station.ref.collection("orders")
            .where("status", "in", activeOrderStatuses)
            .get();
          for (const activeOrder of activeOrders.docs) {
            batch.set(activeOrder.ref, {
              status: "REJECTED",
              rejectionReason: "Station owner account deleted.",
              ownerAccountDeletedAt: now,
              updatedAt: FieldValue.serverTimestamp(),
              clientUpdatedAt: now,
            }, { merge: true });
            writes++;
            if (writes >= 450) await commitBatch();
          }
          batch.set(station.ref, {
            isActive: false,
            isAcceptingOnlineOrders: false,
            isAcceptingOrders: false,
            deletedAt: now,
            updatedAt: FieldValue.serverTimestamp(),
          }, { merge: true });
          writes++;
          if (writes >= 450) await commitBatch();
        }
        batch.set(subscriptionRef, {
          status: "CANCELLED",
          cancelAtPeriodEnd: false,
          cancelledAt: now,
          accountDeletedAt: now,
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        writes++;
      }
    }

    await commitBatch();
    await Promise.all(batches.map((writeBatch) => writeBatch.commit()));
    await db.recursiveDelete(userRef);
    await getAuth().deleteUser(uid).catch((error: unknown) => {
      const code = (error as { code?: string }).code;
      if (code !== "auth/user-not-found") throw error;
    });

    return {
      deleted: true,
      role: role ?? "UNKNOWN",
      businessArchived: role === "OWNER" && !retainedSharedBusiness,
      businessesRetainedForTransfer: retainedSharedBusiness,
      retainedOrderHistory: true,
    };
  },
);
