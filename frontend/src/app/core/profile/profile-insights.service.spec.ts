import { TestBed } from '@angular/core/testing';
import { BehaviorSubject } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { ProfileInsightsService, ProfileInsightsState } from './profile-insights.service';

describe('ProfileInsightsService', () => {
  const STORAGE_PREFIX = 'mr-profile-insights:v2:';
  let authUserSubject: BehaviorSubject<unknown>;

  beforeEach(() => {
    clearInsightsStorage();
    authUserSubject = new BehaviorSubject<unknown>(null);
    TestBed.configureTestingModule({
      providers: [
        ProfileInsightsService,
        {
          provide: AuthService,
          useValue: {
            user$: authUserSubject.asObservable(),
          },
        },
      ],
    });
  });

  afterEach(() => {
    clearInsightsStorage();
  });

  it('does not increase winsTotal for SOLO win', () => {
    const service = TestBed.inject(ProfileInsightsService);

    service.trackFinishedGame('SOLO', 'solo:1', 4, true);

    const state = currentState(service);
    expect(state.soloGames).toBe(1);
    expect(state.lobbyGames).toBe(0);
    expect(state.totalGames).toBe(1);
    expect(state.winsTotal).toBe(0);
  });

  it('increases winsTotal only for LOBBY win', () => {
    const service = TestBed.inject(ProfileInsightsService);

    service.trackFinishedGame('SOLO', 'solo:1', 2, true);
    service.trackFinishedGame('LOBBY', 'lobby:1', 3, true);

    const state = currentState(service);
    expect(state.soloGames).toBe(1);
    expect(state.lobbyGames).toBe(1);
    expect(state.totalGames).toBe(2);
    expect(state.winsTotal).toBe(1);
  });

  it('caps loaded winsTotal to lobbyGames for legacy data consistency', () => {
    localStorage.setItem(
      `${STORAGE_PREFIX}guest`,
      JSON.stringify({
        soloGames: 3,
        lobbyGames: 1,
        totalGames: 4,
        winsTotal: 4,
        correctAnswersTotal: 11,
        lastPlayedMode: 'SOLO',
        lastPlayedAtIso: new Date().toISOString(),
        trackedGameKeys: [],
      })
    );

    const service = TestBed.inject(ProfileInsightsService);
    const state = currentState(service);

    expect(state.winsTotal).toBe(1);
    expect(state.lobbyGames).toBe(1);
  });

  function currentState(service: ProfileInsightsService): ProfileInsightsState {
    let latest!: ProfileInsightsState;
    const sub = service.state$.subscribe((state) => {
      latest = state;
    });
    sub.unsubscribe();
    return latest;
  }

  function clearInsightsStorage(): void {
    const keysToRemove: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      if (!key) continue;
      if (key.startsWith(STORAGE_PREFIX)) keysToRemove.push(key);
    }
    for (const key of keysToRemove) {
      localStorage.removeItem(key);
    }
  }
});
