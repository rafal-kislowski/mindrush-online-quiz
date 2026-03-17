import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import {
  AdminQuestionDto,
  AdminQuestionIssueInputDto,
  AdminQuizApi,
  AdminQuizListItemDto,
  AdminSubmissionOwnerModerationDto,
  AdminQuizSubmissionDetailDto,
  AdminQuizSubmissionListItemDto,
} from '../../core/api/admin-quiz.api';
import { PlayerAvatarComponent } from '../../core/ui/player-avatar.component';
import { PremiumBadgeComponent } from '../../core/ui/premium-badge.component';
import { ToastService } from '../../core/ui/toast.service';

@Component({
  selector: 'app-admin-quiz-submissions',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, PlayerAvatarComponent, PremiumBadgeComponent],
  templateUrl: './admin-quiz-submissions.component.html',
  styleUrl: './admin-quiz-submissions.component.scss',
})
export class AdminQuizSubmissionsComponent implements OnInit, OnDestroy {
  private static readonly STATUS_TAB_ORDER: Record<'PENDING' | 'APPROVED' | 'REJECTED', number> = {
    PENDING: 0,
    APPROVED: 1,
    REJECTED: 2,
  };
  private static readonly DETAIL_TAB_ORDER: Record<'details' | 'questions', number> = {
    details: 0,
    questions: 1,
  };

  submissions: AdminQuizSubmissionListItemDto[] = [];
  selectedSubmission: AdminQuizSubmissionDetailDto | null = null;
  detailTab: 'details' | 'questions' = 'details';
  detailTabTransition: 'forward' | 'backward' = 'forward';
  private detailTabTransitionFlip = false;
  detailTabHeightPx: number | null = null;
  questionFeedbackModalOpen = false;
  rejectFeedbackModalOpen = false;
  questionFeedbackTargetId: number | null = null;
  currentQuestionIndex = 0;
  questionPageIndex = 0;
  statusTab: 'PENDING' | 'APPROVED' | 'REJECTED' = 'PENDING';
  statusTabTransition: 'forward' | 'backward' = 'forward';
  private statusTabTransitionFlip = false;
  pageIndex = 0;
  openMenu: 'pageSize' | 'sort' | 'creatorTier' | 'questionPageSize' | null = null;
  openRowActionsId: number | null = null;
  rowActionsMenuStyle: Record<string, string> | null = null;
  private lastRowActionsOpenedAtMs = 0;

  loadingList = false;
  loadingDetail = false;
  actionBusy = false;
  previewImageUrl: string | null = null;
  previewImageAlt = 'Question image preview';
  previewImageName = '';
  previewQuestionImageId: number | null = null;

