import { CommonModule } from '@angular/common';
import { AfterViewInit, Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin, Subscription } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import {
  LibraryPolicyDto,
  LibraryQuestionDto,
  LibraryQuizApi,
  LibraryQuizDetailDto,
  LibraryQuizListItemDto,
  QuizModerationStatus,
  QuizStatus,
} from '../../core/api/library-quiz.api';
import { AuthService } from '../../core/auth/auth.service';
import { AuthUserDto } from '../../core/models/auth.models';
import { ToastService } from '../../core/ui/toast.service';

const MIN_TIME_LIMIT_SECONDS = 5;
const DEFAULT_TIME_LIMIT_SECONDS = 15;
const MIN_QUESTIONS_PER_GAME = 1;
const DEFAULT_QUESTIONS_PER_GAME = 7;
const QUESTION_PAGE_SIZE = 25;
const BYTES_PER_MB = 1024 * 1024;
const FALLBACK_ALLOWED_IMAGE_MIME_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
const DEFAULT_POLICY: LibraryPolicyDto = {
  maxOwnedQuizzes: 20,
  maxPublishedQuizzes: 5,
  maxPendingSubmissions: 3,
  minQuestionsToSubmit: 5,
  maxQuestionsPerQuiz: 50,
  maxQuestionImagesPerQuiz: 10,
  minQuestionTimeLimitSeconds: 5,
  maxQuestionTimeLimitSeconds: 180,
  maxQuestionsPerGame: 50,
  maxUploadBytes: 2 * BYTES_PER_MB,
  allowedUploadMimeTypes: FALLBACK_ALLOWED_IMAGE_MIME_TYPES,
  ownedCount: 0,
  publishedCount: 0,
  pendingCount: 0,
};

@Component({
  selector: 'app-library',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './library.component.html',
  styleUrl: './library.component.scss',
})
export class LibraryComponent implements OnInit, AfterViewInit, OnDestroy {
  private static readonly ALL_CATEGORIES = 'all';
  private static readonly PANE_HEIGHT_DOWN_EPSILON_PX = 10;
  private static readonly PANE_HEIGHT_UP_EPSILON_PX = 2;
  private static readonly MODERATION_BANNER_DISMISSED_STORAGE_KEY = 'library:moderationBannerDismissed:v1';
  private static readonly TAB_ORDER: Record<'active' | 'draft' | 'trash', number> = {
    active: 0,
    draft: 1,
    trash: 2,
  };
  private static readonly EDITOR_TAB_ORDER: Record<'details' | 'questions', number> = {
    details: 0,
    questions: 1,
  };
  openMenu: 'mineCategory' | 'mineSort' | 'minePageSize' | 'questionPageSize' | null = null;
  previewImageUrl: string | null = null;
  previewImageAlt = 'Image preview';
  previewImageName = '';
  tab: 'active' | 'draft' | 'trash' | 'editor' = 'active';
  tabTransition: 'forward' | 'backward' = 'forward';
  private tabTransitionFlip = false;
  listTab: 'active' | 'draft' | 'trash' = 'active';
  editorMode: 'create' | 'edit' = 'create';
  editorTab: 'details' | 'questions' = 'details';
  editorTabTransition: 'forward' | 'backward' = 'forward';
  private editorTabTransitionFlip = false;
  avatarPreviewHeightPx: number | null = null;
  questionPaneHeightPx: number | null = null;

  private leftDetailsRef: ElementRef<HTMLElement> | null = null;
  private avatarPanelRef: ElementRef<HTMLElement> | null = null;
  private avatarEditorRef: ElementRef<HTMLElement> | null = null;
  private avatarResizeObserver: ResizeObserver | null = null;
  private avatarPreviewRafId: number | null = null;
  private detailsPaneRef: ElementRef<HTMLElement> | null = null;
  private detailsFormRef: ElementRef<HTMLElement> | null = null;
  private detailsPaneHeightPx: number | null = null;
  private questionsRightPaneRef: ElementRef<HTMLElement> | null = null;
  private questionsPaneResizeObserver: ResizeObserver | null = null;
  private questionsPaneRafId: number | null = null;
  private dismissedModerationSignatures = new Set<string>();
  private expandedQuestionIssueId: number | null = null;
  private pendingOpenQuizRequest: { quizId: number; tab: 'details' | 'questions'; reopen: boolean } | null = null;

  @ViewChild('quizDetailsLeft')
  set quizDetailsLeftRef(ref: ElementRef<HTMLElement> | undefined) {
    this.leftDetailsRef = ref ?? null;
    this.rebindAvatarObserver();
    this.queueAvatarPreviewResize();
  }

  @ViewChild('avatarPanel')
  set avatarPanelElementRef(ref: ElementRef<HTMLElement> | undefined) {
    this.avatarPanelRef = ref ?? null;
    this.rebindAvatarObserver();
    this.queueAvatarPreviewResize();
  }

  @ViewChild('avatarEditor')
  set avatarEditorElementRef(ref: ElementRef<HTMLElement> | undefined) {
    this.avatarEditorRef = ref ?? null;
    this.rebindAvatarObserver();
    this.queueAvatarPreviewResize();
  }

  @ViewChild('questionsPaneRight')
  set questionsPaneRightElementRef(ref: ElementRef<HTMLElement> | undefined) {
    this.questionsRightPaneRef = ref ?? null;
    this.rebindQuestionsPaneObserver();
    this.queueQuestionsPaneResize();
  }

  @ViewChild('detailsPane')
  set detailsPaneElementRef(ref: ElementRef<HTMLElement> | undefined) {
    this.detailsPaneRef = ref ?? null;
    this.rebindQuestionsPaneObserver();
    this.queueQuestionsPaneResize();
  }

  @ViewChild('detailsForm')
  set detailsFormElementRef(ref: ElementRef<HTMLElement> | undefined) {
    this.detailsFormRef = ref ?? null;
    this.rebindQuestionsPaneObserver();
    this.queueQuestionsPaneResize();
  }

  @ViewChild('imagePreviewDialog')
  imagePreviewDialogRef: ElementRef<HTMLDialogElement> | null = null;

  myQuizzes: LibraryQuizListItemDto[] = [];
  policy: LibraryPolicyDto = DEFAULT_POLICY;
  authUser: AuthUserDto | null = null;
  selectedQuiz: LibraryQuizDetailDto | null = null;
  selectedQuestionId: number | null = null;
  minePageIndex = 0;
  readonly minePageSize = new FormControl<number>(8, { nonNullable: true });
  readonly minePageSizeOptions: ReadonlyArray<number> = [8, 12, 25, 50];
  questionPageIndex = 0;
  readonly questionPageSize = new FormControl<number>(QUESTION_PAGE_SIZE, { nonNullable: true });
  readonly questionPageSizeOptions: ReadonlyArray<number> = [10, 25, 50, 100];

  loading = false;
  hasLoadedLibrary = false;
  loadingQuiz = false;
  savingQuiz = false;
  savingQuestion = false;
  deletingQuestion = false;
  deletingQuiz = false;
  openRowActionsId: number | null = null;
  rowActionsMenuStyle: Record<string, string> | null = null;
  private lastRowActionsOpenedAtMs = 0;

  private _error: string | null = null;
  private questionSearchSub?: Subscription;
  private filteredMyQuizzesCache:
    | {
      quizzesRef: LibraryQuizListItemDto[];
      query: string;
      category: string;
      status: QuizStatus;
      sort: 'az' | 'za';
      value: LibraryQuizListItemDto[];
    }
    | null = null;
  private mineCategoriesCache:
    | {
      quizzesRef: LibraryQuizListItemDto[];
      status: QuizStatus;
      value: string[];
    }
    | null = null;
  private filteredSelectedQuestionsCache:
    | {
      quizRef: LibraryQuizDetailDto | null;
      questionsRef: LibraryQuestionDto[] | null;
      query: string;
      value: LibraryQuestionDto[];
    }
    | null = null;

  readonly searchMine = new FormControl('', { nonNullable: true });
  readonly mineCategory = new FormControl<string>(LibraryComponent.ALL_CATEGORIES, { nonNullable: true });
  readonly mineSort = new FormControl<'az' | 'za'>('az', { nonNullable: true });
  readonly questionSearch = new FormControl('', { nonNullable: true });

