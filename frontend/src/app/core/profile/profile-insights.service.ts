import { Injectable, inject } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { AuthService } from '../auth/auth.service';

export type PlayedMode = 'SOLO' | 'LOBBY';

export interface ProfileInsightsState {
  soloGames: number;
  lobbyGames: number;
  totalGames: number;
  winsTotal: number;
  correctAnswersTotal: number;
  lastPlayedMode: PlayedMode | null;
  lastPlayedAtIso: string | null;
  trackedGameKeys: string[];
}

const STORAGE_PREFIX = 'mr-profile-insights:v2:';
const MAX_TRACKED_KEYS = 120;

const DEFAULT_STATE: ProfileInsightsState = {
  soloGames: 0,
  lobbyGames: 0,
  totalGames: 0,
  winsTotal: 0,
  correctAnswersTotal: 0,
  lastPlayedMode: null,
  lastPlayedAtIso: null,
  trackedGameKeys: [],
};

@Injectable({ providedIn: 'root' })
export class ProfileInsightsService {
  private readonly auth = inject(AuthService);
  private readonly stateSubject = new BehaviorSubject<ProfileInsightsState>(DEFAULT_STATE);
  readonly state$ = this.stateSubject.asObservable();

  private storageKey = `${STORAGE_PREFIX}guest`;

  constructor() {
    this.auth.user$.subscribe((user) => {
      const userKey = user?.id != null ? String(user.id) : 'guest';
      this.storageKey = `${STORAGE_PREFIX}${userKey}`;
      this.stateSubject.next(this.loadState());
    });
  }

  trackFinishedGame(mode: PlayedMode, gameKey: string, correctAnswers: number, won: boolean): void {
    const normalizedKey = String(gameKey ?? '').trim();
    if (!normalizedKey) return;

    const safeCorrectAnswers = Math.max(0, Math.floor(Number(correctAnswers) || 0));
    const safeWon = won === true;
    const current = this.stateSubject.value;

    if (current.trackedGameKeys.includes(normalizedKey)) return;

    const soloGames = mode === 'SOLO' ? current.soloGames + 1 : current.soloGames;
    const lobbyGames = mode === 'LOBBY' ? current.lobbyGames + 1 : current.lobbyGames;

    const nextTrackedKeys = [...current.trackedGameKeys, normalizedKey];
    const trimmedTrackedKeys =
      nextTrackedKeys.length > MAX_TRACKED_KEYS
        ? nextTrackedKeys.slice(nextTrackedKeys.length - MAX_TRACKED_KEYS)
        : nextTrackedKeys;

    const next: ProfileInsightsState = {
      ...current,
      soloGames,
      lobbyGames,
      totalGames: soloGames + lobbyGames,
      winsTotal: current.winsTotal + (mode === 'LOBBY' && safeWon ? 1 : 0),
      correctAnswersTotal: current.correctAnswersTotal + safeCorrectAnswers,
      lastPlayedMode: mode,
      lastPlayedAtIso: new Date().toISOString(),
      trackedGameKeys: trimmedTrackedKeys,
    };

    this.stateSubject.next(next);
    this.saveState(next);
  }

  private loadState(): ProfileInsightsState {
    let parsed: unknown = null;
    try {
      const raw = localStorage.getItem(this.storageKey);
      parsed = raw ? JSON.parse(raw) : null;
    } catch {
      parsed = null;
    }

    if (!parsed || typeof parsed !== 'object') {
      return { ...DEFAULT_STATE };
    }

    const source = parsed as Partial<ProfileInsightsState>;
    const soloGames = this.toNonNegativeInt(source.soloGames);
    const lobbyGames = this.toNonNegativeInt(source.lobbyGames);
    const totalGames = Math.max(this.toNonNegativeInt(source.totalGames), soloGames + lobbyGames);
    // v2 data could include solo wins; cap to lobby games for consistent multiplayer-only wins.
    const winsTotal = Math.min(this.toNonNegativeInt(source.winsTotal), lobbyGames);
    const correctAnswersTotal = this.toNonNegativeInt(source.correctAnswersTotal);
    const lastPlayedMode = source.lastPlayedMode === 'SOLO' || source.lastPlayedMode === 'LOBBY' ? source.lastPlayedMode : null;
    const lastPlayedAtIso = this.normalizeIso(source.lastPlayedAtIso);
    const trackedGameKeys = this.normalizeTrackedGameKeys(source.trackedGameKeys);

    return {
      soloGames,
      lobbyGames,
      totalGames,
      winsTotal,
      correctAnswersTotal,
      lastPlayedMode,
      lastPlayedAtIso,
      trackedGameKeys,
    };
  }

  private saveState(state: ProfileInsightsState): void {
    try {
      localStorage.setItem(this.storageKey, JSON.stringify(state));
    } catch {
    }
  }

  private toNonNegativeInt(value: unknown): number {
    const n = Number(value);
    if (!Number.isFinite(n)) return 0;
    return Math.max(0, Math.floor(n));
  }

  private normalizeIso(value: unknown): string | null {
    const raw = String(value ?? '').trim();
    if (!raw) return null;
    const timestamp = Date.parse(raw);
    if (!Number.isFinite(timestamp)) return null;
    return new Date(timestamp).toISOString();
  }

  private normalizeTrackedGameKeys(value: unknown): string[] {
    if (!Array.isArray(value)) return [];
    const normalized = value
      .map((item) => String(item ?? '').trim())
      .filter((item) => item.length > 0);
    if (normalized.length <= MAX_TRACKED_KEYS) return normalized;
    return normalized.slice(normalized.length - MAX_TRACKED_KEYS);
  }
}
