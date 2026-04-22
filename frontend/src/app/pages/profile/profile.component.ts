import { CommonModule } from '@angular/common';
import {
  AfterViewInit,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  QueryList,
  ViewChild,
  ViewChildren,
  inject,
} from '@angular/core';
import { Router } from '@angular/router';
import { Subscription, catchError, combineLatest, interval, map, of, shareReplay, startWith, switchMap } from 'rxjs';
import { AchievementApi, AchievementItemDto, AchievementListResponseDto } from '../../core/api/achievement.api';
import { apiErrorMessage } from '../../core/api/api-error.util';
import { LibraryQuizApi, LibraryQuizListItemDto } from '../../core/api/library-quiz.api';
import { AuthService } from '../../core/auth/auth.service';
import {
  activeBonusIconPath,
  activeBonusRemainingLabel,
  buildActiveBonuses,
} from '../../core/bonus/active-bonuses';
import { computeLevelProgress, levelTheme, rankForPoints } from '../../core/progression/progression';
import { ProfileInsightsService } from '../../core/profile/profile-insights.service';
import { PlayerAvatarComponent } from '../../core/ui/player-avatar.component';
import { PremiumBadgeComponent } from '../../core/ui/premium-badge.component';
import { ToastService } from '../../core/ui/toast.service';

type ProfileTab = 'stats' | 'achievements' | 'quizzes';
type QuizSort = 'nameAsc' | 'nameDesc';
type QuizFilterMenu = 'category' | 'sort';

interface ProfileQuizListState {
  paged: LibraryQuizListItemDto[];
  totalCount: number;
  totalPages: number;
  page: number;
  from: number;
  to: number;
}

const EMPTY_ACHIEVEMENTS: AchievementListResponseDto = {
  title: 'Achievements',
  description: 'Unlock milestones by playing and progressing.',
  totalCount: 0,
  unlockedCount: 0,
  completionPct: 0,
  items: [],
};

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, PlayerAvatarComponent, PremiumBadgeComponent],
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.scss',
})
export class ProfileComponent implements AfterViewInit, OnDestroy {
  private static readonly DISPLAY_NAME_CHANGE_COST = 50_000;
  private static readonly DISPLAY_NAME_CHANGE_COOLDOWN_MS = 7 * 24 * 60 * 60 * 1000;
  private static readonly DISPLAY_NAME_RE = /^[A-Za-z0-9 _-]{3,32}$/;
  private static readonly TAB_ORDER: Record<ProfileTab, number> = {
    stats: 0,
    achievements: 1,
    quizzes: 2,
  };
  private static readonly ACHIEVEMENT_TIER_ORDER: Record<string, number> = {
    rookie: 0,
    bronze: 1,
    silver: 2,
    gold: 3,
    platinum: 4,
    diamond: 5,
    master: 6,
    legend: 7,
    mythic: 8,
  };

  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly profileInsights = inject(ProfileInsightsService);
  private readonly libraryQuizApi = inject(LibraryQuizApi);
  private readonly achievementApi = inject(AchievementApi);
  private readonly toast = inject(ToastService);
  private readonly bonusNow$ = interval(30_000).pipe(startWith(0), map(() => Date.now()));
  private readonly subscriptions = new Subscription();
  private marqueeRafId: number | null = null;
  private tabHeightRafId: number | null = null;
  statsTabHeightPx: number | null = null;

  @ViewChildren('profileName')
  private profileNameEls?: QueryList<ElementRef<HTMLElement>>;
  @ViewChild('profileTabView')
  private profileTabViewEl?: ElementRef<HTMLElement>;

  tab: ProfileTab = 'stats';
  tabTransition: 'forward' | 'backward' = 'forward';
  private tabTransitionFlip = false;
  quizSearchQuery = '';
  quizCategory = 'all';
  quizSort: QuizSort = 'nameAsc';
  quizPageIndex = 0;
  readonly quizPageSize = 6;
  openQuizMenuId: number | null = null;
  openQuizFilterMenu: QuizFilterMenu | null = null;
  previewQuiz: LibraryQuizListItemDto | null = null;
  displayNameModalOpen = false;
  displayNameDraft = '';
  displayNameSubmitting = false;

