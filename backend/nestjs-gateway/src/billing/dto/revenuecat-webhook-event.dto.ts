// backend/nestjs-gateway/src/billing/dto/revenuecat-webhook-event.dto.ts

/**
 * RevenueCat'in webhook olarak gönderdiği event tipleri.
 * Referans: RevenueCat Webhooks dokümantasyonu (Event Types).
 */
export enum RevenueCatEventType {
  INITIAL_PURCHASE = 'INITIAL_PURCHASE',
  RENEWAL = 'RENEWAL',
  CANCELLATION = 'CANCELLATION',
  UNCANCELLATION = 'UNCANCELLATION',
  EXPIRATION = 'EXPIRATION',
  BILLING_ISSUE = 'BILLING_ISSUE',
  PRODUCT_CHANGE = 'PRODUCT_CHANGE',
  SUBSCRIBER_ALIAS = 'SUBSCRIBER_ALIAS',
  TRANSFER = 'TRANSFER',
  NON_RENEWING_PURCHASE = 'NON_RENEWING_PURCHASE',
  SUBSCRIPTION_PAUSED = 'SUBSCRIPTION_PAUSED',
  TEST = 'TEST',
}

export interface RevenueCatWebhookEvent {
  type: RevenueCatEventType;
  id: string;
  app_user_id: string;
  original_app_user_id: string;
  product_id: string;
  entitlement_ids: string[] | null;
  period_type: 'NORMAL' | 'TRIAL' | 'INTRO' | 'PROMOTIONAL' | null;
  purchased_at_ms: number;
  expiration_at_ms: number | null;
  environment: 'SANDBOX' | 'PRODUCTION';
  store: 'PLAY_STORE' | 'APP_STORE' | 'STRIPE' | 'AMAZON' | 'MAC_APP_STORE' | 'PROMOTIONAL';
  is_family_share: boolean | null;
  price: number | null;
  currency: string | null;
  cancel_reason: string | null;
}

export interface RevenueCatWebhookPayload {
  event: RevenueCatWebhookEvent;
  api_version: string;
}
