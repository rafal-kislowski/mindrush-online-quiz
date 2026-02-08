import { CommonModule } from '@angular/common';
import { Component, HostListener, OnInit } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import {
  AdminQuestionDto,
  AdminQuizApi,
  AdminQuizDetailDto,
  AdminQuizListItemDto,
  GameMode,
  QuizStatus,
} from '../../core/api/admin-quiz.api';

const MIN_QUESTION_TIME_LIMIT_SECONDS = 5;
const MAX_QUESTION_TIME_LIMIT_SECONDS = 600;
const DEFAULT_QUESTION_TIME_LIMIT_SECONDS = 15;

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

@Component({
  selector: 'app-admin-quiz',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './admin-quiz.component.html',
  styleUrl: './admin-quiz.component.scss',
})
export class AdminQuizComponent implements OnInit {
  tab: 'manage' | 'create' = 'manage';
  editorTab: 'details' | 'questions' = 'details';
  openMenu: 'category' | 'sort' | 'pageSize' | 'gameModeCreate' | 'gameModeEdit' | null = null;
  openQuizActionsId: number | null = null;
  quizActionsMenuStyle: Record<string, string> | null = null;
  private lastQuizActionsOpenedAtMs = 0;
  error: string | null = null;

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

  readonly quizSearch = new FormControl('', { nonNullable: true });
  readonly quizCategory = new FormControl<string>('all', { nonNullable: true });
  readonly quizStatusTab = new FormControl<QuizStatus>('ACTIVE', { nonNullable: true });
  readonly questionSearch = new FormControl('', { nonNullable: true });
  readonly pageSize = new FormControl<number>(8, { nonNullable: true });
  readonly quizSort = new FormControl<'titleAsc' | 'titleDesc' | 'questionsDesc' | 'questionsAsc' | 'categoryAsc' | 'categoryDesc'>(
    'titleAsc',
    { nonNullable: true }
  );
  quizPageIndex = 0;

  readonly quizSortOptions: ReadonlyArray<{
    value: 'titleAsc' | 'titleDesc' | 'questionsDesc' | 'questionsAsc' | 'categoryAsc' | 'categoryDesc';
    label: string;
  }> = [
    { value: 'titleAsc', label: 'Title (A-Z)' },
    { value: 'titleDesc', label: 'Title (Z-A)' },
    { value: 'questionsDesc', label: 'Questions (high-low)' },
    { value: 'questionsAsc', label: 'Questions (low-high)' },
    { value: 'categoryAsc', label: 'Category (A-Z)' },
    { value: 'categoryDesc', label: 'Category (Z-A)' },
  ];