  readonly approvedQuizzes$ = this.auth.user$.pipe(
    switchMap((user) => {
      if (!user) return of([] as LibraryQuizListItemDto[]);
      return this.libraryQuizApi.listMy().pipe(
        catchError(() => of([] as LibraryQuizListItemDto[]))
      );
    }),
    map((quizzes) =>
      (quizzes ?? [])
        .filter((quiz) => quiz.status === 'ACTIVE' && quiz.moderationStatus === 'APPROVED')
        .sort((a, b) => this.sortByDateThenIdDesc(a, b))
    ),
    shareReplay({ bufferSize: 1, refCount: true })
  );

  readonly achievements$ = this.auth.user$.pipe(
    switchMap((user) => {
      if (!user) return of(EMPTY_ACHIEVEMENTS);
      return this.achievementApi.mine().pipe(
        catchError(() => of(EMPTY_ACHIEVEMENTS))
      );
    }),
    shareReplay({ bufferSize: 1, refCount: true })
  );

  readonly vm$ = combineLatest([this.auth.user$, this.profileInsights.state$, this.approvedQuizzes$, this.achievements$, this.bonusNow$]).pipe(
    map(([user, insights, approvedQuizzes, achievements, nowMs]) => {
      if (!user) return null;
      this.reconcileQuizPagination(approvedQuizzes);
      const rank = rankForPoints(user.rankPoints ?? 0);
      const level = computeLevelProgress(user.xp ?? 0);
      const theme = levelTheme(level.level);
      const progressPct = Math.max(0, Math.min(100, Math.round(level.progress * 100)));
      const winsTotal = Math.max(0, insights.winsTotal ?? 0);
      const gamesPlayed = Math.max(0, insights.totalGames ?? 0);
      const multiplayerGames = Math.max(0, insights.lobbyGames ?? 0);
      const winRatioPct = multiplayerGames > 0
        ? Math.max(0, Math.min(100, Math.round((winsTotal / multiplayerGames) * 100)))
        : 0;
      const rawAchievementItems = Array.isArray(achievements.items) ? achievements.items : [];
      const achievementItems = rawAchievementItems.map((item) => this.normalizeAchievementForDisplay(item));
      const sortedAchievementItems = [...achievementItems].sort((a, b) => this.compareAchievements(a, b));
      const progressionAchievementItems = this.buildProgressionAchievementItems(sortedAchievementItems);
      const achievementPreviewItems = progressionAchievementItems.slice(0, 4);
      const achievementsTotal = achievementItems.length;
      const achievementsUnlocked = achievementItems.filter((item) => item.unlocked === true).length;
      const achievementsCompletionPct = achievementsTotal > 0
        ? Math.max(0, Math.min(100, Math.round((achievementsUnlocked * 100) / achievementsTotal)))
        : 0;
      const displayNameNextChangeAtMs = this.nextDisplayNameChangeAt(user.lastDisplayNameChangeAt);
      const displayNameCooldownActive = displayNameNextChangeAtMs != null && displayNameNextChangeAtMs > nowMs;
      const displayNameCanAfford = (user.coins ?? 0) >= ProfileComponent.DISPLAY_NAME_CHANGE_COST;
      const displayNameCanChangeNow = !displayNameCooldownActive && displayNameCanAfford;
      const activeBonuses = buildActiveBonuses(user, nowMs)
        .map((bonus) => ({
          key: bonus.key,
          label: bonus.label,
          iconPath: activeBonusIconPath(bonus.key),
          remainingLabel: activeBonusRemainingLabel(bonus.expiresAtMs, nowMs),
          expiresLabel: this.formatDateTimeFromMillis(bonus.expiresAtMs) ?? 'No expiry',
        }))
        .sort((a, b) => (a.key === 'premium' ? -1 : b.key === 'premium' ? 1 : 0));

      return {
        displayName: user.displayName,
        isPremium: (user.roles ?? []).includes('PREMIUM'),
        roleTitle: this.resolveRoleTitle(user.roles),
        rankName: rank.name,
        rankColor: rank.color,
        rankColor2: this.tintHex(rank.color, 0.28),
        rankSoft: this.withAlpha(rank.color, 0.16, '170,179,194'),
        rankGlow: this.withAlpha(rank.color, 0.34, '170,179,194'),
        ringDim: theme.ringDim,
        rankPoints: user.rankPoints ?? 0,
        totalXp: user.xp ?? 0,
        coins: user.coins ?? 0,
        level: level.level,
        progress: level.progress,
        xpInLevel: level.xpInLevel,
        levelXpNeeded: Math.max(1, level.levelXpEnd - level.levelXpStart),
        xpToNext: level.xpToNext,
        progressPct,
        maxLevel: level.maxLevel,
        gamesPlayed,
        winsTotal,
        winRatioPct,
        correctAnswers: insights.correctAnswersTotal,
        accountCreatedLabel: this.formatDateTimeLabel(user.createdAt),
        displayNameChangeCost: ProfileComponent.DISPLAY_NAME_CHANGE_COST,
        displayNameCanAfford,
        displayNameCooldownActive,
        displayNameCanChangeNow,
        displayNameNextChangeLabel: this.formatDateTimeFromMillis(displayNameNextChangeAtMs),
        activeBonuses,
        achievementsTitle: String(achievements.title ?? '').trim() || 'Achievements',
        achievementsDescription: String(achievements.description ?? '').trim()
          || 'Unlock milestones by playing and progressing.',
        achievementsTotal,
        achievementsUnlocked,
        achievementsCompletionPct,
        achievementsItems: progressionAchievementItems,
        achievementPreviewItems,
        approvedQuizzes,
      };
    })
  );

