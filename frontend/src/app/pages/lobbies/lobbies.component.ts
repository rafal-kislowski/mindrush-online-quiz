import { CommonModule } from '@angular/common';
import { Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import { AuthService } from '../../core/auth/auth.service';
import { LobbyApi } from '../../core/api/lobby.api';
import { ActiveLobbyDto, LobbyOwnerType } from '../../core/models/lobby.models';
import { rankForPoints } from '../../core/progression/progression';
import { PlayerAvatarComponent } from '../../core/ui/player-avatar.component';
import { ToastService } from '../../core/ui/toast.service';

type LobbySort = 'newest' | 'oldest' | 'playersDesc' | 'playersAsc';
type LobbyRoomFilter = 'all' | 'private' | 'public' | 'available' | 'full';
type LobbiesMenuId = 'roomFilter' | 'sort' | 'pageSize';
type ActiveLobbyRowVm = ActiveLobbyDto & {
  leaderRankPoints: number;
  leaderRankName: string;
  leaderRankColor: string;
};

@Component({
  selector: 'app-lobbies',
  standalone: true,
  imports: [CommonModule, FormsModule, PlayerAvatarComponent],
  templateUrl: './lobbies.component.html',
  styleUrl: './lobbies.component.scss',
})
export class LobbiesComponent implements OnInit, OnDestroy {
  private readonly refreshMs = 4000;
  private readonly minJoinTransitionMs = 1500;
  private refreshTimerId: number | null = null;
  private readonly joinDelayTimers = new Set<number>();
  private readonly subscriptions = new Subscription();
  private destroyed = false;
  private isLoggedIn = false;
  private ownerFilterTouched = false;

  loading = true;
  syncing = false;
  private requestInFlight = false;

  rows: ActiveLobbyRowVm[] = [];
  private _error: string | null = null;

  sort: LobbySort = 'newest';
  roomFilter: LobbyRoomFilter = 'all';
  searchTerm = '';
  page = 1;
  pageSize: 10 | 25 | 50 = 10;

  ownerFilter: LobbyOwnerType = 'GUEST';
  openMenu: LobbiesMenuId | null = null;

  joiningCode: string | null = null;

  readonly roomFilterOptions: ReadonlyArray<{ value: LobbyRoomFilter; label: string }> = [
    { value: 'all', label: 'All rooms' },
    { value: 'available', label: 'Free spots' },
    { value: 'private', label: 'Private rooms' },
    { value: 'public', label: 'Public rooms' },
    { value: 'full', label: 'Full rooms' },
  ];

  readonly sortOptions: ReadonlyArray<{ value: LobbySort; label: string }> = [
    { value: 'newest', label: 'Newest' },
    { value: 'oldest', label: 'Oldest' },
    { value: 'playersDesc', label: 'Players desc' },
    { value: 'playersAsc', label: 'Players asc' },
  ];

  readonly pageSizeOptions: ReadonlyArray<10 | 25 | 50> = [10, 25, 50];

  constructor(
    private readonly auth: AuthService,
    private readonly lobbyApi: LobbyApi,
    private readonly router: Router,
    private readonly toast: ToastService
  ) {}

  get error(): string | null {
    return this._error;
  }

  set error(value: string | null) {
    this._error = value;
    if (!value) return;
    this.toast.error(value, { title: 'Active lobbies', dedupeKey: `lobbies:error:${value}` });
  }

  ngOnInit(): void {
    this.subscriptions.add(
      this.auth.user$.subscribe((user) => {
        this.isLoggedIn = !!user;
        this.applyDefaultOwnerFilter();
        this.rows = this.rows.map((row) => this.normalizeActiveRow(row));
      })
    );
    this.auth.ensureLoaded().subscribe({ error: () => {} });
    this.refresh(false);
    this.refreshTimerId = window.setInterval(() => this.refresh(true), this.refreshMs);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    if (this.refreshTimerId != null) {
      window.clearInterval(this.refreshTimerId);
      this.refreshTimerId = null;
    }
    for (const timerId of this.joinDelayTimers) {
      window.clearTimeout(timerId);
    }
    this.joinDelayTimers.clear();
    this.subscriptions.unsubscribe();
  }

  goHome(): void {
    this.router.navigate(['/']);
  }

  refreshNow(): void {
    this.refresh(true);
  }

  setSort(value: LobbySort): void {
    if (this.sort === value) {
      this.closeMenus();
      return;
    }
    this.sort = value;
    this.page = 1;
    this.closeMenus();
  }

  setOwnerFilter(type: LobbyOwnerType, fromUser: boolean = true): void {
    if (this.ownerFilter === type) return;
    if (fromUser) this.ownerFilterTouched = true;
    this.ownerFilter = type;
    this.page = 1;
  }

  setRoomFilter(value: LobbyRoomFilter): void {
    if (this.roomFilter === value) {
      this.closeMenus();
      return;
    }
    this.roomFilter = value;
    this.page = 1;
    this.closeMenus();
  }

  onSearchTermChange(value: string): void {
    const next = (value ?? '').trimStart();
    if (this.searchTerm === next) return;
    this.searchTerm = next;
    this.page = 1;
  }

  get showGuestRooms(): boolean {
    return this.ownerFilter === 'GUEST';
  }

  get showUserRooms(): boolean {
    return this.ownerFilter === 'AUTHENTICATED';
  }

  get filteredRows(): ActiveLobbyRowVm[] {
    let filtered = this.rows.filter((row) => row.ownerType === this.ownerFilter);

    const q = this.searchTerm.trim().toLowerCase();
    if (q) {
      filtered = filtered.filter((row) => {
        const code = (row.code ?? '').toLowerCase();
        const leader = (row.leaderDisplayName ?? '').toLowerCase();
        return code.includes(q) || leader.includes(q);
      });
    }

    if (this.roomFilter !== 'all') {
      filtered = filtered.filter((row) => {
        if (this.roomFilter === 'private') return row.hasPassword;
        if (this.roomFilter === 'public') return !row.hasPassword;
        if (this.roomFilter === 'available') return row.playerCount < row.maxPlayers;
        if (this.roomFilter === 'full') return row.playerCount >= row.maxPlayers;
        return true;
      });
    }

    const sorted = filtered.slice();
    sorted.sort((a, b) => {
      if (this.sort === 'playersDesc') return b.playerCount - a.playerCount;
      if (this.sort === 'playersAsc') return a.playerCount - b.playerCount;
      const at = new Date(a.createdAt).getTime();
      const bt = new Date(b.createdAt).getTime();
      return this.sort === 'oldest' ? at - bt : bt - at;
    });
    return sorted;
  }

  get totalRows(): number {
    return this.filteredRows.length;
  }

  get totalPages(): number {
    return Math.max(1, Math.ceil(this.totalRows / this.pageSize));
  }

  get pagedRows(): ActiveLobbyRowVm[] {
    const start = (this.page - 1) * this.pageSize;
    return this.filteredRows.slice(start, start + this.pageSize);
  }

  canPrev(): boolean {
    return this.page > 1;
  }

  canNext(): boolean {
    return this.page < this.totalPages;
  }

  prev(): void {
    if (!this.canPrev()) return;
    this.page -= 1;
  }

  next(): void {
    if (!this.canNext()) return;
    this.page += 1;
  }

  onPageSizeChange(raw: string): void {
    const n = Number(raw);
    if (n !== 10 && n !== 25 && n !== 50) return;
    this.pageSize = n;
    this.page = 1;
    this.closeMenus();
  }

  setPageSize(size: 10 | 25 | 50): void {
    if (this.pageSize === size) {
      this.closeMenus();
      return;
    }
    this.pageSize = size;
    this.page = 1;
    this.closeMenus();
  }

  toggleMenu(menu: LobbiesMenuId, ev?: Event): void {
    ev?.stopPropagation();
    this.openMenu = this.openMenu === menu ? null : menu;
  }

  closeMenus(): void {
    this.openMenu = null;
  }

  get roomFilterLabel(): string {
    return this.roomFilterOptions.find((o) => o.value === this.roomFilter)?.label ?? 'All rooms';
  }

  get sortLabel(): string {
    return this.sortOptions.find((o) => o.value === this.sort)?.label ?? 'Newest';
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(ev: MouseEvent): void {
    const target = ev.target as HTMLElement | null;
    if (target?.closest('.mr-select-wrap')) return;
    this.closeMenus();
  }

  @HostListener('document:keydown.escape')
  onDocumentEscape(): void {
    this.closeMenus();
  }

  trackByCode(_: number, row: ActiveLobbyRowVm): string {
    return row.code;
  }

  canJoin(row: ActiveLobbyDto): boolean {
    return this.isOpenWithFreeSlots(row) && !this.isOwnLobby(row);
  }

  isOwnLobby(row: ActiveLobbyDto): boolean {
    return !!row.isOwner;
  }

  isFull(row: ActiveLobbyDto): boolean {
    return row.playerCount >= row.maxPlayers;
  }

  isJoinDisabled(row: ActiveLobbyDto): boolean {
    if (this.joiningCode === row.code) return true;
    if (this.isOwnLobby(row)) return false;
    return !this.canJoin(row);
  }

  joinButtonLabel(row: ActiveLobbyDto): string {
    if (this.isOwnLobby(row)) return 'Open';
    if (!this.isOpenWithFreeSlots(row)) return 'Full';
    return 'Join';
  }

  joinRow(row: ActiveLobbyDto): void {
    if (this.joiningCode) return;
    if (this.isOwnLobby(row)) {
      this.openLobby(row.code);
      return;
    }
    if (!this.canJoin(row)) return;
    if (row.hasPassword) {
      this.openLobby(row.code);
      return;
    }
    const startedAt = performance.now();
    this.joinLobby(row.code, startedAt);
  }

  isAuthenticatedOwner(row: ActiveLobbyDto): boolean {
    return this.resolvedOwnerType(row) === 'AUTHENTICATED';
  }

  createdAgo(iso: string): string {
    const t = Date.parse(iso);
    if (!Number.isFinite(t)) return 'just now';

    const diffSec = Math.max(0, Math.floor((Date.now() - t) / 1000));
    if (diffSec < 30) return 'just now';
    if (diffSec < 60) return `${diffSec}s ago`;

    const diffMin = Math.floor(diffSec / 60);
    if (diffMin < 60) return `${diffMin}m ago`;

    const diffHr = Math.floor(diffMin / 60);
    if (diffHr < 24) return `${diffHr}h ago`;

    const diffDays = Math.floor(diffHr / 24);
    return `${diffDays}d ago`;
  }

  private refresh(background: boolean): void {
    if (this.requestInFlight) return;
    this.requestInFlight = true;
    this.error = null;

    if (background && !this.loading) {
      this.syncing = true;
    } else {
      this.loading = true;
    }

    this.lobbyApi.listActive().subscribe({
      next: (rows) => {
        this.rows = (rows ?? []).map((row) => this.normalizeActiveRow(row));
        this.applyDefaultOwnerFilter();
        this.maybeAutoSwitchToOwnedTab();
        this.clampPage();
        this.loading = false;
        this.syncing = false;
        this.requestInFlight = false;
      },
      error: (err) => {
        this.error = apiErrorMessage(err, 'Failed to load active lobbies');
        this.loading = false;
        this.syncing = false;
        this.requestInFlight = false;
      },
    });
  }

  private openLobby(code: string): void {
    const lobbyCode = (code ?? '').trim().toUpperCase();
    if (!lobbyCode) return;
    this.error = null;
    this.router.navigate(['/lobby', lobbyCode]).then((ok) => {
      if (ok) return;
      this.error = 'Failed to open lobby';
    }).catch(() => {
      this.error = 'Failed to open lobby';
    });
  }

  private joinLobby(code: string, startedAt: number): void {
    const lobbyCode = (code ?? '').trim().toUpperCase();
    if (!lobbyCode) return;

    this.joiningCode = lobbyCode;

    this.lobbyApi.join(lobbyCode).subscribe({
      next: () => {
        this.runAfterMinimumJoinTransition(startedAt, () => {
          this.router.navigate(['/lobby', lobbyCode]).then((ok) => {
            if (ok) return;
            this.error = 'Failed to open lobby';
            this.joiningCode = null;
          }).catch(() => {
            this.error = 'Failed to open lobby';
            this.joiningCode = null;
          });
        });
      },
      error: (err) => {
        if (err?.status === 409) {
          this.lobbyApi.getCurrent().subscribe({
            next: (currentLobby) => {
              const currentCode = (currentLobby?.code ?? '').trim().toUpperCase();
              if (currentCode) {
                if (currentCode !== lobbyCode) {
                  this.notifyMustLeaveCurrentLobby(currentCode);
                }
                this.runAfterMinimumJoinTransition(startedAt, () => {
                  this.router.navigate(['/lobby', currentCode]).then((ok) => {
                    if (ok) return;
                    this.error = 'Failed to open lobby';
                    this.joiningCode = null;
                  }).catch(() => {
                    this.error = 'Failed to open lobby';
                    this.joiningCode = null;
                  });
                });
                return;
              }

              const message = apiErrorMessage(err, 'Failed to join lobby');
              this.runAfterMinimumJoinTransition(startedAt, () => {
                this.error = message;
                this.joiningCode = null;
              });
            },
            error: () => {
              const message = apiErrorMessage(err, 'Failed to join lobby');
              this.runAfterMinimumJoinTransition(startedAt, () => {
                this.error = message;
                this.joiningCode = null;
              });
            },
          });
          return;
        }
        const message = apiErrorMessage(err, 'Failed to join lobby');
        this.runAfterMinimumJoinTransition(startedAt, () => {
          this.error = message;
          this.joiningCode = null;
        });
      },
    });
  }

  private runAfterMinimumJoinTransition(startedAt: number, action: () => void): void {
    const elapsed = performance.now() - startedAt;
    const remaining = Math.max(0, this.minJoinTransitionMs - elapsed);
    if (remaining <= 0) {
      action();
      return;
    }

    const timerId = window.setTimeout(() => {
      this.joinDelayTimers.delete(timerId);
      if (this.destroyed) return;
      action();
    }, remaining);
    this.joinDelayTimers.add(timerId);
  }

  private maybeAutoSwitchToOwnedTab(): void {
    if (this.ownerFilterTouched) return;
    if (!this.isLoggedIn) return;
    const hasOwnedLobby = this.rows.some((row) => row.isOwner);
    if (hasOwnedLobby) {
      this.setOwnerFilter('AUTHENTICATED', false);
    }
  }

  private applyDefaultOwnerFilter(): void {
    if (this.ownerFilterTouched) return;
    this.ownerFilter = this.isLoggedIn ? 'AUTHENTICATED' : 'GUEST';
  }

  private normalizeActiveRow(row: ActiveLobbyDto): ActiveLobbyRowVm {
    const ownerType = this.resolvedOwnerType(row);
    const leaderRankPoints = this.toNonNegativeInt(row.leaderRankPoints);
    const leaderRank = rankForPoints(leaderRankPoints);
    return {
      ...row,
      ownerType,
      leaderRankPoints,
      leaderRankName: leaderRank.name,
      leaderRankColor: leaderRank.color,
    };
  }

  private resolvedOwnerType(row: ActiveLobbyDto): LobbyOwnerType {
    if (row.ownerType === 'AUTHENTICATED') return 'AUTHENTICATED';
    if (this.isLoggedIn && row.isOwner) return 'AUTHENTICATED';
    return 'GUEST';
  }

  private toNonNegativeInt(value: unknown): number {
    const n = Number(value);
    if (!Number.isFinite(n)) return 0;
    return Math.max(0, Math.floor(n));
  }

  private clampPage(): void {
    const max = this.totalPages;
    if (this.page > max) this.page = max;
    if (this.page < 1) this.page = 1;
  }

  private isOpenWithFreeSlots(row: ActiveLobbyDto): boolean {
    return row.status === 'OPEN' && !this.isFull(row);
  }

  private notifyMustLeaveCurrentLobby(currentCode: string): void {
    this.toast.warning(
      `You are already in lobby ${currentCode}. Leave your current lobby before joining another one.`,
      {
        title: 'Lobby',
        dedupeKey: `lobbies:lobby-switch-blocked:${currentCode}`,
      }
    );
  }
}
