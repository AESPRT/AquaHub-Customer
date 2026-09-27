import { createHash } from "node:crypto";
import { GoogleAuth } from "google-auth-library";

export const PLAY_PACKAGE_NAME = "com.aesprt.aquahub";
export const PLAY_BILLING_ENABLED = "true";

export type PlayPlan = {
  productId: string;
  basePlanId: string;
  planCode: "STARTER" | "BUSINESS" | "PRO";
  billingCycle: "MONTHLY" | "ANNUAL";
};

export const PLAY_PLANS: readonly PlayPlan[] = [
  { productId: "aquahub_starter", basePlanId: "monthly", planCode: "STARTER", billingCycle: "MONTHLY" },
  { productId: "aquahub_starter", basePlanId: "yearly", planCode: "STARTER", billingCycle: "ANNUAL" },
  { productId: "aquahub_business", basePlanId: "monthly", planCode: "BUSINESS", billingCycle: "MONTHLY" },
  { productId: "aquahub_business", basePlanId: "yearly", planCode: "BUSINESS", billingCycle: "ANNUAL" },
  { productId: "aquahub_pro", basePlanId: "monthly", planCode: "PRO", billingCycle: "MONTHLY" },
  { productId: "aquahub_pro", basePlanId: "yearly", planCode: "PRO", billingCycle: "ANNUAL" },
];

export class PlayBillingNotConfiguredError extends Error {}
export type PlayBillingErrorDetails = {
  httpStatus: number | null;
  googleErrorCode: number | string | null;
  googleStatus: string | null;
  debugMessage: string;
  retryable: boolean;
};

export class PlayBillingVerificationError extends Error {
  readonly httpStatus: number | null;
  readonly googleErrorCode: number | string | null;
  readonly googleStatus: string | null;
  readonly debugMessage: string;
  readonly retryable: boolean;

  constructor(message: string, details?: Partial<PlayBillingErrorDetails>) {
    super(message);
    this.name = "PlayBillingVerificationError";
    this.httpStatus = details?.httpStatus ?? null;
    this.googleErrorCode = details?.googleErrorCode ?? null;
    this.googleStatus = details?.googleStatus ?? null;
    this.debugMessage = details?.debugMessage ?? message;
    this.retryable = details?.retryable ?? false;
  }
}

export function playPurchaseTokenHash(purchaseToken: string): string {
  // The raw token is never persisted. This value is also the stable key used
  // by RTDN processing to find the business that owns a subscription.
  return createHash("sha256")
    .update(`aquahub-play-token-v1:${purchaseToken}`)
    .digest("hex");
}

type SubscriptionResponse = {
  subscriptionState?: string;
  acknowledgementState?: string;
  startTime?: string;
  lineItems?: Array<{
    productId?: string;
    expiryTime?: string;
    offerDetails?: { basePlanId?: string; offerId?: string };
    autoRenewingPlan?: { autoRenewEnabled?: boolean; recurringPrice?: unknown };
  }>;
};

export type VerifiedPlaySubscription = {
  productId: string;
  basePlanId: string;
  planCode: PlayPlan["planCode"];
  billingCycle: PlayPlan["billingCycle"];
  status: "ACTIVE" | "ACTIVE_UNTIL_PERIOD_END" | "GRACE_PERIOD" | "SUSPENDED" | "EXPIRED";
  currentPeriodStart: number;
  currentPeriodEnd: number;
  autoRenew: boolean;
  cancelAtPeriodEnd: boolean;
  acknowledged: boolean;
  rawSubscriptionName?: string;
};

function requireBillingEnabled(): void {
  // Billing is enabled by default in deployed Functions. Set the variable to
  // "false" only when deliberately disabling the provider; an unset optional
  // environment variable must not turn every valid Play purchase into the
  // misleading "not configured" response.
  if (process.env.PLAY_BILLING_ENABLED === "false") {
    throw new PlayBillingNotConfiguredError("Google Play billing is not configured on the server.");
  }
}

