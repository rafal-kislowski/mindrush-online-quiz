import { Injectable } from '@angular/core';
import { BehaviorSubject, map } from 'rxjs';

export interface ShopCartItem {
  productCode: string;
  productSlug: string;
  productTitle: string;
  planCode: string;
  planLabel: string;
  currency: string;
  grossAmountMinor: number;
  quantity: number;
  addedAt: string;
}

export interface AddShopCartItemInput {
  productCode: string;
  productSlug: string;
  productTitle: string;
  planCode: string;
  planLabel: string;
  currency: string;
  grossAmountMinor: number;
}

interface PersistedShopCartState {
  items: ShopCartItem[];
}

@Injectable({ providedIn: 'root' })
export class ShopCartService {
  private static readonly STORAGE_KEY = 'mindrush.shop.cart.v1';

  private readonly itemsSubject = new BehaviorSubject<ShopCartItem[]>(this.loadItems());
  readonly items$ = this.itemsSubject.asObservable();
  readonly itemCount$ = this.items$.pipe(
    map((items) => items.reduce((sum, item) => sum + Math.max(0, item.quantity), 0))
  );

  get snapshot(): ShopCartItem[] {
    return this.itemsSubject.value;
  }

  addItem(input: AddShopCartItemInput): void {
    const normalizedSlug = String(input.productSlug ?? '').trim();
    const normalizedPlanCode = String(input.planCode ?? '').trim().toUpperCase();
    if (!normalizedSlug || !normalizedPlanCode) return;

    const current = [...this.itemsSubject.value];
    const index = current.findIndex(
      (item) => item.productSlug === normalizedSlug && item.planCode === normalizedPlanCode
    );
    const now = new Date().toISOString();

    if (index >= 0) {
      const existing = current[index];
      current[index] = {
        ...existing,
        quantity: existing.quantity + 1,
      };
    } else {
      current.unshift({
        productCode: String(input.productCode ?? '').trim().toUpperCase(),
        productSlug: normalizedSlug,
        productTitle: String(input.productTitle ?? '').trim() || 'Product',
        planCode: normalizedPlanCode,
        planLabel: String(input.planLabel ?? '').trim() || normalizedPlanCode,
        currency: String(input.currency ?? '').trim().toUpperCase() || 'PLN',
        grossAmountMinor: Number(input.grossAmountMinor) || 0,
        quantity: 1,
        addedAt: now,
      });
    }

    this.setItems(current);
  }

  decrementItem(productSlug: string, planCode: string): void {
    const normalizedSlug = String(productSlug ?? '').trim();
    const normalizedPlanCode = String(planCode ?? '').trim().toUpperCase();
    if (!normalizedSlug || !normalizedPlanCode) return;

    const current = [...this.itemsSubject.value];
    const index = current.findIndex(
      (item) => item.productSlug === normalizedSlug && item.planCode === normalizedPlanCode
    );
    if (index < 0) return;

    const existing = current[index];
    const nextQuantity = Math.max(0, existing.quantity - 1);
    if (nextQuantity <= 0) {
      current.splice(index, 1);
    } else {
      current[index] = {
        ...existing,
        quantity: nextQuantity,
      };
    }
    this.setItems(current);
  }

  removeItem(productSlug: string, planCode: string): void {
    const normalizedSlug = String(productSlug ?? '').trim();
    const normalizedPlanCode = String(planCode ?? '').trim().toUpperCase();
    if (!normalizedSlug || !normalizedPlanCode) return;
    const next = this.itemsSubject.value.filter(
      (item) => !(item.productSlug === normalizedSlug && item.planCode === normalizedPlanCode)
    );
    this.setItems(next);
  }

  hasItem(productSlug: string, planCode: string): boolean {
    const normalizedSlug = String(productSlug ?? '').trim();
    const normalizedPlanCode = String(planCode ?? '').trim().toUpperCase();
    if (!normalizedSlug || !normalizedPlanCode) return false;
    return this.itemsSubject.value.some(
      (item) => item.productSlug === normalizedSlug && item.planCode === normalizedPlanCode
    );
  }

  clear(): void {
    this.setItems([]);
  }

  private setItems(items: ShopCartItem[]): void {
    const normalized = this.normalizeItems(items);
    this.itemsSubject.next(normalized);
    this.persistItems(normalized);
  }

  private loadItems(): ShopCartItem[] {
    try {
      const raw = window.localStorage.getItem(ShopCartService.STORAGE_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw) as Partial<PersistedShopCartState>;
      return this.normalizeItems(parsed.items ?? []);
    } catch {
      return [];
    }
  }

  private persistItems(items: ShopCartItem[]): void {
    try {
      const payload: PersistedShopCartState = { items };
      window.localStorage.setItem(ShopCartService.STORAGE_KEY, JSON.stringify(payload));
    } catch {
      // noop
    }
  }

  private normalizeItems(items: ShopCartItem[]): ShopCartItem[] {
    const normalized = (items ?? [])
      .map((item) => {
        const productSlug = String(item?.productSlug ?? '').trim();
        const planCode = String(item?.planCode ?? '').trim().toUpperCase();
        const quantityRaw = Number(item?.quantity);
        const quantity = Number.isFinite(quantityRaw) ? Math.max(0, Math.floor(quantityRaw)) : 0;
        if (!productSlug || !planCode || quantity <= 0) return null;
        return {
          productCode: String(item?.productCode ?? '').trim().toUpperCase(),
          productSlug,
          productTitle: String(item?.productTitle ?? '').trim() || 'Product',
          planCode,
          planLabel: String(item?.planLabel ?? '').trim() || planCode,
          currency: String(item?.currency ?? '').trim().toUpperCase() || 'PLN',
          grossAmountMinor: Number(item?.grossAmountMinor) || 0,
          quantity,
          addedAt: String(item?.addedAt ?? '').trim() || new Date().toISOString(),
        } satisfies ShopCartItem;
      })
      .filter((item): item is ShopCartItem => !!item);

    // Defensive normalization for any legacy/duplicated localStorage payloads.
    const dedupedByKey = new Map<string, ShopCartItem>();
    for (const item of normalized) {
      const key = `${item.productSlug}::${item.planCode}`;
      const existing = dedupedByKey.get(key);
      if (!existing) {
        dedupedByKey.set(key, item);
        continue;
      }
      dedupedByKey.set(key, {
        ...existing,
        quantity: existing.quantity + item.quantity,
      });
    }

    return [...dedupedByKey.values()];
  }
}