  ngAfterViewInit(): void {
    this.scheduleProfileNameMeasure();
    this.scheduleStatsTabHeightMeasure();
    if (this.profileNameEls) {
      this.subscriptions.add(
        this.profileNameEls.changes.subscribe(() => this.scheduleProfileNameMeasure())
      );
    }
  }

  ngOnDestroy(): void {
    if (this.marqueeRafId != null) {
      cancelAnimationFrame(this.marqueeRafId);
      this.marqueeRafId = null;
    }
    if (this.tabHeightRafId != null) {
      cancelAnimationFrame(this.tabHeightRafId);
      this.tabHeightRafId = null;
    }
    this.subscriptions.unsubscribe();
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    this.scheduleProfileNameMeasure();
    this.scheduleStatsTabHeightMeasure();
  }

  get tabTransitionClass(): string {
    if (this.tabTransition === 'backward') {
      return this.tabTransitionFlip ? 'profile-tab-view--enter-back-a' : 'profile-tab-view--enter-back-b';
    }
    return this.tabTransitionFlip ? 'profile-tab-view--enter-forward-a' : 'profile-tab-view--enter-forward-b';
  }

  setTab(tab: ProfileTab): void {
    if (this.tab === tab) return;
    if (this.tab === 'stats') {
      this.captureStatsTabHeight();
    }
    const currentOrder = ProfileComponent.TAB_ORDER[this.tab];
    const nextOrder = ProfileComponent.TAB_ORDER[tab];
    this.tabTransition = nextOrder < currentOrder ? 'backward' : 'forward';
    this.tabTransitionFlip = !this.tabTransitionFlip;
    this.tab = tab;
    this.closeQuizMenus();
    if (tab !== 'quizzes') {
      this.previewQuiz = null;
    }
    if (tab === 'stats') {
      this.scheduleStatsTabHeightMeasure();
    }
  }

  onQuizSearchInput(event: Event): void {
    const target = event.target as HTMLInputElement | null;
    const next = String(target?.value ?? '');
    if (this.quizSearchQuery === next) return;
    this.quizSearchQuery = next;
    this.quizPageIndex = 0;
    this.closeQuizMenus();
  }

  onQuizCategoryChange(value: string): void {
    const next = String(value ?? '').trim() || 'all';
    if (this.quizCategory === next) return;
    this.quizCategory = next;
    this.quizPageIndex = 0;
    this.closeQuizMenus();
  }

  onQuizSortChange(value: string): void {
    const next: QuizSort = value === 'nameDesc' ? 'nameDesc' : 'nameAsc';
    if (this.quizSort === next) return;
    this.quizSort = next;
    this.quizPageIndex = 0;
    this.closeQuizMenus();
  }

  get quizCategoryLabel(): string {
    return this.quizCategory === 'all' ? 'All categories' : this.quizCategory;
  }

  get quizSortLabel(): string {
    return this.quizSort === 'nameDesc' ? 'Name Z-A' : 'Name A-Z';
  }

  toggleQuizFilterMenu(menu: QuizFilterMenu, event: Event): void {
    event.stopPropagation();
    this.openQuizMenuId = null;
    this.openQuizFilterMenu = this.openQuizFilterMenu === menu ? null : menu;
  }