function parseMillis(value: string | undefined, field: string): number {
  const millis = value ? Date.parse(value) : NaN;
  if (!Number.isFinite(millis)) throw new PlayBillingVerificationError(`Google Play response has no valid ${field}.`);
  return millis;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

export function describePlayBillingError(error: unknown): PlayBillingErrorDetails {
  const errorRecord = isRecord(error) ? error : {};
  const response = isRecord(errorRecord.response) ? errorRecord.response : {};
  const responseData = isRecord(response.data) ? response.data : {};
  const googleError = isRecord(responseData.error) ? responseData.error : {};
  const httpStatus = typeof response.status === "number" ? response.status : null;
  const googleErrorCode = typeof googleError.code === "number" || typeof googleError.code === "string"
    ? googleError.code
    : null;
  const googleStatus = typeof googleError.status === "string" ? googleError.status : null;
  const debugMessage = typeof googleError.message === "string"
    ? googleError.message
    : error instanceof Error ? error.message : "Unknown Google Play Publisher API error.";
  const retryable = httpStatus === null || httpStatus === 404 || httpStatus === 408 ||
    httpStatus === 429 || httpStatus >= 500;
  return { httpStatus, googleErrorCode, googleStatus, debugMessage, retryable };
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function selectedPlan(productId: string, basePlanId: string): PlayPlan {
  const plan = PLAY_PLANS.find((candidate) =>
    candidate.productId === productId && candidate.basePlanId === basePlanId);
  if (!plan) throw new PlayBillingVerificationError("The Google Play product or base plan is not allowed.");
  return plan;
}

function subscriptionStateToAccess(state: string | undefined, expiry: number): VerifiedPlaySubscription["status"] {
  switch (state) {
    case "SUBSCRIPTION_STATE_ACTIVE":
      return expiry > Date.now() ? "ACTIVE" : "EXPIRED";
    case "SUBSCRIPTION_STATE_CANCELED":
      return expiry > Date.now() ? "ACTIVE_UNTIL_PERIOD_END" : "EXPIRED";
    case "SUBSCRIPTION_STATE_IN_GRACE_PERIOD":
      return expiry > Date.now() ? "GRACE_PERIOD" : "EXPIRED";
    case "SUBSCRIPTION_STATE_ON_HOLD":
    case "SUBSCRIPTION_STATE_PAUSED":
    case "SUBSCRIPTION_STATE_PENDING":
    case "SUBSCRIPTION_STATE_UNSPECIFIED":
      return "SUSPENDED";
    case "SUBSCRIPTION_STATE_EXPIRED":
      return "EXPIRED";
    default:
      throw new PlayBillingVerificationError("Google Play returned an unknown subscription state.");
  }
}

async function publisherRequest<T = SubscriptionResponse>(path: string, init?: RequestInit): Promise<T> {
  requireBillingEnabled();
  let lastError: unknown;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const auth = new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });
      const client = await auth.getClient();
      const response = await client.request<T>({
        url: `https://androidpublisher.googleapis.com/androidpublisher/v3${path}`,
        method: init?.method ?? "GET",
        data: init?.body ? JSON.parse(String(init.body)) : undefined,
        headers: { "content-type": "application/json" },
      });
      return response.data;
    } catch (error) {
      lastError = error;
      const details = describePlayBillingError(error);
      if (!details.retryable || attempt === 3) throw error;
      await wait(250 * 2 ** (attempt - 1));
    }
  }
  throw lastError ?? new Error("Google Play Publisher API request failed.");
}

export async function verifyPlaySubscription(
  productId: string,
  purchaseToken: string,
): Promise<VerifiedPlaySubscription> {
  if (productId.length === 0 || productId.length > 100 || !/^[a-z0-9_]+$/.test(productId)) {
    throw new PlayBillingVerificationError("The Google Play product ID is invalid.");
  }
  if (purchaseToken.length < 20 || purchaseToken.length > 4096) {
    throw new PlayBillingVerificationError("The Google Play purchase token is invalid.");
  }

  const encodedPackage = encodeURIComponent(PLAY_PACKAGE_NAME);
  const encodedToken = encodeURIComponent(purchaseToken);
  let response: SubscriptionResponse;
  try {
    response = await publisherRequest(
      `/applications/${encodedPackage}/purchases/subscriptionsv2/tokens/${encodedToken}`,
    );
  } catch (error) {
    if (error instanceof PlayBillingNotConfiguredError) throw error;
    const details = describePlayBillingError(error);
    console.error("Google Play subscription verification failed", {
      productId,
      purchaseTokenHash: playPurchaseTokenHash(purchaseToken),
      httpStatus: details.httpStatus,
      googleErrorCode: details.googleErrorCode,
      googleStatus: details.googleStatus,
      debugMessage: details.debugMessage,
      retryable: details.retryable,
    });
    throw new PlayBillingVerificationError("Google Play could not verify this purchase.", details);
  }

  const lineItem = response.lineItems?.find((item) => item.productId === productId);
  if (!lineItem) throw new PlayBillingVerificationError("The purchase does not contain the requested product.");
  const expiry = parseMillis(lineItem.expiryTime, "expiry time");
  const start = response.startTime ? parseMillis(response.startTime, "start time") : Date.now();
  // Base-plan identity comes from the verified line item, never from the
  // client-selected cycle or a client-supplied price.
  const basePlanId = lineItem.offerDetails?.basePlanId ?? "";
  const plan = selectedPlan(productId, basePlanId);
  const status = subscriptionStateToAccess(response.subscriptionState, expiry);
  return {
    productId,
    basePlanId,
    planCode: plan.planCode,
    billingCycle: plan.billingCycle,
    status,
    currentPeriodStart: start,
    currentPeriodEnd: expiry,
    autoRenew: lineItem.autoRenewingPlan?.autoRenewEnabled === true,
    cancelAtPeriodEnd: response.subscriptionState === "SUBSCRIPTION_STATE_CANCELED",
    acknowledged: response.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    rawSubscriptionName: undefined,
  };
}

export async function acknowledgePlaySubscription(productId: string, purchaseToken: string): Promise<void> {
  requireBillingEnabled();
  const packageName = encodeURIComponent(PLAY_PACKAGE_NAME);
  const encodedProduct = encodeURIComponent(productId);
  const encodedToken = encodeURIComponent(purchaseToken);
  await publisherRequest(
    `/applications/${packageName}/purchases/subscriptions/${encodedProduct}/tokens/${encodedToken}:acknowledge`,
    { method: "POST", body: "{}" },
  );
}

export async function cancelPlaySubscription(purchaseToken: string): Promise<void> {
  requireBillingEnabled();
  const packageName = encodeURIComponent(PLAY_PACKAGE_NAME);
  const encodedToken = encodeURIComponent(purchaseToken);
  await publisherRequest(
    `/applications/${packageName}/purchases/subscriptionsv2/tokens/${encodedToken}:cancel`,
    { method: "POST", body: "{}" },
  );
}
