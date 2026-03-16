import { CommonModule } from '@angular/common';
import { AfterViewInit, Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import {
  AdminOpenTdbCategoryDto,
  AdminQuestionGenerationDifficulty,
  AdminQuestionGenerationLanguage,
  AdminQuestionDto,
  AdminQuizApi,
  AdminQuizDetailDto,
  AdminQuizListItemDto,
  QuizStatus,
} from '../../core/api/admin-quiz.api';
import { ToastService } from '../../core/ui/toast.service';

const MIN_QUESTION_TIME_LIMIT_SECONDS = 5;
const MAX_QUESTION_TIME_LIMIT_SECONDS = 600;
const DEFAULT_QUESTION_TIME_LIMIT_SECONDS = 15;
const MIN_QUESTIONS_PER_GAME = 1;
const MAX_QUESTIONS_PER_GAME = 10000;
const DEFAULT_QUESTIONS_PER_GAME = 7;
const QUESTION_PAGE_SIZE = 25;
const BYTES_PER_MB = 1024 * 1024;
const ADMIN_MAX_UPLOAD_BYTES = 2 * BYTES_PER_MB;
const ADMIN_ALLOWED_IMAGE_MIME_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
const MIN_GENERATE_QUESTION_COUNT = 1;
const MAX_GENERATE_QUESTION_COUNT = 500;
const DEFAULT_GENERATE_QUESTION_COUNT = 5;
const GENERATION_SOURCE_MAX_FILE_COUNT = 10;
const GENERATION_SOURCE_MAX_FILE_BYTES = 10 * BYTES_PER_MB;
const GENERATION_SOURCE_MAX_TOTAL_BYTES = 20 * BYTES_PER_MB;
const GENERATION_SOURCE_ALLOWED_EXTENSIONS = ['txt', 'pdf', 'doc', 'docx'];

type QuestionGenerationSourceMode = 'AI' | 'OPEN_TDB';
type OpenMenuId =
  | 'category'
  | 'sort'
  | 'pageSize'
  | 'questionPageSize'
  | 'generateDifficulty'
  | 'generateLanguage'
  | 'generateOpenTdbCategory'
  | 'generateOpenTdbLanguage';

function questionTimeLimitValidator(control: AbstractControl): ValidationErrors | null {
  const v = control.value;
  if (v == null || v === '') return null;

  const n = Number(v);
  if (!Number.isFinite(n)) return { timeLimit: true };
  if (n <= 0) return { timeLimitRange: true };
  if (!Number.isInteger(n)) return { timeLimit: true };
  if (n < MIN_QUESTION_TIME_LIMIT_SECONDS || n > MAX_QUESTION_TIME_LIMIT_SECONDS) {
    return { timeLimitRange: true };
  }
  return null;
}

function questionsPerGameValidator(control: AbstractControl): ValidationErrors | null {
  const v = control.value;
  if (v == null || v === '') return null;

  const n = Number(v);
  if (!Number.isFinite(n)) return { questionsPerGame: true };
  if (n <= 0) return { questionsPerGameRange: true };
  if (!Number.isInteger(n)) return { questionsPerGame: true };
  if (n < MIN_QUESTIONS_PER_GAME || n > MAX_QUESTIONS_PER_GAME) {
    return { questionsPerGameRange: true };
  }
  return null;
}

@Component({
  selector: 'app-admin-quiz',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './admin-quiz.component.html',
  styleUrl: './admin-quiz.component.scss',
})
export class AdminQuizComponent implements OnInit, AfterViewInit, OnDestroy {
  private static readonly QUESTIONS_TOTAL_DISPLAY_LIMIT = 50;
  private static readonly MAIN_TAB_ORDER: Record<'manage' | 'create', number> = {
    manage: 0,
    create: 1,
  };
  private static readonly EDITOR_TAB_ORDER: Record<'details' | 'questions', number> = {
    details: 0,
    questions: 1,
  };
  private static readonly STATUS_TAB_ORDER: Record<QuizStatus, number> = {
    ACTIVE: 0,
    DRAFT: 1,
    TRASHED: 2,
  };
  private static readonly PANE_HEIGHT_DOWN_EPSILON_PX = 10;
  private static readonly PANE_HEIGHT_UP_EPSILON_PX = 0;

  tab: 'manage' | 'create' = 'manage';
  tabTransition: 'forward' | 'backward' = 'forward';
  private tabTransitionFlip = false;
  editorTab: 'details' | 'questions' = 'details';
  editorTabTransition: 'forward' | 'backward' = 'forward';
  private editorTabTransitionFlip = false;
  statusTabTransition: 'forward' | 'backward' = 'forward';
  private statusTabTransitionFlip = false;
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

  openMenu: OpenMenuId | null = null;
  previewImageUrl: string | null = null;
  previewImageAlt = 'Image preview';
  previewImageName = '';
  openQuizActionsId: number | null = null;
  quizActionsMenuStyle: Record<string, string> | null = null;
  private lastQuizActionsOpenedAtMs = 0;
  private _error: string | null = null;

  quizzes: AdminQuizListItemDto[] = [];
  selectedQuiz: AdminQuizDetailDto | null = null;
  selectedQuestionId: number | null = null;
  questionPanelMode: 'edit' | 'create' = 'create';
  lastEditingQuestionId: number | null = null;

  loadingList = false;
  loadingQuiz = false;
  savingQuiz = false;
  savingQuestion = false;
  deletingQuestion = false;
  deletingQuiz = false;
  creating = false;
  addingExistingQuestion = false;
  generatingQuestions = false;

  readonly quizSearch = new FormControl('', { nonNullable: true });
  readonly quizCategory = new FormControl<string>('all', { nonNullable: true });
  readonly quizStatusTab = new FormControl<QuizStatus>('ACTIVE', { nonNullable: true });
  readonly questionSearch = new FormControl('', { nonNullable: true });
  readonly pageSize = new FormControl<number>(8, { nonNullable: true });
  readonly quizSort = new FormControl<'newest' | 'oldest' | 'name_az' | 'name_za'>(
    'newest',
    { nonNullable: true }
  );
  quizPageIndex = 0;
  questionPageIndex = 0;
  readonly questionPageSize = new FormControl<number>(QUESTION_PAGE_SIZE, { nonNullable: true });
  readonly questionPageSizeOptions: ReadonlyArray<number> = [10, 25, 50, 100];
  private questionSearchSub?: Subscription;
  readonly generationDifficultyOptions: ReadonlyArray<{ value: AdminQuestionGenerationDifficulty; label: string }> = [
    { value: 'MIXED', label: 'Mixed' },
    { value: 'EASY', label: 'Easy' },
    { value: 'MEDIUM', label: 'Medium' },
    { value: 'HARD', label: 'Hard' },
  ];
  readonly generationLanguageOptions: ReadonlyArray<{ value: AdminQuestionGenerationLanguage; label: string }> = [
    { value: 'PL', label: 'Polish' },
    { value: 'EN', label: 'English' },
  ];
  readonly generationSourceOptions: ReadonlyArray<{ value: QuestionGenerationSourceMode; label: string }> = [
    { value: 'AI', label: 'AI (topic/files)' },
    { value: 'OPEN_TDB', label: 'OpenTDB' },
  ];
  openTdbCategories: AdminOpenTdbCategoryDto[] = [];
  loadingOpenTdbCategories = false;
  generationSourceFiles: File[] = [];

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
  @ViewChild('generateQuestionsDialog')
  generateQuestionsDialogRef: ElementRef<HTMLDialogElement> | null = null;

  readonly quizSortOptions: ReadonlyArray<{
    value: 'newest' | 'oldest' | 'name_az' | 'name_za';
    label: string;
  }> = [
    { value: 'newest', label: 'Newest first' },
    { value: 'oldest', label: 'Oldest first' },
    { value: 'name_az', label: 'Name A-Z' },
    { value: 'name_za', label: 'Name Z-A' },
  ];

  readonly quizForm = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(120)] }),
    description: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
    categoryName: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(64)] }),
    includeInRanking: new FormControl<boolean>(false, { nonNullable: true }),
    xpEnabled: new FormControl<boolean>(true, { nonNullable: true }),
    questionTimeLimitSeconds: new FormControl<number | null>(DEFAULT_QUESTION_TIME_LIMIT_SECONDS, { validators: [questionTimeLimitValidator] }),
    questionsPerGame: new FormControl<number | null>(DEFAULT_QUESTIONS_PER_GAME, { validators: [questionsPerGameValidator] }),
    avatarImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    avatarBgStart: new FormControl<string>('#30D0FF', { nonNullable: true, validators: [Validators.maxLength(32)] }),
    avatarBgEnd: new FormControl<string>('#2F86FF', { nonNullable: true, validators: [Validators.maxLength(32)] }),
    avatarTextColor: new FormControl<string>('#0A0E1C', { nonNullable: true, validators: [Validators.maxLength(32)] }),
  });

  readonly addExistingQuestionForm = new FormGroup({
    prompt: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(500)] }),
    imageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    correctIndex: new FormControl(0, { nonNullable: true }),
    o1: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o1ImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    o2: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o2ImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    o3: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o3ImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    o4: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o4ImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
  });

  readonly editQuizForm = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(120)] }),
    description: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
    categoryName: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(64)] }),
    includeInRanking: new FormControl<boolean>(false, { nonNullable: true }),
    xpEnabled: new FormControl<boolean>(true, { nonNullable: true }),
    questionTimeLimitSeconds: new FormControl<number | null>(DEFAULT_QUESTION_TIME_LIMIT_SECONDS, { validators: [questionTimeLimitValidator] }),
    questionsPerGame: new FormControl<number | null>(DEFAULT_QUESTIONS_PER_GAME, { validators: [questionsPerGameValidator] }),
    avatarImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    avatarBgStart: new FormControl<string>('#30D0FF', { nonNullable: true, validators: [Validators.maxLength(32)] }),
    avatarBgEnd: new FormControl<string>('#2F86FF', { nonNullable: true, validators: [Validators.maxLength(32)] }),
    avatarTextColor: new FormControl<string>('#0A0E1C', { nonNullable: true, validators: [Validators.maxLength(32)] }),
  });

  readonly editQuestionForm = new FormGroup({
    prompt: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(500)] }),
    imageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    correctIndex: new FormControl(0, { nonNullable: true }),
    o1: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o1ImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    o2: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o2ImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    o3: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o3ImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    o4: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    o4ImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
  });

  readonly generateQuestionsForm = new FormGroup({
    sourceMode: new FormControl<QuestionGenerationSourceMode>('AI', { nonNullable: true }),
    topic: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(160)] }),
    categoryHint: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(64)] }),
    instructions: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(1200)] }),
    questionCount: new FormControl<number>(DEFAULT_GENERATE_QUESTION_COUNT, {
      nonNullable: true,
      validators: [Validators.min(MIN_GENERATE_QUESTION_COUNT), Validators.max(MAX_GENERATE_QUESTION_COUNT)],
    }),
    difficulty: new FormControl<AdminQuestionGenerationDifficulty>('MIXED', { nonNullable: true }),
    language: new FormControl<AdminQuestionGenerationLanguage>('PL', { nonNullable: true }),
    openTdbCategoryId: new FormControl<number | null>(null),
    openTdbLanguage: new FormControl<AdminQuestionGenerationLanguage>('EN', { nonNullable: true }),
  });

  constructor(
    private readonly api: AdminQuizApi,
    private readonly toast: ToastService,
    private readonly router: Router
  ) {}

  handleBack(): void {
    if (this.selectedQuiz && this.tab === 'manage') {
      this.closeSelectedQuiz();
      return;
    }
    if (!this.selectedQuiz && this.tab === 'create') {
      this.setTab('manage');
      return;
    }
    void this.router.navigate(['/']);
  }

  get error(): string | null {
    return this._error;
  }

  set error(value: string | null) {
    this._error = value;
    if (!value) return;
    this.toast.error(value, { title: 'Admin', dedupeKey: `admin:error:${value}` });
  }

  ngOnInit(): void {
    this.loadList();
    this.syncAvatarColorDraft('create');
    this.syncAvatarColorDraft('edit');
    this.questionSearchSub = this.questionSearch.valueChanges.subscribe(() => {
      this.resetQuestionPage();
    });
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

  @HostListener('document:click')
  onDocumentClick(): void {
    this.openMenu = null;
    this.closeQuizActions();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    const dialog = this.imagePreviewDialogRef?.nativeElement;
    if (dialog?.open) {
      this.closeImagePreview();
      return;
    }
    if (this.generateQuestionsDialogRef?.nativeElement?.open) {
      return;
    }
    this.openMenu = null;
    this.closeQuizActions();
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    this.openMenu = null;
    this.closeQuizActions();
    this.queueAvatarPreviewResize();
    this.queueQuestionsPaneResize();
  }

  @HostListener('window:scroll')
  onWindowScroll(): void {
    if (this.openQuizActionsId != null && Date.now() - this.lastQuizActionsOpenedAtMs < 250) {
      return;
    }
    this.openMenu = null;
    this.closeQuizActions();
  }

  toggleMenu(menu: OpenMenuId, ev?: Event): void {
    ev?.stopPropagation();
    this.closeQuizActions();
    this.openMenu = this.openMenu === menu ? null : menu;
  }

  setQuizCategory(value: string, ev?: Event): void {
    ev?.stopPropagation();
    this.quizCategory.setValue(value);
    this.resetQuizPage();
    this.openMenu = null;
  }

  setQuizStatusTab(value: QuizStatus, ev?: Event): void {
    ev?.stopPropagation();
    const current = this.quizStatusTab.value;
    if (current !== value) {
      const currentOrder = AdminQuizComponent.STATUS_TAB_ORDER[current];
      const nextOrder = AdminQuizComponent.STATUS_TAB_ORDER[value];
      this.statusTabTransition = nextOrder < currentOrder ? 'backward' : 'forward';
      this.statusTabTransitionFlip = !this.statusTabTransitionFlip;
    }
    this.quizStatusTab.setValue(value);
    this.quizCategory.setValue('all');
    this.resetQuizPage();
    this.openMenu = null;
  }

  setQuizSort(
    value: 'newest' | 'oldest' | 'name_az' | 'name_za',
    ev?: Event
  ): void {
    ev?.stopPropagation();
    this.quizSort.setValue(value);
    this.resetQuizPage();
    this.openMenu = null;
  }

  setPageSize(size: number, ev?: Event): void {
    ev?.stopPropagation();
    this.pageSize.setValue(size);
    this.resetQuizPage();
    this.openMenu = null;
  }

  setQuestionPageSize(size: number, ev?: Event): void {
    ev?.stopPropagation();
    this.questionPageSize.setValue(size);
    this.openMenu = null;
  }

  get quizCategoryLabel(): string {
    const v = this.quizCategory.value;
    if (!v || v === 'all') return 'All categories';
    return v;
  }

  get quizSortLabel(): string {
    const v = this.quizSort.value;
    const found = this.quizSortOptions.find((x) => x.value === v);
    return found?.label ?? 'Sort';
  }

  get pageSizeLabel(): string {
    return `Show ${this.pageSize.value ?? 8}`;
  }

  get questionPageSizeLabel(): string {
    return `Show ${this.questionPageSize.value}`;
  }

  get generationDifficultyLabel(): string {
    const value = this.generateQuestionsForm.controls.difficulty.value;
    return this.generationDifficultyOptions.find((option) => option.value === value)?.label ?? 'Difficulty';
  }

  get generationLanguageLabel(): string {
    const value = this.generateQuestionsForm.controls.language.value;
    return this.generationLanguageOptions.find((option) => option.value === value)?.label ?? 'Language';
  }

  get generationOpenTdbLanguageLabel(): string {
    const value = this.generateQuestionsForm.controls.openTdbLanguage.value;
    return this.generationLanguageOptions.find((option) => option.value === value)?.label ?? 'Language';
  }

  get generationOpenTdbCategoryLabel(): string {
    const value = this.generateQuestionsForm.controls.openTdbCategoryId.value;
    if (value == null) return 'Any category';
    return this.openTdbCategories.find((category) => category.id === value)?.name ?? 'Any category';
  }

  setGenerationDifficulty(value: AdminQuestionGenerationDifficulty, ev?: Event): void {
    ev?.stopPropagation();
    this.generateQuestionsForm.controls.difficulty.setValue(value);
    this.openMenu = null;
  }

  setGenerationLanguage(value: AdminQuestionGenerationLanguage, ev?: Event): void {
    ev?.stopPropagation();
    this.generateQuestionsForm.controls.language.setValue(value);
    this.openMenu = null;
  }

  setGenerationOpenTdbLanguage(value: AdminQuestionGenerationLanguage, ev?: Event): void {
    ev?.stopPropagation();
    this.generateQuestionsForm.controls.openTdbLanguage.setValue(value);
    this.openMenu = null;
  }

  setGenerationOpenTdbCategory(value: number | null, ev?: Event): void {
    ev?.stopPropagation();
    this.generateQuestionsForm.controls.openTdbCategoryId.setValue(value);
    this.openMenu = null;
  }

  get maxUploadMbLabel(): string {
    const mb = Math.max(1, Math.round(ADMIN_MAX_UPLOAD_BYTES / BYTES_PER_MB));
    return `${mb}MB`;
  }

  get allowedUploadMimeTypes(): string[] {
    return ADMIN_ALLOWED_IMAGE_MIME_TYPES;
  }

  get generationSourceAllowedExtensionsLabel(): string {
    return GENERATION_SOURCE_ALLOWED_EXTENSIONS.map((ext) => `.${ext}`).join(', ');
  }

  get generationSourceMaxUploadMbLabel(): string {
    const mb = Math.max(1, Math.round(GENERATION_SOURCE_MAX_FILE_BYTES / BYTES_PER_MB));
    return `${mb}MB`;
  }

  get generationSourceMaxTotalUploadMbLabel(): string {
    const mb = Math.max(1, Math.round(GENERATION_SOURCE_MAX_TOTAL_BYTES / BYTES_PER_MB));
    return `${mb}MB`;
  }

  get tabTransitionClass(): string {
    return this.transitionClass(this.tabTransition, this.tabTransitionFlip);
  }

  get editorTabTransitionClass(): string {
    return this.transitionClass(this.editorTabTransition, this.editorTabTransitionFlip);
  }

  get statusTabTransitionClass(): string {
    return this.transitionClass(this.statusTabTransition, this.statusTabTransitionFlip);
  }

  setEditorTab(tab: 'details' | 'questions'): void {
    if (this.editorTab === tab) return;
    if (tab === 'questions') {
      this.primeQuestionsPaneHeight();
    }
    const currentOrder = AdminQuizComponent.EDITOR_TAB_ORDER[this.editorTab];
    const nextOrder = AdminQuizComponent.EDITOR_TAB_ORDER[tab];
    this.editorTabTransition = nextOrder < currentOrder ? 'backward' : 'forward';
    this.editorTabTransitionFlip = !this.editorTabTransitionFlip;
    this.editorTab = tab;
    if (tab === 'questions') {
      this.resetQuestionPage();
      this.selectInitialQuestionIfNeeded();
    }
    this.queueQuestionsPaneResize();
    this.queueAvatarPreviewResize();
  }

  resetQuizPage(): void {
    this.quizPageIndex = 0;
  }

  prevQuizPage(): void {
    this.quizPageIndex = Math.max(0, this.quizPageIndex - 1);
  }

  nextQuizPage(): void {
    this.quizPageIndex = Math.min(this.quizTotalPages - 1, this.quizPageIndex + 1);
  }

  setQuizPage(index: number): void {
    this.quizPageIndex = Math.min(Math.max(0, index), this.quizTotalPages - 1);
  }

  get quizTotalPages(): number {
    const size = Math.max(1, this.pageSize.value ?? 8);
    const total = this.filteredQuizzes.length;
    return Math.max(1, Math.ceil(total / size));
  }

  get pagedQuizzes(): AdminQuizListItemDto[] {
    const size = Math.max(1, this.pageSize.value ?? 8);
    const start = this.quizPageIndex * size;
    return this.filteredQuizzes.slice(start, start + size);
  }

  get openQuizActionsQuiz(): AdminQuizListItemDto | null {
    const id = this.openQuizActionsId;
    if (id == null) return null;
    return this.pagedQuizzes.find((q) => q.id === id) ?? this.quizzes.find((q) => q.id === id) ?? null;
  }

  get quizPageFrom(): number {
    const total = this.filteredQuizzes.length;
    if (!total) return 0;
    return this.quizPageIndex * (this.pageSize.value ?? 8) + 1;
  }

  get quizPageTo(): number {
    const total = this.filteredQuizzes.length;
    if (!total) return 0;
    return Math.min(total, (this.quizPageIndex + 1) * (this.pageSize.value ?? 8));
  }

  titleInitial(title: string | null | undefined): string {
    const t = (title ?? '').trim();
    if (!t) return '?';
    return t[0].toUpperCase();
  }

  get categories(): string[] {
    const status = this.quizStatusTab.value;
    const set = new Set<string>();
    for (const q of this.quizzes) {
      if (q.status !== status) continue;
      const c = (q.categoryName ?? '').trim();
      if (c) set.add(c);
    }
    return Array.from(set).sort((a, b) => a.localeCompare(b));
  }

  get filteredQuizzes(): AdminQuizListItemDto[] {
    const query = this.quizSearch.value.trim().toLowerCase();
    const category = this.quizCategory.value;
    const status = this.quizStatusTab.value;

    const filtered = this.quizzes.filter((q) => {
      if (q.status !== status) return false;
      const title = (q.title ?? '').toLowerCase();
      const description = (q.description ?? '').toLowerCase();
      const categoryLabel = (q.categoryName ?? '').toLowerCase();
      const cat = (q.categoryName ?? '').trim();

      const matchesQuery = !query || title.includes(query) || description.includes(query) || categoryLabel.includes(query);
      const matchesCategory = category === 'all' || cat === category;
      return matchesQuery && matchesCategory;
    });

    return this.sortQuizzes(filtered);
  }

  toggleQuizActions(id: number, ev?: Event): void {
    ev?.stopPropagation();
    if (this.openQuizActionsId === id) {
      this.closeQuizActions();
      return;
    }
    this.openMenu = null;
    this.openQuizActionsId = id;
    this.lastQuizActionsOpenedAtMs = Date.now();

    const quiz = this.pagedQuizzes.find((q) => q.id === id) ?? this.quizzes.find((q) => q.id === id) ?? null;
    const button = ev?.currentTarget instanceof HTMLElement ? ev.currentTarget : null;
    this.quizActionsMenuStyle = button ? this.computeQuizActionsMenuStyle(button, quiz) : null;
  }

  closeQuizActions(): void {
    this.openQuizActionsId = null;
    this.quizActionsMenuStyle = null;
  }

  private computeQuizActionsMenuStyle(
    button: HTMLElement,
    quiz: AdminQuizListItemDto | null
  ): Record<string, string> {
    const menuWidth = 210;
    const gap = 8;
    const viewportPad = 12;
    const estimatedMenuHeight = this.quizActionsMenuEstimatedHeight(quiz);

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

  private quizActionsMenuEstimatedHeight(quiz: AdminQuizListItemDto | null): number {
    if (!quiz) return 148;
    let items = 1; // Edit
    if (quiz.status === 'DRAFT') items += 1; // Publish
    if (quiz.status === 'ACTIVE') items += 1; // Disable
    if (quiz.status !== 'TRASHED') items += 1; // Move to trash
    if (quiz.status === 'TRASHED') items += 2; // Restore + Delete permanently
    return 16 + items * 40;
  }

  setQuizStatus(quizId: number, status: QuizStatus): void {
    if (this.deletingQuiz) return;
    this.error = null;

    if (status === 'TRASHED' && !confirm('Move this quiz to trash?')) return;

    this.deletingQuiz = true;
    this.api.setStatus(quizId, status).subscribe({
      next: (updated) => {
        this.deletingQuiz = false;
        if (this.selectedQuiz?.id === quizId) {
          this.selectedQuiz = { ...this.selectedQuiz, status: updated.status };
        }
        this.toast.success(this.quizStatusSuccessMessage(updated.status), { title: 'Admin' });
        this.loadList();
      },
      error: (err) => {
        this.deletingQuiz = false;
        this.error = apiErrorMessage(err, 'Failed to update quiz status');
      },
    });
  }

  purgeQuiz(quizId: number): void {
    if (this.deletingQuiz) return;
    this.error = null;

    if (!confirm('Permanently delete this quiz and all its questions/images?')) return;

    this.deletingQuiz = true;
    this.api.purgeQuiz(quizId).subscribe({
      next: () => {
        this.deletingQuiz = false;
        if (this.selectedQuiz?.id === quizId) {
          this.closeSelectedQuiz();
        }
        this.toast.success('Quiz deleted permanently.', { title: 'Admin' });
        this.loadList();
      },
      error: (err) => {
        this.deletingQuiz = false;
        this.error = apiErrorMessage(err, 'Failed to delete quiz permanently');
      },
    });
  }

  get filteredQuestions(): AdminQuestionDto[] {
    const quiz = this.selectedQuiz;
    if (!quiz) return [];

    const query = this.questionSearch.value.trim().toLowerCase();
    if (!query) return quiz.questions;

    return quiz.questions.filter((q) => (q.prompt ?? '').toLowerCase().includes(query));
  }

  get pagedFilteredQuestions(): AdminQuestionDto[] {
    const list = this.filteredQuestions;
    if (!list.length) return [];
    const page = Math.min(this.questionPageIndex, this.questionTotalPages - 1);
    const size = this.questionPageSize.value;
    const start = page * size;
    return list.slice(start, start + size);
  }

  get questionTotalPages(): number {
    const total = this.filteredQuestions.length;
    return Math.max(1, Math.ceil(total / this.questionPageSize.value));
  }

  get questionPageFrom(): number {
    const total = this.filteredQuestions.length;
    if (!total) return 0;
    const page = Math.min(this.questionPageIndex, this.questionTotalPages - 1);
    return page * this.questionPageSize.value + 1;
  }

  get questionPageTo(): number {
    const total = this.filteredQuestions.length;
    if (!total) return 0;
    return Math.min(total, this.questionPageFrom + this.questionPageSize.value - 1);
  }

  get questionTotalLimit(): number {
    const currentCount = this.selectedQuiz?.questions.length ?? 0;
    return Math.max(AdminQuizComponent.QUESTIONS_TOTAL_DISPLAY_LIMIT, currentCount);
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

  prevQuestionPage(): void {
    if (this.questionPageIndex <= 0) return;
    this.questionPageIndex -= 1;
  }

  nextQuestionPage(): void {
    const maxPage = this.questionTotalPages - 1;
    if (this.questionPageIndex >= maxPage) return;
    this.questionPageIndex += 1;
  }

  setQuestionPage(page: number): void {
    this.questionPageIndex = Math.min(Math.max(0, page), this.questionTotalPages - 1);
  }

  resetQuestionPage(): void {
    this.questionPageIndex = 0;
  }

  trackByQuestionId(_: number, q: AdminQuestionDto): number {
    return q.id;
  }

  openImagePreview(url: string | null | undefined, alt: string): void {
    const value = this.normalizeNullableUrl(url ?? null);
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

  onQuestionImageRowClick(mode: 'create' | 'edit', input: HTMLInputElement): void {
    const url = this.questionImageUrl(mode);
    if (url) {
      this.openImagePreview(url, 'Question image');
      return;
    }
    input.value = '';
    input.click();
  }

  onOptionImageRowClick(mode: 'create' | 'edit', index: number): void {
    this.openImagePreview(this.optionImageUrl(mode, index), 'Answer option image');
  }

  private sortQuizzes(list: AdminQuizListItemDto[]): AdminQuizListItemDto[] {
    const sort = this.quizSort.value;

    const byName = (a: AdminQuizListItemDto, b: AdminQuizListItemDto) =>
      (a.title ?? '').localeCompare(b.title ?? '', undefined, { sensitivity: 'base' });
    const byId = (a: AdminQuizListItemDto, b: AdminQuizListItemDto) => (a.id ?? 0) - (b.id ?? 0);

    const cmp = (() => {
      switch (sort) {
        case 'name_az':
          return byName;
        case 'name_za':
          return (a: AdminQuizListItemDto, b: AdminQuizListItemDto) => -byName(a, b);
        case 'oldest':
          return byId;
        case 'newest':
          return (a: AdminQuizListItemDto, b: AdminQuizListItemDto) => -byId(a, b);
        default:
          return (a: AdminQuizListItemDto, b: AdminQuizListItemDto) => -byId(a, b);
      }
    })();

    return list.slice().sort(cmp);
  }

  setTab(tab: 'manage' | 'create'): void {
    if (this.tab !== tab) {
      const currentOrder = AdminQuizComponent.MAIN_TAB_ORDER[this.tab];
      const nextOrder = AdminQuizComponent.MAIN_TAB_ORDER[tab];
      this.tabTransition = nextOrder < currentOrder ? 'backward' : 'forward';
      this.tabTransitionFlip = !this.tabTransitionFlip;
    }
    this.tab = tab;
    this.error = null;
    if (tab === 'create') {
      this.editorTab = 'details';
      this.closeGenerateQuestionsModal();
    }
    if (tab === 'manage') this.loadList();
    this.queueAvatarPreviewResize();
    this.queueQuestionsPaneResize();
  }

  loadList(): void {
    if (this.loadingList) return;
    this.loadingList = true;
    this.api.listQuizzes().subscribe({
      next: (list) => {
        this.loadingList = false;
        this.quizzes = list;
      },
      error: (err) => {
        this.loadingList = false;
        this.error = apiErrorMessage(err, 'Failed to load quizzes');
      },
    });
  }

  selectQuiz(id: number, openTab: 'details' | 'questions' | 'keep' = 'details'): void {
    this.error = null;
    this.loadingQuiz = true;
    this.closeGenerateQuestionsModal();
    this.selectedQuestionId = null;
    this.questionSearch.setValue('');
    this.resetQuestionPage();
    if (openTab !== 'keep') {
      this.editorTab = openTab;
    }
    this.questionPanelMode = 'create';
    this.lastEditingQuestionId = null;
    this.api.getQuiz(id).subscribe({
      next: (quiz) => {
        this.loadingQuiz = false;
        this.selectedQuiz = quiz;
        this.editQuizForm.setValue({
          title: quiz.title ?? '',
          description: quiz.description ?? '',
          categoryName: quiz.categoryName ?? '',
          includeInRanking: !!quiz.includeInRanking,
          xpEnabled: quiz.xpEnabled ?? true,
          questionTimeLimitSeconds: quiz.questionTimeLimitSeconds ?? DEFAULT_QUESTION_TIME_LIMIT_SECONDS,
          questionsPerGame: quiz.questionsPerGame ?? DEFAULT_QUESTIONS_PER_GAME,
          avatarImageUrl: quiz.avatarImageUrl ?? null,
          avatarBgStart: quiz.avatarBgStart ?? '#30D0FF',
          avatarBgEnd: quiz.avatarBgEnd ?? '#2F86FF',
          avatarTextColor: quiz.avatarTextColor ?? '#0A0E1C',
        });
        this.syncAvatarColorDraft('edit');
        this.addExistingQuestionForm.reset({
          prompt: '',
          imageUrl: null,
          correctIndex: 0,
          o1: '',
          o1ImageUrl: null,
          o2: '',
          o2ImageUrl: null,
          o3: '',
          o3ImageUrl: null,
          o4: '',
          o4ImageUrl: null,
        });
        if (this.editorTab === 'questions') {
          this.selectInitialQuestionIfNeeded();
          this.primeQuestionsPaneHeight();
        }
        this.queueAvatarPreviewResize();
        this.queueQuestionsPaneResize();
      },
      error: (err) => {
        this.loadingQuiz = false;
        this.error = apiErrorMessage(err, 'Failed to load quiz');
      },
    });
  }

  private selectInitialQuestionIfNeeded(): void {
    const quiz = this.selectedQuiz;
    if (!quiz) return;

    if (!quiz.questions.length) {
      this.questionPanelMode = 'create';
      this.selectedQuestionId = null;
      this.lastEditingQuestionId = null;
      return;
    }

    if (this.selectedQuestionId != null) {
      const selected = quiz.questions.find((q) => q.id === this.selectedQuestionId);
      if (selected) return;
    }

    // Keep explicit "add question" mode chosen by the user.
    if (this.questionPanelMode === 'create' && this.lastEditingQuestionId != null) return;

    const first = quiz.questions.slice().sort((a, b) => a.orderIndex - b.orderIndex)[0] ?? quiz.questions[0];
    if (first) {
      this.selectQuestion(first);
    }
  }

  closeSelectedQuiz(): void {
    this.closeGenerateQuestionsModal();
    this.selectedQuiz = null;
    this.selectedQuestionId = null;
    this.questionSearch.setValue('');
    this.resetQuestionPage();
    this.editorTab = 'details';
    this.questionPanelMode = 'create';
    this.lastEditingQuestionId = null;
    this.avatarPreviewHeightPx = null;
    this.questionPaneHeightPx = null;
  }

  saveQuiz(): void {
    if (!this.selectedQuiz) return;
    if (this.savingQuiz) return;
    if (this.editQuizForm.invalid) return;
    this.error = null;

    const title = this.editQuizForm.controls.title.value.trim();
    const description = this.editQuizForm.controls.description.value.trim();
    const categoryName = this.editQuizForm.controls.categoryName.value.trim();
    const includeInRanking = !!this.editQuizForm.controls.includeInRanking.value;
    const xpEnabled = !!this.editQuizForm.controls.xpEnabled.value;
    const questionTimeLimitSeconds = this.normalizeQuestionTimeLimit(this.editQuizForm.controls.questionTimeLimitSeconds.value);
    const questionsPerGame = this.normalizeQuestionsPerGame(this.editQuizForm.controls.questionsPerGame.value);
    const avatarImageUrl = this.normalizeNullableUrl(this.editQuizForm.controls.avatarImageUrl.value);
    const avatarBgStart = this.normalizeNullableColor(this.editQuizForm.controls.avatarBgStart.value);
    const avatarBgEnd = this.normalizeNullableColor(this.editQuizForm.controls.avatarBgEnd.value);
    const avatarTextColor = this.normalizeNullableColor(this.editQuizForm.controls.avatarTextColor.value);

    this.savingQuiz = true;
    this.api
      .updateQuiz(this.selectedQuiz.id, {
        title,
        description: description || null,
        categoryName: categoryName || null,
        includeInRanking,
        xpEnabled,
        questionTimeLimitSeconds,
        questionsPerGame,
        avatarImageUrl,
        avatarBgStart,
        avatarBgEnd,
        avatarTextColor,
      })
      .subscribe({
        next: (updated) => {
          this.savingQuiz = false;
          this.selectedQuiz = {
            ...this.selectedQuiz!,
            title: updated.title,
            description: updated.description,
            categoryName: updated.categoryName,
            avatarImageUrl: updated.avatarImageUrl,
            avatarBgStart: updated.avatarBgStart,
            avatarBgEnd: updated.avatarBgEnd,
            avatarTextColor: updated.avatarTextColor,
            gameMode: updated.gameMode,
            includeInRanking: updated.includeInRanking,
            xpEnabled: updated.xpEnabled,
            questionTimeLimitSeconds: updated.questionTimeLimitSeconds,
            questionsPerGame: updated.questionsPerGame,
            status: updated.status,
          };
          this.toast.success('Quiz updated.', { title: 'Admin' });
          this.loadList();
        },
        error: (err) => {
          this.savingQuiz = false;
          this.error = apiErrorMessage(err, 'Failed to save quiz');
        },
      });
  }

  selectQuestion(q: AdminQuestionDto): void {
    this.questionPanelMode = 'edit';
    this.selectedQuestionId = q.id;
    this.lastEditingQuestionId = q.id;
    const correctIndex = Math.max(
      0,
      q.options.findIndex((o) => o.correct)
    );
    const optsSorted = q.options
      .slice()
      .sort((a, b) => a.orderIndex - b.orderIndex)

    const texts = optsSorted.map((o) => o.text ?? '');
    const images = optsSorted.map((o) => o.imageUrl ?? null);

    this.editQuestionForm.setValue({
      prompt: q.prompt,
      imageUrl: q.imageUrl ?? null,
      correctIndex: correctIndex < 0 ? 0 : correctIndex,
      o1: texts[0] ?? '',
      o1ImageUrl: images[0] ?? null,
      o2: texts[1] ?? '',
      o2ImageUrl: images[1] ?? null,
      o3: texts[2] ?? '',
      o3ImageUrl: images[2] ?? null,
      o4: texts[3] ?? '',
      o4ImageUrl: images[3] ?? null,
    });
  }

  onTimeLimitBlur(mode: 'create' | 'edit'): void {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    const v = form.controls.questionTimeLimitSeconds.value;
    if (v == null || !Number.isFinite(Number(v)) || Number(v) <= 0) {
      form.controls.questionTimeLimitSeconds.setValue(DEFAULT_QUESTION_TIME_LIMIT_SECONDS);
    }
  }

  onQuestionsPerGameBlur(mode: 'create' | 'edit'): void {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    const v = form.controls.questionsPerGame.value;
    if (v == null || !Number.isFinite(Number(v)) || Number(v) <= 0) {
      form.controls.questionsPerGame.setValue(DEFAULT_QUESTIONS_PER_GAME);
    }
  }

  private normalizeQuestionTimeLimit(raw: number | null): number | null {
    if (raw == null) return DEFAULT_QUESTION_TIME_LIMIT_SECONDS;

    const n = Number(raw);
    if (!Number.isFinite(n)) return null;
    if (n <= 0) return DEFAULT_QUESTION_TIME_LIMIT_SECONDS;
    return Math.trunc(n);
  }

  private normalizeQuestionsPerGame(raw: number | null): number | null {
    if (raw == null) return DEFAULT_QUESTIONS_PER_GAME;

    const n = Number(raw);
    if (!Number.isFinite(n)) return null;
    if (n <= 0) return DEFAULT_QUESTIONS_PER_GAME;
    return Math.trunc(n);
  }

  startAddQuestion(): void {
    if (!this.selectedQuiz) return;
    this.error = null;
    this.questionPanelMode = 'create';
    this.selectedQuestionId = null;
    this.addExistingQuestionForm.reset({
      prompt: '',
      imageUrl: null,
      correctIndex: 0,
      o1: '',
      o1ImageUrl: null,
      o2: '',
      o2ImageUrl: null,
      o3: '',
      o3ImageUrl: null,
      o4: '',
      o4ImageUrl: null,
    });
  }

  cancelAddQuestion(): void {
    if (!this.selectedQuiz) return;
    this.error = null;

    const previousId = this.lastEditingQuestionId;
    if (previousId == null) {
      this.questionPanelMode = 'create';
      this.selectedQuestionId = null;
      return;
    }

    const q = this.selectedQuiz.questions.find((x) => x.id === previousId);
    if (!q) {
      this.questionPanelMode = 'create';
      this.selectedQuestionId = null;
      return;
    }

    this.selectQuestion(q);
  }

  openGenerateQuestionsModal(): void {
    const quiz = this.selectedQuiz;
    if (!quiz) return;
    this.error = null;
    this.openMenu = null;
    this.closeQuizActions();

    const topic = this.generateQuestionsForm.controls.topic.value.trim();
    if (!topic) {
      this.generateQuestionsForm.controls.topic.setValue((quiz.title ?? '').trim());
    }
    const categoryHint = this.generateQuestionsForm.controls.categoryHint.value.trim();
    if (!categoryHint) {
      this.generateQuestionsForm.controls.categoryHint.setValue((quiz.categoryName ?? '').trim());
    }
    if (this.generateQuestionsForm.controls.sourceMode.value === 'OPEN_TDB') {
      this.loadOpenTdbCategories();
    }

    const dialog = this.generateQuestionsDialogRef?.nativeElement;
    if (!dialog || dialog.open) return;
    dialog.showModal();
  }

  closeGenerateQuestionsModal(): void {
    const dialog = this.generateQuestionsDialogRef?.nativeElement;
    if (dialog?.open) {
      dialog.close();
    }
  }

  onGenerateQuestionsDialogClosed(): void {
    this.openMenu = null;
  }

  onGenerateQuestionsDialogCancel(ev: Event): void {
    ev.preventDefault();
  }

  onGenerateQuestionCountBlur(): void {
    const raw = this.generateQuestionsForm.controls.questionCount.value;
    const value = Number(raw);
    if (!Number.isFinite(value)) {
      this.generateQuestionsForm.controls.questionCount.setValue(DEFAULT_GENERATE_QUESTION_COUNT);
      return;
    }
    const bounded = Math.max(MIN_GENERATE_QUESTION_COUNT, Math.min(MAX_GENERATE_QUESTION_COUNT, Math.trunc(value)));
    if (bounded !== raw) {
      this.generateQuestionsForm.controls.questionCount.setValue(bounded);
    }
  }

  setGenerationSource(mode: QuestionGenerationSourceMode): void {
    if (this.generateQuestionsForm.controls.sourceMode.value === mode) return;
    this.generateQuestionsForm.controls.sourceMode.setValue(mode);
    this.openMenu = null;
    if (mode === 'OPEN_TDB') {
      this.loadOpenTdbCategories();
    }
  }

  isGenerationSource(mode: QuestionGenerationSourceMode): boolean {
    return this.generateQuestionsForm.controls.sourceMode.value === mode;
  }

  generateQuestions(): void {
    if (this.generateQuestionsForm.controls.sourceMode.value === 'OPEN_TDB') {
      this.generateQuestionsFromOpenTdb();
      return;
    }
    this.generateQuestionsWithAi();
  }

  onGenerationSourceFilesSelected(ev: Event): void {
    const input = ev.target as HTMLInputElement | null;
    const selected = Array.from(input?.files ?? []);
    if (input) input.value = '';
    if (!selected.length) return;

    if (this.generationSourceFiles.length >= GENERATION_SOURCE_MAX_FILE_COUNT) {
      this.error = `Max ${GENERATION_SOURCE_MAX_FILE_COUNT} source files allowed.`;
      return;
    }

    for (const file of selected) {
      const validationError = this.validateGenerationSourceFile(file);
      if (validationError) {
        this.error = validationError;
        continue;
      }

      if (this.generationSourceFiles.length >= GENERATION_SOURCE_MAX_FILE_COUNT) {
        this.error = `Max ${GENERATION_SOURCE_MAX_FILE_COUNT} source files allowed.`;
        break;
      }

      const fileKey = `${file.name}::${file.size}::${file.lastModified}`;
      const alreadyAdded = this.generationSourceFiles.some(
        (existing) => `${existing.name}::${existing.size}::${existing.lastModified}` === fileKey
      );
      if (alreadyAdded) continue;
      const currentTotalBytes = this.generationSourceFiles.reduce((sum, existing) => sum + Math.max(0, existing.size ?? 0), 0);
      const nextTotalBytes = currentTotalBytes + Math.max(0, file.size ?? 0);
      if (nextTotalBytes > GENERATION_SOURCE_MAX_TOTAL_BYTES) {
        this.error = `Combined source files are too large. Max ${this.generationSourceMaxTotalUploadMbLabel} total.`;
        break;
      }
      this.generationSourceFiles = [...this.generationSourceFiles, file];
    }
  }

  removeGenerationSourceFile(index: number): void {
    if (index < 0 || index >= this.generationSourceFiles.length) return;
    this.generationSourceFiles = this.generationSourceFiles.filter((_, fileIndex) => fileIndex !== index);
  }

  clearGenerationSourceFiles(): void {
    this.generationSourceFiles = [];
  }

  generationSourceFileSizeLabel(file: File): string {
    const bytes = Math.max(0, file.size ?? 0);
    if (bytes >= BYTES_PER_MB) {
      return `${(bytes / BYTES_PER_MB).toFixed(2)} MB`;
    }
    return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }

  private generateQuestionsWithAi(): void {
    if (!this.selectedQuiz) return;
    if (this.generatingQuestions) return;

    if (this.generateQuestionsForm.invalid) {
      this.generateQuestionsForm.markAllAsTouched();
      return;
    }

    const rawTopic = this.generateQuestionsForm.controls.topic.value.trim();
    const topic = rawTopic || (
      this.generationSourceFiles.length > 0
        ? ((this.selectedQuiz.title ?? '').trim() || 'Source material')
        : ''
    );
    if (!topic) {
      this.generateQuestionsForm.controls.topic.setErrors({ required: true });
      this.generateQuestionsForm.controls.topic.markAsTouched();
      return;
    }

    const quizId = this.selectedQuiz.id;
    this.generatingQuestions = true;
    this.error = null;
    const payload = {
      topic,
      categoryHint: this.normalizeNullableText(this.generateQuestionsForm.controls.categoryHint.value),
      instructions: this.normalizeNullableText(this.generateQuestionsForm.controls.instructions.value),
      questionCount: this.generateQuestionsForm.controls.questionCount.value,
      difficulty: this.generateQuestionsForm.controls.difficulty.value,
      language: this.generateQuestionsForm.controls.language.value,
    };
    const generation$ = this.generationSourceFiles.length > 0
      ? this.api.generateQuestionsFromFiles(quizId, payload, this.generationSourceFiles)
      : this.api.generateQuestions(quizId, payload);

    generation$.subscribe({
      next: (result) => {
        this.handleGenerateQuestionsSuccess(quizId, result.generatedCount);
      },
      error: (err) => {
        this.generatingQuestions = false;
        this.error = apiErrorMessage(err, 'Failed to generate questions');
      },
    });
  }

  private generateQuestionsFromOpenTdb(): void {
    if (!this.selectedQuiz) return;
    if (this.generatingQuestions) return;

    if (this.generateQuestionsForm.invalid) {
      this.generateQuestionsForm.markAllAsTouched();
      return;
    }

    const quizId = this.selectedQuiz.id;
    this.generatingQuestions = true;
    this.error = null;

    this.api.generateQuestionsFromOpenTdb(quizId, {
      questionCount: this.generateQuestionsForm.controls.questionCount.value,
      categoryId: this.generateQuestionsForm.controls.openTdbCategoryId.value,
      difficulty: this.generateQuestionsForm.controls.difficulty.value,
      language: this.generateQuestionsForm.controls.openTdbLanguage.value,
    }).subscribe({
      next: (result) => {
        this.handleGenerateQuestionsSuccess(quizId, result.generatedCount);
      },
      error: (err) => {
        this.generatingQuestions = false;
        this.error = apiErrorMessage(err, 'Failed to generate OpenTDB questions');
      },
    });
  }

  private handleGenerateQuestionsSuccess(quizId: number, generatedCount: number): void {
    this.generatingQuestions = false;
    this.generationSourceFiles = [];
    this.closeGenerateQuestionsModal();
    this.toast.success(`Generated ${generatedCount} question(s).`, { title: 'Admin' });
    if (this.selectedQuiz?.id === quizId && this.tab === 'manage') {
      this.selectQuiz(quizId, 'questions');
      this.loadList();
    } else {
      this.loadList();
    }
  }

  saveQuestion(): void {
    if (!this.selectedQuiz) return;
    if (!this.selectedQuestionId) return;
    if (this.savingQuestion) return;
    if (this.editQuestionForm.invalid) return;
    this.error = null;

    const quiz = this.selectedQuiz;
    const q = quiz.questions.find((x) => x.id === this.selectedQuestionId);
    if (!q) return;

    const prompt = this.editQuestionForm.controls.prompt.value.trim();
    const questionImageUrl = this.normalizeNullableUrl(this.editQuestionForm.controls.imageUrl.value);
    const correctIndex = this.editQuestionForm.controls.correctIndex.value;
    const texts = [
      this.editQuestionForm.controls.o1.value.trim(),
      this.editQuestionForm.controls.o2.value.trim(),
      this.editQuestionForm.controls.o3.value.trim(),
      this.editQuestionForm.controls.o4.value.trim(),
    ];
    const images = [
      this.normalizeNullableUrl(this.editQuestionForm.controls.o1ImageUrl.value),
      this.normalizeNullableUrl(this.editQuestionForm.controls.o2ImageUrl.value),
      this.normalizeNullableUrl(this.editQuestionForm.controls.o3ImageUrl.value),
      this.normalizeNullableUrl(this.editQuestionForm.controls.o4ImageUrl.value),
    ];

    if (!this.validateOptions(texts, images)) return;

    const optionsSorted = q.options.slice().sort((a, b) => a.orderIndex - b.orderIndex);
    const payloadOptions = optionsSorted.map((o, idx) => ({
      id: o.id,
      text: this.normalizeNullableText(texts[idx]),
      imageUrl: images[idx],
      correct: idx === correctIndex,
    }));

    this.savingQuestion = true;
    this.api
      .updateQuestion(quiz.id, q.id, {
        prompt,
        imageUrl: questionImageUrl,
        options: payloadOptions,
      })
      .subscribe({
        next: () => {
          this.savingQuestion = false;
          this.toast.success('Question updated.', { title: 'Admin' });
          this.selectQuiz(quiz.id, 'keep');
        },
        error: (err) => {
          this.savingQuestion = false;
          this.error = apiErrorMessage(err, 'Failed to save question');
        },
      });
  }

  deleteSelectedQuestion(): void {
    if (!this.selectedQuiz) return;
    if (!this.selectedQuestionId) return;
    if (this.deletingQuestion) return;
    this.error = null;

    const quizId = this.selectedQuiz.id;
    const questionId = this.selectedQuestionId;

    this.deletingQuestion = true;
    this.api.deleteQuestion(quizId, questionId).subscribe({
      next: () => {
        this.deletingQuestion = false;
        this.selectedQuestionId = null;
        this.lastEditingQuestionId = null;
        this.toast.success('Question deleted.', { title: 'Admin' });
        this.selectQuiz(quizId, 'keep');
        this.loadList();
      },
      error: (err) => {
        this.deletingQuestion = false;
        this.error = apiErrorMessage(err, 'Failed to delete question');
      },
    });
  }

  deleteSelectedQuiz(): void {
    if (!this.selectedQuiz) return;
    const quizId = this.selectedQuiz.id;

    if (this.selectedQuiz.status === 'TRASHED') {
      this.purgeQuiz(quizId);
      return;
    }

    this.setQuizStatus(quizId, 'TRASHED');
  }

  deleteQuizFromList(id: number): void {
    this.setQuizStatus(id, 'TRASHED');
  }

  addQuestionToSelectedQuiz(): void {
    this.error = null;
    if (!this.selectedQuiz) return;
    if (this.addingExistingQuestion) return;
    if (this.addExistingQuestionForm.invalid) return;

    const prompt = this.addExistingQuestionForm.controls.prompt.value.trim();
    const questionImageUrl = this.normalizeNullableUrl(this.addExistingQuestionForm.controls.imageUrl.value);
    const correctIndex = this.addExistingQuestionForm.controls.correctIndex.value;
    const texts = [
      this.addExistingQuestionForm.controls.o1.value.trim(),
      this.addExistingQuestionForm.controls.o2.value.trim(),
      this.addExistingQuestionForm.controls.o3.value.trim(),
      this.addExistingQuestionForm.controls.o4.value.trim(),
    ];
    const images = [
      this.normalizeNullableUrl(this.addExistingQuestionForm.controls.o1ImageUrl.value),
      this.normalizeNullableUrl(this.addExistingQuestionForm.controls.o2ImageUrl.value),
      this.normalizeNullableUrl(this.addExistingQuestionForm.controls.o3ImageUrl.value),
      this.normalizeNullableUrl(this.addExistingQuestionForm.controls.o4ImageUrl.value),
    ];

    if (!this.validateOptions(texts, images)) return;

    this.addingExistingQuestion = true;
    this.api
      .addQuestion(this.selectedQuiz.id, {
        prompt,
        imageUrl: questionImageUrl,
        options: texts.map((text, idx) => ({
          text: this.normalizeNullableText(text),
          imageUrl: images[idx],
          correct: idx === correctIndex,
        })),
      })
      .subscribe({
        next: () => {
          this.addingExistingQuestion = false;
          const quizId = this.selectedQuiz!.id;
          this.addExistingQuestionForm.reset({
            prompt: '',
            imageUrl: null,
            correctIndex: 0,
            o1: '',
            o1ImageUrl: null,
            o2: '',
            o2ImageUrl: null,
            o3: '',
            o3ImageUrl: null,
            o4: '',
            o4ImageUrl: null,
          });
          this.toast.success('Question added.', { title: 'Admin' });
          this.selectQuiz(quizId, 'keep');
          this.loadList();
        },
        error: (err) => {
          this.addingExistingQuestion = false;
          this.error = apiErrorMessage(err, 'Failed to add question');
        },
      });
  }

  createQuiz(): void {
    this.error = null;
    if (this.quizForm.invalid) {
      this.quizForm.markAllAsTouched();
      this.error = 'Please fix quiz form errors before creating.';
      return;
    }

    const title = this.quizForm.controls.title.value.trim();
    const description = this.quizForm.controls.description.value.trim();
    const categoryName = this.quizForm.controls.categoryName.value.trim();
    const includeInRanking = !!this.quizForm.controls.includeInRanking.value;
    const xpEnabled = !!this.quizForm.controls.xpEnabled.value;
    const questionTimeLimitSeconds = this.normalizeQuestionTimeLimit(this.quizForm.controls.questionTimeLimitSeconds.value);
    const questionsPerGame = this.normalizeQuestionsPerGame(this.quizForm.controls.questionsPerGame.value);
    const avatarImageUrl = this.normalizeNullableUrl(this.quizForm.controls.avatarImageUrl.value);
    const avatarBgStart = this.normalizeNullableColor(this.quizForm.controls.avatarBgStart.value);
    const avatarBgEnd = this.normalizeNullableColor(this.quizForm.controls.avatarBgEnd.value);
    const avatarTextColor = this.normalizeNullableColor(this.quizForm.controls.avatarTextColor.value);

    this.creating = true;
    this.api
      .createQuiz({
        title,
        description: description || null,
        categoryName: categoryName || null,
        includeInRanking,
        xpEnabled,
        questionTimeLimitSeconds,
        questionsPerGame,
        avatarImageUrl,
        avatarBgStart,
        avatarBgEnd,
        avatarTextColor,
      })
      .subscribe({
        next: (quiz) => {
          this.creating = false;
          this.toast.success('Quiz created.', { title: 'Admin' });
          this.quizStatusTab.setValue(quiz.status);
          this.quizCategory.setValue('all');
          this.resetQuizPage();
          this.tab = 'manage';
          this.loadList();
          this.selectQuiz(quiz.id, 'questions');
        },
        error: (err) => {
          this.creating = false;
          this.error = apiErrorMessage(err, 'Failed to create quiz');
        },
      });
  }

  uploadingImage = false;
  uploadingQuizAvatar = false;

  uploadQuestionImage(mode: 'create' | 'edit', ev: Event): void {
    const input = ev.target as HTMLInputElement | null;
    const file = input?.files?.[0] ?? null;
    if (input) input.value = '';
    if (!file) return;

    const fileError = this.validateImageFile(file);
    if (fileError) {
      this.error = fileError;
      return;
    }

    const form = this.getQuestionForm(mode);
    if (!form) return;

    if (this.uploadingImage) return;
    this.uploadingImage = true;
    this.error = null;

    this.api.uploadImage(file).subscribe({
      next: (res) => {
        this.uploadingImage = false;
        form.controls.imageUrl.setValue(res.url);
      },
      error: (err) => {
        this.uploadingImage = false;
        this.error = apiErrorMessage(err, 'Failed to upload image');
      },
    });
  }

  uploadQuizAvatarImage(mode: 'create' | 'edit', ev: Event): void {
    const input = ev.target as HTMLInputElement | null;
    const file = input?.files?.[0] ?? null;
    if (input) input.value = '';
    if (!file) return;

    const fileError = this.validateImageFile(file);
    if (fileError) {
      this.error = fileError;
      return;
    }

    const form = mode === 'create' ? this.quizForm : this.editQuizForm;

    if (this.uploadingQuizAvatar) return;
    this.uploadingQuizAvatar = true;
    this.error = null;

    this.api.uploadImage(file).subscribe({
      next: (res) => {
        this.uploadingQuizAvatar = false;
        form.controls.avatarImageUrl.setValue(res.url);
      },
      error: (err) => {
        this.uploadingQuizAvatar = false;
        this.error = apiErrorMessage(err, 'Failed to upload image');
      },
    });
  }

  clearQuizAvatarImage(mode: 'create' | 'edit'): void {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    form.controls.avatarImageUrl.setValue(null);
  }

  quizAvatarStyle(mode: 'create' | 'edit'): { [key: string]: string } {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    const imageUrl = this.normalizeNullableUrl(form.controls.avatarImageUrl.value);
    const bgStart = this.normalizeNullableColor(form.controls.avatarBgStart.value) ?? '#30D0FF';
    const bgEnd = this.normalizeNullableColor(form.controls.avatarBgEnd.value) ?? '#2F86FF';
    const textColor = this.normalizeNullableColor(form.controls.avatarTextColor.value) ?? '#0A0E1C';

    if (imageUrl) {
      return {
        'background-image': `url(${imageUrl})`,
        'background-size': 'cover',
        'background-position': 'center',
        'color': textColor,
      };
    }

    return {
      'background-image': `linear-gradient(180deg, ${bgStart}, ${bgEnd})`,
      'color': textColor,
    };
  }

  quizListAvatarStyle(q: AdminQuizListItemDto): { [key: string]: string } {
    const imageUrl = this.normalizeNullableUrl(q.avatarImageUrl ?? null);
    const bgStart = this.normalizeNullableColor(q.avatarBgStart ?? null) ?? '#30D0FF';
    const bgEnd = this.normalizeNullableColor(q.avatarBgEnd ?? null) ?? '#2F86FF';
    const textColor = this.normalizeNullableColor(q.avatarTextColor ?? null) ?? '#0A0E1C';

    if (imageUrl) {
      return {
        'background-image': `url(${imageUrl})`,
        'background-size': 'cover',
        'background-position': 'center',
        'color': textColor,
      };
    }

    return {
      'background-image': `linear-gradient(180deg, ${bgStart}, ${bgEnd})`,
      'color': textColor,
    };
  }

  clearQuestionImage(mode: 'create' | 'edit'): void {
    const form = this.getQuestionForm(mode);
    if (!form) return;
    form.controls.imageUrl.setValue(null);
    this.closeImagePreview();
  }

  uploadOptionImage(mode: 'create' | 'edit', index: number, ev: Event): void {
    const input = ev.target as HTMLInputElement | null;
    const file = input?.files?.[0] ?? null;
    if (input) input.value = '';
    if (!file) return;

    const fileError = this.validateImageFile(file);
    if (fileError) {
      this.error = fileError;
      return;
    }

    const form = this.getQuestionForm(mode);
    if (!form) return;

    const key = this.optionImageKey(index);
    if (!key) return;

    if (this.uploadingImage) return;
    this.uploadingImage = true;
    this.error = null;

    this.api.uploadImage(file).subscribe({
      next: (res) => {
        this.uploadingImage = false;
        form.controls[key].setValue(res.url);
      },
      error: (err) => {
        this.uploadingImage = false;
        this.error = apiErrorMessage(err, 'Failed to upload image');
      },
    });
  }

  clearOptionImage(mode: 'create' | 'edit', index: number): void {
    const form = this.getQuestionForm(mode);
    if (!form) return;
    const key = this.optionImageKey(index);
    if (!key) return;
    form.controls[key].setValue(null);
    this.closeImagePreview();
  }

  questionImageUrl(mode: 'create' | 'edit'): string | null {
    const form = this.getQuestionForm(mode);
    if (!form) return null;
    return this.normalizeNullableUrl(form.controls.imageUrl.value);
  }

  optionImageUrl(mode: 'create' | 'edit', index: number): string | null {
    const form = this.getQuestionForm(mode);
    if (!form) return null;
    const key = this.optionImageKey(index);
    if (!key) return null;
    return this.normalizeNullableUrl(form.controls[key].value);
  }

  private getQuestionForm(mode: 'create' | 'edit') {
    return mode === 'create' ? this.addExistingQuestionForm : this.editQuestionForm;
  }

  private optionImageKey(
    index: number
  ): 'o1ImageUrl' | 'o2ImageUrl' | 'o3ImageUrl' | 'o4ImageUrl' | null {
    switch (index) {
      case 0:
        return 'o1ImageUrl';
      case 1:
        return 'o2ImageUrl';
      case 2:
        return 'o3ImageUrl';
      case 3:
        return 'o4ImageUrl';
      default:
        return null;
    }
  }

  private normalizeNullableText(text: string): string | null {
    const t = (text ?? '').trim();
    return t ? t : null;
  }

  private normalizeNullableUrl(url: string | null): string | null {
    const t = (url ?? '').trim();
    return t ? t : null;
  }

  private normalizeNullableColor(color: string | null): string | null {
    const t = (color ?? '').trim();
    return t ? t : null;
  }

  private validateImageFile(file: File): string | null {
    if (file.size > ADMIN_MAX_UPLOAD_BYTES) {
      return `Image is too large. Max ${this.maxUploadMbLabel}.`;
    }
    const type = (file.type ?? '').trim().toLowerCase();
    if (!this.allowedUploadMimeTypes.includes(type)) {
      return 'Unsupported image format.';
    }
    return null;
  }

  private validateGenerationSourceFile(file: File): string | null {
    if (!file) return 'Invalid source file.';
    if (file.size <= 0) return 'Source file is empty.';
    if (file.size > GENERATION_SOURCE_MAX_FILE_BYTES) {
      return `Source file ${file.name} is too large. Max ${this.generationSourceMaxUploadMbLabel}.`;
    }

    const name = (file.name ?? '').trim().toLowerCase();
    const dotIndex = name.lastIndexOf('.');
    const extension = dotIndex >= 0 ? name.substring(dotIndex + 1) : '';
    if (!GENERATION_SOURCE_ALLOWED_EXTENSIONS.includes(extension)) {
      return `Unsupported source file type for ${file.name}. Allowed: ${this.generationSourceAllowedExtensionsLabel}.`;
    }
    return null;
  }

  private loadOpenTdbCategories(): void {
    if (this.loadingOpenTdbCategories || this.openTdbCategories.length > 0) {
      return;
    }
    this.loadingOpenTdbCategories = true;
    this.api.listOpenTdbCategories().subscribe({
      next: (categories) => {
        this.loadingOpenTdbCategories = false;
        this.openTdbCategories = [...(categories ?? [])].sort((a, b) => a.name.localeCompare(b.name));
      },
      error: (err) => {
        this.loadingOpenTdbCategories = false;
        this.error = apiErrorMessage(err, 'Failed to load OpenTDB categories');
      },
    });
  }

  private resetImagePreviewState(): void {
    this.previewImageUrl = null;
    this.previewImageAlt = 'Image preview';
    this.previewImageName = '';
  }

  private validateOptions(texts: string[], imageUrls: Array<string | null>): boolean {
    for (let i = 0; i < 4; i++) {
      const t = this.normalizeNullableText(texts[i] ?? '');
      const u = this.normalizeNullableUrl(imageUrls[i] ?? null);
      if (!t && !u) {
        this.error = `Option ${i + 1} must have text or an image.`;
        return false;
      }
    }
    return true;
  }

  private quizStatusSuccessMessage(status: QuizStatus): string {
    if (status === 'ACTIVE') return 'Quiz published.';
    if (status === 'DRAFT') return 'Quiz moved to draft mode.';
    if (status === 'TRASHED') return 'Quiz moved to trash.';
    return 'Quiz status updated.';
  }

  private readonly avatarColorDraft: Record<
    'create' | 'edit',
    Record<'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor', string>
  > = {
    create: { avatarBgStart: '', avatarBgEnd: '', avatarTextColor: '' },
    edit: { avatarBgStart: '', avatarBgEnd: '', avatarTextColor: '' },
  };

  avatarColorText(mode: 'create' | 'edit', field: 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor'): string {
    return this.avatarColorDraft[mode][field];
  }

  onAvatarColorPicker(mode: 'create' | 'edit', field: 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor', value: string): void {
    const normalized = this.normalizeHexColor(value) ?? value;
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    this.avatarColorDraft[mode][field] = normalized;
    if (normalized !== form.controls[field].value) {
      form.controls[field].setValue(normalized);
    }
  }

  onAvatarColorTextInput(mode: 'create' | 'edit', field: 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor', raw: string): void {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    this.avatarColorDraft[mode][field] = raw;
    const normalized = this.normalizeHexColor(raw);
    if (normalized) {
      this.avatarColorDraft[mode][field] = normalized;
      if (normalized !== form.controls[field].value) {
        form.controls[field].setValue(normalized);
      }
    }
  }

  onAvatarColorTextBlur(mode: 'create' | 'edit', field: 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor'): void {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    const normalized = this.normalizeHexColor(this.avatarColorDraft[mode][field]);
    if (normalized) {
      this.avatarColorDraft[mode][field] = normalized;
      if (normalized !== form.controls[field].value) {
        form.controls[field].setValue(normalized);
      }
      return;
    }
    this.avatarColorDraft[mode][field] = this.normalizeHexColor(form.controls[field].value) ?? (form.controls[field].value ?? '');
  }

  private syncAvatarColorDraft(mode: 'create' | 'edit'): void {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    this.avatarColorDraft[mode].avatarBgStart = this.normalizeHexColor(form.controls.avatarBgStart.value) ?? form.controls.avatarBgStart.value;
    this.avatarColorDraft[mode].avatarBgEnd = this.normalizeHexColor(form.controls.avatarBgEnd.value) ?? form.controls.avatarBgEnd.value;
    this.avatarColorDraft[mode].avatarTextColor =
      this.normalizeHexColor(form.controls.avatarTextColor.value) ?? form.controls.avatarTextColor.value;
  }

  private normalizeHexColor(raw: string | null | undefined): string | null {
    const t = (raw ?? '').trim();
    if (!t) return null;
    let hex = t.startsWith('#') ? t.slice(1) : t;
    if (/^[0-9a-fA-F]{3}$/.test(hex)) {
      hex = hex
        .split('')
        .map((c) => c + c)
        .join('');
    }
    if (!/^[0-9a-fA-F]{6}$/.test(hex)) return null;
    return `#${hex.toUpperCase()}`;
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
    if (this.tab !== 'manage' || !this.selectedQuiz || this.editorTab !== 'details') {
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
    if (this.tab !== 'manage' || !this.selectedQuiz) {
      this.questionPaneHeightPx = null;
      this.detailsPaneHeightPx = null;
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
      if (!right) return;
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

    // Extra safety buffer for button shadow/border to prevent bottom clipping.
    return Math.ceil(total + 6);
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
    if (delta < 0 && Math.abs(delta) <= AdminQuizComponent.PANE_HEIGHT_DOWN_EPSILON_PX) {
      return;
    }
    if (delta > 0 && delta <= AdminQuizComponent.PANE_HEIGHT_UP_EPSILON_PX) {
      return;
    }
    this.questionPaneHeightPx = next;
  }

  private transitionClass(direction: 'forward' | 'backward', flip: boolean): string {
    if (direction === 'backward') {
      return flip ? 'view-screen--enter-back-a' : 'view-screen--enter-back-b';
    }
    return flip ? 'view-screen--enter-forward-a' : 'view-screen--enter-forward-b';
  }
}