  setQuizCategory(category: string, event?: Event): void {
    event?.stopPropagation();
    this.onQuizCategoryChange(category);
    this.openQuizFilterMenu = null;
  }

  setQuizSort(sort: QuizSort, event?: Event): void {
    event?.stopPropagation();
    this.onQuizSortChange(sort);
    this.openQuizFilterMenu = null;
  }

  quizCategoryOptions(quizzes: readonly LibraryQuizListItemDto[]): string[] {
    const set = new Set<string>();
    for (const quiz of quizzes) {
      const category = String(quiz.categoryName ?? '').trim();
      if (category) set.add(category);
    }
    return Array.from(set).sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
  }

  quizListState(quizzes: readonly LibraryQuizListItemDto[]): ProfileQuizListState {
    const filtered = this.filteredApprovedQuizzes(quizzes);
    const totalCount = filtered.length;
    const totalPages = Math.max(1, Math.ceil(totalCount / this.quizPageSize));
    const pageIndex = Math.max(0, Math.min(this.quizPageIndex, totalPages - 1));
    const start = totalCount > 0 ? pageIndex * this.quizPageSize : 0;
    const paged = filtered.slice(start, start + this.quizPageSize);
    const from = totalCount > 0 ? start + 1 : 0;
    const to = totalCount > 0 ? start + paged.length : 0;
    return {
      paged,
      totalCount,
      totalPages,
      page: pageIndex + 1,
      from,
      to,
    };
  }

  prevQuizPage(): void {
    this.quizPageIndex = Math.max(0, this.quizPageIndex - 1);
    this.closeQuizMenus();
  }

  nextQuizPage(totalPages: number): void {
    const safeTotalPages = Math.max(1, Math.floor(totalPages || 1));
    this.quizPageIndex = Math.min(safeTotalPages - 1, this.quizPageIndex + 1);
    this.closeQuizMenus();
  }

  closeQuizMenu(): void {
    this.closeQuizMenus();
  }

  closeQuizMenus(): void {
    this.openQuizMenuId = null;
    this.openQuizFilterMenu = null;
  }

  toggleQuizMenu(quizId: number, event: Event): void {
    event.stopPropagation();
    this.openQuizFilterMenu = null;
    this.openQuizMenuId = this.openQuizMenuId === quizId ? null : quizId;
  }

  openQuizPreview(quiz: LibraryQuizListItemDto, event?: Event): void {
    event?.stopPropagation();
    this.openQuizMenuId = null;
    this.openQuizFilterMenu = null;
    this.previewQuiz = quiz;
  }

  closeQuizPreview(): void {
    this.previewQuiz = null;
  }

  openQuizInLibrary(quiz: Pick<LibraryQuizListItemDto, 'id'>): void {
    this.previewQuiz = null;
    this.closeQuizMenus();
    void this.router.navigate(['/library'], { queryParams: { previewQuizId: quiz.id } });
  }

  quizTimeLabel(quiz: Pick<LibraryQuizListItemDto, 'questionTimeLimitSeconds'>): string {
    const seconds = Number(quiz.questionTimeLimitSeconds);
    if (!Number.isFinite(seconds) || seconds <= 0) return '--';
    return `${Math.round(seconds)}s`;
  }

  hasQuizAvatarImage(quiz: Pick<LibraryQuizListItemDto, 'avatarImageUrl'>): boolean {
    return !!String(quiz.avatarImageUrl ?? '').trim();
  }

  quizAvatarStyle(quiz: Pick<LibraryQuizListItemDto, 'avatarImageUrl' | 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor'>): Record<string, string> {
    const img = String(quiz.avatarImageUrl ?? '').trim();
    const start = String(quiz.avatarBgStart ?? '').trim() || '#30D0FF';
    const end = String(quiz.avatarBgEnd ?? '').trim() || '#2F86FF';
    const text = String(quiz.avatarTextColor ?? '').trim() || '#0A0E1C';
    if (img) {
      return {
        'background-image': `url(${img})`,
        'background-size': 'cover',
        'background-position': 'center',
        color: 'transparent',
      };
    }
    return {
      background: `linear-gradient(135deg, ${start}, ${end})`,
      color: text,
    };
  }

  titleInitial(title: string | null | undefined): string {
    const trimmed = String(title ?? '').trim();
    if (!trimmed) return 'Q';
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
    return initials || 'Q';
  }

