import { CommonModule } from '@angular/common';
import {
  AfterViewInit,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  OnInit,
  QueryList,
  ViewChildren,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription, catchError, firstValueFrom, map, of, switchMap } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import {
  CasualApi,
  CasualThreeLivesBestDto,
} from '../../core/api/casual.api';
import { GameApi } from '../../core/api/game.api';
import { QuizApi } from '../../core/api/quiz.api';
import { SoloGameApi } from '../../core/api/solo-game.api';
import { ActiveGameDto, GameStartMode, GameStateDto } from '../../core/models/game.models';
import { QuizListItemDto } from '../../core/models/quiz.models';
import { SessionService } from '../../core/session/session.service';
import { ToastService } from '../../core/ui/toast.service';

type CasualModeId = 'standard' | 'threeLives' | 'training';
type CasualSelectionView = 'summary' | 'picker';
type CasualSelectionTransition = 'forward' | 'backward';

type CasualModeOption = {
  id: CasualModeId;
  title: string;
  description: string;
  details: string;
  backendMode: GameStartMode;
  icon: 'standard' | 'threeLives' | 'training';
};

@Component({
  selector: 'app-casual',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './casual.component.html',
  styleUrl: './casual.component.scss',
})
export class CasualComponent implements OnInit, AfterViewInit, OnDestroy {
  readonly modes: ReadonlyArray<CasualModeOption> = [
    {
      id: 'standard',
      title: 'Standard Solo',
      description: 'Pick category and start a normal solo run.',
      details:
        'Uses casual non-ranked quizzes. Rewards depend on quiz settings (XP and coins, no rank).',
      backendMode: 'STANDARD',
      icon: 'standard',
    },
    {
      id: 'threeLives',
      title: '3 Lives Challenge',
      description: 'Endless question run.',
      details:
        'Questions are rotated from the selected category pool. Best result is stored on backend per guest/user session.',
      backendMode: 'THREE_LIVES',
      icon: 'threeLives',
    },
    {
      id: 'training',
      title: 'Training',
      description: 'Practice mode without question timer.',
      details:
        'No XP, no coins, no rank points. Great for stress-free drilling in selected category.',
      backendMode: 'TRAINING',
      icon: 'training',
    },
  ];

  activeMode: CasualModeId = 'standard';
  selectedQuizId: number | null = null;
  quizzesLoading = true;
  recordLoading = false;
  starting = false;
  activeSoloActionInProgress = false;
  quizzes: QuizListItemDto[] = [];
  threeLivesBest: CasualThreeLivesBestDto | null = null;
  activeSoloGame: ActiveGameDto | null = null;
  activeSoloState: GameStateDto | null = null;
  pickerScope: 'official' | 'custom' | 'library' = 'official';
  pickerSort: 'az' | 'za' = 'az';
  selectionView: CasualSelectionView = 'summary';
  selectionTransition: CasualSelectionTransition = 'forward';
  sortMenuOpen = false;
  categorySelected: string[] = [];
  categoryMenuOpen = false;
  categoryMenuSearch = '';
  quizSearch = '';
  categoryOptions: ReadonlyArray<{ name: string | null; label: string; count: number }> = [];

  private readonly subscriptions = new Subscription();
  private marqueeRafId: number | null = null;

  @ViewChildren('quizTitle')
  private quizTitleEls?: QueryList<ElementRef<HTMLElement>>;

  constructor(
    private readonly gameApi: GameApi,
    private readonly quizApi: QuizApi,
    private readonly casualApi: CasualApi,
    private readonly soloGameApi: SoloGameApi,
    private readonly sessionService: SessionService,
    private readonly toast: ToastService,
    private readonly router: Router
  ) {}

  get activeModeConfig(): CasualModeOption {
    return this.modes.find((mode) => mode.id === this.activeMode) ?? this.modes[0];
  }

  get modeEligibleQuizzes(): QuizListItemDto[] {
    return this.quizzes;
  }

  get pickerSortLabel(): string {
    return this.pickerSort === 'za' ? 'Name Z-A' : 'Name A-Z';
  }

  get hasSelectedCategory(): boolean {
    return this.categorySelected.length > 0;
  }

