import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, catchError, firstValueFrom, of } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import {
  ShopApi,
  ShopCatalogResponseDto,
  ShopCreateOrderItemRequestDto,
  ShopOrderDto,
  ShopProductDto,
} from '../../core/api/shop.api';
import { AuthService } from '../../core/auth/auth.service';
import { ShopCartItem, ShopCartService } from '../../core/shop/shop-cart.service';
import { AuthUserDto } from '../../core/models/auth.models';
import { ToastService } from '../../core/ui/toast.service';

type ShopSort = 'featured' | 'priceAsc' | 'priceDesc';
type ShopCurrencyFilter = 'fiat' | 'coins';
type ShopMenu = 'sort' | 'pageSize' | null;
type ShopView = 'catalog' | 'detail' | 'cart';

interface ShopFaqItem {
  id: string;
  question: string;
  answer: string;
}

@Component({
  selector: 'app-shop',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './shop.component.html',
  styleUrl: './shop.component.scss',
})
export class ShopComponent implements OnInit, OnDestroy {
  private static readonly VIEW_ORDER: Record<ShopView, number> = {
    catalog: 0,
    detail: 1,
    cart: 2,
  };
  private static readonly CART_SLUG = 'cart';

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly shopApi = inject(ShopApi);
  private readonly shopCart = inject(ShopCartService);
  private readonly toast = inject(ToastService);
  private readonly subscriptions = new Subscription();
  private readonly currencyFormatterCache = new Map<string, Intl.NumberFormat>();

  catalog: ShopProductDto[] = [];
  product: ShopProductDto | null = null;
  orders: ShopOrderDto[] = [];
  latestOrder: ShopOrderDto | null = null;
  authUser: AuthUserDto | null = null;
  productSlug: string | null = null;
  currentView: ShopView = 'catalog';
  selectedPlanCode = '';
  catalogLoading = false;
  productLoading = false;
  ordersLoading = false;
  cartActionSubmitting = false;
  coinsPurchaseSubmitting = false;
  private _error: string | null = null;
  openFaqId: string | null = 'faq-benefits';
  openMenu: ShopMenu = null;
  searchTerm = '';
  currencyFilter: ShopCurrencyFilter = 'fiat';
  sort: ShopSort = 'featured';
  pageSize = 8;
  pageIndex = 0;
  viewTransition: 'forward' | 'backward' = 'forward';
  private viewTransitionFlip = false;
  readonly pageSizeOptions: number[] = [8, 12, 24];
  readonly currencyFilterOptions: ReadonlyArray<{ value: ShopCurrencyFilter; label: string; icon: string }> = [
    { value: 'fiat', label: 'Real money', icon: 'fa-credit-card' },
    { value: 'coins', label: 'Game coins', icon: 'fa-coins' },
  ];

  readonly faqItems: ShopFaqItem[] = [
    {
      id: 'faq-benefits',
      question: 'What changes immediately after buying premium?',
      answer: 'Premium raises creator limits to 60 owned quizzes, 20 published quizzes, 10 pending submissions, 100 questions per quiz, 100 question images per quiz, 300-second timer and 100 questions per game, and enables premium badge + highlighted nickname styling.',
    },
    {
      id: 'faq-expiration',
      question: 'What happens when premium expires?',
      answer: 'Nothing is deleted. Existing quizzes remain available, but premium-only limits stop applying, so you can keep managing content while new actions that increase state beyond the standard tier are blocked.',
    },
    {
      id: 'faq-extension',
      question: 'Does buying again overwrite my current premium end date?',
      answer: 'No. Premium time stacks. A new successful order extends the current premium end date instead of replacing it.',
    },
    {
      id: 'faq-payments',
      question: 'Is this already connected to a live payment provider?',
      answer: 'This release uses a simulated payment flow, but the backend order lifecycle is structured to support a future PayU sandbox integration without rewriting the shop domain.',
    },
    {
      id: 'faq-security',
      question: 'How is the checkout secured?',
      answer: 'Plan pricing is resolved on the backend, orders are tied to the authenticated account, payment state changes are validated server-side and premium fulfillment is idempotent.',
    },
  ];