  trackByQuizId(_: number, quiz: Pick<LibraryQuizListItemDto, 'id'>): number {
    return quiz.id;
  }

  trackByAchievement(_: number, item: Pick<AchievementItemDto, 'key'>): string {
    return String(item.key ?? '');
  }

  achievementProgressPct(item: Pick<AchievementItemDto, 'progress' | 'target'>): number {
    const target = Math.max(1, Math.floor(Number(item.target) || 1));
    const progress = Math.max(0, Math.floor(Number(item.progress) || 0));
    return Math.max(0, Math.min(100, Math.round((Math.min(progress, target) / target) * 100)));
  }

  achievementTierStart(item: Pick<AchievementItemDto, 'tierColor'>): string {
    return this.tintHex(this.normalizeAchievementTierColor(item.tierColor), 0.28);
  }

  achievementTierEnd(item: Pick<AchievementItemDto, 'tierColor'>): string {
    return this.normalizeAchievementTierColor(item.tierColor);
  }

  achievementUnlockedLabel(value: string | null | undefined): string | null {
    const raw = String(value ?? '').trim();
    if (!raw) return null;
    const millis = Date.parse(raw);
    if (!Number.isFinite(millis)) return null;
    const date = new Date(millis);
    const day = this.pad2(date.getDate());
    const month = this.pad2(date.getMonth() + 1);
    const year = date.getFullYear();
    return `${day}.${month}.${year}`;
  }

  achievementIconClasses(value: string | null | undefined): string {
    const raw = String(value ?? '').trim();
    return raw || 'fa-solid fa-trophy';
  }

  showAllAchievements(): void {
    this.setTab('achievements');
  }

  goBack(): void {
    if (this.tab === 'achievements') {
      this.setTab('stats');
      return;
    }
    void this.router.navigate(['/']);
  }

  openDisplayNameModal(): void {
    const user = this.auth.snapshot;
    if (!user) return;
    this.displayNameDraft = String(user.displayName ?? '');
    this.displayNameSubmitting = false;
    this.displayNameModalOpen = true;
  }

  closeDisplayNameModal(): void {
    if (this.displayNameSubmitting) return;
    this.displayNameModalOpen = false;
  }

  onDisplayNameDraftInput(event: Event): void {
    const target = event.target as HTMLInputElement | null;
    this.displayNameDraft = String(target?.value ?? '');
  }

  submitDisplayNameChange(): void {
    if (this.displayNameSubmitting) return;
    const user = this.auth.snapshot;
    if (!user) return;

    const nextAllowedAt = this.nextDisplayNameChangeAt(user.lastDisplayNameChangeAt);
    if (nextAllowedAt != null && nextAllowedAt > Date.now()) {
      const nextLabel = this.formatDateTimeFromMillis(nextAllowedAt);
      this.showDisplayNameAlert(nextLabel
        ? `Nickname can be changed once every 7 days. Next change available on ${nextLabel}.`
        : 'Nickname can be changed once every 7 days.');
      return;
    }

    if ((user.coins ?? 0) < ProfileComponent.DISPLAY_NAME_CHANGE_COST) {
      this.showDisplayNameAlert(`Not enough coins. Nickname change costs ${ProfileComponent.DISPLAY_NAME_CHANGE_COST.toLocaleString()} coins.`);
      return;
    }

    const nextDisplayName = this.displayNameDraft.trim();
    if (nextDisplayName.length < 3 || nextDisplayName.length > 32) {
      this.showDisplayNameAlert('Nickname must be 3-32 characters.');
      return;
    }
    if (!ProfileComponent.DISPLAY_NAME_RE.test(nextDisplayName)) {
      this.showDisplayNameAlert('Nickname contains invalid characters.');
      return;
    }
    if (nextDisplayName === String(user.displayName ?? '').trim()) {
      this.showDisplayNameAlert('New nickname must be different from current nickname.');
      return;
    }

    const confirmed = window.confirm(
      `Are you sure you want to buy nickname change to "${nextDisplayName}" for ${ProfileComponent.DISPLAY_NAME_CHANGE_COST.toLocaleString()} coins?`
    );
    if (!confirmed) {
      return;
    }

    this.displayNameSubmitting = true;
    const request = this.auth.updateDisplayName(nextDisplayName).subscribe({
      next: () => {
        this.displayNameSubmitting = false;
        this.displayNameModalOpen = false;
        this.displayNameDraft = '';
        this.scheduleProfileNameMeasure();
      },
      error: (error: unknown) => {
        this.displayNameSubmitting = false;
        this.showDisplayNameAlert(apiErrorMessage(error, 'Failed to change nickname'), 'error');
      },
    });
    this.subscriptions.add(request);
  }

