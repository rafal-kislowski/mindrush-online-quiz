import { CommonModule } from '@angular/common';
import { Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import {
  AdminShopApi,
  AdminShopCurrencyOptionDto,
  AdminShopEffectOptionDto,
  AdminShopPlanEffectType,
  AdminShopProductDto,
  AdminShopProductInputDto,
  AdminShopProductListItemDto,
  AdminShopPricingMode,
  AdminShopProductStatus,
} from '../../core/api/admin-shop.api';
import { ToastService } from '../../core/ui/toast.service';

type AdminShopMenu = 'sort' | 'pageSize' | null;
type AdminShopSort = 'newest' | 'oldest' | 'name_az' | 'price_low' | 'price_high';

const HERO_ALLOWED_IMAGE_MIME_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
const HERO_MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
const DEFAULT_PRICING_EFFECTS: AdminShopEffectOptionDto[] = [
  { type: 'DURATION_DAYS', code: 'PREMIUM_ACCESS', label: 'Premium access', description: 'Extends premium account access' },
  { type: 'DURATION_DAYS', code: 'XP_BOOST', label: 'XP boost', description: 'Enables XP boost for a duration' },
  { type: 'DURATION_DAYS', code: 'RP_BOOST', label: 'Rank points boost', description: 'Enables rank points boost for a duration' },
  { type: 'DURATION_DAYS', code: 'COINS_BOOST', label: 'Coins boost', description: 'Enables coins boost for a duration' },
  { type: 'RESOURCE_GRANT', code: 'COINS', label: 'Coins', description: 'Grants coins immediately' },
  { type: 'RESOURCE_GRANT', code: 'XP', label: 'XP', description: 'Grants XP immediately' },
  { type: 'RESOURCE_GRANT', code: 'RANK_POINTS', label: 'Rank points', description: 'Grants rank points immediately' },
];

@Component({
  selector: 'app-admin-shop',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './admin-shop.component.html',
  styleUrl: './admin-shop.component.scss',
})
export class AdminShopComponent implements OnInit, OnDestroy {
  private static readonly ADVANTAGE_FALLBACK_ICONS = ['fa-layer-group', 'fa-list-check', 'fa-stopwatch', 'fa-certificate', 'fa-hourglass-end'];
  private static readonly TRUST_FALLBACK_ICONS = ['fa-shield-halved', 'fa-bolt', 'fa-hourglass-half', 'fa-database'];
  private readonly currencyFormatterCache = new Map<string, Intl.NumberFormat>();
  private readonly currencyFractionDigitsByCode = new Map<string, number>();
  readonly statusTab = new FormControl<AdminShopProductStatus>('ACTIVE', { nonNullable: true });
  readonly sort = new FormControl<AdminShopSort>('newest', { nonNullable: true });
  readonly search = new FormControl('', { nonNullable: true });
  readonly pageSize = new FormControl(8, { nonNullable: true });
  readonly pageSizeOptions = [8, 12, 24];
  readonly productForm = new FormGroup({
    code: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(64)] }),
    slug: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(120)] }),
    status: new FormControl<AdminShopProductStatus>('DRAFT', { nonNullable: true }),
    pricingMode: new FormControl<AdminShopPricingMode>('SUBSCRIPTION', { nonNullable: true }),
    title: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(120)] }),
    subtitle: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(220)] }),
    description: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(2000)] }),
    badgeLabel: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(80)] }),
    heroImageUrl: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
    checkoutEnabled: new FormControl(false, { nonNullable: true }),
    trustHighlights: new FormArray<FormGroup>([]),
    advantages: new FormArray<FormGroup>([]),
    pricingPlans: new FormArray<FormGroup>([]),
  });

  products: AdminShopProductListItemDto[] = [];
  pricingModes: AdminShopPricingMode[] = ['SUBSCRIPTION', 'ONE_TIME'];
  pricingCurrencies: AdminShopCurrencyOptionDto[] = [
    { code: 'PLN', label: 'Polish zloty', type: 'FIAT', fractionDigits: 2 },
    { code: 'EUR', label: 'Euro', type: 'FIAT', fractionDigits: 2 },
    { code: 'USD', label: 'US dollar', type: 'FIAT', fractionDigits: 2 },
    { code: 'COINS', label: 'Coins', type: 'GAME', fractionDigits: 0 },
  ];
  pricingEffects: AdminShopEffectOptionDto[] = [...DEFAULT_PRICING_EFFECTS];
  readonly optionEffectTypes: AdminShopPlanEffectType[] = ['DURATION_DAYS', 'RESOURCE_GRANT'];
  readonly subscriptionEffectTypes: AdminShopPlanEffectType[] = ['DURATION_DAYS'];
  openMenu: AdminShopMenu = null;
  openInlineSelectKey: string | null = null;
  openProductActionsId: number | null = null;
  productActionsMenuStyle: Record<string, string> | null = null;
  pageIndex = 0;
  currentProductId: number | null = null;
  editorMode: 'list' | 'create' | 'edit' = 'list';
  loadingList = false;
  loadingProduct = false;
  savingProduct = false;
  uploadingHero = false;
  error: string | null = null;
  currentProductCreatedAt: string | null = null;
  currentProductUpdatedAt: string | null = null;
  private lastProductActionsOpenedAtMs = 0;

  private readonly subscriptions = new Subscription();

  constructor(
    private readonly api: AdminShopApi,
    private readonly toast: ToastService,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.hydrateCurrencyFormatMap(this.pricingCurrencies);
    this.loadConfig();
    this.loadProducts();
    this.subscriptions.add(
      this.search.valueChanges.subscribe(() => {
        this.pageIndex = 0;
      })
    );
    this.subscriptions.add(
      this.pageSize.valueChanges.subscribe(() => {
        this.pageIndex = 0;
      })
    );
    this.subscriptions.add(
      this.statusTab.valueChanges.subscribe(() => {
        this.pageIndex = 0;
      })
    );
    this.subscriptions.add(
      this.sort.valueChanges.subscribe(() => {
        this.pageIndex = 0;
      })
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  @HostListener('document:click')
  onDocumentClick(): void {
    this.openMenu = null;
    this.closeInlineSelect();
    this.closeProductActions();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.openMenu = null;
    this.closeInlineSelect();
    this.closeProductActions();
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    this.openMenu = null;
    this.closeInlineSelect();
    this.closeProductActions();
  }

  @HostListener('window:scroll')
  onWindowScroll(): void {
    if (this.openProductActionsId != null && Date.now() - this.lastProductActionsOpenedAtMs < 250) {
      return;
    }
    this.openMenu = null;
    this.closeInlineSelect();
    this.closeProductActions();
  }

  get isEditorOpen(): boolean {
    return this.editorMode !== 'list';
  }

  get statusLabel(): string {
    return this.productForm.controls.status.value === 'ACTIVE'
      ? 'Active'
      : this.productForm.controls.status.value === 'TRASHED'
        ? 'Trash'
        : 'Draft';
  }

  get selectedPlan(): FormGroup | null {
    const plans = this.pricingPlansArray.controls;
    if (!plans.length) return null;
    return plans[0] ?? null;
  }

  get isOneTimePricing(): boolean {
    return this.productForm.controls.pricingMode.value === 'ONE_TIME';
  }

  get currentPricingMode(): AdminShopPricingMode {
    return this.productForm.controls.pricingMode.value;
  }

  get pricingModeHintTitle(): string {
    return this.isOneTimePricing ? 'One-time package mode' : 'Subscription mode';
  }

  get pricingModeHintDescription(): string {
    if (this.isOneTimePricing) {
      return 'Use this for single purchases. Add resource grants, duration effects, or both in one option.';
    }
    return 'Use this for recurring-style access. Only duration effects are available in this mode.';
  }

  get hasPricingValidationErrors(): boolean {
    return this.pricingPlansArray.controls.some((_, index) => this.planModeError(index) !== null);
  }

  pricingModeLabel(mode: AdminShopPricingMode): string {
    return mode === 'ONE_TIME' ? 'One-time' : 'Subscription';
  }

  optionEffectTypeLabel(type: AdminShopPlanEffectType): string {
    return type === 'DURATION_DAYS' ? 'Duration (days)' : 'Resource grant';
  }

  effectCodeOptions(rawType: unknown, currentCode?: unknown): AdminShopEffectOptionDto[] {
    const type = this.normalizeEffectTypeForMode(rawType, this.currentPricingMode);
    const options = this.effectsByType(type);
    const normalizedCurrent = String(currentCode ?? '').trim().toUpperCase();
    if (!normalizedCurrent || options.some((item) => item.code === normalizedCurrent)) {
      return options;
    }
    return [
      {
        type,
        code: normalizedCurrent,
        label: normalizedCurrent,
        description: 'Existing effect code',
      },
      ...options,
    ];
  }

  effectOptionLabel(option: AdminShopEffectOptionDto): string {
    return `${option.code} - ${option.label}`;
  }

  isResourceGrantEffect(rawType: unknown): boolean {
    return String(rawType ?? '').trim().toUpperCase() === 'RESOURCE_GRANT';
  }

  effectTypeOptionsForMode(): AdminShopPlanEffectType[] {
    return this.currentPricingMode === 'ONE_TIME' ? this.optionEffectTypes : this.subscriptionEffectTypes;
  }

  isEffectTypeLocked(): boolean {
    return this.currentPricingMode === 'SUBSCRIPTION';
  }

  effectValueLabel(rawType: unknown): string {
    return this.isResourceGrantEffect(rawType) ? 'Grant value' : 'Duration (days)';
  }

  effectValuePlaceholder(rawType: unknown): string {
    return this.isResourceGrantEffect(rawType) ? '1000' : '30';
  }

  planModeError(planIndex: number): string | null {
    const effectGroups = this.planEffects(planIndex).controls;
    if (!effectGroups.length) {
      return 'Add at least one purchase effect.';
    }

    let hasAnyPositiveEffect = false;
    let hasPositiveDuration = false;
    for (const effectGroup of effectGroups) {
      const value = Number(effectGroup.controls['value']?.value ?? 0) || 0;
      if (value <= 0) continue;
      hasAnyPositiveEffect = true;
      if (this.normalizeEffectType(effectGroup.controls['type']?.value) === 'DURATION_DAYS') {
        hasPositiveDuration = true;
      }
    }

    if (!hasAnyPositiveEffect) {
      return 'Each option must have at least one effect with value greater than 0.';
    }
    if (this.currentPricingMode === 'SUBSCRIPTION' && !hasPositiveDuration) {
      return 'Subscription option must include at least one duration effect with value greater than 0.';
    }
    return null;
  }

  get filteredProducts(): AdminShopProductListItemDto[] {
    const query = this.search.value.trim().toLowerCase();
    return this.products.filter((item) => {
      if (item.status !== this.statusTab.value) return false;
      if (!query) return true;
      return [
        item.title,
        item.code,
        item.slug,
        item.badgeLabel,
      ].some((value) => String(value ?? '').toLowerCase().includes(query));
    });
  }

  get sortedProducts(): AdminShopProductListItemDto[] {
    const items = [...this.filteredProducts];
    switch (this.sort.value) {
      case 'oldest':
        items.sort((a, b) => this.timestamp(a.updatedAt) - this.timestamp(b.updatedAt));
        break;
      case 'name_az':
        items.sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }));
        break;
      case 'price_low':
        items.sort((a, b) => this.sortablePrice(a) - this.sortablePrice(b));
        break;
      case 'price_high':
        items.sort((a, b) => this.sortablePrice(b) - this.sortablePrice(a));
        break;
      default:
        items.sort((a, b) => this.timestamp(b.updatedAt) - this.timestamp(a.updatedAt));
        break;
    }
    return items;
  }

  get pagedProducts(): AdminShopProductListItemDto[] {
    const start = this.pageIndex * this.pageSize.value;
    return this.sortedProducts.slice(start, start + this.pageSize.value);
  }

  get totalPages(): number {
    return Math.max(1, Math.ceil(this.sortedProducts.length / this.pageSize.value));
  }

  get pageFrom(): number {
    if (!this.sortedProducts.length) return 0;
    return this.pageIndex * this.pageSize.value + 1;
  }

  get pageTo(): number {
    if (!this.sortedProducts.length) return 0;
    return Math.min(this.sortedProducts.length, this.pageFrom + this.pageSize.value - 1);
  }

  get sortLabel(): string {
    switch (this.sort.value) {
      case 'oldest':
        return 'Oldest first';
      case 'name_az':
        return 'Name A-Z';
      case 'price_low':
        return 'Price low-high';
      case 'price_high':
        return 'Price high-low';
      default:
        return 'Newest first';
    }
  }

  get pageSizeLabel(): string {
    return `Show ${this.pageSize.value}`;
  }

  get trustHighlightsArray(): FormArray<FormGroup> {
    return this.productForm.controls.trustHighlights;
  }

  get advantagesArray(): FormArray<FormGroup> {
    return this.productForm.controls.advantages;
  }

  get pricingPlansArray(): FormArray<FormGroup> {
    return this.productForm.controls.pricingPlans;
  }

  handleBack(): void {
    if (this.editorMode === 'edit' || this.editorMode === 'create') {
      this.currentProductId = null;
      this.editorMode = 'list';
      this.currentProductCreatedAt = null;
      this.currentProductUpdatedAt = null;
      this.openMenu = null;
      this.closeProductActions();
      this.resetCreateForm();
      return;
    }
    void this.router.navigate(['/']);
  }

  toggleMenu(menu: Exclude<AdminShopMenu, null>, event: Event): void {
    event.stopPropagation();
    this.closeInlineSelect();
    this.closeProductActions();
    this.openMenu = this.openMenu === menu ? null : menu;
  }

  setSort(value: AdminShopSort, event?: Event): void {
    event?.stopPropagation();
    this.sort.setValue(value);
    this.openMenu = null;
    this.closeInlineSelect();
  }

  setPageSize(value: number, event?: Event): void {
    event?.stopPropagation();
    if (!this.pageSizeOptions.includes(value)) return;
    this.pageSize.setValue(value);
    this.openMenu = null;
    this.closeInlineSelect();
  }

  toggleInlineSelect(key: string, event: Event): void {
    event.stopPropagation();
    this.openMenu = null;
    this.closeProductActions();
    this.openInlineSelectKey = this.openInlineSelectKey === key ? null : key;
  }

  isInlineSelectOpen(key: string): boolean {
    return this.openInlineSelectKey === key;
  }

  closeInlineSelect(): void {
    this.openInlineSelectKey = null;
  }

  planCurrencySelectKey(planIndex: number): string {
    return `plan:${planIndex}:currency`;
  }

  planEffectTypeSelectKey(planIndex: number, effectIndex: number): string {
    return `plan:${planIndex}:effect:${effectIndex}:type`;
  }

  planEffectCodeSelectKey(planIndex: number, effectIndex: number): string {
    return `plan:${planIndex}:effect:${effectIndex}:code`;
  }

  planCurrencyLabel(planIndex: number): string {
    const planGroup = this.pricingPlansArray.at(planIndex) as FormGroup;
    const code = String(planGroup.controls['currency']?.value ?? '').trim().toUpperCase();
    const option = this.pricingCurrencies.find((item) => item.code === code);
    return option ? `${option.code} - ${option.label}` : code;
  }

  planEffectTypeLabel(planIndex: number, effectIndex: number): string {
    const effectGroup = this.planEffects(planIndex).at(effectIndex);
    const type = this.normalizeEffectType(effectGroup.controls['type']?.value);
    return this.optionEffectTypeLabel(type);
  }

  planEffectCodeLabel(planIndex: number, effectIndex: number): string {
    const effectGroup = this.planEffects(planIndex).at(effectIndex);
    const type = this.normalizeEffectType(effectGroup.controls['type']?.value);
    const code = String(effectGroup.controls['code']?.value ?? '').trim().toUpperCase();
    if (!code) return '';
    const option = this.effectCodeOptions(type, code).find((item) => item.code === code);
    return option ? this.effectOptionLabel(option) : code;
  }

  setPlanCurrency(planIndex: number, currencyCode: string, event?: Event): void {
    event?.stopPropagation();
    const planGroup = this.pricingPlansArray.at(planIndex) as FormGroup;
    planGroup.controls['currency']?.setValue(String(currencyCode ?? '').trim().toUpperCase());
    this.closeInlineSelect();
  }

  setPlanEffectType(planIndex: number, effectIndex: number, type: AdminShopPlanEffectType, event?: Event): void {
    event?.stopPropagation();
    const effectGroup = this.planEffects(planIndex).at(effectIndex);
    effectGroup.controls['type']?.setValue(type);
    this.onPlanEffectTypeChanged(planIndex, effectIndex);
    this.closeInlineSelect();
  }

  setPlanEffectCode(planIndex: number, effectIndex: number, code: string, event?: Event): void {
    event?.stopPropagation();
    const effectGroup = this.planEffects(planIndex).at(effectIndex);
    const type = this.normalizeEffectType(effectGroup.controls['type']?.value);
    effectGroup.controls['code']?.setValue(this.normalizeEffectCode(type, code));
    this.closeInlineSelect();
  }

  setPricingMode(mode: AdminShopPricingMode, event?: Event): void {
    event?.stopPropagation();
    if (this.productForm.controls.pricingMode.value === mode) return;
    this.productForm.controls.pricingMode.setValue(mode);
    this.applyPricingModeToPlans(mode);
  }

  prevPage(): void {
    this.pageIndex = Math.max(0, this.pageIndex - 1);
  }

  nextPage(): void {
    this.pageIndex = Math.min(this.totalPages - 1, this.pageIndex + 1);
  }

  toggleProductActions(productId: number, event: Event): void {
    event.stopPropagation();
    if (this.openProductActionsId === productId) {
      this.closeProductActions();
      return;
    }
    this.openMenu = null;
    this.closeInlineSelect();
    this.openProductActionsId = productId;
    this.lastProductActionsOpenedAtMs = Date.now();

    const button = event.currentTarget instanceof HTMLElement ? event.currentTarget : null;
    this.productActionsMenuStyle = button ? this.computeProductActionsMenuStyle(button) : null;
  }

  closeProductActions(): void {
    this.openProductActionsId = null;
    this.productActionsMenuStyle = null;
  }

  startCreate(): void {
    this.currentProductId = null;
    this.editorMode = 'create';
    this.currentProductCreatedAt = null;
    this.currentProductUpdatedAt = null;
    this.openMenu = null;
    this.closeInlineSelect();
    this.closeProductActions();
    this.resetCreateForm();
  }

  openProduct(productId: number): void {
    this.openMenu = null;
    this.closeInlineSelect();
    this.closeProductActions();
    this.loadingProduct = true;
    this.error = null;
    this.api.getProduct(productId).subscribe({
      next: (product) => {
        this.loadingProduct = false;
        this.currentProductId = product.id;
        this.editorMode = 'edit';
        this.currentProductCreatedAt = product.createdAt;
        this.currentProductUpdatedAt = product.updatedAt;
        this.patchProductForm(product);
      },
      error: (err) => {
        this.loadingProduct = false;
        this.error = apiErrorMessage(err, 'Failed to load product');
      },
    });
  }

  saveProduct(): void {
    if (this.savingProduct) {
      return;
    }

    if (this.productForm.invalid || this.hasPricingValidationErrors) {
      this.productForm.markAllAsTouched();
      if (this.hasPricingValidationErrors) {
        this.error = 'Fix purchase option effects before saving.';
        this.toast.warning('Fix purchase option effects before saving.', {
          title: 'Admin shop',
          dedupeKey: 'admin-shop-pricing-validation',
        });
      }
      return;
    }

    const payload = this.buildPayload();
    this.savingProduct = true;
    this.error = null;

    const isCreating = this.currentProductId == null;
    const request = isCreating
      ? this.api.createProduct(payload)
      : this.api.updateProduct(this.currentProductId!, payload);

    request.subscribe({
      next: (product) => {
        this.savingProduct = false;
        this.currentProductId = product.id;
        this.editorMode = 'edit';
        this.currentProductCreatedAt = product.createdAt;
        this.currentProductUpdatedAt = product.updatedAt;
        this.patchProductForm(product);
        this.toast.success(isCreating ? 'Product created.' : 'Product saved.', { title: 'Admin shop' });
        this.loadProducts();
      },
      error: (err) => {
        this.savingProduct = false;
        this.error = apiErrorMessage(err, 'Failed to save product');
      },
    });
  }

  setProductStatus(status: AdminShopProductStatus, event?: Event): void {
    event?.stopPropagation();
    const currentProductId = this.currentProductId;
    if (currentProductId == null || this.savingProduct) return;
    this.savingProduct = true;
    this.api.setStatus(currentProductId, status).subscribe({
      next: (product) => {
        this.savingProduct = false;
        this.patchProductForm(product);
        this.currentProductCreatedAt = product.createdAt;
        this.currentProductUpdatedAt = product.updatedAt;
        this.toast.success('Product status updated.', { title: 'Admin shop' });
        this.loadProducts();
      },
      error: (err) => {
        this.savingProduct = false;
        this.error = apiErrorMessage(err, 'Failed to update product status');
      },
    });
  }

  setRowStatus(productId: number, status: AdminShopProductStatus, event?: Event): void {
    event?.stopPropagation();
    this.closeInlineSelect();
    this.closeProductActions();
    this.api.setStatus(productId, status).subscribe({
      next: () => {
        this.toast.success('Product status updated.', { title: 'Admin shop' });
        this.loadProducts();
      },
      error: (err) => {
        this.error = apiErrorMessage(err, 'Failed to update product status');
      },
    });
  }

  uploadHeroImage(event: Event): void {
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0] ?? null;
    if (input) input.value = '';
    if (!file) return;

    const fileError = this.validateImageFile(file);
    if (fileError) {
      this.error = fileError;
      return;
    }

    this.uploadingHero = true;
    this.api.uploadImage(file).subscribe({
      next: (res) => {
        this.uploadingHero = false;
        this.productForm.controls.heroImageUrl.setValue(res.url);
      },
      error: (err) => {
        this.uploadingHero = false;
        this.error = apiErrorMessage(err, 'Failed to upload product image');
      },
    });
  }

  clearHeroImage(): void {
    this.productForm.controls.heroImageUrl.setValue('');
  }

  normalizeProductCode(): void {
    this.productForm.controls.code.setValue(String(this.productForm.controls.code.value ?? '').trim().toUpperCase());
  }

  normalizeProductSlug(): void {
    this.productForm.controls.slug.setValue(
      String(this.productForm.controls.slug.value ?? '')
        .trim()
        .toLowerCase()
        .replace(/\s+/g, '-')
    );
  }

  addTrustHighlight(): void {
    this.trustHighlightsArray.push(this.createTrustHighlightGroup());
  }

  removeTrustHighlight(index: number): void {
    this.trustHighlightsArray.removeAt(index);
  }

  addAdvantage(): void {
    this.advantagesArray.push(this.createAdvantageGroup());
  }

  removeAdvantage(index: number): void {
    this.advantagesArray.removeAt(index);
  }

  addAdvantageBullet(advantageIndex: number): void {
    this.advantageBullets(advantageIndex).push(this.createBulletControl(''));
  }

  removeAdvantageBullet(advantageIndex: number, bulletIndex: number): void {
    this.advantageBullets(advantageIndex).removeAt(bulletIndex);
  }

  addPlan(): void {
    this.pricingPlansArray.push(this.createPlanGroup());
    this.applyPricingModeToPlans(this.productForm.controls.pricingMode.value);
  }

  removePlan(index: number): void {
    this.closeInlineSelect();
    this.pricingPlansArray.removeAt(index);
  }

  planEffects(planIndex: number): FormArray<FormGroup> {
    const group = this.pricingPlansArray.at(planIndex) as FormGroup;
    return group.controls['effects'] as FormArray<FormGroup>;
  }

  addPlanEffect(planIndex: number, type?: AdminShopPlanEffectType): void {
    const array = this.planEffects(planIndex);
    const fallbackType = this.normalizeEffectTypeForMode(
      type
      ?? (this.currentPricingMode === 'SUBSCRIPTION' ? 'DURATION_DAYS' : 'RESOURCE_GRANT'),
      this.currentPricingMode
    );
    array.push(this.createPlanEffectGroup({ type: fallbackType, value: fallbackType === 'DURATION_DAYS' ? 30 : 1 }));
  }

  removePlanEffect(planIndex: number, effectIndex: number): void {
    const array = this.planEffects(planIndex);
    if (array.length <= 1) {
      this.toast.info('Each purchase option must include at least one effect.', { title: 'Admin shop' });
      return;
    }

    const effectGroup = array.at(effectIndex);
    const removingType = this.normalizeEffectType(effectGroup.controls['type']?.value);
    if (this.currentPricingMode === 'SUBSCRIPTION' && removingType === 'DURATION_DAYS') {
      const remainingDurationCount = array.controls.filter((group, index) => {
        if (index === effectIndex) return false;
        return this.normalizeEffectType(group.controls['type']?.value) === 'DURATION_DAYS';
      }).length;
      if (remainingDurationCount === 0) {
        this.toast.info('Subscription option needs at least one duration effect row.', { title: 'Admin shop' });
        return;
      }
    }

    this.closeInlineSelect();
    array.removeAt(effectIndex);
  }

  onPlanEffectTypeChanged(planIndex: number, effectIndex: number): void {
    const effect = this.planEffects(planIndex).at(effectIndex);
    const type = this.normalizeEffectTypeForMode(effect.controls['type']?.value, this.currentPricingMode);
    effect.controls['type']?.setValue(type);
    const nextCode = this.normalizeEffectCode(type, effect.controls['code']?.value);
    effect.controls['code']?.setValue(nextCode);
    if (type === 'DURATION_DAYS' && (Number(effect.controls['value']?.value ?? 0) || 0) <= 0) {
      effect.controls['value']?.setValue(30);
    }
  }

  setEditorStatus(status: AdminShopProductStatus): void {
    this.productForm.controls.status.setValue(status);
  }

  applyStatusAction(status: AdminShopProductStatus, event?: Event): void {
    if (this.currentProductId != null) {
      this.setProductStatus(status, event);
      return;
    }
    event?.stopPropagation();
    this.setEditorStatus(status);
  }

  productCardImage(item: Pick<AdminShopProductListItemDto, 'heroImageUrl'>): string {
    return String(item.heroImageUrl ?? '').trim();
  }

  productCardInitial(item: Pick<AdminShopProductListItemDto, 'title'>): string {
    const title = String(item.title ?? '').trim();
    return title ? title.charAt(0).toUpperCase() : 'P';
  }

  productCardStyle(item: Pick<AdminShopProductListItemDto, 'heroImageUrl'>): Record<string, string> {
    const image = this.productCardImage(item);
    if (image) {
      return {
        'background-image': `url(${image})`,
        'background-size': 'cover',
        'background-position': 'center',
      };
    }
    return {
      'background-image': 'linear-gradient(180deg, #5D6B92, #33415F)',
    };
  }

  formatMoney(amountMinor: number | null | undefined, currency: string | null | undefined): string {
    const normalizedCurrency = String(currency ?? '').trim().toUpperCase() || 'PLN';
    const valueMinor = Number(amountMinor ?? 0) || 0;
    const fractionDigits = this.currencyFractionDigitsByCode.get(normalizedCurrency) ?? 2;
    const formatterKey = `${normalizedCurrency}:${fractionDigits}`;
    let formatter = this.currencyFormatterCache.get(formatterKey);
    try {
      if (!formatter) {
        formatter = new Intl.NumberFormat('pl-PL', {
          style: 'currency',
          currency: normalizedCurrency,
          minimumFractionDigits: fractionDigits,
          maximumFractionDigits: fractionDigits,
        });
        this.currencyFormatterCache.set(formatterKey, formatter);
      }
      return formatter.format(valueMinor / Math.pow(10, fractionDigits));
    } catch {
      const base = valueMinor / Math.pow(10, fractionDigits);
      return `${new Intl.NumberFormat('pl-PL', {
        minimumFractionDigits: fractionDigits,
        maximumFractionDigits: fractionDigits,
      }).format(base)} ${normalizedCurrency}`;
    }
  }

  formatUpdatedAt(value: string | null | undefined): string {
    const timestamp = this.timestamp(value);
    if (!timestamp) return 'n/a';
    const date = new Date(timestamp);
    const day = String(date.getDate()).padStart(2, '0');
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const year = date.getFullYear();
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    return `${day}.${month}.${year}, ${hours}:${minutes}`;
  }

  iconPreviewClasses(raw: unknown, fallbackToken: string): string[] {
    const token = this.normalizeIconToken(raw) || fallbackToken;
    if (token.includes(' ')) {
      return token.split(/\s+/).filter(Boolean);
    }
    const normalized = token.startsWith('fa-') ? token : `fa-${token}`;
    return ['fa-solid', normalized];
  }

  iconPreviewLabel(raw: unknown, fallbackToken: string): string {
    return this.normalizeIconToken(raw) || fallbackToken;
  }

  advantageFallbackIcon(index: number): string {
    return AdminShopComponent.ADVANTAGE_FALLBACK_ICONS[index] ?? 'fa-circle-check';
  }

  trustHighlightFallbackIcon(index: number): string {
    return AdminShopComponent.TRUST_FALLBACK_ICONS[index] ?? 'fa-circle-check';
  }

  private loadProducts(): void {
    this.loadingList = true;
    this.api.listProducts().subscribe({
      next: (items) => {
        this.loadingList = false;
        this.products = items ?? [];
      },
      error: (err) => {
        this.loadingList = false;
        this.error = apiErrorMessage(err, 'Failed to load products');
      },
    });
  }

  private loadConfig(): void {
    this.api.getConfig().subscribe({
      next: (config) => {
        this.pricingModes = (config.pricingModes?.length ? config.pricingModes : ['SUBSCRIPTION', 'ONE_TIME']).includes('SUBSCRIPTION')
          ? (config.pricingModes?.length ? config.pricingModes : ['SUBSCRIPTION', 'ONE_TIME'])
          : ['SUBSCRIPTION', ...(config.pricingModes ?? [])];
        this.pricingCurrencies = config.pricingCurrencies?.length
          ? config.pricingCurrencies
          : this.pricingCurrencies;
        this.pricingEffects = this.normalizePricingEffects(config.pricingEffects);
        this.hydrateCurrencyFormatMap(this.pricingCurrencies);
        this.applyPricingModeToPlans(this.productForm.controls.pricingMode.value);
      },
      error: () => {
        this.pricingEffects = this.normalizePricingEffects(this.pricingEffects);
        this.hydrateCurrencyFormatMap(this.pricingCurrencies);
      },
    });
  }

  private resetCreateForm(): void {
    this.productForm.reset({
      code: '',
      slug: '',
      status: 'DRAFT',
      pricingMode: 'SUBSCRIPTION',
      title: '',
      subtitle: '',
      description: '',
      badgeLabel: '',
      heroImageUrl: '',
      checkoutEnabled: false,
    });
    this.clearFormArray(this.trustHighlightsArray);
    this.clearFormArray(this.advantagesArray);
    this.clearFormArray(this.pricingPlansArray);

    for (let i = 0; i < 4; i++) {
      this.trustHighlightsArray.push(this.createTrustHighlightGroup());
    }
    this.advantagesArray.push(this.createAdvantageGroup());
    this.pricingPlansArray.push(this.createPlanGroup());
    this.applyPricingModeToPlans('SUBSCRIPTION');
  }

  private patchProductForm(product: AdminShopProductDto): void {
    this.productForm.reset({
      code: product.code,
      slug: product.slug,
      status: product.status,
      pricingMode: product.pricingMode ?? 'SUBSCRIPTION',
      title: product.title,
      subtitle: product.subtitle ?? '',
      description: product.description ?? '',
      badgeLabel: product.badgeLabel ?? '',
      heroImageUrl: product.heroImageUrl ?? '',
      checkoutEnabled: product.checkoutEnabled,
    });

    this.clearFormArray(this.trustHighlightsArray);
    this.clearFormArray(this.advantagesArray);
    this.clearFormArray(this.pricingPlansArray);

    for (const item of product.trustHighlights ?? []) {
      this.trustHighlightsArray.push(this.createTrustHighlightGroup(item));
    }
    for (const item of product.advantages ?? []) {
      this.advantagesArray.push(this.createAdvantageGroup(item));
    }
    for (const item of product.pricingPlans ?? []) {
      this.pricingPlansArray.push(this.createPlanGroup(item));
    }
    if (!this.trustHighlightsArray.length) {
      for (let i = 0; i < 4; i++) this.trustHighlightsArray.push(this.createTrustHighlightGroup());
    }
    if (!this.advantagesArray.length) {
      this.advantagesArray.push(this.createAdvantageGroup());
    }
    this.applyPricingModeToPlans(this.productForm.controls.pricingMode.value);
  }

  private buildPayload(): AdminShopProductInputDto {
    const pricingMode = this.productForm.controls.pricingMode.value;
    const trustHighlights = this.trustHighlightsArray.controls
      .map((group) => ({
        icon: this.nullableText(group.controls['icon']?.value),
        title: String(group.controls['title']?.value ?? '').trim(),
        detail: String(group.controls['detail']?.value ?? '').trim(),
      }))
      .filter((item) => item.title.length > 0 || item.detail.length > 0)
      .map((item) => ({
        icon: item.icon,
        title: item.title,
        detail: item.detail,
      }));

    const advantages = this.advantagesArray.controls
      .map((group) => ({
        icon: this.nullableText(group.controls['icon']?.value),
        title: String(group.controls['title']?.value ?? '').trim(),
        bullets: this.advantageBulletsFromGroup(group).controls
          .map((control) => String(control.value ?? '').trim())
          .filter((value) => value.length > 0),
      }))
      .filter((item) => item.title.length > 0 || item.bullets.length > 0)
      .map((item) => ({
        icon: item.icon,
        title: item.title,
        bullets: item.bullets,
      }));

    const pricingPlans = this.pricingPlansArray.controls
      .map((group) => {
        const fallbackCurrency = this.pricingCurrencies[0]?.code ?? 'PLN';
        const effects = (group.controls['effects'] as FormArray<FormGroup>).controls
          .map((effectGroup) => {
            const type = this.normalizeEffectType(effectGroup.controls['type']?.value);
            const value = Number(effectGroup.controls['value']?.value ?? 0) || 0;
            const normalizedCode = this.normalizeEffectCode(type, effectGroup.controls['code']?.value);
            return {
              type,
              value,
              code: normalizedCode || null,
            };
          })
          .filter((item) => item.value > 0);

        const durationDays = effects
          .filter((effect) => effect.type === 'DURATION_DAYS')
          .reduce((total, effect) => total + effect.value, 0);
        const grantEffect = effects.find((effect) => effect.type === 'RESOURCE_GRANT') ?? null;

        return {
          code: String(group.controls['code']?.value ?? '').trim().toUpperCase(),
          label: String(group.controls['label']?.value ?? '').trim(),
          durationDays,
          currency: String(group.controls['currency']?.value ?? fallbackCurrency).trim().toUpperCase(),
          grossAmountMinor: Number(group.controls['grossAmountMinor']?.value ?? 0) || 0,
          grantValue: grantEffect?.value ?? null,
          grantUnit: grantEffect?.code ?? null,
          teaser: this.nullableText(group.controls['teaser']?.value),
          effects,
        };
      })
      .filter((item) =>
        item.code.length > 0 ||
        item.label.length > 0 ||
        item.grossAmountMinor > 0 ||
        item.durationDays > 0 ||
        (item.grantValue ?? 0) > 0 ||
        (item.grantUnit?.length ?? 0) > 0 ||
        item.effects.length > 0
      );

    return {
      code: String(this.productForm.controls.code.value ?? '').trim().toUpperCase(),
      slug: String(this.productForm.controls.slug.value ?? '').trim().toLowerCase(),
      status: this.productForm.controls.status.value,
      pricingMode,
      title: String(this.productForm.controls.title.value ?? '').trim(),
      subtitle: this.nullableText(this.productForm.controls.subtitle.value),
      description: this.nullableText(this.productForm.controls.description.value),
      badgeLabel: this.nullableText(this.productForm.controls.badgeLabel.value),
      heroImageUrl: this.nullableText(this.productForm.controls.heroImageUrl.value),
      checkoutEnabled: this.productForm.controls.checkoutEnabled.value,
      trustHighlights,
      advantages,
      pricingPlans,
    };
  }

  private createTrustHighlightGroup(value?: Partial<{ icon: string | null; title: string; detail: string }>): FormGroup {
    return new FormGroup({
      icon: new FormControl(value?.icon ?? '', { nonNullable: true, validators: [Validators.maxLength(64)] }),
      title: new FormControl(value?.title ?? '', { nonNullable: true, validators: [Validators.maxLength(120)] }),
      detail: new FormControl(value?.detail ?? '', { nonNullable: true, validators: [Validators.maxLength(220)] }),
    });
  }

  private createAdvantageGroup(
    value?: Partial<{ icon: string | null; title: string; bullets: Array<{ value: string } | string> }>
  ): FormGroup {
    const bullets = new FormArray<FormControl<string>>([]);
    const sourceBullets = value?.bullets ?? [''];
    for (const bullet of sourceBullets) {
      const resolved = typeof bullet === 'string' ? bullet : bullet?.value ?? '';
      bullets.push(this.createBulletControl(resolved));
    }
    if (!bullets.length) {
      bullets.push(this.createBulletControl(''));
    }
    return new FormGroup({
      icon: new FormControl(value?.icon ?? '', { nonNullable: true, validators: [Validators.maxLength(64)] }),
      title: new FormControl(value?.title ?? '', { nonNullable: true, validators: [Validators.maxLength(120)] }),
      bullets,
    });
  }

  private createPlanGroup(
    value?: Partial<{
      code: string;
      label: string;
      durationDays: number;
      currency: string;
      grossAmountMinor: number;
      grantValue: number | null;
      grantUnit: string | null;
      teaser: string | null;
      effects: Array<{
        type: AdminShopPlanEffectType;
        code: string | null;
        value: number;
      }>;
    }>
  ): FormGroup {
    const fallbackCurrency = this.pricingCurrencies[0]?.code ?? 'PLN';
    const effects = new FormArray<FormGroup>([]);
    const sourceEffects = (value?.effects ?? []).length
      ? (value?.effects ?? [])
      : this.legacyPlanEffectsFallback(value);
    for (const item of sourceEffects) {
      effects.push(this.createPlanEffectGroup(item));
    }
    if (!effects.length) {
      const defaultType: AdminShopPlanEffectType = this.productForm.controls.pricingMode.value === 'SUBSCRIPTION'
        ? 'DURATION_DAYS'
        : 'RESOURCE_GRANT';
      effects.push(this.createPlanEffectGroup({
        type: defaultType,
        value: defaultType === 'DURATION_DAYS' ? 30 : 1,
        code: this.defaultEffectCode(defaultType),
      }));
    }
    return new FormGroup({
      code: new FormControl(value?.code ?? '', { nonNullable: true, validators: [Validators.maxLength(32)] }),
      label: new FormControl(value?.label ?? '', { nonNullable: true, validators: [Validators.maxLength(120)] }),
      durationDays: new FormControl(value?.durationDays ?? 0, { nonNullable: true }),
      currency: new FormControl(value?.currency ?? fallbackCurrency, { nonNullable: true, validators: [Validators.maxLength(16)] }),
      grossAmountMinor: new FormControl(value?.grossAmountMinor ?? 0, { nonNullable: true }),
      grantValue: new FormControl(value?.grantValue ?? 0, { nonNullable: true }),
      grantUnit: new FormControl(value?.grantUnit ?? '', { nonNullable: true, validators: [Validators.maxLength(32)] }),
      teaser: new FormControl(value?.teaser ?? '', { nonNullable: true, validators: [Validators.maxLength(220)] }),
      effects,
    });
  }

  private createPlanEffectGroup(
    value?: Partial<{ type: AdminShopPlanEffectType; code: string | null; value: number }>
  ): FormGroup {
    const type = this.normalizeEffectType(value?.type);
    return new FormGroup({
      type: new FormControl<AdminShopPlanEffectType>(type, { nonNullable: true }),
      code: new FormControl(this.normalizeEffectCode(type, value?.code), { nonNullable: true, validators: [Validators.maxLength(64)] }),
      value: new FormControl(Number(value?.value ?? 0) || 0, { nonNullable: true }),
    });
  }

  private legacyPlanEffectsFallback(
    value?: Partial<{ durationDays: number; grantValue: number | null; grantUnit: string | null }>
  ): Array<{ type: AdminShopPlanEffectType; code: string | null; value: number }> {
    const result: Array<{ type: AdminShopPlanEffectType; code: string | null; value: number }> = [];
    const durationDays = Number(value?.durationDays ?? 0) || 0;
    const grantValue = Number(value?.grantValue ?? 0) || 0;
    const grantUnit = String(value?.grantUnit ?? '').trim().toUpperCase();
    if (durationDays > 0) {
      result.push({ type: 'DURATION_DAYS', code: this.defaultEffectCode('DURATION_DAYS'), value: durationDays });
    }
    if (grantValue > 0) {
      result.push({ type: 'RESOURCE_GRANT', code: this.normalizeEffectCode('RESOURCE_GRANT', grantUnit), value: grantValue });
    }
    return result;
  }

  private createBulletControl(value: string): FormControl<string> {
    return new FormControl(value ?? '', { nonNullable: true, validators: [Validators.maxLength(240)] });
  }

  private advantageBullets(index: number): FormArray<FormControl<string>> {
    return this.advantageBulletsFromGroup(this.advantagesArray.at(index) as FormGroup);
  }

  private advantageBulletsFromGroup(group: FormGroup): FormArray<FormControl<string>> {
    return group.controls['bullets'] as FormArray<FormControl<string>>;
  }

  private clearFormArray(array: FormArray): void {
    while (array.length) {
      array.removeAt(0);
    }
  }

  private normalizePricingEffects(raw: AdminShopEffectOptionDto[] | null | undefined): AdminShopEffectOptionDto[] {
    const safeInput = raw?.length ? raw : DEFAULT_PRICING_EFFECTS;
    const deduped = new Map<string, AdminShopEffectOptionDto>();
    for (const item of safeInput) {
      const type = this.normalizeEffectType(item?.type);
      const code = String(item?.code ?? '').trim().toUpperCase();
      if (!code) continue;
      const key = `${type}:${code}`;
      if (deduped.has(key)) continue;
      deduped.set(key, {
        type,
        code,
        label: String(item?.label ?? code).trim() || code,
        description: String(item?.description ?? '').trim(),
      });
    }
    const normalized = Array.from(deduped.values());
    return normalized.length ? normalized : [...DEFAULT_PRICING_EFFECTS];
  }

  private normalizeEffectType(raw: unknown): AdminShopPlanEffectType {
    return String(raw ?? '').trim().toUpperCase() === 'RESOURCE_GRANT'
      ? 'RESOURCE_GRANT'
      : 'DURATION_DAYS';
  }

  private normalizeEffectTypeForMode(raw: unknown, mode: AdminShopPricingMode): AdminShopPlanEffectType {
    const normalized = this.normalizeEffectType(raw);
    if (mode === 'SUBSCRIPTION') {
      return 'DURATION_DAYS';
    }
    return normalized;
  }

  private effectsByType(type: AdminShopPlanEffectType): AdminShopEffectOptionDto[] {
    return this.pricingEffects.filter((item) => item.type === type);
  }

  private defaultEffectCode(type: AdminShopPlanEffectType): string {
    const options = this.effectsByType(type);
    if (options.length) {
      return options[0].code;
    }
    return type === 'RESOURCE_GRANT' ? 'COINS' : 'PREMIUM_ACCESS';
  }

  private normalizeEffectCode(type: AdminShopPlanEffectType, raw: unknown): string {
    const normalized = String(raw ?? '').trim().toUpperCase();
    const options = this.effectsByType(type);
    if (!options.length) {
      return normalized || this.defaultEffectCode(type);
    }
    if (normalized && options.some((item) => item.code === normalized)) {
      return normalized;
    }
    return this.defaultEffectCode(type);
  }

  private nullableText(value: unknown): string | null {
    const normalized = String(value ?? '').trim();
    return normalized ? normalized : null;
  }

  private applyPricingModeToPlans(mode: AdminShopPricingMode): void {
    for (const group of this.pricingPlansArray.controls) {
      const effects = group.controls['effects'] as FormArray<FormGroup>;
      if (!effects.length) {
        if (mode === 'SUBSCRIPTION') {
          effects.push(this.createPlanEffectGroup({ type: 'DURATION_DAYS', value: 30, code: this.defaultEffectCode('DURATION_DAYS') }));
        } else {
          effects.push(this.createPlanEffectGroup({ type: 'RESOURCE_GRANT', value: 1, code: this.defaultEffectCode('RESOURCE_GRANT') }));
        }
      }

      for (const effectGroup of effects.controls) {
        const type = this.normalizeEffectTypeForMode(effectGroup.controls['type']?.value, mode);
        effectGroup.controls['type']?.setValue(type, { emitEvent: false });
        const normalizedCode = this.normalizeEffectCode(type, effectGroup.controls['code']?.value);
        effectGroup.controls['code']?.setValue(normalizedCode, { emitEvent: false });
        const normalizedValue = Number(effectGroup.controls['value']?.value ?? 0) || 0;
        if (type === 'DURATION_DAYS' && normalizedValue <= 0) {
          effectGroup.controls['value']?.setValue(30, { emitEvent: false });
        }
        if (type === 'RESOURCE_GRANT' && normalizedValue <= 0) {
          effectGroup.controls['value']?.setValue(1, { emitEvent: false });
        }
      }
    }
  }

  private hydrateCurrencyFormatMap(currencies: AdminShopCurrencyOptionDto[]): void {
    this.currencyFractionDigitsByCode.clear();
    for (const item of currencies ?? []) {
      const code = String(item?.code ?? '').trim().toUpperCase();
      if (!code) continue;
      const fractionDigits = Math.max(0, Math.min(6, Number(item?.fractionDigits ?? 2) || 0));
      this.currencyFractionDigitsByCode.set(code, fractionDigits);
    }
    if (!this.currencyFractionDigitsByCode.size) {
      this.currencyFractionDigitsByCode.set('PLN', 2);
      this.currencyFractionDigitsByCode.set('EUR', 2);
      this.currencyFractionDigitsByCode.set('USD', 2);
      this.currencyFractionDigitsByCode.set('COINS', 0);
    }
    this.currencyFormatterCache.clear();
  }

  private timestamp(value: string | null | undefined): number {
    const timestamp = Date.parse(String(value ?? ''));
    return Number.isFinite(timestamp) ? timestamp : 0;
  }

  private sortablePrice(item: AdminShopProductListItemDto): number {
    return item.lowestPriceMinor >= 0 ? item.lowestPriceMinor : Number.MAX_SAFE_INTEGER;
  }

  private normalizeIconToken(raw: unknown): string {
    return String(raw ?? '').trim();
  }

  private validateImageFile(file: File): string | null {
    if (file.size > HERO_MAX_UPLOAD_BYTES) {
      return 'Image is too large. Max 2 MB.';
    }
    if (!HERO_ALLOWED_IMAGE_MIME_TYPES.includes((file.type ?? '').toLowerCase())) {
      return 'Unsupported image format.';
    }
    return null;
  }

  private computeProductActionsMenuStyle(button: HTMLElement): Record<string, string> {
    const menuWidth = 210;
    const gap = 8;
    const viewportPad = 12;
    const estimatedMenuHeight = 176;

    const r = button.getBoundingClientRect();
    let leftViewport = r.right - menuWidth;
    leftViewport = Math.max(viewportPad, Math.min(leftViewport, window.innerWidth - menuWidth - viewportPad));

    const viewportH = window.innerHeight;
    let topViewport = r.bottom + gap;
    if (topViewport + estimatedMenuHeight > viewportH - viewportPad) {
      topViewport = r.top - gap - estimatedMenuHeight;
    }
    topViewport = Math.max(viewportPad, Math.min(topViewport, viewportH - estimatedMenuHeight - viewportPad));

    const host = button.closest('.table-wrap') as HTMLElement | null;
    if (!host) {
      return {
        position: 'fixed',
        left: `${Math.round(leftViewport)}px`,
        top: `${Math.round(topViewport)}px`,
        width: `${menuWidth}px`,
        maxHeight: `${Math.max(120, viewportH - viewportPad * 2)}px`,
        zIndex: '9999',
      };
    }

    const hostRect = host.getBoundingClientRect();
    const hostWidth = host.clientWidth;
    const leftInHost = Math.max(0, Math.min(leftViewport - hostRect.left, hostWidth - menuWidth));
    const topInHost = topViewport - hostRect.top;

    return {
      position: 'absolute',
      left: `${Math.round(leftInHost)}px`,
      top: `${Math.round(topInHost)}px`,
      width: `${menuWidth}px`,
      maxHeight: `${Math.max(120, viewportH - viewportPad * 2)}px`,
      zIndex: '9999',
    };
  }
}