  get selectedQuiz(): QuizListItemDto | null {
    const id = this.selectedQuizId;
    if (id == null) return null;
    return this.modeEligibleQuizzes.find((q) => q.id === id) ?? null;
  }

  get categorySelectedLabel(): string {
    const count = this.categorySelected.length;
    if (!count) return 'All categories';
    if (count === 1) return this.categorySelected[0] ?? 'All categories';
    return `Categories (${count})`;
  }

  get categoryCalloutTitle(): string {
    if (this.categorySelected.length === 1) return this.categorySelected[0] ?? 'No category selected';
    if (this.categorySelected.length > 1) return `Categories (${this.categorySelected.length})`;
    const quizCategory = (this.selectedQuiz?.categoryName ?? '').trim();
    return quizCategory || 'No category selected';
  }

  get categoryCalloutHint(): string {
    if (!this.selectedQuizId) return 'Click to choose category and quiz.';
    return 'Click to change category and quiz.';
  }

  get categoryMenuItems(): string[] {
    const items = (this.categoryOptions ?? [])
      .map((c) => (c.name ?? '').trim())
      .filter(Boolean);
    return Array.from(new Set(items)).sort((a, b) => a.localeCompare(b, 'pl'));
  }

  get categoryMenuItemsFiltered(): string[] {
    const needle = (this.categoryMenuSearch ?? '').trim().toLowerCase();
    if (!needle) return this.categoryMenuItems;
    return this.categoryMenuItems.filter((c) => c.toLowerCase().includes(needle));
  }

  get filteredQuizzes(): QuizListItemDto[] {
    const selectedCategories = this.categorySelected
      .map((c) => c.trim().toLowerCase())
      .filter(Boolean);

    const needle = (this.quizSearch ?? '').trim().toLowerCase();
    const scope = this.pickerScope;

    const matchesScope = (q: QuizListItemDto): boolean => {
      const raw = (q as any)?.source ?? (q as any)?.scope ?? (q as any)?.type ?? null;
      if (typeof raw !== 'string' || !raw.trim()) return true;
      const v = raw.trim().toLowerCase();
      if (v === 'official') return scope === 'official';
      if (v === 'custom') return scope === 'custom';
      if (v === 'library') return scope === 'library';
      if (v === 'user') return scope !== 'official';
      return true;
    };

    const filtered = this.modeEligibleQuizzes.filter((q) => {
      if (!matchesScope(q)) return false;

      if (selectedCategories.length) {
        const c = (q.categoryName ?? '').trim().toLowerCase();
        if (!c || !selectedCategories.includes(c)) return false;
      }

      if (!needle) return true;
      const title = (q.title ?? '').toLowerCase();
      const desc = (q.description ?? '').toLowerCase();
      const cat = (q.categoryName ?? '').toLowerCase();
      return title.includes(needle) || desc.includes(needle) || cat.includes(needle);
    });

    return filtered.slice().sort((a, b) => {
      const at = (a.title ?? '').toLowerCase();
      const bt = (b.title ?? '').toLowerCase();
      return this.pickerSort === 'za' ? bt.localeCompare(at) : at.localeCompare(bt);
    });
  }

  get canStartMode(): boolean {
    if (this.starting || this.activeSoloActionInProgress) return false;
    return !!this.selectedQuizId;
  }

  get threeLivesAnsweredBest(): string {
    const answered = this.threeLivesBest?.answered;
    if (typeof answered !== 'number' || !Number.isFinite(answered) || answered < 0) {
      return '-';
    }
    return String(Math.floor(answered));
  }

  get hasActiveSoloGame(): boolean {
    const sessionId = (this.activeSoloGame?.gameSessionId ?? '').trim();
    return sessionId.length > 0;
  }

  get activeSoloModeLabel(): string {
    const mode = String(this.activeSoloState?.mode ?? '').trim().toUpperCase();
    if (mode === 'TRAINING') return 'Training';
    if (mode === 'THREE_LIVES') return '3 Lives Challenge';
    return 'Standard Solo';
  }

  ngOnInit(): void {
    this.sessionService.ensure().subscribe({
      next: () => this.loadThreeLivesBestRecord(),
      error: () => this.loadThreeLivesBestRecord(),
    });
    this.loadActiveSoloGame();
    this.loadQuizzes();
  }