  readonly quizForm = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(120)] }),
    description: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
    categoryName: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(64)] }),
    avatarImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    avatarBgStart: new FormControl<string>('#30D0FF', { nonNullable: true, validators: [Validators.maxLength(32)] }),
    avatarBgEnd: new FormControl<string>('#2F86FF', { nonNullable: true, validators: [Validators.maxLength(32)] }),
    avatarTextColor: new FormControl<string>('#0A0E1C', { nonNullable: true, validators: [Validators.maxLength(32)] }),
    questionTimeLimitSeconds: new FormControl<number | null>(15),
    questionsPerGame: new FormControl<number | null>(7),
  });

  readonly questionForm = new FormGroup({
    prompt: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(500)] }),
    imageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    correctIndex: new FormControl(0, { nonNullable: true }),
    o1: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o2: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o3: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o4: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
  });

  constructor(
    private readonly api: LibraryQuizApi,
    private readonly auth: AuthService,
    private readonly toast: ToastService,
    private readonly router: Router,
    private readonly route: ActivatedRoute
  ) {}

  get error(): string | null {
    return this._error;
  }

  set error(value: string | null) {
    this._error = value;
    if (!value) return;
    this.toast.error(value, { title: 'Library', dedupeKey: `library:error:${value}` });
  }

  ngOnInit(): void {
    this.loadDismissedModerationSignatures();
    this.refreshLists();
    this.questionSearchSub = new Subscription();
    this.questionSearchSub.add(this.auth.user$.subscribe((user) => {
      this.authUser = user;
    }));
    this.questionSearchSub.add(this.route.queryParamMap.subscribe((params) => {
      const rawQuizId = Number(params.get('openQuiz'));
      if (!Number.isFinite(rawQuizId) || rawQuizId <= 0) return;
      const tab = params.get('moderationTab') === 'questions' ? 'questions' : 'details';
      const reopen = params.get('reopenModeration') === '1';
      this.pendingOpenQuizRequest = {
        quizId: Math.trunc(rawQuizId),
        tab,
        reopen,
      };
      this.consumePendingOpenQuizRequest();
    }));
    this.questionSearchSub.add(this.questionSearch.valueChanges.subscribe(() => {
      this.resetQuestionPage();
    }));
    this.questionSearchSub.add(this.searchMine.valueChanges.subscribe(() => {
      this.resetMinePage();
    }));
    this.questionSearchSub.add(this.mineCategory.valueChanges.subscribe(() => {
      this.resetMinePage();
    }));
    this.questionSearchSub.add(this.mineSort.valueChanges.subscribe(() => {
      this.resetMinePage();
    }));
    this.questionSearchSub.add(this.minePageSize.valueChanges.subscribe(() => {
      this.resetMinePage();
    }));
    this.questionSearchSub.add(this.questionPageSize.valueChanges.subscribe(() => {
      this.resetQuestionPage();
    }));
  }

  ngAfterViewInit(): void {
    this.rebindAvatarObserver();
    this.queueAvatarPreviewResize();
    this.rebindQuestionsPaneObserver();
    this.queueQuestionsPaneResize();
  }

  ngOnDestroy(): void {
    this.questionSearchSub?.unsubscribe();
    this.questionSearchSub = undefined;
    if (this.questionsPaneRafId != null) {
      cancelAnimationFrame(this.questionsPaneRafId);
      this.questionsPaneRafId = null;
    }
    this.questionsPaneResizeObserver?.disconnect();
    this.questionsPaneResizeObserver = null;
    if (this.avatarPreviewRafId != null) {
      cancelAnimationFrame(this.avatarPreviewRafId);
      this.avatarPreviewRafId = null;
    }
    this.avatarResizeObserver?.disconnect();
    this.avatarResizeObserver = null;
  }

  get filteredMyQuizzes(): LibraryQuizListItemDto[] {
    return this.getFilteredMyQuizzesCached();
  }

  get pagedMyQuizzes(): LibraryQuizListItemDto[] {
    const list = this.getFilteredMyQuizzesCached();
    if (!list.length) return [];
    const page = this.safeMinePageIndex;
    const size = this.minePageSize.value;
    const start = page * size;
    return list.slice(start, start + size);
  }

  get mineTotalPages(): number {
    const total = this.getFilteredMyQuizzesCached().length;
    return Math.max(1, Math.ceil(total / this.minePageSize.value));
  }

  get minePageFrom(): number {
    const total = this.getFilteredMyQuizzesCached().length;
    if (!total) return 0;
    return this.safeMinePageIndex * this.minePageSize.value + 1;
  }

  get minePageTo(): number {
    const total = this.getFilteredMyQuizzesCached().length;
    if (!total) return 0;
    return Math.min(total, this.minePageFrom + this.minePageSize.value - 1);
  }

  get minePageSizeLabel(): string {
    return `Show ${this.minePageSize.value}`;
  }

  get openRowActionsQuiz(): LibraryQuizListItemDto | null {
    const id = this.openRowActionsId;
    if (id == null) return null;
    return this.myQuizzes.find((q) => q.id === id) ?? null;
  }

  get mineCategories(): string[] {
    return this.getMineCategoriesCached();
  }

  get filteredSelectedQuestions(): LibraryQuestionDto[] {
    return this.getFilteredSelectedQuestionsCached();
  }

  get pagedSelectedQuestions(): LibraryQuestionDto[] {
    const list = this.getFilteredSelectedQuestionsCached();
    if (!list.length) return [];
    const page = Math.min(this.questionPageIndex, this.questionTotalPages - 1);
    const size = this.questionPageSize.value;
    const start = page * size;
    return list.slice(start, start + size);
  }

  get questionTotalPages(): number {
    const total = this.getFilteredSelectedQuestionsCached().length;
    return Math.max(1, Math.ceil(total / this.questionPageSize.value));
  }

  get questionPageFrom(): number {
    const total = this.getFilteredSelectedQuestionsCached().length;
    if (!total) return 0;
    const page = Math.min(this.questionPageIndex, this.questionTotalPages - 1);
    return page * this.questionPageSize.value + 1;
  }

  get questionPageTo(): number {
    const total = this.getFilteredSelectedQuestionsCached().length;
    if (!total) return 0;
    return Math.min(total, this.questionPageFrom + this.questionPageSize.value - 1);
  }

  get questionVisiblePages(): number[] {
    const total = this.questionTotalPages;
    if (total <= 1) return [1];
    const current = this.questionPageIndex + 1;
    const start = Math.max(1, current - 1);
    const end = Math.min(total, start + 2);
    const normalizedStart = Math.max(1, end - 2);
    return Array.from({ length: end - normalizedStart + 1 }, (_, i) => normalizedStart + i);
  }

  get questionPageSizeLabel(): string {
    return `Show ${this.questionPageSize.value}`;
  }

  get selectedQuestion(): LibraryQuestionDto | null {
    const id = this.selectedQuestionId;
    if (!id || !this.selectedQuiz) return null;
    return this.selectedQuiz.questions.find((q) => q.id === id) ?? null;
  }

  get canCreateQuiz(): boolean {
    return this.policy.ownedCount < this.policy.maxOwnedQuizzes;
  }

  get premiumExpiredWarning(): string | null {
    const raw = String(this.authUser?.premiumExpiresAt ?? '').trim();
    if (!raw) return null;
    const expiresAt = Date.parse(raw);
    if (!Number.isFinite(expiresAt) || expiresAt > Date.now()) return null;
    return 'Premium expired. Existing quizzes stay intact, but premium-only increases are blocked until premium is renewed.';
  }

  get overLimitWarnings(): string[] {
    const warnings: string[] = [];
    if (this.policy.ownedCount > this.policy.maxOwnedQuizzes) {
      warnings.push(
        `You currently own ${this.policy.ownedCount} quizzes, above the active tier limit of ${this.policy.maxOwnedQuizzes}. Existing quizzes stay available, but you cannot create more until the count is reduced or premium is active again.`
      );
    }
    if (this.policy.publishedCount > this.policy.maxPublishedQuizzes) {
      warnings.push(
        `You currently have ${this.policy.publishedCount} published quizzes, above the active tier limit of ${this.policy.maxPublishedQuizzes}. Existing published quizzes remain visible, but new submissions cannot exceed the current tier.`
      );
    }
    if (this.policy.pendingCount > this.policy.maxPendingSubmissions) {
      warnings.push(
        `You currently have ${this.policy.pendingCount} pending submissions, above the active tier limit of ${this.policy.maxPendingSubmissions}. Existing pending submissions stay in review, but additional submissions are blocked for now.`
      );
    }

    const quiz = this.selectedQuiz;
    const questionCount = quiz?.questions?.length ?? 0;
    if (quiz && questionCount > this.policy.maxQuestionsPerQuiz) {
      warnings.push(
        `This quiz has ${questionCount} questions, above the active tier limit of ${this.policy.maxQuestionsPerQuiz}. You can still edit existing content, but you cannot add more questions until the quiz is back within the current tier.`
      );
    }
    if (quiz?.questionTimeLimitSeconds && quiz.questionTimeLimitSeconds > this.policy.maxQuestionTimeLimitSeconds) {
      warnings.push(
        `This quiz uses ${quiz.questionTimeLimitSeconds}s per question, above the active tier max of ${this.policy.maxQuestionTimeLimitSeconds}s. Lower it before saving quiz details under the current tier.`
      );
    }
    if (quiz?.questionsPerGame && quiz.questionsPerGame > this.policy.maxQuestionsPerGame) {
      warnings.push(
        `This quiz uses ${quiz.questionsPerGame} questions per game, above the active tier max of ${this.policy.maxQuestionsPerGame}. Lower it before saving quiz details under the current tier.`
      );
    }
    return warnings;
  }

  get canAddQuestion(): boolean {
    const count = this.selectedQuiz?.questions?.length ?? 0;
    return count < this.policy.maxQuestionsPerQuiz;
  }

  get maxUploadMbLabel(): string {
    const mb = Math.max(1, Math.round((this.policy.maxUploadBytes ?? DEFAULT_POLICY.maxUploadBytes) / BYTES_PER_MB));
    return `${mb}MB`;
  }

  get questionImageLimitPerQuiz(): number {
    const limit = Number(this.policy.maxQuestionImagesPerQuiz);
    if (!Number.isFinite(limit) || limit < 0) {
      return DEFAULT_POLICY.maxQuestionImagesPerQuiz;
    }
    return Math.trunc(limit);
  }

  get questionImageCountInQuiz(): number {
    const questions = this.selectedQuiz?.questions ?? [];
    return questions.reduce((count, question) => (
      this.hasImageUrl(question.imageUrl) ? count + 1 : count
    ), 0);
  }

  get questionImageUsageLabel(): string {
    return `${this.questionImageCountInQuiz}/${this.questionImageLimitPerQuiz}`;
  }

  get questionImageUploadBlocked(): boolean {
    // Replacing an existing image in the currently edited question does not increase usage.
    if (this.hasImageUrl(this.questionForm.controls.imageUrl.value)) return false;
    if (this.hasImageUrl(this.selectedQuestion?.imageUrl ?? null)) return false;
    return this.questionImageCountInQuiz >= this.questionImageLimitPerQuiz;
  }

  get allowedUploadMimeTypes(): string[] {
    const values = this.policy.allowedUploadMimeTypes ?? [];
    return values.length ? values : FALLBACK_ALLOWED_IMAGE_MIME_TYPES;
  }

  get currentListStatusTab(): QuizStatus {
    if (this.tab === 'draft') return 'DRAFT';
    if (this.tab === 'trash') return 'TRASHED';
    return 'ACTIVE';
  }

  get listSectionTitle(): string {
    const tab = this.tab === 'editor' ? this.listTab : this.tab;
    if (tab === 'draft') return 'Draft quizzes';
    if (tab === 'trash') return 'Trashed quizzes';
    return 'Active quizzes';
  }

  get listSectionHint(): string {
    const tab = this.tab === 'editor' ? this.listTab : this.tab;
    if (tab === 'draft') {
      return 'Work in progress. Edit your quiz and submit it for admin review when ready.';
    }
    if (tab === 'trash') {
      return 'Quizzes moved to trash. You can restore them or delete them permanently.';
    }
    return 'Published quizzes available to players in the community library.';
  }

  get tabTransitionClass(): string {
    if (this.tabTransition === 'backward') {
      return this.tabTransitionFlip ? 'view-screen--enter-back-a' : 'view-screen--enter-back-b';
    }
    return this.tabTransitionFlip ? 'view-screen--enter-forward-a' : 'view-screen--enter-forward-b';
  }

  get editorTabTransitionClass(): string {
    if (this.editorTabTransition === 'backward') {
      return this.editorTabTransitionFlip ? 'view-screen--enter-back-a' : 'view-screen--enter-back-b';
    }
    return this.editorTabTransitionFlip ? 'view-screen--enter-forward-a' : 'view-screen--enter-forward-b';
  }

  private getFilteredMyQuizzesCached(): LibraryQuizListItemDto[] {
    const q = this.searchMine.value.trim().toLowerCase();
    const category = this.mineCategory.value;
    const tabStatus = this.currentListStatusTab;
    const sort = this.mineSort.value;
    const cache = this.filteredMyQuizzesCache;
    if (
      cache
      && cache.quizzesRef === this.myQuizzes
      && cache.query === q
      && cache.category === category
      && cache.status === tabStatus
      && cache.sort === sort
    ) {
      return cache.value;
    }

    const filtered = this.myQuizzes.filter((item) => {
      const byQuery = !q || (
        (item.title ?? '').toLowerCase().includes(q)
        || (item.description ?? '').toLowerCase().includes(q)
        || (item.categoryName ?? '').toLowerCase().includes(q)
      );
      const byCategory = category === LibraryComponent.ALL_CATEGORIES || (item.categoryName ?? '') === category;
      const byStatus = item.status === tabStatus;
      return byQuery && byCategory && byStatus;
    });

    const sorted = this.sortByTitle(filtered, sort);
    this.filteredMyQuizzesCache = {
      quizzesRef: this.myQuizzes,
      query: q,
      category,
      status: tabStatus,
      sort,
      value: sorted,
    };
    return sorted;
  }

  private getMineCategoriesCached(): string[] {
    const status = this.currentListStatusTab;
    const cache = this.mineCategoriesCache;
    if (cache && cache.quizzesRef === this.myQuizzes && cache.status === status) {
      return cache.value;
    }
    const categories = this.extractCategories(this.myQuizzes.filter((item) => item.status === status));
    this.mineCategoriesCache = {
      quizzesRef: this.myQuizzes,
      status,
      value: categories,
    };
    return categories;
  }

  private ensureMineCategoryAvailable(): void {
    const current = this.mineCategory.value;
    if (current === LibraryComponent.ALL_CATEGORIES) return;
    const categories = this.getMineCategoriesCached();
    if (categories.includes(current)) return;
    this.mineCategory.setValue(LibraryComponent.ALL_CATEGORIES, { emitEvent: false });
  }

  private getFilteredSelectedQuestionsCached(): LibraryQuestionDto[] {
    const quiz = this.selectedQuiz;
    const query = this.questionSearch.value.trim().toLowerCase();
    const questionsRef = quiz?.questions ?? null;
    const cache = this.filteredSelectedQuestionsCache;
    if (
      cache
      && cache.quizRef === quiz
      && cache.questionsRef === questionsRef
      && cache.query === query
    ) {
      return cache.value;
    }
    if (!quiz) {
      this.filteredSelectedQuestionsCache = {
        quizRef: null,
        questionsRef: null,
        query,
        value: [],
      };
      return [];
    }

    const questions = [...(quiz.questions ?? [])].sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0));
    const filtered = !query
      ? questions
      : questions.filter((q) => (q.prompt ?? '').toLowerCase().includes(query));
    this.filteredSelectedQuestionsCache = {
      quizRef: quiz,
      questionsRef,
      query,
      value: filtered,
    };
    return filtered;
  }

  refreshLists(): void {
    if (this.loading) return;
    this.loading = true;
    this.error = null;

    forkJoin({
      mine: this.api.listMy(),
      policy: this.api.getPolicy(),
    }).subscribe({
      next: ({ mine, policy }) => {
        this.loading = false;
        this.myQuizzes = mine ?? [];
        this.policy = policy ?? DEFAULT_POLICY;
        this.hasLoadedLibrary = true;
        this.ensureMineCategoryAvailable();
        this.resetMinePage();
        this.consumePendingOpenQuizRequest();
      },
      error: (err) => {
        this.loading = false;
        this.error = apiErrorMessage(err, 'Failed to load library data');
      },
    });
  }

  setTab(tab: 'active' | 'draft' | 'trash'): void {
    if (this.tab === tab) {
      this.openMenu = null;
      return;
    }
    const currentTab = this.tab === 'editor' ? 'active' : this.tab;
    const currentOrder = LibraryComponent.TAB_ORDER[currentTab];
    const nextOrder = LibraryComponent.TAB_ORDER[tab];
    this.tabTransition = nextOrder < currentOrder ? 'backward' : 'forward';
    this.tabTransitionFlip = !this.tabTransitionFlip;
    this.tab = tab;
    this.listTab = tab;
    this.ensureMineCategoryAvailable();
    this.resetMinePage();
    this.error = null;
    this.openMenu = null;
  }

  goHome(): void {
    void this.router.navigate(['/']);
  }

  onTopBack(): void {
    if (this.tab === 'editor') {
      this.backToList();
      return;
    }
    this.goHome();
  }

  setEditorTab(tab: 'details' | 'questions'): void {
    if (tab === 'questions' && this.editorMode !== 'edit') return;
    if (this.editorTab === tab) return;
    if (tab === 'questions') {
      this.primeQuestionsPaneHeight();
    }
    const currentOrder = LibraryComponent.EDITOR_TAB_ORDER[this.editorTab];
    const nextOrder = LibraryComponent.EDITOR_TAB_ORDER[tab];
    this.editorTabTransition = nextOrder < currentOrder ? 'backward' : 'forward';
    this.editorTabTransitionFlip = !this.editorTabTransitionFlip;
    this.editorTab = tab;
    this.openMenu = null;
    if (tab === 'questions') {
      this.resetQuestionPage();
    }
    this.queueQuestionsPaneResize();
    this.queueAvatarPreviewResize();
  }

  onTimeLimitBlur(): void {
    this.quizForm.controls.questionTimeLimitSeconds.setValue(
      this.normalizeTimeLimit(this.quizForm.controls.questionTimeLimitSeconds.value)
    );
  }

  onQuestionsPerGameBlur(): void {
    this.quizForm.controls.questionsPerGame.setValue(
      this.normalizeQuestionsPerGame(this.quizForm.controls.questionsPerGame.value)
    );
  }

  get mineCategoryLabel(): string {
    const value = this.mineCategory.value;
    return value === LibraryComponent.ALL_CATEGORIES ? 'All categories' : value;
  }

  get mineSortLabel(): string {
    return this.mineSort.value === 'za' ? 'Name Z-A' : 'Name A-Z';
  }

  toggleMenu(menu: 'mineCategory' | 'mineSort' | 'minePageSize' | 'questionPageSize', ev?: Event): void {
    ev?.stopPropagation();
    this.openMenu = this.openMenu === menu ? null : menu;
  }

  setMineCategory(value: string, ev?: Event): void {
    ev?.stopPropagation();
    this.mineCategory.setValue(value);
    this.openMenu = null;
  }

  setMineSort(value: 'az' | 'za', ev?: Event): void {
    ev?.stopPropagation();
    this.mineSort.setValue(value);
    this.openMenu = null;
  }

  setMinePageSize(size: number, ev?: Event): void {
    ev?.stopPropagation();
    this.minePageSize.setValue(size);
    this.openMenu = null;
  }

  setQuestionPageSize(size: number, ev?: Event): void {
    ev?.stopPropagation();
    this.questionPageSize.setValue(size);
    this.openMenu = null;
  }

  @HostListener('document:click')
  onDocumentClick(): void {
    this.openMenu = null;
    this.closeRowActions();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.openMenu = null;
    this.closeRowActions();
    this.closeImagePreview();
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    this.openMenu = null;
    this.closeRowActions();
    this.queueAvatarPreviewResize();
    this.queueQuestionsPaneResize();
  }

  @HostListener('window:scroll')
  onWindowScroll(): void {
    if (this.openRowActionsId != null && Date.now() - this.lastRowActionsOpenedAtMs < 250) {
      return;
    }
    this.openMenu = null;
    this.closeRowActions();
  }

  toggleRowActions(quizId: number, ev?: Event): void {
    ev?.stopPropagation();
    if (this.openRowActionsId === quizId) {
      this.closeRowActions();
      return;
    }
    this.openMenu = null;
    this.openRowActionsId = quizId;
    this.lastRowActionsOpenedAtMs = Date.now();
    const trigger = ev?.currentTarget instanceof HTMLElement ? ev.currentTarget : null;
    const quiz = this.openRowActionsQuiz;
    this.rowActionsMenuStyle = trigger ? this.computeRowActionsMenuStyle(trigger, quiz) : null;
  }

  closeRowActions(): void {
    this.openRowActionsId = null;
    this.rowActionsMenuStyle = null;
  }

  private computeRowActionsMenuStyle(
    button: HTMLElement,
    quiz: LibraryQuizListItemDto | null
  ): Record<string, string> {
    const menuWidth = 210;
    const gap = 8;
    const viewportPad = 12;
    const estimatedMenuHeight = this.rowActionsMenuEstimatedHeight(quiz);
    const viewportH = window.innerHeight;
    const r = button.getBoundingClientRect();

    let leftViewport = r.right - menuWidth;
    leftViewport = Math.max(viewportPad, Math.min(leftViewport, window.innerWidth - menuWidth - viewportPad));

    const fitsBelow = r.bottom + gap + estimatedMenuHeight <= viewportH - viewportPad;
    const fitsAbove = r.top - gap - estimatedMenuHeight >= viewportPad;

    let topViewport: number;
    if (fitsBelow || !fitsAbove) {
      topViewport = r.bottom + gap;
    } else {
      topViewport = r.top - gap - estimatedMenuHeight;
    }
    topViewport = Math.max(viewportPad, Math.min(topViewport, viewportH - estimatedMenuHeight - viewportPad));

    const host = button.closest('.picker-list') as HTMLElement | null;
    if (!host) {
      return {
        position: 'fixed',
        left: `${Math.round(leftViewport)}px`,
        top: `${Math.round(topViewport)}px`,
        right: 'auto',
        bottom: 'auto',
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
      right: 'auto',
      bottom: 'auto',
      width: `${menuWidth}px`,
      maxHeight: `${Math.max(120, viewportH - viewportPad * 2)}px`,
      zIndex: '9999',
    };
  }

  private rowActionsMenuEstimatedHeight(quiz: LibraryQuizListItemDto | null): number {
    if (!quiz) return 148;
    let items = 1; // Edit
    if (quiz.status === 'DRAFT' && quiz.moderationStatus !== 'PENDING') items += 1; // Submit for review
    if (quiz.status === 'ACTIVE') items += 1; // Disable
    if (quiz.status !== 'TRASHED') items += 1; // Move to trash
    if (quiz.status === 'TRASHED') items += 2; // Restore + Delete permanently
    return 16 + items * 40;
  }

  openCreate(): void {
    if (!this.canCreateQuiz) {
      this.error = `You reached your quiz limit (${this.policy.maxOwnedQuizzes}).`;
      return;
    }
    this.editorMode = 'create';
    this.editorTabTransition = 'forward';
    this.editorTab = 'details';
    this.selectedQuiz = null;
    this.selectedQuestionId = null;
    this.expandedQuestionIssueId = null;
    this.questionSearch.setValue('');
    this.resetQuestionPage();
    this.resetQuizForm();
    this.resetQuestionForm();
    this.tab = 'editor';
    this.openMenu = null;
    this.error = null;
    this.queueAvatarPreviewResize();
    this.queueQuestionsPaneResize();
  }

  openEdit(
    quizId: number,
    targetTab: 'details' | 'questions' = 'details',
    options?: { reopenModerationBanner?: boolean; fromModerationLink?: boolean }
  ): void {
    if (this.loadingQuiz) return;
    this.error = null;
    this.loadingQuiz = true;
    this.editorMode = 'edit';
    this.editorTabTransition = 'forward';
    this.editorTab = targetTab;

    this.api.getMyQuiz(quizId).subscribe({
      next: (detail) => {
        this.loadingQuiz = false;
        this.selectedQuiz = detail;
        this.expandedQuestionIssueId = null;
        if (options?.reopenModerationBanner) {
          const sig = this.getModerationSignatureForQuiz(detail);
          if (sig) {
            this.dismissedModerationSignatures.delete(sig);
            this.persistDismissedModerationSignatures();
          }
        }
        this.syncMyQuizListItem(detail);
        this.patchQuizForm(detail);
        this.resetQuestionForm();
        this.selectedQuestionId = null;
        this.questionSearch.setValue('');
        this.resetQuestionPage();
        this.tab = 'editor';
        this.openMenu = null;
        if (targetTab === 'questions') {
          this.primeQuestionsPaneHeight();
          this.queueQuestionsPaneResize();
        } else {
          this.queueAvatarPreviewResize();
          this.queueQuestionsPaneResize();
        }
      },
      error: (err) => {
        this.loadingQuiz = false;
        if (options?.fromModerationLink && this.isNotFoundLikeError(err)) {
          this.handleMissingModerationQuizTarget();
          return;
        }
        this.error = apiErrorMessage(err, 'Failed to load quiz');
      },
    });
  }

  backToList(): void {
    this.tabTransition = 'backward';
    this.tab = this.listTab;
    this.editorMode = 'edit';
    this.editorTab = 'details';
    this.selectedQuestionId = null;
    this.expandedQuestionIssueId = null;
    this.questionSearch.setValue('');
    this.resetQuestionPage();
    this.openMenu = null;
    this.error = null;
    this.refreshLists();
    this.queueAvatarPreviewResize();
  }

  saveQuiz(): void {
    if (this.editorMode === 'create' && !this.canCreateQuiz) {
      this.error = `You reached your quiz limit (${this.policy.maxOwnedQuizzes}).`;
      return;
    }
    if (this.quizForm.invalid) {
      this.quizForm.markAllAsTouched();
      return;
    }
    if (this.savingQuiz) return;
    this.savingQuiz = true;
    this.error = null;

    const payload = {
      title: this.quizForm.controls.title.value,
      description: this.quizForm.controls.description.value || null,
      categoryName: this.quizForm.controls.categoryName.value || null,
      avatarImageUrl: this.quizForm.controls.avatarImageUrl.value || null,
      avatarBgStart: this.quizForm.controls.avatarBgStart.value || null,
      avatarBgEnd: this.quizForm.controls.avatarBgEnd.value || null,
      avatarTextColor: this.quizForm.controls.avatarTextColor.value || null,
      questionTimeLimitSeconds: this.normalizeNumber(this.quizForm.controls.questionTimeLimitSeconds.value),
      questionsPerGame: this.normalizeNumber(this.quizForm.controls.questionsPerGame.value),
    };
    const isCreate = this.editorMode === 'create';

    const request$ = isCreate
      ? this.api.createQuiz(payload)
      : this.api.updateQuiz(this.selectedQuiz?.id ?? 0, payload);

    request$.subscribe({
      next: (saved) => {
        this.savingQuiz = false;
        this.toast.success(isCreate ? 'Quiz created.' : 'Quiz updated.', { title: 'Library' });
        this.refreshLists();
        this.openEdit(saved.id, isCreate ? 'questions' : 'details');
      },
      error: (err) => {
        this.savingQuiz = false;
        this.error = apiErrorMessage(err, 'Failed to save quiz');
      },
    });
  }

  submitForReview(item: LibraryQuizListItemDto): void {
    const submitBlockedReason = this.getSubmitBlockedReason(item);
    if (submitBlockedReason) {
      this.toast.warning(submitBlockedReason, { title: 'Library' });
      return;
    }
    this.error = null;
    this.api.submit(item.id).subscribe({
      next: () => {
        this.toast.success('Quiz sent for moderation.', { title: 'Library' });
        this.refreshLists();
        if (this.selectedQuiz?.id === item.id) {
          this.openEdit(item.id);
        }
      },
      error: (err) => {
        this.error = apiErrorMessage(err, 'Failed to submit quiz');
      },
    });
  }

  submitSelectedQuiz(): void {
    if (!this.selectedQuiz) return;
    this.submitForReview({
      ...this.selectedQuiz,
      moderationUpdatedAt: this.selectedQuiz.moderationUpdatedAt,
      moderationQuestionIssueCount: this.selectedQuiz.moderationQuestionIssues?.length ?? 0,
      questionCount: this.selectedQuiz.questions.length,
    });
  }

  setSelectedQuizStatus(status: QuizStatus): void {
    const quiz = this.selectedQuiz;
    if (!quiz) return;
    this.setQuizStatus(
      {
        ...quiz,
        moderationUpdatedAt: quiz.moderationUpdatedAt,
        moderationQuestionIssueCount: quiz.moderationQuestionIssues?.length ?? 0,
        questionCount: quiz.questions.length,
      },
      status
    );
  }

  purgeSelectedQuiz(): void {
    const quiz = this.selectedQuiz;
    if (!quiz) return;
    this.purgeQuiz({
      ...quiz,
      moderationUpdatedAt: quiz.moderationUpdatedAt,
      moderationQuestionIssueCount: quiz.moderationQuestionIssues?.length ?? 0,
      questionCount: quiz.questions.length,
    });
  }

  trashQuiz(item: LibraryQuizListItemDto): void {
    if (!confirm('Move this quiz to trash?')) return;
    if (this.deletingQuiz) return;
    this.deletingQuiz = true;
    this.error = null;
    this.api.trashQuiz(item.id).subscribe({
      next: () => {
        this.deletingQuiz = false;
        this.toast.success('Quiz moved to trash.', { title: 'Library' });
        this.refreshLists();
        if (this.selectedQuiz?.id === item.id) {
          this.backToList();
        }
      },
      error: (err) => {
        this.deletingQuiz = false;
        this.error = apiErrorMessage(err, 'Failed to move quiz to trash');
      },
    });
  }

  setQuizStatus(item: LibraryQuizListItemDto, status: QuizStatus): void {
    if (this.deletingQuiz) return;
    if (status === 'TRASHED' && !confirm('Move this quiz to trash?')) return;
    if (status === 'ACTIVE') {
      this.error = 'Publishing is available only after admin approval. Submit quiz for moderation.';
      return;
    }
    this.deletingQuiz = true;
    this.error = null;
    this.api.setStatus(item.id, status).subscribe({
      next: () => {
        this.deletingQuiz = false;
        this.toast.success(
          status === 'DRAFT' ? 'Quiz moved to draft.' : 'Quiz moved to trash.',
          { title: 'Library' }
        );
        this.refreshLists();
        if (this.selectedQuiz?.id === item.id) {
          this.openEdit(item.id);
        }
      },
      error: (err) => {
        this.deletingQuiz = false;
        this.error = apiErrorMessage(err, 'Failed to update quiz status');
      },
    });
  }

  purgeQuiz(item: LibraryQuizListItemDto): void {
    if (this.deletingQuiz) return;
    if (!confirm('Permanently delete this quiz and all its questions/images?')) return;
    this.deletingQuiz = true;
    this.error = null;
    this.api.purgeQuiz(item.id).subscribe({
      next: () => {
        this.deletingQuiz = false;
        this.toast.success('Quiz deleted permanently.', { title: 'Library' });
        this.refreshLists();
        if (this.selectedQuiz?.id === item.id) {
          this.backToList();
        }
      },
      error: (err) => {
        this.deletingQuiz = false;
        this.error = apiErrorMessage(err, 'Failed to delete quiz permanently');
      },
    });
  }

  startNewQuestion(): void {
    if (!this.canAddQuestion) {
      this.error = `Question limit reached (${this.policy.maxQuestionsPerQuiz}).`;
      return;
    }
    this.selectedQuestionId = null;
    this.resetQuestionForm();
    this.queueQuestionsPaneResize();
  }

  startEditQuestion(question: LibraryQuestionDto): void {
    this.selectedQuestionId = question.id;
    const options = [...(question.options ?? [])].sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0));
    const correctIndex = Math.max(0, options.findIndex((o) => o.correct));
    this.questionForm.patchValue({
      prompt: question.prompt ?? '',
      imageUrl: question.imageUrl ?? null,
      correctIndex,
      o1: options[0]?.text ?? '',
      o2: options[1]?.text ?? '',
      o3: options[2]?.text ?? '',
      o4: options[3]?.text ?? '',
    });
    this.queueQuestionsPaneResize();
  }

  onQuestionRowClick(question: LibraryQuestionDto): void {
    this.startEditQuestion(question);
    if (!this.questionHasModerationIssue(question.id)) {
      if (this.expandedQuestionIssueId != null) {
        this.expandedQuestionIssueId = null;
        this.queueQuestionsPaneResize();
      }
      return;
    }
    this.toggleQuestionIssuePanel(question.id);
  }

  prevQuestionPage(): void {
    if (this.questionPageIndex <= 0) return;
    this.questionPageIndex -= 1;
  }

  prevMinePage(): void {
    const page = this.safeMinePageIndex;
    if (page <= 0) return;
    this.minePageIndex = page - 1;
  }

  nextQuestionPage(): void {
    const maxPage = this.questionTotalPages - 1;
    if (this.questionPageIndex >= maxPage) return;
    this.questionPageIndex += 1;
  }

  nextMinePage(): void {
    const page = this.safeMinePageIndex;
    const maxPage = this.mineTotalPages - 1;
    if (page >= maxPage) return;
    this.minePageIndex = page + 1;
  }

  setQuestionPage(page: number): void {
    this.questionPageIndex = Math.min(Math.max(0, page), this.questionTotalPages - 1);
    this.queueQuestionsPaneResize();
  }

  resetQuestionPage(): void {
    this.questionPageIndex = 0;
    this.queueQuestionsPaneResize();
  }

  resetMinePage(): void {
    this.minePageIndex = 0;
  }

  trackByQuestionId(_: number, question: LibraryQuestionDto): number {
    return question.id;
  }

  get safeMinePageIndex(): number {
    return Math.min(this.minePageIndex, this.mineTotalPages - 1);
  }

  saveQuestion(): void {
    const quizId = this.selectedQuiz?.id;
    if (!quizId) return;
    if (this.selectedQuestionId == null && !this.canAddQuestion) {
      this.error = `Question limit reached (${this.policy.maxQuestionsPerQuiz}).`;
      return;
    }
    if (this.questionForm.invalid) {
      this.questionForm.markAllAsTouched();
      return;
    }
    if (this.savingQuestion) return;

    const options = this.buildOptionsPayload();
    if (!this.optionsHaveContent(options)) {
      this.error = 'Each answer option must have text.';
      return;
    }

    this.savingQuestion = true;
    this.error = null;
    const prompt = this.questionForm.controls.prompt.value;
    const imageUrl = this.questionForm.controls.imageUrl.value || null;

    if (this.selectedQuestionId != null) {
      const source = this.selectedQuestion;
      if (!source) {
        this.savingQuestion = false;
        this.error = 'Question not found.';
        return;
      }
      const existing = [...(source.options ?? [])].sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0));
      this.api.updateQuestion(quizId, this.selectedQuestionId, {
        prompt,
        imageUrl,
        options: options.map((opt, idx) => ({
          id: existing[idx]?.id ?? 0,
          text: opt.text,
          imageUrl: null,
          correct: opt.correct,
        })),
      }).subscribe({
        next: () => {
          this.savingQuestion = false;
          this.toast.success('Question updated.', { title: 'Library' });
          this.openEdit(quizId);
          this.editorTab = 'questions';
        },
        error: (err) => {
          this.savingQuestion = false;
          this.error = apiErrorMessage(err, 'Failed to update question');
        },
      });
      return;
    }

    this.api.addQuestion(quizId, { prompt, imageUrl, options }).subscribe({
      next: () => {
        this.savingQuestion = false;
        this.toast.success('Question added.', { title: 'Library' });
        this.openEdit(quizId);
        this.editorTab = 'questions';
      },
      error: (err) => {
        this.savingQuestion = false;
        this.error = apiErrorMessage(err, 'Failed to add question');
      },
    });
  }

  deleteQuestion(question: LibraryQuestionDto): void {
    const quizId = this.selectedQuiz?.id;
    if (!quizId || this.deletingQuestion) return;
    if (!confirm('Delete this question?')) return;

    this.deletingQuestion = true;
    this.error = null;
    this.api.deleteQuestion(quizId, question.id).subscribe({
      next: () => {
        this.deletingQuestion = false;
        this.toast.success('Question deleted.', { title: 'Library' });
        this.openEdit(quizId);
        this.editorTab = 'questions';
      },
      error: (err) => {
        this.deletingQuestion = false;
        this.error = apiErrorMessage(err, 'Failed to delete question');
      },
    });
  }

  uploadQuizImage(ev: Event): void {
    const input = ev.target as HTMLInputElement | null;
    const file = input?.files?.[0];
    if (!file) {
      this.resetFileInput(input);
      return;
    }
    const fileError = this.validateImageFile(file);
    if (fileError) {
      this.error = fileError;
      this.resetFileInput(input);
      return;
    }
    this.error = null;
    this.api.uploadImage(file).subscribe({
      next: (res) => {
        this.quizForm.controls.avatarImageUrl.setValue(res.url);
        this.resetFileInput(input);
      },
      error: (err) => {
        this.error = apiErrorMessage(err, 'Failed to upload image');
        this.resetFileInput(input);
      },
    });
  }

  uploadQuestionImage(ev: Event): void {
    const input = ev.target as HTMLInputElement | null;
    const file = input?.files?.[0];
    if (!file) {
      this.resetFileInput(input);
      return;
    }
    if (this.questionImageUploadBlocked) {
      this.error = `Question image limit reached (${this.questionImageUsageLabel}).`;
      this.resetFileInput(input);
      return;
    }
    const fileError = this.validateImageFile(file);
    if (fileError) {
      this.error = fileError;
      this.resetFileInput(input);
      return;
    }
    this.error = null;
    this.api.uploadImage(file).subscribe({
      next: (res) => {
        this.questionForm.controls.imageUrl.setValue(res.url);
        this.resetFileInput(input);
      },
      error: (err) => {
        this.error = apiErrorMessage(err, 'Failed to upload image');
        this.resetFileInput(input);
      },
    });
  }

  questionImageUrl(): string | null {
    const url = (this.questionForm.controls.imageUrl.value ?? '').trim();
    return url || null;
  }

  openImagePreview(url: string | null | undefined, alt: string): void {
    const value = (url ?? '').trim();
    if (!value) return;
    this.previewImageUrl = value;
    this.previewImageAlt = alt;
    this.previewImageName = alt;
    const dialog = this.imagePreviewDialogRef?.nativeElement;
    if (!dialog) return;
    if (!dialog.open) {
      dialog.showModal();
    }
  }

  closeImagePreview(): void {
    const dialog = this.imagePreviewDialogRef?.nativeElement;
    if (dialog?.open) {
      dialog.close();
      return;
    }
    this.resetImagePreviewState();
  }

  onImagePreviewDialogClosed(): void {
    this.resetImagePreviewState();
  }

  onImagePreviewDialogClick(ev: MouseEvent): void {
    const dialog = this.imagePreviewDialogRef?.nativeElement;
    if (!dialog) return;
    if (ev.target === dialog) {
      this.closeImagePreview();
    }
  }

  clearQuestionImage(): void {
    this.questionForm.controls.imageUrl.setValue(null);
    this.closeImagePreview();
  }

  avatarColorText(field: 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor'): string {
    const value = this.quizForm.controls[field].value ?? '';
    return this.normalizeHex(value, this.defaultAvatarColor(field));
  }

  onAvatarColorPicker(
    field: 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor',
    value: string
  ): void {
    this.quizForm.controls[field].setValue(this.normalizeHex(value, this.defaultAvatarColor(field)));
  }

  onAvatarColorTextInput(
    field: 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor',
    raw: string
  ): void {
    this.quizForm.controls[field].setValue(raw.trim().toUpperCase(), { emitEvent: false });
  }

  onAvatarColorTextBlur(field: 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor'): void {
    const current = this.quizForm.controls[field].value ?? '';
    this.quizForm.controls[field].setValue(this.normalizeHex(current, this.defaultAvatarColor(field)));
  }

  moderationLabel(status: QuizModerationStatus): string {
    if (status === 'PENDING') return 'Pending review';
    if (status === 'APPROVED') return 'Approved';
    if (status === 'REJECTED') return 'Rejected';
    return 'Private draft';
  }

  moderationClass(status: QuizModerationStatus): string {
    if (status === 'PENDING') return 'pending';
    if (status === 'APPROVED') return 'approved';
    if (status === 'REJECTED') return 'rejected';
    return 'draft';
  }

  moderationIcon(status: QuizModerationStatus): string {
    if (status === 'PENDING') return 'fa-regular fa-hourglass-half';
    if (status === 'APPROVED') return 'fa-regular fa-circle-check';
    if (status === 'REJECTED') return 'fa-solid fa-triangle-exclamation';
    return 'fa-regular fa-pen-to-square';
  }

  get showModerationBanner(): boolean {
    const sig = this.getModerationSignatureForQuiz(this.selectedQuiz);
    return !!sig && !this.dismissedModerationSignatures.has(sig);
  }

  showRowRejectedState(
    quiz: Pick<LibraryQuizListItemDto, 'id' | 'moderationStatus' | 'moderationReason' | 'moderationUpdatedAt'>
  ): boolean {
    if (quiz.moderationStatus !== 'REJECTED') return false;
    const sig = this.getModerationSignatureForQuiz(quiz);
    if (!sig) return false;
    return !this.dismissedModerationSignatures.has(sig);
  }

  dismissModerationBanner(): void {
    const sig = this.getModerationSignatureForQuiz(this.selectedQuiz);
    if (!sig) return;
    this.dismissedModerationSignatures.add(sig);
    this.persistDismissedModerationSignatures();
  }

  rowModerationTooltip(quiz: Pick<LibraryQuizListItemDto, 'moderationStatus' | 'moderationReason'>): string {
    if (quiz.moderationStatus === 'PENDING') {
      return 'Pending review';
    }
    if (quiz.moderationStatus === 'REJECTED') {
      const reason = (quiz.moderationReason ?? '').trim();
      return reason
        ? `Rejected by administrator. Admin message: ${reason}`
        : 'Rejected by administrator.';
    }
    return '';
  }

  titleInitial(title: string | null | undefined): string {
    const trimmed = String(title ?? '').trim();
    if (!trimmed) return 'U';

    const words = trimmed.split(/\s+/u).filter(Boolean);
    const pickFirstAlphaNum = (value: string): string => {
      const chars = Array.from(value);
      const first = chars.find((ch) => /[\p{L}\p{N}]/u.test(ch));
      return first ?? '';
    };

    let raw = '';
    if (words.length >= 2) {
      raw = `${pickFirstAlphaNum(words[0])}${pickFirstAlphaNum(words[words.length - 1])}`;
    } else {
      const chars = Array.from(words[0] ?? '').filter((ch) => /[\p{L}\p{N}]/u.test(ch));
      raw = chars.slice(0, 2).join('');
    }

    const initials = raw.toUpperCase().slice(0, 2);
    return initials || 'U';
  }

  quizAvatarStyle(q: {
    avatarImageUrl?: string | null;
    avatarBgStart?: string | null;
    avatarBgEnd?: string | null;
    avatarTextColor?: string | null;
  } | null | undefined): Record<string, string> {
    const img = (q?.avatarImageUrl ?? '').trim();
    const start = (q?.avatarBgStart ?? '').trim() || '#30D0FF';
    const end = (q?.avatarBgEnd ?? '').trim() || '#2F86FF';
    const text = (q?.avatarTextColor ?? '').trim() || '#0A0E1C';
    if (img) {
      return {
        'background-image': `url(${img})`,
        'background-size': 'cover',
        'background-position': 'center',
        color: text,
      };
    }
    return {
      'background-image': `linear-gradient(180deg, ${start}, ${end})`,
      color: text,
    };
  }

  private extractCategories(items: LibraryQuizListItemDto[]): string[] {
    const set = new Set<string>();
    for (const item of items) {
      const c = (item.categoryName ?? '').trim();
      if (c) set.add(c);
    }
    return Array.from(set).sort((a, b) => a.localeCompare(b));
  }

  private sortByTitle(items: LibraryQuizListItemDto[], mode: 'az' | 'za'): LibraryQuizListItemDto[] {
    const sorted = [...items].sort((a, b) => (a.title ?? '').localeCompare(b.title ?? ''));
    return mode === 'za' ? sorted.reverse() : sorted;
  }

  private resetQuizForm(): void {
    const defaultTime = this.normalizeTimeLimit(DEFAULT_TIME_LIMIT_SECONDS);
    const defaultQuestions = this.normalizeQuestionsPerGame(DEFAULT_QUESTIONS_PER_GAME);
    this.quizForm.reset({
      title: '',
      description: '',
      categoryName: '',
      avatarImageUrl: null,
      avatarBgStart: '#30D0FF',
      avatarBgEnd: '#2F86FF',
      avatarTextColor: '#0A0E1C',
      questionTimeLimitSeconds: defaultTime,
      questionsPerGame: defaultQuestions,
    });
  }

  private patchQuizForm(quiz: LibraryQuizDetailDto): void {
    this.quizForm.patchValue({
      title: quiz.title ?? '',
      description: quiz.description ?? '',
      categoryName: quiz.categoryName ?? '',
      avatarImageUrl: quiz.avatarImageUrl ?? null,
      avatarBgStart: quiz.avatarBgStart ?? '#30D0FF',
      avatarBgEnd: quiz.avatarBgEnd ?? '#2F86FF',
      avatarTextColor: quiz.avatarTextColor ?? '#0A0E1C',
      questionTimeLimitSeconds: this.normalizeTimeLimit(quiz.questionTimeLimitSeconds ?? DEFAULT_TIME_LIMIT_SECONDS),
      questionsPerGame: this.normalizeQuestionsPerGame(quiz.questionsPerGame ?? DEFAULT_QUESTIONS_PER_GAME),
    });
  }

  private syncMyQuizListItem(quiz: LibraryQuizDetailDto): void {
    const index = this.myQuizzes.findIndex((item) => item.id === quiz.id);
    if (index < 0) return;
    const current = this.myQuizzes[index];
    const next: LibraryQuizListItemDto = {
      ...current,
      title: quiz.title,
      description: quiz.description,
      categoryName: quiz.categoryName,
      avatarImageUrl: quiz.avatarImageUrl,
      avatarBgStart: quiz.avatarBgStart,
      avatarBgEnd: quiz.avatarBgEnd,
      avatarTextColor: quiz.avatarTextColor,
      questionTimeLimitSeconds: quiz.questionTimeLimitSeconds,
      questionsPerGame: quiz.questionsPerGame,
      status: quiz.status,
      moderationStatus: quiz.moderationStatus,
      moderationReason: quiz.moderationReason,
      moderationUpdatedAt: quiz.moderationUpdatedAt,
      moderationQuestionIssueCount: quiz.moderationQuestionIssues?.length ?? 0,
      favorite: quiz.favorite,
      questionCount: quiz.questions?.length ?? current.questionCount,
    };
    this.myQuizzes = [
      ...this.myQuizzes.slice(0, index),
      next,
      ...this.myQuizzes.slice(index + 1),
    ];
    this.ensureMineCategoryAvailable();
  }

  private resetQuestionForm(): void {
    this.closeImagePreview();
    this.questionForm.reset({
      prompt: '',
      imageUrl: null,
      correctIndex: 0,
      o1: '',
      o2: '',
      o3: '',
      o4: '',
    });
  }

  private resetImagePreviewState(): void {
    this.previewImageUrl = null;
    this.previewImageAlt = 'Image preview';
    this.previewImageName = '';
  }

  private getSubmitBlockedReason(item: Pick<LibraryQuizListItemDto, 'status' | 'moderationStatus' | 'questionCount'>): string | null {
    if (item.status === 'TRASHED') return 'Restore this quiz from trash before submitting.';
    if (item.moderationStatus === 'PENDING') return 'This quiz is already pending review.';
    if ((item.questionCount ?? 0) < this.policy.minQuestionsToSubmit) {
      return `To submit for review, add at least ${this.policy.minQuestionsToSubmit} questions.`;
    }
    if (this.policy.pendingCount >= this.policy.maxPendingSubmissions) {
      return `You have reached the pending submissions limit (${this.policy.maxPendingSubmissions}).`;
    }
    if (this.policy.publishedCount >= this.policy.maxPublishedQuizzes) {
      return `You have reached the published quizzes limit (${this.policy.maxPublishedQuizzes}).`;
    }
    return null;
  }

  private resetFileInput(input: HTMLInputElement | null): void {
    if (!input) return;
    input.value = '';
  }

  private validateImageFile(file: File): string | null {
    const maxBytes = this.policy.maxUploadBytes ?? DEFAULT_POLICY.maxUploadBytes;
    if (file.size > maxBytes) {
      return `Image is too large. Max ${this.maxUploadMbLabel}.`;
    }
    const type = (file.type ?? '').trim().toLowerCase();
    if (!this.allowedUploadMimeTypes.includes(type)) {
      return 'Unsupported image format.';
    }
    return null;
  }

  private buildOptionsPayload(): Array<{ text: string | null; imageUrl: string | null; correct: boolean }> {
    const correct = this.questionForm.controls.correctIndex.value;
    return [1, 2, 3, 4].map((idx) => {
      const textKey = (`o${idx}`) as 'o1' | 'o2' | 'o3' | 'o4';
      const text = (this.questionForm.controls[textKey].value ?? '').trim();
      return {
        text: text || null,
        imageUrl: null,
        correct: correct === (idx - 1),
      };
    });
  }

  private optionsHaveContent(options: Array<{ text: string | null; imageUrl: string | null }>): boolean {
    return options.every((o) => !!(o.text ?? '').trim());
  }

  private hasImageUrl(value: string | null | undefined): boolean {
    return !!(value ?? '').trim();
  }

  private normalizeNumber(value: number | null | undefined): number | null {
    const n = Number(value);
    if (!Number.isFinite(n)) return null;
    if (n <= 0) return null;
    return Math.trunc(n);
  }

  private normalizeTimeLimit(value: number | null | undefined): number {
    const n = Number(value);
    if (!Number.isFinite(n)) return DEFAULT_TIME_LIMIT_SECONDS;
    const min = this.policy.minQuestionTimeLimitSeconds ?? MIN_TIME_LIMIT_SECONDS;
    const max = this.policy.maxQuestionTimeLimitSeconds ?? DEFAULT_POLICY.maxQuestionTimeLimitSeconds;
    if (n < min) return min;
    if (n > max) return max;
    return Math.trunc(n);
  }

  private normalizeQuestionsPerGame(value: number | null | undefined): number {
    const n = Number(value);
    if (!Number.isFinite(n)) return DEFAULT_QUESTIONS_PER_GAME;
    if (n < MIN_QUESTIONS_PER_GAME) return MIN_QUESTIONS_PER_GAME;
    const max = this.policy.maxQuestionsPerGame ?? DEFAULT_POLICY.maxQuestionsPerGame;
    if (n > max) return max;
    return Math.trunc(n);
  }

  private defaultAvatarColor(field: 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor'): string {
    if (field === 'avatarBgStart') return '#30D0FF';
    if (field === 'avatarBgEnd') return '#2F86FF';
    return '#0A0E1C';
  }

  private normalizeHex(raw: string | null | undefined, fallback: string): string {
    const value = (raw ?? '').trim().toUpperCase();
    const body = value.startsWith('#') ? value.slice(1) : value;
    if (/^[0-9A-F]{6}$/.test(body)) return `#${body}`;
    if (/^[0-9A-F]{3}$/.test(body)) {
      return `#${body[0]}${body[0]}${body[1]}${body[1]}${body[2]}${body[2]}`;
    }
    return fallback;
  }

  private getModerationSignatureForQuiz(
    quiz: Pick<LibraryQuizDetailDto, 'id' | 'moderationStatus' | 'moderationReason' | 'moderationUpdatedAt'> | null | undefined
  ): string | null {
    if (!quiz) return null;
    if (quiz.moderationStatus !== 'PENDING' && quiz.moderationStatus !== 'REJECTED') return null;
    const reason = (quiz.moderationReason ?? '').trim();
    const updatedAt = (quiz.moderationUpdatedAt ?? '').trim();
    return `${quiz.id}:${quiz.moderationStatus}:${reason}:${updatedAt}`;
  }

  questionModerationIssueMessage(questionId: number): string | null {
    if (!this.showModerationBanner) return null;
    const quiz = this.selectedQuiz;
    if (!quiz || quiz.moderationStatus !== 'REJECTED') return null;
    const issue = (quiz.moderationQuestionIssues ?? []).find((item) => item.questionId === questionId);
    const message = (issue?.message ?? '').trim();
    return message || null;
  }

  questionHasModerationIssue(questionId: number): boolean {
    return !!this.questionModerationIssueMessage(questionId);
  }

  isQuestionIssueExpanded(questionId: number): boolean {
    return this.expandedQuestionIssueId === questionId;
  }

  toggleQuestionIssuePanel(questionId: number): void {
    if (!this.questionHasModerationIssue(questionId)) return;
    this.expandedQuestionIssueId = this.expandedQuestionIssueId === questionId ? null : questionId;
    this.queueQuestionsPaneResize();
  }

  private consumePendingOpenQuizRequest(): void {
    const request = this.pendingOpenQuizRequest;
    if (!request || !this.hasLoadedLibrary || this.loadingQuiz) return;

    this.pendingOpenQuizRequest = null;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        openQuiz: null,
        moderationTab: null,
        reopenModeration: null,
      },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });

    const found = this.myQuizzes.some((quiz) => quiz.id === request.quizId);
    if (!found) {
      this.handleMissingModerationQuizTarget();
      return;
    }

    this.openEdit(request.quizId, request.tab, {
      reopenModerationBanner: request.reopen,
      fromModerationLink: true,
    });
  }

  private handleMissingModerationQuizTarget(): void {
    this.toast.warning('This quiz is no longer available.', {
      title: 'Library',
      dedupeKey: 'library:moderation:quiz-missing',
    });
    void this.router.navigate(['/']);
  }

  private isNotFoundLikeError(err: unknown): boolean {
    const rawStatus = (err as { status?: unknown } | null)?.status;
    const status = Number(rawStatus);
    return status === 404 || status === 410;
  }

  private loadDismissedModerationSignatures(): void {
    if (typeof window === 'undefined') return;
    try {
      const raw = window.localStorage.getItem(LibraryComponent.MODERATION_BANNER_DISMISSED_STORAGE_KEY);
      if (!raw) return;
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) return;
      this.dismissedModerationSignatures = new Set(
        parsed.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
      );
    } catch {
      this.dismissedModerationSignatures = new Set<string>();
    }
  }

  private persistDismissedModerationSignatures(): void {
    if (typeof window === 'undefined') return;
    try {
      window.localStorage.setItem(
        LibraryComponent.MODERATION_BANNER_DISMISSED_STORAGE_KEY,
        JSON.stringify(Array.from(this.dismissedModerationSignatures))
      );
    } catch {
      // Ignore storage write errors.
    }
  }

  private rebindAvatarObserver(): void {
    this.avatarResizeObserver?.disconnect();
    this.avatarResizeObserver = null;

    if (typeof ResizeObserver === 'undefined') return;
    if (!this.leftDetailsRef || !this.avatarPanelRef || !this.avatarEditorRef) return;

    this.avatarResizeObserver = new ResizeObserver(() => {
      this.queueAvatarPreviewResize();
    });
    this.avatarResizeObserver.observe(this.leftDetailsRef.nativeElement);
    this.avatarResizeObserver.observe(this.avatarPanelRef.nativeElement);
    this.avatarResizeObserver.observe(this.avatarEditorRef.nativeElement);
  }

  private queueAvatarPreviewResize(): void {
    if (this.avatarPreviewRafId != null) return;
    this.avatarPreviewRafId = requestAnimationFrame(() => {
      this.avatarPreviewRafId = null;
      this.syncAvatarPreviewHeight();
    });
  }

  private syncAvatarPreviewHeight(): void {
    if (this.tab !== 'editor' || this.editorTab !== 'details') {
      this.avatarPreviewHeightPx = null;
      return;
    }

    const left = this.leftDetailsRef?.nativeElement;
    const panel = this.avatarPanelRef?.nativeElement;
    const editor = this.avatarEditorRef?.nativeElement;
    if (!left || !panel || !editor) {
      this.avatarPreviewHeightPx = null;
      return;
    }

    if (window.innerWidth <= 1260) {
      this.avatarPreviewHeightPx = null;
      return;
    }

    const leftHeight = left.getBoundingClientRect().height;
    const editorHeight = editor.getBoundingClientRect().height;
    const panelStyle = window.getComputedStyle(panel);
    const rawGap = panelStyle.rowGap || panelStyle.gap || '0';
    const gap = Number.parseFloat(rawGap);
    const safeGap = Number.isFinite(gap) ? gap : 0;
    const target = Math.floor(leftHeight - editorHeight - safeGap);
    this.avatarPreviewHeightPx = Math.max(64, target);
  }

  private rebindQuestionsPaneObserver(): void {
    this.questionsPaneResizeObserver?.disconnect();
    this.questionsPaneResizeObserver = null;

    if (typeof ResizeObserver === 'undefined') return;
    if (!this.questionsRightPaneRef && !this.detailsPaneRef && !this.detailsFormRef) return;

    this.questionsPaneResizeObserver = new ResizeObserver(() => {
      this.queueQuestionsPaneResize();
    });
    if (this.questionsRightPaneRef) {
      this.questionsPaneResizeObserver.observe(this.questionsRightPaneRef.nativeElement);
    }
    if (this.detailsPaneRef) {
      this.questionsPaneResizeObserver.observe(this.detailsPaneRef.nativeElement);
    }
    if (this.detailsFormRef) {
      this.questionsPaneResizeObserver.observe(this.detailsFormRef.nativeElement);
    }
  }

  private queueQuestionsPaneResize(): void {
    if (this.questionsPaneRafId != null) return;
    this.questionsPaneRafId = requestAnimationFrame(() => {
      this.questionsPaneRafId = null;
      this.syncQuestionsPaneHeight();
    });
  }

  private syncQuestionsPaneHeight(): void {
    if (this.tab !== 'editor') {
      return;
    }
    if (window.innerWidth <= 1260) {
      this.questionPaneHeightPx = null;
      this.detailsPaneHeightPx = null;
      return;
    }

    let target: number | null = null;

    if (this.editorTab === 'details') {
      const details = this.detailsPaneRef?.nativeElement;
      if (!details) return;
      const detailsHeight = this.measureDetailsContentHeight(details);
      if (!Number.isFinite(detailsHeight) || detailsHeight <= 0) return;
      this.detailsPaneHeightPx = detailsHeight;
      target = detailsHeight;
    } else {
      const right = this.questionsRightPaneRef?.nativeElement;
      if (!right) {
        // Keep last value until target pane is measurable.
        return;
      }
      const rightHeight = Math.floor(right.getBoundingClientRect().height);
      if (!Number.isFinite(rightHeight) || rightHeight <= 0) return;
      target = rightHeight;
    }

    this.applyQuestionPaneHeight(target);
  }

  private measureDetailsContentHeight(details: HTMLElement): number {
    const detailsStyle = window.getComputedStyle(details);
    const detailsPaddingTop = Number.parseFloat(detailsStyle.paddingTop) || 0;
    const detailsPaddingBottom = Number.parseFloat(detailsStyle.paddingBottom) || 0;
    const detailsBorderTop = Number.parseFloat(detailsStyle.borderTopWidth) || 0;
    const detailsBorderBottom = Number.parseFloat(detailsStyle.borderBottomWidth) || 0;

    let total = detailsPaddingTop + detailsPaddingBottom + detailsBorderTop + detailsBorderBottom;
    const children = Array.from(details.children) as HTMLElement[];
    for (const child of children) {
      const rect = child.getBoundingClientRect();
      if (!Number.isFinite(rect.height) || rect.height <= 0) continue;
      const style = window.getComputedStyle(child);
      const marginTop = Number.parseFloat(style.marginTop) || 0;
      const marginBottom = Number.parseFloat(style.marginBottom) || 0;
      total += rect.height + marginTop + marginBottom;
    }

    // Small safety buffer to avoid a 1-2px scrollbar due to subpixel rounding.
    return Math.ceil(total + 2);
  }

  private primeQuestionsPaneHeight(): void {
    if (window.innerWidth <= 1260) {
      this.questionPaneHeightPx = null;
      return;
    }
    if (this.questionPaneHeightPx != null) return;

    const leftHeight = Math.floor(this.leftDetailsRef?.nativeElement.getBoundingClientRect().height ?? 0);
    const viewportFallback = Math.floor(Math.max(420, Math.min(window.innerHeight * 0.58, 760)));
    const target = leftHeight > 0 ? leftHeight : viewportFallback;
    this.applyQuestionPaneHeight(target);
  }

  private applyQuestionPaneHeight(rawHeight: number): void {
    const next = Math.max(280, Math.round(rawHeight));
    const current = this.questionPaneHeightPx;
    if (current == null) {
      this.questionPaneHeightPx = next;
      return;
    }

    const delta = next - current;
    if (delta < 0 && Math.abs(delta) <= LibraryComponent.PANE_HEIGHT_DOWN_EPSILON_PX) {
      return;
    }
    if (delta > 0 && delta <= LibraryComponent.PANE_HEIGHT_UP_EPSILON_PX) {
      return;
    }
    this.questionPaneHeightPx = next;
  }
}