  ngOnInit(): void {
    this.subscriptions.add(
      this.route.paramMap.subscribe((params) => {
        const previousView = this.currentView;
        const nextSlugRaw = params.get('slug');
        const normalizedSlug = String(nextSlugRaw ?? '').trim().toLowerCase();
        const nextView: ShopView = !nextSlugRaw
          ? 'catalog'
          : normalizedSlug === ShopComponent.CART_SLUG
            ? 'cart'
            : 'detail';
        this.viewTransition =
          ShopComponent.VIEW_ORDER[nextView] < ShopComponent.VIEW_ORDER[previousView]
            ? 'backward'
            : 'forward';
        this.viewTransitionFlip = !this.viewTransitionFlip;
        this.currentView = nextView;
        this.productSlug = nextView === 'detail' ? nextSlugRaw : null;
        this.error = null;
        this.openMenu = null;
        if (nextView === 'detail' && this.productSlug) {
          this.loadProduct(this.productSlug);
          return;
        }
        this.product = null;
        this.productLoading = false;
        this.selectedPlanCode = '';
        if (nextView === 'catalog') {
          this.loadCatalog();
          return;
        }
        if (nextView === 'cart') {
          this.ensureCatalogLookup();
        }
        this.latestOrder = this.orders[0] ?? null;
      })
    );

    this.subscriptions.add(
      this.auth.user$.subscribe((user) => {
        this.authUser = user;
        if (!user) {
          this.orders = [];
          this.latestOrder = null;
          this.ordersLoading = false;
          return;
        }
        this.loadOrders();
      })
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  get error(): string | null {
    return this._error;
  }

  set error(value: string | null) {
    this._error = value;
    if (!value) return;
    this.toast.error(value, {
      title: 'Shop',
      dedupeKey: `shop:error:${value}`,
    });
  }

  get viewTitle(): string {
    if (this.isCartView) return 'Cart';
    if (this.isDetailView) return this.product?.title || 'Product details';
    return 'Shop';
  }

  get isCatalogView(): boolean {
    return this.currentView === 'catalog';
  }

  get isDetailView(): boolean {
    return this.currentView === 'detail';
  }

  get isCartView(): boolean {
    return this.currentView === 'cart';
  }

  get sortLabel(): string {
    if (this.sort === 'priceAsc') return 'Price low-high';
    if (this.sort === 'priceDesc') return 'Price high-low';
    return 'Featured';
  }

  get pageSizeLabel(): string {
    return `Show ${this.pageSize}`;
  }

  get viewTransitionClass(): string {
    if (this.viewTransition === 'backward') {
      return this.viewTransitionFlip ? 'view-screen--enter-back-a' : 'view-screen--enter-back-b';
    }
    return this.viewTransitionFlip ? 'view-screen--enter-forward-a' : 'view-screen--enter-forward-b';
  }

  get premiumActive(): boolean {
    const user = this.authUser;
    if (!user) return false;
    if (!user.roles?.includes('PREMIUM')) return false;
    const expiresAt = this.timestamp(user.premiumExpiresAt);
    return expiresAt > Date.now();
  }

  get premiumEndsLabel(): string | null {
    const user = this.authUser;
    if (!user?.premiumExpiresAt) return null;
    return this.formatDateTime(user.premiumExpiresAt);
  }

  get premiumDaysLeft(): number | null {
    const user = this.authUser;
    const expiresAt = this.timestamp(user?.premiumExpiresAt);
    if (expiresAt <= 0) return null;
    return Math.max(0, Math.ceil((expiresAt - Date.now()) / (24 * 60 * 60 * 1000)));
  }

  get filteredCatalog(): ShopProductDto[] {
    const query = this.searchTerm.trim().toLowerCase();
    return this.catalog.filter((product) => {
      if (this.currencyFilter === 'coins' && !this.productHasCoinsPlans(product)) {
        return false;
      }
      if (this.currencyFilter === 'fiat' && !this.productHasFiatPlans(product)) {
        return false;
      }
      if (!query) {
        return true;
      }
      return this.matchesSearch(product, query);
    });
  }

  get sortedCatalog(): ShopProductDto[] {
    const items = [...this.filteredCatalog];
    if (this.sort === 'priceAsc') {
      items.sort((a, b) => this.lowestPriceMinor(a) - this.lowestPriceMinor(b));
      return items;
    }
    if (this.sort === 'priceDesc') {
      items.sort((a, b) => this.lowestPriceMinor(b) - this.lowestPriceMinor(a));
      return items;
    }
    items.sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }));
    return items;
  }

  get pagedCatalog(): ShopProductDto[] {
    const start = this.pageIndex * this.pageSize;
    return this.sortedCatalog.slice(start, start + this.pageSize);
  }

  get catalogTotalPages(): number {
    return Math.max(1, Math.ceil(this.sortedCatalog.length / this.pageSize));
  }

  get catalogFrom(): number {
    if (!this.sortedCatalog.length) return 0;
    return this.pageIndex * this.pageSize + 1;
  }

  get catalogTo(): number {
    if (!this.sortedCatalog.length) return 0;
    return Math.min(this.sortedCatalog.length, this.catalogFrom + this.pageSize - 1);
  }

  get selectedPlan() {
    const product = this.product;
    if (!product) return null;
    return product.pricingPlans.find((plan) => plan.code === this.selectedPlanCode) ?? this.defaultProductPlan(product);
  }

  get cartItemsCount(): number {
    return this.shopCart.snapshot.reduce((sum, item) => sum + item.quantity, 0);
  }

  get cartItems(): ShopCartItem[] {
    return this.shopCart.snapshot;
  }

  get cartTotalsByCurrency(): Array<{ currency: string; amountMinor: number }> {
    const totals = new Map<string, number>();
    for (const item of this.cartItems) {
      const currency = String(item.currency ?? '').trim().toUpperCase() || 'PLN';
      const lineTotal = Math.max(0, Number(item.grossAmountMinor) || 0) * Math.max(0, item.quantity);
      totals.set(currency, (totals.get(currency) ?? 0) + lineTotal);
    }
    return [...totals.entries()]
      .map(([currency, amountMinor]) => ({ currency, amountMinor }))
      .sort((a, b) => a.currency.localeCompare(b.currency));
  }

  get selectedPlanInCart(): boolean {
    const product = this.product;
    const plan = this.selectedPlan;
    if (!product || !plan) return false;
    return this.shopCart.hasItem(product.slug, plan.code);
  }

  get shouldShowPlanSelection(): boolean {
    return (this.product?.pricingPlans?.length ?? 0) > 1;
  }

  get selectedPlanIsCoins(): boolean {
    return this.isCoinsCurrency(this.selectedPlan?.currency);
  }

  get selectedPlanCoinsPrice(): number {
    const plan = this.selectedPlan;
    if (!plan || !this.selectedPlanIsCoins) return 0;
    return Math.max(0, Number(plan.grossAmountMinor) || 0);
  }

  get userCoinsBalance(): number {
    return Math.max(0, Number(this.authUser?.coins) || 0);
  }

  get hasEnoughCoinsForSelectedPlan(): boolean {
    return this.userCoinsBalance >= this.selectedPlanCoinsPrice;
  }

  get missingCoinsForSelectedPlan(): number {
    return Math.max(0, this.selectedPlanCoinsPrice - this.userCoinsBalance);
  }

  get lacksCoinsForSelectedPlan(): boolean {
    return this.selectedPlanIsCoins && !!this.authUser && this.missingCoinsForSelectedPlan > 0;
  }

  checkoutSelectionLabel(plan: ShopProductDto['pricingPlans'][number] | null | undefined): string {
    const label = String(plan?.label ?? '').trim();
    if (label) {
      return label;
    }
    return String(this.product?.title ?? '').trim() || 'Configured option';
  }

  get recentPremiumOrders(): ShopOrderDto[] {
    return this.orders.filter((order) => order.productSlug === 'premium').slice(0, 5);
  }

  trackProduct(_: number, item: ShopProductDto): string {
    return item.slug;
  }

  trackPlan(_: number, item: ShopProductDto['pricingPlans'][number]): string {
    return item.code;
  }

  trackOrder(_: number, item: ShopOrderDto): string {
    return item.publicId;
  }

  trackFaq(_: number, item: ShopFaqItem): string {
    return item.id;
  }

  trackCartItem(_: number, item: ShopCartItem): string {
    return `${item.productSlug}:${item.planCode}`;
  }

  productTrustHighlights(product: ShopProductDto | null | undefined): ReadonlyArray<{ icon: string; title: string; detail: string }> {
    return (product?.trustHighlights ?? []).map((item, index) => ({
      icon: this.resolveTrustIcon(item.icon, index),
      title: String(item.title ?? '').trim(),
      detail: String(item.detail ?? '').trim(),
    }));
  }

  productHeroImage(slug: string | null | undefined, heroImageUrl: string | null | undefined): string {
    return String(heroImageUrl ?? '').trim();
  }

  toggleMenu(menu: Exclude<ShopMenu, null>, event: Event): void {
    event.stopPropagation();
    this.openMenu = this.openMenu === menu ? null : menu;
  }

  closeMenus(): void {
    this.openMenu = null;
  }

  onSearchTermChange(value: string): void {
    const next = (value ?? '').trimStart();
    if (this.searchTerm === next) return;
    this.searchTerm = next;
    this.pageIndex = 0;
  }

  setCurrencyFilter(filter: ShopCurrencyFilter): void {
    if (this.currencyFilter === filter) return;
    this.currencyFilter = filter;
    this.pageIndex = 0;
  }

  setSort(sort: ShopSort, event?: Event): void {
    event?.stopPropagation();
    this.sort = sort;
    this.pageIndex = 0;
    this.closeMenus();
  }

  setPageSize(size: number, event?: Event): void {
    event?.stopPropagation();
    const safe = this.pageSizeOptions.includes(size) ? size : this.pageSizeOptions[0];
    this.pageSize = safe;
    this.pageIndex = 0;
    this.closeMenus();
  }

  prevPage(): void {
    this.pageIndex = Math.max(0, this.pageIndex - 1);
  }

  nextPage(): void {
    this.pageIndex = Math.min(this.catalogTotalPages - 1, this.pageIndex + 1);
  }

  openProduct(product: ShopProductDto): void {
    void this.router.navigate(['/shop', product.slug]);
  }

  goBack(): void {
    if (this.isDetailView || this.isCartView) {
      void this.router.navigate(['/shop']);
      return;
    }
    void this.router.navigate(['/']);
  }

  selectPlan(code: string): void {
    this.selectedPlanCode = String(code ?? '').trim().toUpperCase();
  }

  toggleFaq(id: string): void {
    this.openFaqId = this.openFaqId === id ? null : id;
  }

  addToCart(): void {
    const product = this.product;
    const plan = this.selectedPlan;
    if (!product || !plan) return;
    if (this.isCoinsCurrency(plan.currency)) {
      void this.buySelectedPlanWithCoins();
      return;
    }
    this.shopCart.addItem({
      productCode: product.code,
      productSlug: product.slug,
      productTitle: product.title,
      planCode: plan.code,
      planLabel: plan.label,
      currency: plan.currency,
      grossAmountMinor: plan.grossAmountMinor,
    });
    this.toast.success('Added to cart.', {
      title: 'Cart',
      dedupeKey: `shop:cart:add:${product.slug}:${plan.code}`,
    });
  }

  async buySelectedPlanWithCoins(): Promise<void> {
    if (this.coinsPurchaseSubmitting) return;
    const product = this.product;
    const plan = this.selectedPlan;
    if (!product || !plan || !this.isCoinsCurrency(plan.currency)) return;

    const authUser = this.auth.snapshot;
    if (!authUser) {
      this.toast.warning('Log in to buy this product with coins.', {
        title: 'Shop',
        dedupeKey: 'shop:coins:login-required',
      });
      void this.router.navigate(['/login']);
      return;
    }

    const priceCoins = Math.max(0, Number(plan.grossAmountMinor) || 0);
    if ((Number(authUser.coins) || 0) < priceCoins) {
      this.toast.error('Not enough coins for this purchase.', {
        title: 'Shop',
        dedupeKey: `shop:coins:not-enough:${product.slug}:${plan.code}`,
      });
      return;
    }

    const confirmationLabel = this.checkoutSelectionLabel(plan);
    const confirmationAmount = this.formatMoneyAmount(priceCoins, 'COINS');
    const confirmed = window.confirm(`Buy "${confirmationLabel}" for ${confirmationAmount} coins?`);
    if (!confirmed) return;

    this.coinsPurchaseSubmitting = true;
    try {
      const createdOrder = await firstValueFrom(this.shopApi.createOrder(product.slug, plan.code, 1));
      this.upsertOrder(createdOrder);
      const paidOrder = await firstValueFrom(this.shopApi.simulatePayment(createdOrder.publicId, 'SUCCESS'));
      this.upsertOrder(paidOrder);
      this.latestOrder = paidOrder;
      this.auth.reloadMe().subscribe({ error: () => {} });
      this.toast.success('Purchase completed. Product was activated.', {
        title: 'Shop',
        dedupeKey: `shop:coins:success:${product.slug}:${plan.code}`,
      });
    } catch (error: unknown) {
      this.toast.error(apiErrorMessage(error, 'Failed to complete coins purchase'), {
        title: 'Shop',
        dedupeKey: `shop:coins:error:${product.slug}:${plan.code}`,
      });
    } finally {
      this.coinsPurchaseSubmitting = false;
    }
  }

  incrementCartItem(item: ShopCartItem): void {
    this.shopCart.addItem({
      productCode: item.productCode,
      productSlug: item.productSlug,
      productTitle: item.productTitle,
      planCode: item.planCode,
      planLabel: item.planLabel,
      currency: item.currency,
      grossAmountMinor: item.grossAmountMinor,
    });
  }

  decrementCartItem(item: ShopCartItem): void {
    this.shopCart.decrementItem(item.productSlug, item.planCode);
  }

  removeCartItem(item: ShopCartItem): void {
    this.shopCart.removeItem(item.productSlug, item.planCode);
  }

  async processCartPayment(status: 'PENDING' | 'SUCCESS' | 'FAILURE'): Promise<void> {
    if (this.cartActionSubmitting) return;
    const items = this.shopCart.snapshot;
    if (!items.length) {
      this.toast.warning('Cart is empty.', {
        title: 'Cart',
        dedupeKey: 'shop:cart:empty',
      });
      return;
    }

    if (!this.auth.snapshot) {
      this.toast.warning('Log in to process cart payment.', {
        title: 'Cart',
        dedupeKey: 'shop:cart:login-required',
      });
      void this.router.navigate(['/login']);
      return;
    }

    this.cartActionSubmitting = true;
    const totalUnits = items.reduce((sum, item) => sum + item.quantity, 0);
    let processedOrders = 0;
    let paidOrders = 0;
    let failedOrders = 0;
    let pendingOrders = 0;

    try {
      const orderItems: ShopCreateOrderItemRequestDto[] = items.map((item) => ({
        productSlug: item.productSlug,
        planCode: item.planCode,
        quantity: item.quantity,
      }));
      const createdOrders = await firstValueFrom(this.shopApi.createOrdersBatch(orderItems));
      processedOrders = createdOrders.length;

      for (const createdOrder of createdOrders) {
        this.upsertOrder(createdOrder);
        this.latestOrder = createdOrder;

        if (status === 'PENDING') {
          pendingOrders += 1;
          continue;
        }

        const simulated = await firstValueFrom(
          this.shopApi.simulatePayment(createdOrder.publicId, status === 'SUCCESS' ? 'SUCCESS' : 'FAILURE')
        );
        this.upsertOrder(simulated);
        this.latestOrder = simulated;
        if (status === 'SUCCESS') {
          paidOrders += 1;
        } else {
          failedOrders += 1;
        }
      }

      if (status === 'SUCCESS') {
        this.shopCart.clear();
        this.auth.reloadMe().subscribe({ error: () => {} });
      } else if (status === 'PENDING') {
        this.shopCart.clear();
      }

      const summary = status === 'SUCCESS'
        ? `Payment passed for ${paidOrders} product${paidOrders === 1 ? '' : 's'} (${totalUnits} unit${totalUnits === 1 ? '' : 's'} consolidated). Purchased services were activated.`
        : status === 'FAILURE'
          ? `Payment failed for ${failedOrders} product${failedOrders === 1 ? '' : 's'} (${totalUnits} unit${totalUnits === 1 ? '' : 's'} consolidated).`
          : `Payment pending for ${pendingOrders} product${pendingOrders === 1 ? '' : 's'} (${totalUnits} unit${totalUnits === 1 ? '' : 's'} consolidated).`;
      this.toast.success(summary, {
        title: 'Cart',
        dedupeKey: `shop:cart:payment:${status}:${processedOrders}`,
      });
    } catch (error: unknown) {
      this.toast.error(apiErrorMessage(error, 'Failed to process cart payment'), {
        title: 'Cart',
        dedupeKey: 'shop:cart:payment:error',
      });
    } finally {
      this.cartActionSubmitting = false;
    }
  }

  isFaqOpen(id: string): boolean {
    return this.openFaqId === id;
  }

  formatMoney(amountMinor: number, currency: string): string {
    const code = String(currency ?? '').trim().toUpperCase() || 'PLN';
    const fractionDigits = code === 'COINS' ? 0 : 2;
    const formatterKey = `${code}:${fractionDigits}`;
    let formatter = this.currencyFormatterCache.get(formatterKey);
    try {
      if (!formatter) {
        formatter = new Intl.NumberFormat('pl-PL', {
          style: 'currency',
          currency: code,
          minimumFractionDigits: fractionDigits,
          maximumFractionDigits: fractionDigits,
        });
        this.currencyFormatterCache.set(formatterKey, formatter);
      }
      return formatter.format((Number(amountMinor) || 0) / Math.pow(10, fractionDigits));
    } catch {
      const base = (Number(amountMinor) || 0) / Math.pow(10, fractionDigits);
      return `${new Intl.NumberFormat('pl-PL', {
        minimumFractionDigits: fractionDigits,
        maximumFractionDigits: fractionDigits,
      }).format(base)} ${code}`;
    }
  }

  isCoinsCurrency(currency: string | null | undefined): boolean {
    return String(currency ?? '').trim().toUpperCase() === 'COINS';
  }

  formatMoneyAmount(amountMinor: number, currency: string): string {
    if (this.isCoinsCurrency(currency)) {
      return new Intl.NumberFormat('pl-PL', {
        minimumFractionDigits: 0,
        maximumFractionDigits: 0,
      }).format(Number(amountMinor) || 0);
    }
    return this.formatMoney(amountMinor, currency);
  }

  cartLineTotalAmountMinor(item: ShopCartItem): number {
    const quantity = Math.max(0, Number(item.quantity) || 0);
    return Math.max(0, Number(item.grossAmountMinor) || 0) * quantity;
  }

  cartProductImage(item: ShopCartItem): string {
    const fromCatalog = this.catalog.find((product) => product.slug === item.productSlug)?.heroImageUrl;
    return this.productHeroImage(item.productSlug, fromCatalog);
  }

  planLabelHasCoinsWord(label: string | null | undefined): boolean {
    return /\bcoins?\b/i.test(String(label ?? ''));
  }

  planLabelWithoutCoinsWord(label: string | null | undefined): string {
    const raw = String(label ?? '').trim();
    if (!raw) return '';
    const normalized = raw.replace(/\bcoins?\b/gi, '').replace(/\s{2,}/g, ' ').trim();
    return normalized;
  }

  productPricingLabel(product: ShopProductDto): string {
    return product.pricingMode === 'ONE_TIME' ? 'Select purchase option' : 'Select duration';
  }

  formatDateTime(value: string | null | undefined): string | null {
    const timestamp = this.timestamp(value);
    if (timestamp <= 0) return null;
    const date = new Date(timestamp);
    const day = String(date.getDate()).padStart(2, '0');
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const year = date.getFullYear();
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    return `${day}.${month}.${year}, ${hours}:${minutes}`;
  }

  relativeOrderState(order: ShopOrderDto): string {
    if (order.paymentStatus === 'PAID' && order.premiumExpiresAt) {
      const expires = this.formatDateTime(order.premiumExpiresAt);
      return expires ? `Premium active until ${expires}` : 'Premium active';
    }
    if (order.paymentStatus === 'FAILED') return order.failureReason || 'Payment failed';
    if (order.paymentStatus === 'CANCELLED') return order.failureReason || 'Payment cancelled';
    return 'Awaiting payment action';
  }

  lowestPlanLabel(product: ShopProductDto): string {
    const plan = this.lowestPlan(product);
    if (!plan) return 'No options configured';
    return `${this.formatMoney(plan.grossAmountMinor, plan.currency)} / ${plan.label}`;
  }

  orderStatusClass(order: ShopOrderDto): string {
    const status = String(order.paymentStatus ?? '').trim().toUpperCase();
    if (status === 'PAID') return 'is-paid';
    if (status === 'FAILED') return 'is-failed';
    if (status === 'CANCELLED') return 'is-cancelled';
    return 'is-pending';
  }

  private loadCatalog(): void {
    this.catalogLoading = true;
    this.productLoading = false;
    this.product = null;
    this.error = null;
    this.shopApi.getCatalog()
      .pipe(
        catchError((error: unknown) => {
          this.catalogLoading = false;
          this.error = apiErrorMessage(error, 'Failed to load shop catalog');
          return of<ShopCatalogResponseDto>({ items: [] });
        })
      )
      .subscribe((response) => {
        this.catalogLoading = false;
        this.catalog = response.items ?? [];
        this.pageIndex = 0;
      });
  }

  private ensureCatalogLookup(): void {
    if (this.catalog.length || this.catalogLoading) return;
    this.catalogLoading = true;
    this.shopApi.getCatalog()
      .pipe(
        catchError(() => of<ShopCatalogResponseDto>({ items: [] }))
      )
      .subscribe((response) => {
        this.catalogLoading = false;
        this.catalog = response.items ?? [];
      });
  }

  private loadProduct(slug: string): void {
    this.productLoading = true;
    this.catalogLoading = false;
    this.product = null;
    this.error = null;
    this.shopApi.getProduct(slug)
      .pipe(
        catchError((error: unknown) => {
          this.productLoading = false;
          this.error = apiErrorMessage(error, 'Failed to load product');
          return of<ShopProductDto | null>(null);
        })
      )
      .subscribe((product) => {
        this.productLoading = false;
        this.product = product;
        if (!product) return;
        if (!product.pricingPlans.some((plan) => plan.code === this.selectedPlanCode)) {
          this.selectedPlanCode = this.defaultProductPlan(product)?.code ?? '';
        }
        this.latestOrder = this.orders.find((order) => order.productSlug === product.slug) ?? null;
      });
  }

  private loadOrders(): void {
    if (!this.authUser) return;
    this.ordersLoading = true;
    this.shopApi.listOrders()
      .pipe(
        catchError((error: unknown) => {
          this.ordersLoading = false;
          this.toast.error(apiErrorMessage(error, 'Failed to load order history'), {
            title: 'Shop',
            dedupeKey: 'shop:orders:error',
          });
          return of([] as ShopOrderDto[]);
        })
      )
      .subscribe((orders) => {
        this.ordersLoading = false;
        this.orders = orders ?? [];
        if (this.isDetailView && this.product?.slug) {
          this.latestOrder = this.orders.find((order) => order.productSlug === this.product?.slug) ?? null;
          return;
        }
        this.latestOrder = this.orders[0] ?? null;
      });
  }

  private upsertOrder(order: ShopOrderDto): void {
    const current = this.orders.filter((item) => item.publicId !== order.publicId);
    this.orders = [order, ...current].sort((a, b) => this.timestamp(b.createdAt) - this.timestamp(a.createdAt));
  }

  private lowestPriceMinor(product: ShopProductDto): number {
    return this.lowestPlan(product)?.grossAmountMinor ?? Number.MAX_SAFE_INTEGER;
  }

  lowestPlan(product: ShopProductDto) {
    const preferredPlans = this.plansForCurrentCurrencyFilter(product);
    if (preferredPlans.length > 0) {
      return [...preferredPlans].sort((a, b) => a.grossAmountMinor - b.grossAmountMinor)[0] ?? null;
    }
    return [...(product.pricingPlans ?? [])].sort((a, b) => a.grossAmountMinor - b.grossAmountMinor)[0] ?? null;
  }

  private matchesSearch(product: ShopProductDto, query: string): boolean {
    const haystacks = [
      product.title,
      product.subtitle,
      product.description,
      product.badgeLabel,
      product.slug,
      ...(product.trustHighlights ?? []).flatMap((item) => [item.title, item.detail]),
      ...(product.advantages ?? []).flatMap((item) => [item.title, ...(item.bullets ?? [])]),
      ...(product.pricingPlans ?? []).map((plan) => `${plan.label} ${plan.teaser}`),
    ];
    return haystacks.some((value) => String(value ?? '').toLowerCase().includes(query));
  }

  private productHasCoinsPlans(product: ShopProductDto): boolean {
    return (product.pricingPlans ?? []).some((plan) => this.isCoinsCurrency(plan.currency));
  }

  private productHasFiatPlans(product: ShopProductDto): boolean {
    return (product.pricingPlans ?? []).some((plan) => !this.isCoinsCurrency(plan.currency));
  }

  private plansForCurrentCurrencyFilter(product: ShopProductDto): ShopProductDto['pricingPlans'] {
    const plans = product.pricingPlans ?? [];
    if (this.currencyFilter === 'coins') {
      return plans.filter((plan) => this.isCoinsCurrency(plan.currency));
    }
    return plans.filter((plan) => !this.isCoinsCurrency(plan.currency));
  }

  private defaultProductPlan(product: ShopProductDto): ShopProductDto['pricingPlans'][number] | null {
    const preferred = this.plansForCurrentCurrencyFilter(product);
    const source = preferred.length > 0 ? preferred : (product.pricingPlans ?? []);
    return source.find((plan) => plan.code === 'MONTH_1') ?? source[0] ?? null;
  }

  private resolveTrustIcon(icon: string | null | undefined, index: number): string {
    const normalized = String(icon ?? '').trim();
    if (normalized) {
      return normalized;
    }
    if (index === 0) return 'fa-shield-halved';
    if (index === 1) return 'fa-bolt';
    if (index === 2) return 'fa-hourglass-half';
    return 'fa-database';
  }

  private timestamp(value: string | null | undefined): number {
    const timestamp = Date.parse(String(value ?? ''));
    return Number.isFinite(timestamp) ? timestamp : 0;
  }
}
