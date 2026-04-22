import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface ShopPricingPlanDto {
  code: string;
  label: string;
  durationDays: number;
  currency: string;
  grossAmountMinor: number;
  grantValue: number | null;
  grantUnit: string | null;
  teaser: string;
  effects: ShopPlanEffectDto[];
}

export interface ShopPlanEffectDto {
  type: 'DURATION_DAYS' | 'RESOURCE_GRANT';
  code: string | null;
  value: number;
}

export interface ShopTrustHighlightDto {
  icon: string | null;
  title: string;
  detail: string;
}

export interface ShopAdvantageDto {
  icon: string | null;
  title: string;
  bullets: string[];
}

export interface ShopProductDto {
  code: string;
  slug: string;
  pricingMode: 'SUBSCRIPTION' | 'ONE_TIME';
  title: string;
  subtitle: string | null;
  description: string | null;
  badgeLabel: string | null;
  heroImageUrl: string | null;
  checkoutEnabled: boolean;
  trustHighlights: ShopTrustHighlightDto[];
  advantages: ShopAdvantageDto[];
  pricingPlans: ShopPricingPlanDto[];
}

export interface ShopCatalogResponseDto {
  items: ShopProductDto[];
}

export interface ShopOrderDto {
  publicId: string;
  productCode: string;
  productSlug: string;
  productName: string;
  planCode: string;
  planName: string;
  currency: string;
  grossAmountMinor: number;
  quantity: number;
  durationDays: number;
  paymentProvider: string;
  paymentStatus: string;
  checkoutRedirectUrl: string | null;
  failureReason: string | null;
  createdAt: string | null;
  paidAt: string | null;
  fulfilledAt: string | null;
  premiumStartsAt: string | null;
  premiumExpiresAt: string | null;
  simulationActionsEnabled: boolean;
}

export interface ShopCreateOrderItemRequestDto {
  productSlug: string;
  planCode: string;
  quantity: number;
}

export type ShopSimulationOutcome = 'SUCCESS' | 'FAILURE' | 'CANCEL';

@Injectable({ providedIn: 'root' })
export class ShopApi {
  constructor(private readonly http: HttpClient) {}

  getCatalog(): Observable<ShopCatalogResponseDto> {
    return this.http.get<ShopCatalogResponseDto>('/api/shop/catalog', {
      withCredentials: true,
    });
  }

  getProduct(slug: string): Observable<ShopProductDto> {
    return this.http.get<ShopProductDto>(`/api/shop/products/${encodeURIComponent(String(slug))}`, {
      withCredentials: true,
    });
  }

  listOrders(): Observable<ShopOrderDto[]> {
    return this.http.get<ShopOrderDto[]>('/api/shop/orders', {
      withCredentials: true,
    });
  }

  createOrder(productSlug: string, planCode: string, quantity = 1): Observable<ShopOrderDto> {
    return this.http.post<ShopOrderDto>(
      '/api/shop/orders',
      { productSlug, planCode, quantity },
      { withCredentials: true }
    );
  }

  createOrdersBatch(items: ShopCreateOrderItemRequestDto[]): Observable<ShopOrderDto[]> {
    return this.http.post<ShopOrderDto[]>(
      '/api/shop/orders/batch',
      { items },
      { withCredentials: true }
    );
  }

  simulatePayment(publicId: string, outcome: ShopSimulationOutcome): Observable<ShopOrderDto> {
    return this.http.post<ShopOrderDto>(
      `/api/shop/orders/${encodeURIComponent(String(publicId))}/simulate-payment`,
      { outcome },
      { withCredentials: true }
    );
  }
}
