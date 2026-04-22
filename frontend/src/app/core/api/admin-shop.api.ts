import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type AdminShopProductStatus = 'DRAFT' | 'ACTIVE' | 'TRASHED';
export type AdminShopPricingMode = 'SUBSCRIPTION' | 'ONE_TIME';
export type AdminShopPlanEffectType = 'DURATION_DAYS' | 'RESOURCE_GRANT';

export interface AdminShopCurrencyOptionDto {
  code: string;
  label: string;
  type: 'FIAT' | 'GAME';
  fractionDigits: number;
}

export interface AdminShopConfigDto {
  pricingModes: AdminShopPricingMode[];
  pricingCurrencies: AdminShopCurrencyOptionDto[];
  pricingEffects: AdminShopEffectOptionDto[];
}

export interface AdminShopEffectOptionDto {
  type: AdminShopPlanEffectType;
  code: string;
  label: string;
  description: string;
}

export interface AdminShopPlanDto {
  id: number | null;
  code: string;
  label: string;
  durationDays: number;
  currency: string;
  grossAmountMinor: number;
  grantValue: number | null;
  grantUnit: string | null;
  teaser: string | null;
  sortOrder: number;
  effects: AdminShopPlanEffectDto[];
}

export interface AdminShopPlanEffectDto {
  id: number | null;
  type: AdminShopPlanEffectType;
  code: string | null;
  value: number;
  sortOrder: number;
}

export interface AdminShopTrustHighlightDto {
  id: number | null;
  icon: string | null;
  title: string;
  detail: string;
  sortOrder: number;
}

export interface AdminShopAdvantageBulletDto {
  id: number | null;
  value: string;
  sortOrder: number;
}

export interface AdminShopAdvantageDto {
  id: number | null;
  icon: string | null;
  title: string;
  bullets: AdminShopAdvantageBulletDto[];
  sortOrder: number;
}

export interface AdminShopProductListItemDto {
  id: number;
  code: string;
  slug: string;
  status: AdminShopProductStatus;
  title: string;
  badgeLabel: string | null;
  heroImageUrl: string | null;
  checkoutEnabled: boolean;
  planCount: number;
  advantageCount: number;
  lowestPriceMinor: number;
  lowestPriceCurrency: string | null;
  updatedAt: string | null;
}

export interface AdminShopProductDto {
  id: number;
  code: string;
  slug: string;
  status: AdminShopProductStatus;
  pricingMode: AdminShopPricingMode;
  title: string;
  subtitle: string | null;
  description: string | null;
  badgeLabel: string | null;
  heroImageUrl: string | null;
  checkoutEnabled: boolean;
  sortOrder: number;
  createdAt: string | null;
  updatedAt: string | null;
  trustHighlights: AdminShopTrustHighlightDto[];
  advantages: AdminShopAdvantageDto[];
  pricingPlans: AdminShopPlanDto[];
}

export interface AdminShopProductInputDto {
  code: string;
  slug: string;
  status: AdminShopProductStatus;
  pricingMode: AdminShopPricingMode;
  title: string;
  subtitle?: string | null;
  description?: string | null;
  badgeLabel?: string | null;
  heroImageUrl?: string | null;
  checkoutEnabled?: boolean | null;
  sortOrder?: number | null;
  trustHighlights: Array<{
    icon?: string | null;
    title: string;
    detail: string;
  }>;
  advantages: Array<{
    icon?: string | null;
    title: string;
    bullets: string[];
  }>;
  pricingPlans: Array<{
    code: string;
    label: string;
    durationDays: number;
    currency: string;
    grossAmountMinor: number;
    grantValue?: number | null;
    grantUnit?: string | null;
    teaser?: string | null;
    effects?: Array<{
      type: AdminShopPlanEffectType;
      code?: string | null;
      value: number;
    }>;
  }>;
}

@Injectable({ providedIn: 'root' })
export class AdminShopApi {
  constructor(private readonly http: HttpClient) {}

  uploadImage(file: File): Observable<{ url: string }> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ url: string }>('/api/admin/media', form, {
      withCredentials: true,
    });
  }

  listProducts(): Observable<AdminShopProductListItemDto[]> {
    return this.http.get<AdminShopProductListItemDto[]>('/api/admin/shop/products', {
      withCredentials: true,
    });
  }

  getConfig(): Observable<AdminShopConfigDto> {
    return this.http.get<AdminShopConfigDto>('/api/admin/shop/products/config', {
      withCredentials: true,
    });
  }

  getProduct(id: number): Observable<AdminShopProductDto> {
    return this.http.get<AdminShopProductDto>(`/api/admin/shop/products/${encodeURIComponent(String(id))}`, {
      withCredentials: true,
    });
  }

  createProduct(input: AdminShopProductInputDto): Observable<AdminShopProductDto> {
    return this.http.post<AdminShopProductDto>('/api/admin/shop/products', input, {
      withCredentials: true,
    });
  }

  updateProduct(id: number, input: AdminShopProductInputDto): Observable<AdminShopProductDto> {
    return this.http.put<AdminShopProductDto>(`/api/admin/shop/products/${encodeURIComponent(String(id))}`, input, {
      withCredentials: true,
    });
  }

  setStatus(id: number, status: AdminShopProductStatus): Observable<AdminShopProductDto> {
    return this.http.put<AdminShopProductDto>(
      `/api/admin/shop/products/${encodeURIComponent(String(id))}/status`,
      { status },
      { withCredentials: true }
    );
  }
}