  readonly search = new FormControl('', { nonNullable: true });
  readonly pageSize = new FormControl<number>(8, { nonNullable: true });
  readonly pageSizeOptions: ReadonlyArray<number> = [8, 12, 25, 50];
  readonly sortBy = new FormControl<'premium_newest' | 'newest' | 'oldest' | 'name_az' | 'name_za'>('premium_newest', { nonNullable: true });
  readonly creatorTier = new FormControl<'all' | 'premium' | 'standard'>('all', { nonNullable: true });
  readonly questionSearch = new FormControl('', { nonNullable: true });
  readonly questionPageSize = new FormControl<number>(10, { nonNullable: true });
  readonly questionPageSizeOptions: ReadonlyArray<number> = [10, 25, 50, 100];
  readonly rejectForm = new FormGroup({
    reason: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(500)],
    }),
  });
  readonly questionFeedbackForm = new FormGroup({
    message: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(500)],
    }),
  });
  private readonly selectedQuestionIssueIds = new Set<number>();
  private questionIssueNotes: Record<number, string> = {};
  private readonly uiSub = new Subscription();
  private heightSyncScheduled = false;
  private pendingOpenSubmissionId: number | null = null;
  private submissionAvatarOverrides = new Map<number, Pick<
    AdminQuizListItemDto,
    'avatarImageUrl' | 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor'
  >>();
  private avatarLookupInFlight = false;

  private _error: string | null = null;
  @ViewChild('imagePreviewDialog') imagePreviewDialogRef: ElementRef<HTMLDialogElement> | null = null;
  @ViewChild('detailsSection') private detailsSectionRef?: ElementRef<HTMLElement>;
  @ViewChild('questionFeedbackDialog') private questionFeedbackDialogRef?: ElementRef<HTMLDialogElement>;
  @ViewChild('rejectFeedbackDialog') private rejectFeedbackDialogRef?: ElementRef<HTMLDialogElement>;

  constructor(
    private readonly api: AdminQuizApi,
    private readonly toast: ToastService,
    private readonly route: ActivatedRoute,
    private readonly router: Router
  ) {}

  get error(): string | null {
    return this._error;
  }

  set error(value: string | null) {
    this._error = value;
    if (!value) return;
    this.toast.error(value, { title: 'Review Submissions', dedupeKey: `submissions:error:${value}` });
  }

  ngOnInit(): void {
    this.loadSubmissions();
    this.uiSub.add(
      this.route.queryParamMap.subscribe((params) => {
        this.handleOpenQuizQueryParam(params.get('openQuiz'));
      })
    );
    this.uiSub.add(this.search.valueChanges.subscribe(() => this.resetPage()));
    this.uiSub.add(this.pageSize.valueChanges.subscribe(() => this.resetPage()));
    this.uiSub.add(this.sortBy.valueChanges.subscribe(() => this.resetPage()));
    this.uiSub.add(this.creatorTier.valueChanges.subscribe(() => this.resetPage()));
    this.uiSub.add(this.questionSearch.valueChanges.subscribe(() => this.resetQuestionPage()));
    this.uiSub.add(this.questionPageSize.valueChanges.subscribe(() => this.resetQuestionPage()));
  }

  ngOnDestroy(): void {
    this.uiSub.unsubscribe();
  }

  get filteredSubmissions(): AdminQuizSubmissionListItemDto[] {
    const q = this.search.value.trim().toLowerCase();
    const creatorTier = this.creatorTier.value;
    const filtered = this.submissions.filter((item) => {
      if (this.normalizeModerationStatus(item.moderationStatus) !== this.statusTab) return false;
      const isPremium = !!item.ownerIsPremium;
      if (creatorTier === 'premium' && !isPremium) return false;
      if (creatorTier === 'standard' && isPremium) return false;
      if (!q) return true;
      return (
        (item.title ?? '').toLowerCase().includes(q)
        || (item.categoryName ?? '').toLowerCase().includes(q)
        || (item.ownerDisplayName ?? '').toLowerCase().includes(q)
      );
    });

    const sort = this.sortBy.value;
    filtered.sort((a, b) => {
      if (sort === 'premium_newest') {
        const premiumDelta = Number(!!b.ownerIsPremium) - Number(!!a.ownerIsPremium);
        if (premiumDelta !== 0) return premiumDelta;
        const bv = this.submissionTimestampValue(b);
        const av = this.submissionTimestampValue(a);
        if (bv !== av) return bv - av;
        return (a.title ?? '').localeCompare(b.title ?? '', undefined, { sensitivity: 'base' });
      }
      if (sort === 'name_az') {
        return (a.title ?? '').localeCompare(b.title ?? '', undefined, { sensitivity: 'base' });
      }
      if (sort === 'name_za') {
        return (b.title ?? '').localeCompare(a.title ?? '', undefined, { sensitivity: 'base' });
      }
      const av = this.submissionTimestampValue(a);
      const bv = this.submissionTimestampValue(b);
      if (sort === 'oldest') return av - bv;
      return bv - av;
    });

    return filtered;
  }

  get pagedSubmissions(): AdminQuizSubmissionListItemDto[] {
    const list = this.filteredSubmissions;
    if (!list.length) return [];
    const start = this.safePageIndex * this.pageSize.value;
    return list.slice(start, start + this.pageSize.value);
  }

  get totalPages(): number {
    const total = this.filteredSubmissions.length;
    return Math.max(1, Math.ceil(total / this.pageSize.value));
  }

  get pageFrom(): number {
    const total = this.filteredSubmissions.length;
    if (!total) return 0;
    return this.safePageIndex * this.pageSize.value + 1;
  }

  get pageTo(): number {
    const total = this.filteredSubmissions.length;
    if (!total) return 0;
    return Math.min(total, this.pageFrom + this.pageSize.value - 1);
  }

  get pageSizeLabel(): string {
    return `Show ${this.pageSize.value}`;
  }

  get safePageIndex(): number {
    return Math.min(this.pageIndex, this.totalPages - 1);
  }

  get pendingReviewCount(): number {
    return this.submissions.filter((item) => this.normalizeModerationStatus(item.moderationStatus) === 'PENDING').length;
  }

  get canModerateSelected(): boolean {
    return this.normalizeModerationStatus(this.selectedSubmission?.moderationStatus) === 'PENDING';
  }

  get canUndoSelectedApproval(): boolean {
    return this.normalizeModerationStatus(this.selectedSubmission?.moderationStatus) === 'APPROVED';
  }

  get selectedSubmissionStatusLabel(): string {
    return this.moderationStatusLabel(this.selectedSubmission?.moderationStatus);
  }

  get selectedSubmissionVersionLabel(): string {
    const version = this.selectedSubmission?.submissionVersion;
    if (version == null || !Number.isFinite(version)) return 'v?';
    return `v${version}`;
  }

  get canModerateSelectedOwner(): boolean {
    const detail = this.selectedSubmission;
    return !!detail?.ownerUserId;
  }

  get selectedOwnerRolesLabel(): string {
    const roles = (this.selectedSubmission?.ownerRoles ?? [])
      .filter((role) => !!role && role !== 'BANNED');
    if (!roles.length) return 'No roles';
    return roles.join(', ');
  }

  get sortLabel(): string {
    const sort = this.sortBy.value;
    if (sort === 'premium_newest') return 'Premium first (newest)';
    if (sort === 'oldest') return 'Oldest first';
    if (sort === 'name_az') return 'Name A-Z';
    if (sort === 'name_za') return 'Name Z-A';
    return 'Newest first';
  }

  get creatorTierLabel(): string {
    const tier = this.creatorTier.value;
    if (tier === 'premium') return 'Premium creators';
    if (tier === 'standard') return 'Standard creators';
    return 'All creators';
  }

  get statusTabTransitionClass(): string {
    return this.transitionClass(this.statusTabTransition, this.statusTabTransitionFlip);
  }

  get detailTabTransitionClass(): string {
    return this.transitionClass(this.detailTabTransition, this.detailTabTransitionFlip);
  }

  get emptySubmissionsLabel(): string {
    if (this.statusTab === 'APPROVED') return 'No passed submissions yet.';
    if (this.statusTab === 'REJECTED') return 'No rejected submissions yet.';
    return 'No submissions waiting for review.';
  }

  get openRowActionsItem(): AdminQuizSubmissionListItemDto | null {
    const id = this.openRowActionsId;
    if (id == null) return null;
    return this.pagedSubmissions.find((item) => item.id === id)
      ?? this.filteredSubmissions.find((item) => item.id === id)
      ?? null;
  }

  get orderedQuestions(): AdminQuestionDto[] {
    const detail = this.selectedSubmission;
    if (!detail) return [];
    return [...(detail.questions ?? [])].sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0));
  }

  get filteredReviewQuestions(): AdminQuestionDto[] {
    const q = this.questionSearch.value.trim().toLowerCase();
    const list = this.orderedQuestions;
    if (!q) return list;
    return list.filter((question) => (question.prompt ?? '').toLowerCase().includes(q));
  }

  get pagedReviewQuestions(): AdminQuestionDto[] {
    const list = this.filteredReviewQuestions;
    if (!list.length) return [];
    const page = Math.min(this.questionPageIndex, this.questionTotalPages - 1);
    const size = this.questionPageSize.value;
    const start = page * size;
    return list.slice(start, start + size);
  }

  get questionTotalPages(): number {
    const total = this.filteredReviewQuestions.length;
    return Math.max(1, Math.ceil(total / this.questionPageSize.value));
  }

  get questionPageFrom(): number {
    const total = this.filteredReviewQuestions.length;
    if (!total) return 0;
    const page = Math.min(this.questionPageIndex, this.questionTotalPages - 1);
    return page * this.questionPageSize.value + 1;
  }

  get questionPageTo(): number {
    const total = this.filteredReviewQuestions.length;
    if (!total) return 0;
    return Math.min(total, this.questionPageFrom + this.questionPageSize.value - 1);
  }

  get questionPageSizeLabel(): string {
    return `Show ${this.questionPageSize.value}`;
  }

  get currentQuestion(): AdminQuestionDto | null {
    const questions = this.orderedQuestions;
    if (!questions.length) return null;
    const safeIndex = Math.max(0, Math.min(this.currentQuestionIndex, questions.length - 1));
    return questions[safeIndex] ?? null;
  }

  get currentQuestionOptions() {
    const question = this.currentQuestion;
    if (!question) return [];
    return [...(question.options ?? [])].sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0));
  }

  get reviewPromptLines(): number {
    return this.computeQuestionPreviewSizing().promptLines;
  }

  get reviewOptionLines(): number {
    return this.computeQuestionPreviewSizing().optionLines;
  }

  get questionPaneHeightPx(): number {
    return this.computeQuestionPreviewSizing().paneHeightPx;
  }

  get activeTabHeightPx(): number | null {
    if (this.detailTab === 'questions') {
      const questionHeight = this.questionPaneHeightPx;
      if (this.detailTabHeightPx == null) return questionHeight;
      return Math.max(this.detailTabHeightPx, questionHeight);
    }
    return this.detailTabHeightPx;
  }

  get selectedQuestionIssueCount(): number {
    return this.selectedQuestionIssueIds.size;
  }

  get questionFeedbackTarget(): AdminQuestionDto | null {
    const id = this.questionFeedbackTargetId;
    if (id == null) return null;
    return this.orderedQuestions.find((question) => question.id === id) ?? null;
  }

  loadSubmissions(): void {
    if (this.loadingList) return;
    this.loadingList = true;
    this.error = null;
    this.closeRowActions();

    this.api.listPendingSubmissions().subscribe({
      next: (items) => {
        this.loadingList = false;
        this.submissions = items ?? [];
        this.hydrateSubmissionAvatars();
      },
      error: (err) => {
        this.loadingList = false;
        this.error = apiErrorMessage(err, 'Failed to load quiz submissions');
      },
    });
  }

  openSubmission(quizId: number): void {
    if (this.loadingDetail) return;
    this.loadingDetail = true;
    this.error = null;
    this.detailTabTransition = 'forward';
    this.closeImagePreview();
    this.closeRowActions();
    this.rejectForm.reset({ reason: '' });
    this.resetQuestionIssuesDraft();

    this.api.getPendingSubmission(quizId).subscribe({
      next: (detail) => {
        this.loadingDetail = false;
        this.selectedSubmission = this.normalizeSubmissionDetail(detail);
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { openQuiz: detail.id },
          queryParamsHandling: 'merge',
          replaceUrl: true,
        });
        this.detailTab = 'details';
        this.detailTabHeightPx = null;
        this.closeQuestionFeedbackModal();
        this.closeRejectFeedbackModal();
        this.currentQuestionIndex = 0;
        this.questionSearch.setValue('');
        this.resetQuestionPage();
        this.resetQuestionIssuesDraft();
        this.scheduleDetailsHeightSync();
        this.flushPendingOpenSubmission(quizId);
      },
      error: (err) => {
        this.loadingDetail = false;
        this.error = apiErrorMessage(err, 'Failed to load submission details');
        this.flushPendingOpenSubmission(quizId);
      },
    });
  }

  backToList(): void {
    this.selectedSubmission = null;
    this.closeImagePreview();
    this.detailTab = 'details';
    this.detailTabHeightPx = null;
    this.closeQuestionFeedbackModal();
    this.closeRejectFeedbackModal();
    this.currentQuestionIndex = 0;
    this.questionSearch.setValue('');
    this.resetQuestionPage();
    this.closeRowActions();
    this.rejectForm.reset({ reason: '' });
    this.resetQuestionIssuesDraft();
    this.error = null;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { openQuiz: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    this.loadSubmissions();
  }

  goHome(): void {
    void this.router.navigate(['/']);
  }

  handleBack(): void {
    if (this.selectedSubmission) {
      this.backToList();
      return;
    }
    this.goHome();
  }

  setStatusTab(tab: 'PENDING' | 'APPROVED' | 'REJECTED'): void {
    if (this.statusTab === tab) return;
    const currentOrder = AdminQuizSubmissionsComponent.STATUS_TAB_ORDER[this.statusTab];
    const nextOrder = AdminQuizSubmissionsComponent.STATUS_TAB_ORDER[tab];
    this.statusTabTransition = nextOrder < currentOrder ? 'backward' : 'forward';
    this.statusTabTransitionFlip = !this.statusTabTransitionFlip;
    this.statusTab = tab;
    this.openMenu = null;
    this.closeRowActions();
    this.resetPage();
  }

  setDetailTab(tab: 'details' | 'questions'): void {
    if (!this.selectedSubmission) return;
    if (this.detailTab === tab) return;
    const currentOrder = AdminQuizSubmissionsComponent.DETAIL_TAB_ORDER[this.detailTab];
    const nextOrder = AdminQuizSubmissionsComponent.DETAIL_TAB_ORDER[tab];
    this.detailTabTransition = nextOrder < currentOrder ? 'backward' : 'forward';
    this.detailTabTransitionFlip = !this.detailTabTransitionFlip;
    if (tab === 'questions' && this.detailTab === 'details') {
      this.syncDetailsHeight();
    }
    this.detailTab = tab;
    if (tab === 'details') {
      this.scheduleDetailsHeightSync();
    }
    this.openMenu = null;
    this.closeRowActions();
  }

  openQuestionFeedbackModal(question: AdminQuestionDto): void {
    if (!this.canModerateSelected) return;
    const questionId = question?.id;
    if (!questionId) return;

    this.closeRejectFeedbackModal();
    this.questionFeedbackTargetId = questionId;
    this.questionFeedbackForm.reset({ message: this.questionIssueMessage(questionId) });
    this.questionFeedbackModalOpen = true;
    const dialog = this.questionFeedbackDialogRef?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  closeQuestionFeedbackModal(): void {
    const dialog = this.questionFeedbackDialogRef?.nativeElement;
    if (dialog?.open) {
      dialog.close();
      return;
    }
    this.onQuestionFeedbackDialogClosed();
  }

  acceptQuestionFeedback(): void {
    const questionId = this.questionFeedbackTargetId;
    if (!questionId) return;
    if (this.questionFeedbackForm.invalid) {
      this.questionFeedbackForm.markAllAsTouched();
      return;
    }

    const message = this.questionFeedbackForm.controls.message.value.trim();
    if (!message) {
      this.questionFeedbackForm.controls.message.setErrors({ required: true });
      this.questionFeedbackForm.markAllAsTouched();
      return;
    }

    this.toggleQuestionIssue(questionId, true);
    this.onQuestionIssueMessage(questionId, message);
    this.closeQuestionFeedbackModal();
  }

  openRejectFeedbackModal(): void {
    if (!this.canModerateSelected) return;
    this.closeQuestionFeedbackModal();
    this.rejectFeedbackModalOpen = true;
    const dialog = this.rejectFeedbackDialogRef?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  closeRejectFeedbackModal(): void {
    const dialog = this.rejectFeedbackDialogRef?.nativeElement;
    if (dialog?.open) {
      dialog.close();
      return;
    }
    this.onRejectFeedbackDialogClosed();
  }

  onQuestionFeedbackDialogClosed(): void {
    this.questionFeedbackModalOpen = false;
    this.questionFeedbackTargetId = null;
    this.questionFeedbackForm.reset({ message: '' });
  }

  onQuestionFeedbackDialogCancel(ev: Event): void {
    ev.preventDefault();
  }

  onRejectFeedbackDialogClosed(): void {
    this.rejectFeedbackModalOpen = false;
  }

  onRejectFeedbackDialogCancel(ev: Event): void {
    ev.preventDefault();
  }

  toggleMenu(menu: 'pageSize' | 'sort' | 'creatorTier' | 'questionPageSize', ev?: Event): void {
    ev?.stopPropagation();
    this.closeRowActions();
    this.openMenu = this.openMenu === menu ? null : menu;
  }

  setPageSize(size: number, ev?: Event): void {
    ev?.stopPropagation();
    this.pageSize.setValue(size);
    this.openMenu = null;
  }

  setSortBy(value: 'premium_newest' | 'newest' | 'oldest' | 'name_az' | 'name_za', ev?: Event): void {
    ev?.stopPropagation();
    this.sortBy.setValue(value);
    this.openMenu = null;
  }

  setCreatorTier(value: 'all' | 'premium' | 'standard', ev?: Event): void {
    ev?.stopPropagation();
    this.creatorTier.setValue(value);
    this.openMenu = null;
  }

  setQuestionPageSize(size: number, ev?: Event): void {
    ev?.stopPropagation();
    this.questionPageSize.setValue(size);
    this.openMenu = null;
  }

  toggleRowActions(id: number, ev?: Event): void {
    ev?.stopPropagation();
    if (this.openRowActionsId === id) {
      this.closeRowActions();
      return;
    }

    this.openMenu = null;
    this.openRowActionsId = id;
    this.lastRowActionsOpenedAtMs = Date.now();

    const item = this.pagedSubmissions.find((entry) => entry.id === id) ?? this.filteredSubmissions.find((entry) => entry.id === id) ?? null;
    const button = ev?.currentTarget instanceof HTMLElement ? ev.currentTarget : null;
    this.rowActionsMenuStyle = button ? this.computeRowActionsMenuStyle(button, item) : null;
  }

  closeRowActions(): void {
    this.openRowActionsId = null;
    this.rowActionsMenuStyle = null;
  }

  openSubmissionFromActions(id: number): void {
    this.closeRowActions();
    this.openSubmission(id);
  }

  undoApproveFromActions(item: AdminQuizSubmissionListItemDto): void {
    const status = this.normalizeModerationStatus(item?.moderationStatus);
    if (!item?.id || status !== 'APPROVED' || this.actionBusy) return;

    this.closeRowActions();
    const confirmed = window.confirm('Reopen review and move this quiz back to Pending?');
    if (!confirmed) return;

    this.actionBusy = true;
    this.error = null;
    this.api.undoApproveSubmission(item.id, item.submissionVersion).subscribe({
      next: () => {
        this.actionBusy = false;
        this.toast.success('Review reopened. Quiz moved back to Pending.', { title: 'Review Submissions' });
        this.loadSubmissions();
      },
      error: (err) => {
        this.actionBusy = false;
        this.error = apiErrorMessage(err, 'Failed to reopen review');
        this.loadSubmissions();
      },
    });
  }

  prevPage(): void {
    const page = this.safePageIndex;
    if (page <= 0) return;
    this.closeRowActions();
    this.pageIndex = page - 1;
  }

  nextPage(): void {
    const page = this.safePageIndex;
    const maxPage = this.totalPages - 1;
    if (page >= maxPage) return;
    this.closeRowActions();
    this.pageIndex = page + 1;
  }

  resetPage(): void {
    this.closeRowActions();
    this.pageIndex = 0;
  }

  resetQuestionPage(): void {
    this.questionPageIndex = 0;
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

  @HostListener('document:click')
  onDocumentClick(): void {
    this.openMenu = null;
    this.closeRowActions();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    const previewDialog = this.imagePreviewDialogRef?.nativeElement;
    if (previewDialog?.open) {
      this.closeImagePreview();
      return;
    }
    if (this.questionFeedbackModalOpen) {
      this.closeQuestionFeedbackModal();
      return;
    }
    if (this.rejectFeedbackModalOpen) {
      this.closeRejectFeedbackModal();
      return;
    }
    this.openMenu = null;
    this.closeRowActions();
  }

  @HostListener('document:keydown.arrowleft', ['$event'])
  onArrowLeft(event: KeyboardEvent): void {
    if (!this.selectedSubmission || this.isTypingTarget(event.target)) return;
    event.preventDefault();
    this.prevQuestion();
  }

  @HostListener('document:keydown.arrowright', ['$event'])
  onArrowRight(event: KeyboardEvent): void {
    if (!this.selectedSubmission || this.isTypingTarget(event.target)) return;
    event.preventDefault();
    this.nextQuestion();
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    this.openMenu = null;
    this.closeRowActions();
    if (this.detailTab === 'details') {
      this.scheduleDetailsHeightSync();
    }
  }

  @HostListener('window:scroll')
  onWindowScroll(): void {
    if (this.openRowActionsId != null && Date.now() - this.lastRowActionsOpenedAtMs < 250) {
      return;
    }
    this.openMenu = null;
    this.closeRowActions();
  }

  approve(): void {
    const detail = this.selectedSubmission;
    if (!detail || !this.canModerateSelected || this.actionBusy) return;

    this.actionBusy = true;
    this.error = null;
    this.api.approveSubmission(detail.id, detail.submissionVersion).subscribe({
      next: () => {
        this.actionBusy = false;
        this.toast.success('Quiz approved and published.', { title: 'Review Submissions' });
        this.backToList();
      },
      error: (err) => {
        this.actionBusy = false;
        if (this.handleOutdatedSubmission(err)) return;
        this.error = apiErrorMessage(err, 'Failed to approve submission');
      },
    });
  }

  undoApproveSelected(): void {
    const detail = this.selectedSubmission;
    if (!detail || !this.canUndoSelectedApproval || this.actionBusy) return;

    const confirmed = window.confirm('Reopen review and move this quiz back to Pending?');
    if (!confirmed) return;

    this.actionBusy = true;
    this.error = null;
    this.api.undoApproveSubmission(detail.id, detail.submissionVersion).subscribe({
      next: () => {
        this.actionBusy = false;
        this.toast.success('Review reopened. Quiz moved back to Pending.', { title: 'Review Submissions' });
        this.openSubmission(detail.id);
        this.loadSubmissions();
      },
      error: (err) => {
        this.actionBusy = false;
        if (this.handleOutdatedSubmission(err)) return;
        this.error = apiErrorMessage(err, 'Failed to reopen review');
      },
    });
  }

  reject(): void {
    const detail = this.selectedSubmission;
    if (!detail || !this.canModerateSelected || this.actionBusy) return;
    if (this.rejectForm.invalid) {
      this.rejectForm.markAllAsTouched();
      this.openRejectFeedbackModal();
      return;
    }

    const reason = this.rejectForm.controls.reason.value.trim();
    if (!reason) {
      this.rejectForm.controls.reason.setErrors({ required: true });
      this.rejectForm.markAllAsTouched();
      this.openRejectFeedbackModal();
      return;
    }

    const questionIssues = this.buildQuestionIssuesPayload();
    const hasIssueValidationError = questionIssues.some((issue) => !issue.message.trim());
    if (hasIssueValidationError) {
      this.error = 'Provide feedback text for each selected question.';
      this.openRejectFeedbackModal();
      return;
    }

    this.actionBusy = true;
    this.error = null;
    this.api.rejectSubmission(detail.id, detail.submissionVersion, reason, questionIssues).subscribe({
      next: () => {
        this.actionBusy = false;
        this.closeRejectFeedbackModal();
        this.toast.success('Quiz rejected. Feedback sent.', { title: 'Review Submissions' });
        this.backToList();
      },
      error: (err) => {
        this.actionBusy = false;
        if (this.handleOutdatedSubmission(err)) return;
        this.error = apiErrorMessage(err, 'Failed to reject submission');
      },
    });
  }

  banSubmissionOwner(): void {
    const detail = this.selectedSubmission;
    if (!detail?.ownerUserId || this.actionBusy) return;
    const ownerLabel = detail.ownerDisplayName || detail.ownerEmail || `User #${detail.ownerUserId}`;
    const confirmed = detail.ownerBanned
      ? window.confirm(`Unban ${ownerLabel}?`)
      : window.confirm(`Ban ${ownerLabel} and revoke active sessions?`);
    if (!confirmed) return;

    this.actionBusy = true;
    this.error = null;
    const request$ = detail.ownerBanned
      ? this.api.unbanSubmissionOwner(detail.id)
      : this.api.banSubmissionOwner(detail.id);
    request$.subscribe({
      next: (result) => {
        this.actionBusy = false;
        this.applyOwnerModeration(result);
        if (result.banned) {
          this.toast.warning('User has been banned.', { title: 'Review Submissions' });
        } else {
          this.toast.success('User has been unbanned.', { title: 'Review Submissions' });
        }
      },
      error: (err) => {
        this.actionBusy = false;
        this.error = apiErrorMessage(
          err,
          detail.ownerBanned ? 'Failed to unban submission owner' : 'Failed to ban submission owner'
        );
      },
    });
  }

  removeSubmissionAvatarImage(): void {
    const detail = this.selectedSubmission;
    if (!detail || this.actionBusy) return;
    const avatarUrl = (detail.avatarImageUrl ?? '').trim();
    if (!avatarUrl) return;

    const confirmed = window.confirm('Remove this avatar image from quiz and server storage?');
    if (!confirmed) return;

    this.actionBusy = true;
    this.error = null;
    this.api.removeSubmissionAvatarImage(detail.id).subscribe({
      next: (updated) => {
        this.actionBusy = false;
        this.applyUpdatedSubmission(updated);
        this.toast.success('Avatar image removed.', { title: 'Review Submissions' });
      },
      error: (err) => {
        this.actionBusy = false;
        this.error = apiErrorMessage(err, 'Failed to remove avatar image');
      },
    });
  }

  removeQuestionImage(question: AdminQuestionDto): void {
    const detail = this.selectedSubmission;
    const questionId = question?.id;
    if (!detail || !questionId || this.actionBusy) return;
    if (!(question.imageUrl ?? '').trim()) return;

    const confirmed = window.confirm('Remove this question image from quiz and server storage?');
    if (!confirmed) return;

    this.actionBusy = true;
    this.error = null;
    this.api.removeSubmissionQuestionImage(detail.id, questionId).subscribe({
      next: (updated) => {
        this.actionBusy = false;
        this.applyUpdatedSubmission(updated);
        this.closeImagePreview();
        this.toast.success('Question image removed.', { title: 'Review Submissions' });
      },
      error: (err) => {
        this.actionBusy = false;
        this.error = apiErrorMessage(err, 'Failed to remove question image');
      },
    });
  }

  removeOptionImage(question: AdminQuestionDto, optionId: number): void {
    const detail = this.selectedSubmission;
    const questionId = question?.id;
    if (!detail || !questionId || !optionId || this.actionBusy) return;

    const confirmed = window.confirm('Remove this option image from quiz and server storage?');
    if (!confirmed) return;

    this.actionBusy = true;
    this.error = null;
    this.api.removeSubmissionOptionImage(detail.id, questionId, optionId).subscribe({
      next: (updated) => {
        this.actionBusy = false;
        this.applyUpdatedSubmission(updated);
        this.toast.success('Option image removed.', { title: 'Review Submissions' });
      },
      error: (err) => {
        this.actionBusy = false;
        this.error = apiErrorMessage(err, 'Failed to remove option image');
      },
    });
  }

  openQuestionImagePreview(question: AdminQuestionDto): void {
    const questionId = question?.id;
    if (!questionId) return;
    const imageUrl = (question.imageUrl ?? '').trim();
    if (!imageUrl) return;
    this.previewQuestionImageId = questionId;
    this.openImagePreview(imageUrl, 'Question image', question.prompt || 'Question image');
  }

  openImagePreview(url: string | null | undefined, alt: string, name?: string): void {
    const value = (url ?? '').trim();
    if (!value) return;
    this.previewImageUrl = value;
    this.previewImageAlt = (alt ?? '').trim() || 'Image preview';
    this.previewImageName = (name ?? '').trim() || this.previewImageAlt;
    const dialog = this.imagePreviewDialogRef?.nativeElement;
    if (!dialog || dialog.open) return;
    dialog.showModal();
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

  removePreviewQuestionImage(): void {
    const questionId = this.previewQuestionImageId;
    if (!questionId || this.actionBusy) return;
    const target = this.orderedQuestions.find((question) => question.id === questionId);
    if (!target) return;
    this.removeQuestionImage(target);
  }

  titleInitial(title: string | null | undefined): string {
    const t = (title ?? '').trim();
    if (!t) return '?';

    const words = t.split(/\s+/).filter(Boolean);
    const pickFirstAlphaNum = (value: string): string => {
      for (const ch of Array.from(value)) {
        if (/[\p{L}\p{N}]/u.test(ch)) return ch;
      }
      return '';
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

  submissionAvatarStyle(item: AdminQuizSubmissionListItemDto): Record<string, string> {
    return this.quizAvatarStyle(this.submissionAvatarData(item));
  }

  submissionHasAvatarImage(item: AdminQuizSubmissionListItemDto): boolean {
    const data = this.submissionAvatarData(item);
    return !!(data.avatarImageUrl ?? '').trim();
  }

  hasQuizAvatarImage(detail: AdminQuizSubmissionDetailDto | null | undefined): boolean {
    return !!(detail?.avatarImageUrl ?? '').trim();
  }

  detailAvatarColor(
    detail: AdminQuizSubmissionDetailDto | null | undefined,
    channel: 'bgStart' | 'bgEnd' | 'text'
  ): string {
    const fallback = channel === 'bgStart'
      ? '#30D0FF'
      : channel === 'bgEnd'
        ? '#2F86FF'
        : '#0A0E1C';
    const value = channel === 'bgStart'
      ? detail?.avatarBgStart
      : channel === 'bgEnd'
        ? detail?.avatarBgEnd
        : detail?.avatarTextColor;
    const normalized = (value ?? '').trim();
    return normalized || fallback;
  }

  private handleOutdatedSubmission(error: unknown): boolean {
    if (!(error instanceof HttpErrorResponse) || error.status !== 409) return false;
    const message = apiErrorMessage(
      error,
      'Submission changed during review. Queue has been refreshed to show the latest version.'
    );
    this.toast.warning(message, { title: 'Review Submissions' });
    this.backToList();
    return true;
  }

  isQuestionIssueSelected(questionId: number): boolean {
    return this.selectedQuestionIssueIds.has(questionId);
  }

  toggleQuestionIssue(questionId: number, selected: boolean): void {
    if (selected) {
      this.selectedQuestionIssueIds.add(questionId);
      if (!(questionId in this.questionIssueNotes)) {
        this.questionIssueNotes[questionId] = '';
      }
      return;
    }

    this.selectedQuestionIssueIds.delete(questionId);
    delete this.questionIssueNotes[questionId];
  }

  questionIssueMessage(questionId: number): string {
    return this.questionIssueNotes[questionId] ?? '';
  }

  onQuestionIssueMessage(questionId: number, value: string): void {
    if (!this.selectedQuestionIssueIds.has(questionId)) return;
    this.questionIssueNotes[questionId] = value;
  }

  setCurrentQuestionIndex(index: number): void {
    const maxIndex = this.orderedQuestions.length - 1;
    if (maxIndex < 0) {
      this.currentQuestionIndex = 0;
      return;
    }
    this.currentQuestionIndex = Math.max(0, Math.min(index, maxIndex));
    this.syncQuestionPageWithCurrent();
    if (this.detailTab === 'details') {
      this.scheduleDetailsHeightSync();
    }
  }

  onQuestionRowClick(question: AdminQuestionDto): void {
    const index = this.orderedQuestions.findIndex((q) => q.id === question.id);
    if (index < 0) return;
    this.setCurrentQuestionIndex(index);
  }

  prevQuestion(): void {
    this.setCurrentQuestionIndex(this.currentQuestionIndex - 1);
  }

  nextQuestion(): void {
    this.setCurrentQuestionIndex(this.currentQuestionIndex + 1);
  }

  moderationStatusLabel(raw: string | null | undefined): 'Pending' | 'Passed' | 'Rejected' {
    const status = this.normalizeModerationStatus(raw);
    if (status === 'APPROVED') return 'Passed';
    if (status === 'REJECTED') return 'Rejected';
    return 'Pending';
  }

  moderationStatusClass(raw: string | null | undefined): 'pending' | 'approved' | 'rejected' {
    const status = this.normalizeModerationStatus(raw);
    if (status === 'APPROVED') return 'approved';
    if (status === 'REJECTED') return 'rejected';
    return 'pending';
  }

  statusTabCount(tab: 'PENDING' | 'APPROVED' | 'REJECTED'): number {
    return this.submissions.filter((item) => this.normalizeModerationStatus(item.moderationStatus) === tab).length;
  }

  submissionTimestampLabel(item: AdminQuizSubmissionListItemDto): string {
    const timestamp = this.submissionTimestampValue(item);
    if (!Number.isFinite(timestamp)) return 'Date unavailable';
    const date = new Date(timestamp);
    if (Number.isNaN(date.getTime())) return 'Date unavailable';
    const day = String(date.getDate()).padStart(2, '0');
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const year = date.getFullYear();
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    return `${day}.${month}.${year} | ${hours}:${minutes}`;
  }

  trackBySubmissionId(_: number, item: AdminQuizSubmissionListItemDto): number {
    return item.id;
  }

  trackByQuestionId(_: number, question: AdminQuestionDto): number {
    return question.id;
  }

  trackByOptionId(_: number, option: { id: number }): number {
    return option.id;
  }

  private resetImagePreviewState(): void {
    this.previewImageUrl = null;
    this.previewImageAlt = 'Question image preview';
    this.previewImageName = '';
    this.previewQuestionImageId = null;
  }

  private applyUpdatedSubmission(updated: AdminQuizSubmissionDetailDto): void {
    const previousQuestionId = this.currentQuestion?.id ?? null;
    const nextDetail = this.normalizeSubmissionDetail(updated);
    this.selectedSubmission = nextDetail;

    const ordered = this.orderedQuestions;
    if (!ordered.length) {
      this.currentQuestionIndex = 0;
      this.resetQuestionPage();
    } else if (previousQuestionId != null) {
      const idx = ordered.findIndex((question) => question.id === previousQuestionId);
      this.currentQuestionIndex = idx >= 0 ? idx : Math.min(this.currentQuestionIndex, ordered.length - 1);
    } else {
      this.currentQuestionIndex = Math.min(this.currentQuestionIndex, ordered.length - 1);
    }

    this.syncQuestionPageWithCurrent();
    if (this.detailTab === 'details') {
      this.scheduleDetailsHeightSync();
    }
  }

  private applyOwnerModeration(result: AdminSubmissionOwnerModerationDto): void {
    const detail = this.selectedSubmission;
    if (!detail || !result?.userId || detail.ownerUserId !== result.userId) return;
    this.selectedSubmission = {
      ...detail,
      ownerBanned: !!result.banned,
      ownerRoles: [...(result.roles ?? [])],
    };
    if (this.detailTab === 'details') {
      this.scheduleDetailsHeightSync();
    }
  }

  private scheduleDetailsHeightSync(): void {
    if (this.heightSyncScheduled) return;
    this.heightSyncScheduled = true;
    requestAnimationFrame(() => {
      this.heightSyncScheduled = false;
      this.syncDetailsHeight();
    });
  }

  private syncDetailsHeight(): void {
    const element = this.detailsSectionRef?.nativeElement;
    if (!element) return;
    const height = element.getBoundingClientRect().height;
    if (!Number.isFinite(height) || height <= 0) return;
    const normalized = Number(height.toFixed(2));
    if (this.detailTabHeightPx == null || Math.abs(this.detailTabHeightPx - normalized) > 0.25) {
      this.detailTabHeightPx = normalized;
    }
  }

  private normalizeSubmissionDetail(detail: AdminQuizSubmissionDetailDto): AdminQuizSubmissionDetailDto {
    return {
      ...detail,
      ownerBanned: !!detail.ownerBanned,
      ownerRoles: [...(detail.ownerRoles ?? [])],
    };
  }

  private computeRowActionsMenuStyle(
    button: HTMLElement,
    item: AdminQuizSubmissionListItemDto | null
  ): Record<string, string> {
    const menuWidth = 188;
    const gap = 8;
    const viewportPad = 12;
    const estimatedMenuHeight = this.rowActionsMenuEstimatedHeight(item);

    const triggerRect = button.getBoundingClientRect();
    let leftViewport = triggerRect.right - menuWidth;
    leftViewport = Math.max(viewportPad, Math.min(leftViewport, window.innerWidth - menuWidth - viewportPad));

    const viewportH = window.innerHeight;
    let topViewport = triggerRect.bottom + gap;
    if (topViewport + estimatedMenuHeight > viewportH - viewportPad) {
      topViewport = triggerRect.top - gap - estimatedMenuHeight;
    }
    topViewport = Math.max(viewportPad, Math.min(topViewport, viewportH - estimatedMenuHeight - viewportPad));

    const host = button.closest('.picker-list') as HTMLElement | null;
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

  private rowActionsMenuEstimatedHeight(item: AdminQuizSubmissionListItemDto | null): number {
    const status = this.normalizeModerationStatus(item?.moderationStatus);
    return status === 'APPROVED' ? 96 : 58;
  }

  private submissionAvatarData(
    item: AdminQuizSubmissionListItemDto
  ): {
    avatarImageUrl?: string | null;
    avatarBgStart?: string | null;
    avatarBgEnd?: string | null;
    avatarTextColor?: string | null;
  } {
    const override = this.submissionAvatarOverrides.get(item.id);
    if (override) return override;
    return item;
  }

  private hydrateSubmissionAvatars(): void {
    if (this.avatarLookupInFlight) return;
    this.avatarLookupInFlight = true;
    this.api.listQuizzes().subscribe({
      next: (quizzes) => {
        const map = new Map<number, Pick<AdminQuizListItemDto, 'avatarImageUrl' | 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor'>>();
        for (const quiz of quizzes ?? []) {
          map.set(quiz.id, {
            avatarImageUrl: quiz.avatarImageUrl ?? null,
            avatarBgStart: quiz.avatarBgStart ?? null,
            avatarBgEnd: quiz.avatarBgEnd ?? null,
            avatarTextColor: quiz.avatarTextColor ?? null,
          });
        }
        this.submissionAvatarOverrides = map;
      },
      error: () => {
        // Keep fallback from submissions payload if avatar enrichment fails.
        this.avatarLookupInFlight = false;
      },
      complete: () => {
        this.avatarLookupInFlight = false;
      },
    });
  }

  private normalizeModerationStatus(raw: string | null | undefined): 'PENDING' | 'APPROVED' | 'REJECTED' {
    const value = String(raw ?? '').trim().toUpperCase();
    if (value === 'APPROVED') return 'APPROVED';
    if (value === 'REJECTED') return 'REJECTED';
    return 'PENDING';
  }

  private resetQuestionIssuesDraft(): void {
    this.selectedQuestionIssueIds.clear();
    this.questionIssueNotes = {};
  }

  private submissionTimestampValue(item: AdminQuizSubmissionListItemDto): number {
    const raw = String(item.moderationUpdatedAt ?? '').trim();
    if (!raw) return 0;
    const timestamp = Date.parse(raw);
    if (!Number.isFinite(timestamp)) return 0;
    return timestamp;
  }

  private buildQuestionIssuesPayload(): AdminQuestionIssueInputDto[] {
    return this.orderedQuestions
      .filter((question) => this.selectedQuestionIssueIds.has(question.id))
      .map((question) => ({
        questionId: question.id,
        message: (this.questionIssueNotes[question.id] ?? '').trim(),
      }));
  }

  private syncQuestionPageWithCurrent(): void {
    const current = this.currentQuestion;
    if (!current) return;
    const filtered = this.filteredReviewQuestions;
    const filteredIndex = filtered.findIndex((question) => question.id === current.id);
    if (filteredIndex < 0) return;
    const size = this.questionPageSize.value;
    const targetPage = Math.floor(filteredIndex / size);
    if (targetPage !== this.questionPageIndex) {
      this.questionPageIndex = targetPage;
    }
  }

  private isTypingTarget(target: EventTarget | null): boolean {
    const node = target as HTMLElement | null;
    if (!node) return false;
    return !!node.closest('input, textarea, [contenteditable="true"]');
  }

  private computeQuestionPreviewSizing(): { promptLines: number; optionLines: number; paneHeightPx: number } {
    const question = this.currentQuestion;
    if (!question) {
      const fallback = Math.max(this.detailTabHeightPx ?? 0, 520);
      return { promptLines: 2, optionLines: 1, paneHeightPx: fallback };
    }

    const viewportWidth = typeof window !== 'undefined' && Number.isFinite(window.innerWidth) && window.innerWidth > 0
      ? window.innerWidth
      : 1366;
    const promptCharsPerLine = viewportWidth >= 1700 ? 96 : viewportWidth >= 1500 ? 86 : viewportWidth >= 1300 ? 76 : viewportWidth >= 1150 ? 68 : 60;
    const optionCharsPerLine = viewportWidth >= 1700 ? 90 : viewportWidth >= 1500 ? 80 : viewportWidth >= 1300 ? 70 : viewportWidth >= 1150 ? 62 : 54;

    const promptLines = this.estimateTextLines(question.prompt ?? '', promptCharsPerLine, 7);
    const options = this.currentQuestionOptions;
    const optionsCount = Math.max(1, options.length || 4);
    const hasQuestionImage = !!(question.imageUrl ?? '').trim();
    const maxOptionLines = Math.max(
      1,
      ...options.map((option) => this.estimateTextLines(option.text ?? '', optionCharsPerLine, 5))
    );

    const promptHeight = 22 + promptLines * 22;
    const optionHeight = 16 + maxOptionLines * 22;
    const imageRowHeight = hasQuestionImage ? 50 : 0;
    const optionsBlockHeight = optionsCount * optionHeight + Math.max(0, optionsCount - 1) * 10;
    const chromeHeight = 210;
    const paneHeightPx = Math.max(520, Math.ceil(chromeHeight + promptHeight + imageRowHeight + optionsBlockHeight));

    return { promptLines, optionLines: maxOptionLines, paneHeightPx };
  }

  private estimateTextLines(value: string, charsPerLine: number, maxLines: number): number {
    const raw = String(value ?? '');
    const fragments = raw.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    if (!fragments.length) return 1;
    let lines = 0;
    const perLine = Math.max(24, charsPerLine);
    for (const fragment of fragments) {
      lines += Math.max(1, Math.ceil(fragment.length / perLine));
    }
    return Math.max(1, Math.min(maxLines, lines));
  }

  private transitionClass(direction: 'forward' | 'backward', flip: boolean): string {
    if (direction === 'backward') {
      return flip ? 'view-screen--enter-back-a' : 'view-screen--enter-back-b';
    }
    return flip ? 'view-screen--enter-forward-a' : 'view-screen--enter-forward-b';
  }

  private handleOpenQuizQueryParam(rawQuizId: string | null): void {
    const quizId = this.parsePositiveInt(rawQuizId);
    if (quizId == null) {
      this.pendingOpenSubmissionId = null;
      return;
    }
    if (this.selectedSubmission?.id === quizId) {
      this.pendingOpenSubmissionId = null;
      return;
    }
    if (this.loadingDetail) {
      this.pendingOpenSubmissionId = quizId;
      return;
    }
    this.pendingOpenSubmissionId = null;
    this.openSubmission(quizId);
  }

  private flushPendingOpenSubmission(currentQuizId: number): void {
    const queuedQuizId = this.pendingOpenSubmissionId;
    this.pendingOpenSubmissionId = null;
    if (queuedQuizId == null || queuedQuizId === currentQuizId) return;
    if (this.selectedSubmission?.id === queuedQuizId) return;
    this.openSubmission(queuedQuizId);
  }

  private parsePositiveInt(raw: string | null): number | null {
    if (raw == null) return null;
    const value = Number(raw);
    if (!Number.isFinite(value)) return null;
    const normalized = Math.trunc(value);
    return normalized > 0 ? normalized : null;
  }

}