  ngAfterViewInit(): void {
    this.scheduleMarqueeMeasure();
    if (this.quizTitleEls) {
      this.subscriptions.add(
        this.quizTitleEls.changes.subscribe(() => this.scheduleMarqueeMeasure())
      );
    }
  }

  ngOnDestroy(): void {
    if (this.marqueeRafId != null) {
      cancelAnimationFrame(this.marqueeRafId);
      this.marqueeRafId = null;
    }
    this.subscriptions.unsubscribe();
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    this.scheduleMarqueeMeasure();
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(ev: MouseEvent): void {
    if (!this.categoryMenuOpen && !this.sortMenuOpen) return;
    const target = ev.target as HTMLElement | null;
    if (!target) return;
    if (target.closest('.mr-select-wrap')) return;
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
  }

  @HostListener('document:keydown', ['$event'])
  onDocumentKeydown(ev: KeyboardEvent): void {
    if (ev.key !== 'Escape') return;
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
  }

  setActiveMode(modeId: CasualModeId): void {
    this.activeMode = modeId;
    this.selectionView = 'summary';
    this.recomputeCategoryOptions();
    this.ensureSelectedCategoriesStillAvailable();
    this.ensureQuizSelection();
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
    this.scheduleMarqueeMeasure();
    if (modeId === 'threeLives') {
      this.loadThreeLivesBestRecord();
    }
  }

  modeAccentClass(mode: CasualModeOption): 'accent--cyan' | 'accent--orange' | 'accent--purple' {
    if (mode.id === 'threeLives') return 'accent--orange';
    if (mode.id === 'training') return 'accent--purple';
    return 'accent--cyan';
  }

  setPickerScope(scope: 'official' | 'custom' | 'library'): void {
    this.pickerScope = scope;
    this.ensureQuizSelection();
    this.scheduleMarqueeMeasure();
  }

  openPicker(): void {
    this.selectionTransition = 'forward';
    this.selectionView = 'picker';
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
    this.scheduleMarqueeMeasure();
  }

  closePicker(): void {
    this.selectionTransition = 'backward';
    this.selectionView = 'summary';
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
    this.scheduleMarqueeMeasure();
  }

  toggleCategoryMenu(): void {
    const next = !this.categoryMenuOpen;
    this.categoryMenuOpen = next;
    if (next) this.categoryMenuSearch = '';
    this.sortMenuOpen = false;
  }

  clearCategories(): void {
    this.categorySelected = [];
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
    this.ensureQuizSelection();
    this.scheduleMarqueeMeasure();
  }

  isCategorySelected(name: string): boolean {
    return this.categorySelected.includes(name);
  }

  toggleCategory(name: string): void {
    if (this.isCategorySelected(name)) {
      this.categorySelected = this.categorySelected.filter((c) => c !== name);
    } else {
      this.categorySelected = [...this.categorySelected, name].sort((a, b) =>
        a.localeCompare(b, 'pl')
      );
    }
    this.ensureQuizSelection();
    this.scheduleMarqueeMeasure();
  }

  toggleSortMenu(): void {
    this.sortMenuOpen = !this.sortMenuOpen;
    if (this.sortMenuOpen) this.categoryMenuOpen = false;
  }

  selectSort(sort: 'az' | 'za'): void {
    this.pickerSort = sort;
    this.sortMenuOpen = false;
    this.ensureQuizSelection();
    this.scheduleMarqueeMeasure();
  }

  onQuizSearchChange(): void {
    this.ensureQuizSelection();
    this.scheduleMarqueeMeasure();
  }

  selectQuiz(quizId: number): void {
    this.selectedQuizId = quizId;
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
    this.closePicker();
  }

  quizAvatarStyle(quiz: QuizListItemDto): Record<string, string> {
    const imageUrl = this.normalizeNullableUrl(quiz.avatarImageUrl ?? null);
    const start =
      this.normalizeNullableColor(quiz.avatarBgStart ?? null) ?? 'rgba(56, 189, 248, 0.4)';
    const end = this.normalizeNullableColor(quiz.avatarBgEnd ?? null);
    const textColor =
      this.normalizeNullableColor(quiz.avatarTextColor ?? null) ?? 'rgba(255, 255, 255, 0.94)';

    if (imageUrl) {
      return {
        'background-image': `url(${imageUrl})`,
        'background-size': 'cover',
        'background-position': 'center',
        color: textColor,
      };
    }

    if (end) {
      return {
        'background-image': `linear-gradient(180deg, ${start}, ${end})`,
        color: textColor,
      };
    }

    return {
      background: start,
      color: textColor,
    };
  }

  quizInitials(title: string): string {
    const t = (title ?? '').trim();
    if (!t) return '?';
    const parts = t.split(/\s+/).filter(Boolean);
    const first = parts[0]?.[0] ?? '?';
    const second = parts.length > 1 ? (parts[1]?.[0] ?? '') : (parts[0]?.[1] ?? '');
    return (first + second).toUpperCase();
  }

  formatDuration(durationMs: number): string {
    const totalSeconds = Math.max(0, Math.floor(durationMs / 1000));
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${String(seconds).padStart(2, '0')}`;
  }

  startMode(): void {
    if (this.hasActiveSoloGame) {
      void this.endActiveSoloGameAndStartSelected();
      return;
    }
    void this.startSoloMode(this.activeModeConfig.backendMode);
  }

  private loadQuizzes(): void {
    this.quizzesLoading = true;

    this.quizApi
      .list()
      .pipe(
        map((rows) => [...rows]),
        catchError((err) => {
          this.showError(apiErrorMessage(err, 'Failed to load quizzes'));
          return of([] as QuizListItemDto[]);
        })
      )
      .subscribe((rows) => {
        this.quizzes = rows;
        this.quizzesLoading = false;
        this.recomputeCategoryOptions();
    this.ensureSelectedCategoriesStillAvailable();
        this.ensureQuizSelection();
        this.scheduleMarqueeMeasure();
      });
  }

  private ensureSelectedCategoriesStillAvailable(): void {
    if (!this.categorySelected.length) return;
    const allowed = new Set(this.categoryMenuItems);
    this.categorySelected = this.categorySelected.filter((name) => allowed.has(name));
  }

  private recomputeCategoryOptions(): void {
    const counts = new Map<string, number>();
    for (const q of this.modeEligibleQuizzes) {
      const name = (q.categoryName ?? '').trim();
      if (!name) continue;
      counts.set(name, (counts.get(name) ?? 0) + 1);
    }

    const entries = Array.from(counts.entries())
      .sort((a, b) => a[0].localeCompare(b[0], 'pl'))
      .map(([name, count]) => ({ name, label: name, count }));

    this.categoryOptions = [
      { name: null, label: 'All', count: this.modeEligibleQuizzes.length },
      ...entries,
    ];
  }

  private ensureQuizSelection(): void {
    const visible = this.filteredQuizzes;
    if (!visible.length) {
      this.selectedQuizId = null;
      return;
    }
    const selected = this.selectedQuizId;
    const stillVisible =
      selected != null && visible.some((quiz) => quiz.id === selected);
    if (stillVisible) return;
    this.selectedQuizId = visible[0]?.id ?? null;
  }

  private normalizeNullableUrl(url: string | null): string | null {
    const t = (url ?? '').trim();
    return t ? t : null;
  }

  private normalizeNullableColor(color: string | null): string | null {
    const t = (color ?? '').trim();
    return t ? t : null;
  }

  resumeActiveSoloGame(): void {
    const sessionId = (this.activeSoloGame?.gameSessionId ?? '').trim();
    if (!sessionId) return;
    void this.router.navigate(['/solo-game', sessionId]);
  }

  endActiveSoloGame(): void {
    void this.endActiveSoloGameInternal(false);
  }

  endActiveSoloGameAndStartSelected(): Promise<void> {
    return this.endActiveSoloGameInternal(true);
  }

  private async endActiveSoloGameInternal(startSelectedModeAfterEnd: boolean): Promise<void> {
    const activeSessionId = (this.activeSoloGame?.gameSessionId ?? '').trim();
    if (!activeSessionId) {
      if (startSelectedModeAfterEnd) {
        await this.startSoloMode(this.activeModeConfig.backendMode);
      }
      return;
    }

    this.activeSoloActionInProgress = true;
    try {
      await firstValueFrom(this.sessionService.ensure());
      await firstValueFrom(this.soloGameApi.end(activeSessionId));
      this.activeSoloGame = null;
      this.activeSoloState = null;

      if (startSelectedModeAfterEnd) {
        await this.startSoloMode(this.activeModeConfig.backendMode);
        return;
      }

      this.toast.success('Previous solo session closed.', {
        title: 'Casual',
        dedupeKey: 'casual:active-solo-closed',
      });
    } catch (err) {
      this.showError(
        apiErrorMessage(
          err,
          startSelectedModeAfterEnd
            ? 'Failed to close current game and start a new one'
            : 'Failed to close current game'
        )
      );
    } finally {
      this.activeSoloActionInProgress = false;
      this.loadActiveSoloGame();
    }
  }

  private loadActiveSoloGame(): void {
    this.gameApi
      .current()
      .pipe(
        catchError(() => of(null)),
        switchMap((current) => {
          const type = String(current?.type ?? '').trim().toUpperCase();
          const sessionId = String(current?.gameSessionId ?? '').trim();
          const isSolo = type === 'SOLO' && sessionId.length > 0;
          if (!isSolo) {
            return of({ game: null as ActiveGameDto | null, state: null as GameStateDto | null });
          }

          return this.soloGameApi.state(sessionId).pipe(
            map((state) => ({ game: current as ActiveGameDto, state })),
            catchError(() => of({ game: null as ActiveGameDto | null, state: null as GameStateDto | null }))
          );
        })
      )
      .subscribe(({ game, state }) => {
        if (!game || !state) {
          this.activeSoloGame = null;
          this.activeSoloState = null;
          return;
        }

        if (state.stage === 'FINISHED' || state.stage === 'NO_GAME') {
          this.activeSoloGame = null;
          this.activeSoloState = null;
          return;
        }

        this.activeSoloGame = game;
        this.activeSoloState = state;
      });
  }

  private async startSoloMode(mode: GameStartMode): Promise<void> {
    const quizId = this.selectedQuizId;
    if (!quizId) {
      this.toast.warning('Select quiz first.', {
        title: 'Casual',
        dedupeKey: 'casual:select-quiz-first',
      });
      return;
    }

    this.starting = true;

    try {
      await firstValueFrom(this.sessionService.ensure());
      const state = await firstValueFrom(this.soloGameApi.start(quizId, mode));
      const gameSessionId = (state.gameSessionId ?? '').trim();
      if (!gameSessionId) {
        throw new Error('Missing game session id');
      }
      const navigated = await this.router.navigate(['/solo-game', gameSessionId]);
      if (!navigated) {
        throw new Error('Failed to open game view');
      }
    } catch (err) {
      this.showError(apiErrorMessage(err, 'Failed to start solo game'));
      if ((err as any)?.status === 409) {
        this.loadActiveSoloGame();
      }
    } finally {
      this.starting = false;
    }
  }

  private showError(message: string | null): void {
    if (!message) return;
    this.toast.error(message, {
      title: 'Casual',
      dedupeKey: `casual:error:${message}`,
    });
  }

  private loadThreeLivesBestRecord(forceRefresh = false): void {
    if (!forceRefresh && this.recordLoading) return;
    this.recordLoading = true;

    this.casualApi
      .threeLivesBest()
      .pipe(
        catchError((err) => {
          this.toast.warning(apiErrorMessage(err, 'Failed to load best 3 lives result'), {
            title: 'Casual',
            dedupeKey: 'casual:three-lives-best-error',
          });
          return of(null);
        })
      )
      .subscribe((record) => {
        this.recordLoading = false;
        this.threeLivesBest = record;
      });
  }

  private scheduleMarqueeMeasure(): void {
    if (this.marqueeRafId != null) {
      cancelAnimationFrame(this.marqueeRafId);
    }
    this.marqueeRafId = requestAnimationFrame(() => {
      this.marqueeRafId = null;
      this.updateTitleOverflow();
    });
  }

  private updateTitleOverflow(): void {
    const items = this.quizTitleEls?.toArray() ?? [];
    for (const item of items) {
      const host = item.nativeElement;
      const primary = host.querySelector<HTMLElement>(
        '.quiz-card__titleOnce:not([aria-hidden="true"])'
      );
      if (!primary) continue;
      const hasOverflow = primary.scrollWidth > host.clientWidth + 1;
      host.classList.toggle('is-overflow', hasOverflow);
    }
  }
}