  private showDisplayNameAlert(message: string, tone: 'warning' | 'error' = 'warning'): void {
    const normalized = String(message ?? '').trim();
    if (!normalized) return;
    const options = {
      title: 'Profile',
      dedupeKey: `profile:nickname:${tone}:${normalized}`,
    };
    if (tone === 'error') {
      this.toast.error(normalized, options);
      return;
    }
    this.toast.warning(normalized, options);
  }

  private reconcileQuizPagination(quizzes: readonly LibraryQuizListItemDto[]): void {
    const filtered = this.filteredApprovedQuizzes(quizzes);
    const totalPages = Math.max(1, Math.ceil(filtered.length / this.quizPageSize));
    this.quizPageIndex = Math.max(0, Math.min(this.quizPageIndex, totalPages - 1));
    if (!filtered.some((quiz) => quiz.id === this.openQuizMenuId)) {
      this.openQuizMenuId = null;
    }
    if (this.previewQuiz && !filtered.some((quiz) => quiz.id === this.previewQuiz?.id)) {
      this.previewQuiz = null;
    }
  }

  private filteredApprovedQuizzes(quizzes: readonly LibraryQuizListItemDto[]): LibraryQuizListItemDto[] {
    const q = this.quizSearchQuery.trim().toLowerCase();
    const category = this.quizCategory;
    const filtered = (quizzes ?? []).filter((quiz) => {
      const quizCategory = String(quiz.categoryName ?? '').trim();
      const categoryOk = category === 'all' || quizCategory === category;
      if (!categoryOk) return false;

      if (!q) return true;
      const title = String(quiz.title ?? '').toLowerCase();
      const description = String(quiz.description ?? '').toLowerCase();
      const categoryLower = quizCategory.toLowerCase();
      return title.includes(q) || description.includes(q) || categoryLower.includes(q);
    });

    if (this.quizSort === 'nameDesc') {
      filtered.sort((a, b) => String(b.title ?? '').localeCompare(String(a.title ?? ''), undefined, { sensitivity: 'base' }));
    } else {
      filtered.sort((a, b) => String(a.title ?? '').localeCompare(String(b.title ?? ''), undefined, { sensitivity: 'base' }));
    }
    return filtered;
  }

  private sortByDateThenIdDesc(a: LibraryQuizListItemDto, b: LibraryQuizListItemDto): number {
    const aDate = this.timestamp(a.moderationUpdatedAt);
    const bDate = this.timestamp(b.moderationUpdatedAt);
    if (aDate !== bDate) return bDate - aDate;
    return b.id - a.id;
  }

  private timestamp(iso: string | null | undefined): number {
    const value = Date.parse(String(iso ?? ''));
    return Number.isFinite(value) ? value : 0;
  }

  private compareAchievements(a: AchievementItemDto, b: AchievementItemDto): number {
    const aUnlocked = a.unlocked === true ? 1 : 0;
    const bUnlocked = b.unlocked === true ? 1 : 0;
    if (aUnlocked !== bUnlocked) return bUnlocked - aUnlocked;

    const aUnlockedAt = this.timestamp(a.unlockedAt);
    const bUnlockedAt = this.timestamp(b.unlockedAt);
    if (aUnlockedAt !== bUnlockedAt) return bUnlockedAt - aUnlockedAt;

    const aProgress = this.achievementProgressPct(a);
    const bProgress = this.achievementProgressPct(b);
    if (aProgress !== bProgress) return bProgress - aProgress;

    return String(a.title ?? '').localeCompare(String(b.title ?? ''), undefined, { sensitivity: 'base' });
  }