  readonly quizForm = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(120)] }),
    description: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
    categoryName: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(64)] }),
    gameMode: new FormControl<GameMode>('CASUAL', { nonNullable: true }),
    includeInRanking: new FormControl<boolean>(false, { nonNullable: true }),
    xpEnabled: new FormControl<boolean>(true, { nonNullable: true }),
    questionTimeLimitSeconds: new FormControl<number | null>(DEFAULT_QUESTION_TIME_LIMIT_SECONDS, { validators: [questionTimeLimitValidator] }),
    avatarImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    avatarBgStart: new FormControl<string>('#30D0FF', { nonNullable: true, validators: [Validators.maxLength(32)] }),
    avatarUseGradient: new FormControl<boolean>(true, { nonNullable: true }),
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
    gameMode: new FormControl<GameMode>('CASUAL', { nonNullable: true }),
    includeInRanking: new FormControl<boolean>(false, { nonNullable: true }),
    xpEnabled: new FormControl<boolean>(true, { nonNullable: true }),
    questionTimeLimitSeconds: new FormControl<number | null>(DEFAULT_QUESTION_TIME_LIMIT_SECONDS, { validators: [questionTimeLimitValidator] }),
    avatarImageUrl: new FormControl<string | null>(null, { validators: [Validators.maxLength(500)] }),
    avatarBgStart: new FormControl<string>('#30D0FF', { nonNullable: true, validators: [Validators.maxLength(32)] }),
    avatarUseGradient: new FormControl<boolean>(true, { nonNullable: true }),
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

  constructor(private readonly api: AdminQuizApi) {}

  ngOnInit(): void {
    this.loadList();
    this.syncAvatarColorDraft('create');
    this.syncAvatarColorDraft('edit');
    this.enforceGameModeRules('create');
    this.enforceGameModeRules('edit');
  }

  @HostListener('document:click')
  onDocumentClick(): void {
    this.openMenu = null;
    this.closeQuizActions();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.openMenu = null;
    this.closeQuizActions();
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    this.openMenu = null;
    this.closeQuizActions();
  }

  @HostListener('window:scroll')
  onWindowScroll(): void {
    if (this.openQuizActionsId != null && Date.now() - this.lastQuizActionsOpenedAtMs < 250) {
      return;
    }
    this.openMenu = null;
    this.closeQuizActions();
  }

  toggleMenu(menu: 'category' | 'sort' | 'pageSize' | 'gameModeCreate' | 'gameModeEdit', ev?: Event): void {
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
    this.quizStatusTab.setValue(value);
    this.quizCategory.setValue('all');
    this.resetQuizPage();
    this.openMenu = null;
  }

  setQuizSort(
    value: 'titleAsc' | 'titleDesc' | 'questionsDesc' | 'questionsAsc' | 'categoryAsc' | 'categoryDesc',
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

  setGameMode(mode: 'create' | 'edit', value: GameMode, ev?: Event): void {
    ev?.stopPropagation();
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    form.controls.gameMode.setValue(value);
    this.openMenu = null;
    this.onGameModeChange(mode);
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

  setEditorTab(tab: 'details' | 'questions'): void {
    this.editorTab = tab;
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
      const cat = (q.categoryName ?? '').trim();

      const matchesQuery = !query || title.includes(query);
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

    const button = ev?.currentTarget instanceof HTMLElement ? ev.currentTarget : null;
    this.quizActionsMenuStyle = button ? this.computeQuizActionsMenuStyle(button) : null;
  }

  closeQuizActions(): void {
    this.openQuizActionsId = null;
    this.quizActionsMenuStyle = null;
  }

  private computeQuizActionsMenuStyle(button: HTMLElement): Record<string, string> {
    const menuWidth = 210;
    const gap = 8;
    const viewportPad = 12;
    const estimatedMenuHeight = 260;

    const r = button.getBoundingClientRect();

    let left = r.right - menuWidth;
    left = Math.max(viewportPad, Math.min(left, window.innerWidth - menuWidth - viewportPad));

    const viewportH = window.innerHeight;
    const maxTop = Math.max(viewportPad, viewportH - estimatedMenuHeight - viewportPad);

    let top = r.bottom + gap;
    if (top > maxTop) {
      top = r.top - gap - estimatedMenuHeight;
    }
    top = Math.max(viewportPad, Math.min(top, maxTop));

    return {
      position: 'fixed',
      left: `${left}px`,
      top: `${top}px`,
      width: `${menuWidth}px`,
      maxHeight: `${Math.max(120, viewportH - viewportPad * 2)}px`,
      zIndex: '9999',
    };
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
        this.loadList();
      },
      error: (err) => {
        this.deletingQuiz = false;
        this.error = err?.error?.message ?? 'Failed to update quiz status';
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
        this.loadList();
      },
      error: (err) => {
        this.deletingQuiz = false;
        this.error = err?.error?.message ?? 'Failed to delete quiz permanently';
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

  private sortQuizzes(list: AdminQuizListItemDto[]): AdminQuizListItemDto[] {
    const sort = this.quizSort.value;

    const byTitle = (a: AdminQuizListItemDto, b: AdminQuizListItemDto) =>
      (a.title ?? '').localeCompare(b.title ?? '', undefined, { sensitivity: 'base' });
    const byCategory = (a: AdminQuizListItemDto, b: AdminQuizListItemDto) =>
      (a.categoryName ?? '').localeCompare(b.categoryName ?? '', undefined, { sensitivity: 'base' });
    const byQuestions = (a: AdminQuizListItemDto, b: AdminQuizListItemDto) => (a.questionCount ?? 0) - (b.questionCount ?? 0);

    const cmp = (() => {
      switch (sort) {
        case 'titleAsc':
          return byTitle;
        case 'titleDesc':
          return (a: AdminQuizListItemDto, b: AdminQuizListItemDto) => -byTitle(a, b);
        case 'categoryAsc':
          return byCategory;
        case 'categoryDesc':
          return (a: AdminQuizListItemDto, b: AdminQuizListItemDto) => -byCategory(a, b);
        case 'questionsAsc':
          return byQuestions;
        case 'questionsDesc':
          return (a: AdminQuizListItemDto, b: AdminQuizListItemDto) => -byQuestions(a, b);
        default:
          return byTitle;
      }
    })();

    return list.slice().sort(cmp);
  }

  setTab(tab: 'manage' | 'create'): void {
    this.tab = tab;
    this.error = null;
    if (tab === 'manage') this.loadList();
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
        this.error = err?.error?.message ?? 'Failed to load quizzes';
      },
    });
  }

  selectQuiz(id: number, openTab: 'details' | 'questions' | 'keep' = 'details'): void {
    this.error = null;
    this.loadingQuiz = true;
    this.selectedQuestionId = null;
    this.questionSearch.setValue('');
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
          gameMode: quiz.gameMode ?? 'CASUAL',
          includeInRanking: !!quiz.includeInRanking,
          xpEnabled: quiz.xpEnabled ?? true,
          questionTimeLimitSeconds: quiz.questionTimeLimitSeconds ?? DEFAULT_QUESTION_TIME_LIMIT_SECONDS,
          avatarImageUrl: quiz.avatarImageUrl ?? null,
          avatarBgStart: quiz.avatarBgStart ?? '#30D0FF',
          avatarUseGradient: (quiz.avatarBgEnd ?? null) != null,
          avatarBgEnd: quiz.avatarBgEnd ?? '#2F86FF',
          avatarTextColor: quiz.avatarTextColor ?? '#0A0E1C',
        });
        this.enforceGameModeRules('edit');
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
      },
      error: (err) => {
        this.loadingQuiz = false;
        this.error = err?.error?.message ?? 'Failed to load quiz';
      },
    });
  }

  closeSelectedQuiz(): void {
    this.selectedQuiz = null;
    this.selectedQuestionId = null;
    this.questionSearch.setValue('');
    this.editorTab = 'details';
    this.questionPanelMode = 'create';
    this.lastEditingQuestionId = null;
  }

  saveQuiz(): void {
    if (!this.selectedQuiz) return;
    if (this.savingQuiz) return;
    if (this.editQuizForm.invalid) return;
    this.error = null;

    const title = this.editQuizForm.controls.title.value.trim();
    const description = this.editQuizForm.controls.description.value.trim();
    const categoryName = this.editQuizForm.controls.categoryName.value.trim();
    const gameMode = this.editQuizForm.controls.gameMode.value;
    const includeInRanking =
      gameMode === 'RANKED' ? true : !!this.editQuizForm.controls.includeInRanking.value;
    const xpEnabled = !!this.editQuizForm.controls.xpEnabled.value;
    const questionTimeLimitSeconds = this.normalizeQuestionTimeLimit(this.editQuizForm.controls.questionTimeLimitSeconds.value);
    const avatarImageUrl = this.normalizeNullableUrl(this.editQuizForm.controls.avatarImageUrl.value);
    const avatarBgStart = this.normalizeNullableColor(this.editQuizForm.controls.avatarBgStart.value);
    const avatarBgEnd = this.editQuizForm.controls.avatarUseGradient.value
      ? this.normalizeNullableColor(this.editQuizForm.controls.avatarBgEnd.value)
      : null;
    const avatarTextColor = this.normalizeNullableColor(this.editQuizForm.controls.avatarTextColor.value);

    this.savingQuiz = true;
    this.api
      .updateQuiz(this.selectedQuiz.id, {
        title,
        description: description || null,
        categoryName: categoryName || null,
        gameMode,
        includeInRanking,
        xpEnabled,
        questionTimeLimitSeconds,
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
            status: updated.status,
          };
          this.loadList();
        },
        error: (err) => {
          this.savingQuiz = false;
          this.error = err?.error?.message ?? 'Failed to save quiz';
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

  onGameModeChange(mode: 'create' | 'edit'): void {
    this.enforceGameModeRules(mode);
  }

  onTimeLimitBlur(mode: 'create' | 'edit'): void {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    const v = form.controls.questionTimeLimitSeconds.value;
    if (v == null || !Number.isFinite(Number(v)) || Number(v) <= 0) {
      form.controls.questionTimeLimitSeconds.setValue(DEFAULT_QUESTION_TIME_LIMIT_SECONDS);
    }
  }

  private enforceGameModeRules(mode: 'create' | 'edit'): void {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    const isRanked = form.controls.gameMode.value === 'RANKED';

    if (isRanked) {
      if (form.controls.includeInRanking.enabled) {
        form.controls.includeInRanking.setValue(true, { emitEvent: false });
        form.controls.includeInRanking.disable({ emitEvent: false });
      } else {
        form.controls.includeInRanking.setValue(true, { emitEvent: false });
      }
      return;
    }

    if (form.controls.includeInRanking.disabled) {
      form.controls.includeInRanking.enable({ emitEvent: false });
    }
  }

  private normalizeQuestionTimeLimit(raw: number | null): number | null {
    if (raw == null) return DEFAULT_QUESTION_TIME_LIMIT_SECONDS;

    const n = Number(raw);
    if (!Number.isFinite(n)) return null;
    if (n <= 0) return DEFAULT_QUESTION_TIME_LIMIT_SECONDS;
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
          this.selectQuiz(quiz.id, 'keep');
        },
        error: (err) => {
          this.savingQuestion = false;
          this.error = err?.error?.message ?? 'Failed to save question';
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
        this.selectQuiz(quizId, 'keep');
        this.loadList();
      },
      error: (err) => {
        this.deletingQuestion = false;
        this.error = err?.error?.message ?? 'Failed to delete question';
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
          this.selectQuiz(quizId, 'keep');
          this.loadList();
        },
        error: (err) => {
          this.addingExistingQuestion = false;
          this.error = err?.error?.message ?? 'Failed to add question';
        },
      });
  }

  createQuiz(): void {
    this.error = null;
    if (this.quizForm.invalid) return;

    const title = this.quizForm.controls.title.value.trim();
    const description = this.quizForm.controls.description.value.trim();
    const categoryName = this.quizForm.controls.categoryName.value.trim();
    const gameMode = this.quizForm.controls.gameMode.value;
    const includeInRanking = gameMode === 'RANKED' ? true : !!this.quizForm.controls.includeInRanking.value;
    const xpEnabled = !!this.quizForm.controls.xpEnabled.value;
    const questionTimeLimitSeconds = this.normalizeQuestionTimeLimit(this.quizForm.controls.questionTimeLimitSeconds.value);
    const avatarImageUrl = this.normalizeNullableUrl(this.quizForm.controls.avatarImageUrl.value);
    const avatarBgStart = this.normalizeNullableColor(this.quizForm.controls.avatarBgStart.value);
    const avatarBgEnd = this.quizForm.controls.avatarUseGradient.value
      ? this.normalizeNullableColor(this.quizForm.controls.avatarBgEnd.value)
      : null;
    const avatarTextColor = this.normalizeNullableColor(this.quizForm.controls.avatarTextColor.value);

    this.creating = true;
    this.api
      .createQuiz({
        title,
        description: description || null,
        categoryName: categoryName || null,
        gameMode,
        includeInRanking,
        xpEnabled,
        questionTimeLimitSeconds,
        avatarImageUrl,
        avatarBgStart,
        avatarBgEnd,
        avatarTextColor,
      })
      .subscribe({
        next: (quiz) => {
          this.creating = false;
          this.tab = 'manage';
          this.loadList();
          this.selectQuiz(quiz.id, 'questions');
        },
        error: (err) => {
          this.creating = false;
          this.error = err?.error?.message ?? 'Failed to create quiz';
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
        this.error = err?.error?.message ?? 'Failed to upload image';
      },
    });
  }

  uploadQuizAvatarImage(mode: 'create' | 'edit', ev: Event): void {
    const input = ev.target as HTMLInputElement | null;
    const file = input?.files?.[0] ?? null;
    if (input) input.value = '';
    if (!file) return;

    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    if (form.invalid) return;

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
        this.error = err?.error?.message ?? 'Failed to upload image';
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
    const bgEnd = form.controls.avatarUseGradient.value
      ? this.normalizeNullableColor(form.controls.avatarBgEnd.value)
      : null;
    const textColor = this.normalizeNullableColor(form.controls.avatarTextColor.value) ?? '#0A0E1C';

    if (imageUrl) {
      return {
        'background-image': `url(${imageUrl})`,
        'background-size': 'cover',
        'background-position': 'center',
        'color': textColor,
      };
    }

    if (bgEnd) {
      return {
        'background-image': `linear-gradient(180deg, ${bgStart}, ${bgEnd})`,
        'color': textColor,
      };
    }

    return {
      'background': bgStart,
      'color': textColor,
    };
  }

  quizListAvatarStyle(q: AdminQuizListItemDto): { [key: string]: string } {
    const imageUrl = this.normalizeNullableUrl(q.avatarImageUrl ?? null);
    const bgStart = this.normalizeNullableColor(q.avatarBgStart ?? null) ?? '#30D0FF';
    const bgEnd = this.normalizeNullableColor(q.avatarBgEnd ?? null);
    const textColor = this.normalizeNullableColor(q.avatarTextColor ?? null) ?? '#0A0E1C';

    if (imageUrl) {
      return {
        'background-image': `url(${imageUrl})`,
        'background-size': 'cover',
        'background-position': 'center',
        'color': textColor,
      };
    }

    if (bgEnd) {
      return {
        'background-image': `linear-gradient(180deg, ${bgStart}, ${bgEnd})`,
        'color': textColor,
      };
    }

    return {
      'background': bgStart,
      'color': textColor,
    };
  }

  avatarGradientEnabled(mode: 'create' | 'edit'): boolean {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    return form.controls.avatarUseGradient.value;
  }

  setAvatarGradient(mode: 'create' | 'edit', enabled: boolean): void {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    form.controls.avatarUseGradient.setValue(enabled);
  }

  clearQuestionImage(mode: 'create' | 'edit'): void {
    const form = this.getQuestionForm(mode);
    if (!form) return;
    form.controls.imageUrl.setValue(null);
  }

  uploadOptionImage(mode: 'create' | 'edit', index: number, ev: Event): void {
    const input = ev.target as HTMLInputElement | null;
    const file = input?.files?.[0] ?? null;
    if (input) input.value = '';
    if (!file) return;

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
        this.error = err?.error?.message ?? 'Failed to upload image';
      },
    });
  }

  clearOptionImage(mode: 'create' | 'edit', index: number): void {
    const form = this.getQuestionForm(mode);
    if (!form) return;
    const key = this.optionImageKey(index);
    if (!key) return;
    form.controls[key].setValue(null);
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

  avatarSwatches: ReadonlyArray<{ start: string; end?: string; text: string }> = [
    { start: '#30D0FF', end: '#2F86FF', text: '#0A0E1C' },
    { start: '#FFB454', end: '#FF6B6B', text: '#1A0F0F' },
    { start: '#33D9A0', end: '#1EC8FF', text: '#07131A' },
    { start: '#A77BFF', end: '#FF5BD6', text: '#120815' },
    { start: '#2B2F44', end: '#151A2D', text: '#FFFFFF' },
  ];

  applyAvatarSwatch(mode: 'create' | 'edit', sw: { start: string; end?: string; text: string }): void {
    const form = mode === 'create' ? this.quizForm : this.editQuizForm;
    form.controls.avatarBgStart.setValue(sw.start);
    form.controls.avatarUseGradient.setValue(!!sw.end);
    form.controls.avatarBgEnd.setValue(sw.end ?? sw.start);
    form.controls.avatarTextColor.setValue(sw.text);
    form.controls.avatarImageUrl.setValue(null);
    this.syncAvatarColorDraft(mode);
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
}