  private buildProgressionAchievementItems(items: AchievementItemDto[]): AchievementItemDto[] {
    const groups = new Map<string, AchievementItemDto[]>();
    for (const item of items) {
      const key = this.achievementTrackKey(item);
      const current = groups.get(key);
      if (current) {
        current.push(item);
      } else {
        groups.set(key, [item]);
      }
    }

    const visibleItems: AchievementItemDto[] = [];
    for (const groupItems of groups.values()) {
      const ordered = [...groupItems].sort((a, b) => this.compareAchievementTier(a, b));
      const unlockedInTrack = ordered
        .filter((item) => item.unlocked === true)
        .sort((a, b) => this.compareAchievementTier(b, a));
      const nextLocked = ordered.find((item) => item.unlocked !== true);
      visibleItems.push(...unlockedInTrack);
      if (nextLocked) {
        visibleItems.push(nextLocked);
      }
    }

    const rankedItems = visibleItems.map((item) => ({
      item,
      unlocked: item.unlocked === true ? 1 : 0,
      tierOrder: this.achievementTierOrder(item.tier),
      progressPct: this.achievementProgressPct(item),
      unlockedAt: this.timestamp(item.unlockedAt),
    }));

    rankedItems.sort((a, b) => {
      const aUnlocked = a.unlocked;
      const bUnlocked = b.unlocked;
      if (aUnlocked !== bUnlocked) {
        return bUnlocked - aUnlocked;
      }
      if (a.tierOrder !== b.tierOrder) {
        return b.tierOrder - a.tierOrder;
      }
      if (aUnlocked === 1 && a.unlockedAt !== b.unlockedAt) {
        return b.unlockedAt - a.unlockedAt;
      }
      if (aUnlocked === 0 && a.progressPct !== b.progressPct) {
        return b.progressPct - a.progressPct;
      }
      return String(a.item.title ?? '').localeCompare(String(b.item.title ?? ''), undefined, { sensitivity: 'base' });
    });

    return rankedItems.map((entry) => entry.item);
  }

  private normalizeAchievementForDisplay(item: AchievementItemDto): AchievementItemDto {
    const target = Math.max(1, Math.floor(Number(item.target) || 1));
    const rawProgress = Math.max(0, Math.floor(Number(item.progress) || 0));
    const progress = Math.min(rawProgress, target);
    const unlockedNow = progress >= target;
    return {
      ...item,
      target,
      progress,
      unlocked: unlockedNow,
      unlockedAt: unlockedNow ? item.unlockedAt : null,
    };
  }

  private compareAchievementTier(a: AchievementItemDto, b: AchievementItemDto): number {
    const aTier = this.achievementTierOrder(a.tier);
    const bTier = this.achievementTierOrder(b.tier);
    if (aTier !== bTier) return aTier - bTier;
    const aTarget = Math.max(0, Number(a.target) || 0);
    const bTarget = Math.max(0, Number(b.target) || 0);
    if (aTarget !== bTarget) return aTarget - bTarget;
    return String(a.key ?? '').localeCompare(String(b.key ?? ''), undefined, { sensitivity: 'base' });
  }

  private achievementTrackKey(item: Pick<AchievementItemDto, 'key' | 'tier'>): string {
    const rawKey = String(item.key ?? '').trim();
    if (!rawKey) return '__unknown__';
    const tier = this.normalizedTierKey(item.tier);
    if (!tier) return rawKey;
    const suffix = `_${tier}`;
    if (!rawKey.endsWith(suffix)) return rawKey;
    return rawKey.slice(0, rawKey.length - suffix.length);
  }

  private achievementTierOrder(value: string | null | undefined): number {
    const key = this.normalizedTierKey(value);
    if (!key) return Number.MAX_SAFE_INTEGER;
    return ProfileComponent.ACHIEVEMENT_TIER_ORDER[key];
  }

  private normalizedTierKey(value: string | null | undefined): string | null {
    const raw = String(value ?? '').trim().toLowerCase();
    if (!raw) return null;
    if (!(raw in ProfileComponent.ACHIEVEMENT_TIER_ORDER)) return null;
    return raw;
  }

  private resolveRoleTitle(roles: readonly string[] | null | undefined): string {
    const normalized = new Set((roles ?? []).map((role) => String(role ?? '').trim().toUpperCase()));
    if (normalized.has('ADMIN')) return 'Administrator';
    if (normalized.has('PREMIUM')) return 'Premium User';
    return 'User';
  }

  private normalizeAchievementTierColor(value: string | null | undefined): string {
    const raw = String(value ?? '').trim();
    if (/^#[0-9a-fA-F]{6}$/.test(raw)) return raw;
    return '#9AA4B2';
  }

  private hexToRgb(hex: string): { r: number; g: number; b: number } | null {
    const normalized = String(hex ?? '').replace('#', '').trim();
    if (normalized.length !== 6) return null;
    const num = Number.parseInt(normalized, 16);
    if (!Number.isFinite(num)) return null;
    return { r: (num >> 16) & 255, g: (num >> 8) & 255, b: num & 255 };
  }

  private withAlpha(hex: string, alpha: number, fallback = '255,255,255'): string {
    const rgb = this.hexToRgb(hex);
    const clamped = Math.max(0, Math.min(1, alpha));
    if (!rgb) return `rgba(${fallback},${clamped})`;
    return `rgba(${rgb.r},${rgb.g},${rgb.b},${clamped})`;
  }

  private tintHex(hex: string, amount: number): string {
    const rgb = this.hexToRgb(hex);
    if (!rgb) return '#C7D0DF';
    const t = Math.max(0, Math.min(1, amount));
    const mix = (c: number) => Math.round(c + (255 - c) * t);
    const toHex = (c: number) => mix(c).toString(16).padStart(2, '0');
    return `#${toHex(rgb.r)}${toHex(rgb.g)}${toHex(rgb.b)}`;
  }

  private nextDisplayNameChangeAt(value: string | null | undefined): number | null {
    const lastChangeAt = this.timestamp(value);
    if (lastChangeAt <= 0) return null;
    return lastChangeAt + ProfileComponent.DISPLAY_NAME_CHANGE_COOLDOWN_MS;
  }

  private formatDateTimeFromMillis(millis: number | null): string | null {
    if (millis == null || !Number.isFinite(millis) || millis <= 0) return null;
    const date = new Date(millis);
    const day = this.pad2(date.getDate());
    const month = this.pad2(date.getMonth() + 1);
    const year = date.getFullYear();
    const hours = this.pad2(date.getHours());
    const minutes = this.pad2(date.getMinutes());
    return `${day}.${month}.${year}, ${hours}:${minutes}`;
  }

  private formatDateTimeLabel(value: string | null | undefined): string {
    const iso = String(value ?? '').trim();
    if (!iso) return 'No data';
    const millis = Date.parse(iso);
    if (!Number.isFinite(millis)) return 'No data';
    const date = new Date(millis);
    const day = this.pad2(date.getDate());
    const month = this.pad2(date.getMonth() + 1);
    const year = date.getFullYear();
    const hours = this.pad2(date.getHours());
    const minutes = this.pad2(date.getMinutes());
    return `${day}.${month}.${year}, ${hours}:${minutes}`;
  }

  private pad2(value: number): string {
    return String(value).padStart(2, '0');
  }

  private scheduleProfileNameMeasure(): void {
    if (this.marqueeRafId != null) {
      cancelAnimationFrame(this.marqueeRafId);
    }
    this.marqueeRafId = requestAnimationFrame(() => {
      this.marqueeRafId = null;
      this.updateProfileNameOverflow();
    });
  }

  private scheduleStatsTabHeightMeasure(): void {
    if (this.tabHeightRafId != null) {
      cancelAnimationFrame(this.tabHeightRafId);
    }
    this.tabHeightRafId = requestAnimationFrame(() => {
      this.tabHeightRafId = null;
      this.captureStatsTabHeight();
    });
  }

  private captureStatsTabHeight(): void {
    if (this.tab !== 'stats') return;
    const host = this.profileTabViewEl?.nativeElement;
    if (!host) return;
    const measured = Math.round(host.getBoundingClientRect().height);
    if (measured > 0) {
      this.statsTabHeightPx = measured;
    }
  }

  private updateProfileNameOverflow(): void {
    const items = this.profileNameEls?.toArray() ?? [];
    for (const item of items) {
      const host = item.nativeElement;
      const clip = host.querySelector<HTMLElement>('.profile-name__clip');
      const primary = host.querySelector<HTMLElement>('.profile-name__once:not([aria-hidden="true"])');
      if (!primary) continue;

      const availableWidth = Math.floor((clip ?? host).clientWidth);
      const contentWidth = Math.ceil(primary.getBoundingClientRect().width);
      if (availableWidth <= 0) continue;
      // Keep a small tolerance to avoid false marquee on near-equal widths.
      const hasOverflow = contentWidth > availableWidth + 2;
      host.classList.toggle('is-overflow', hasOverflow);
    }
  }
}
