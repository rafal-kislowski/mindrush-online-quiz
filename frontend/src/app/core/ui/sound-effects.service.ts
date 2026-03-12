import { Injectable } from '@angular/core';

export type SoundEffectId =
  | 'winner'
  | 'chat'
  | 'correct'
  | 'wrong'
  | 'notification'
  | 'game_over'
  | 'join_lobby'
  | 'leave_lobby';

interface SoundEffectDefinition {
  src: string;
  volume: number;
  preload: 'auto' | 'metadata' | 'none';
}

interface PlayEffectOptions {
  volumeMultiplier?: number;
  playbackRate?: number;
}

@Injectable({ providedIn: 'root' })
export class SoundEffectsService {
  private static readonly SOUND_ENABLED_STORAGE_KEY = 'mindrush.settings.sound_enabled';
  private static readonly SOUND_MASTER_VOLUME_STORAGE_KEY = 'mindrush.settings.sound_master_volume';
  private static readonly MAX_PENDING_EFFECTS = 8;

  private readonly effects: Readonly<Record<SoundEffectId, SoundEffectDefinition>> = {
    winner: {
      src: '/audio/winner_sound.mp3',
      volume: 0.85,
      preload: 'auto',
    },
    chat: {
      src: '/audio/chat_sound.mp3',
      volume: 0.72,
      preload: 'auto',
    },
    correct: {
      src: '/audio/correct_sound.mp3',
      volume: 0.78,
      preload: 'auto',
    },
    wrong: {
      src: '/audio/wrong_sound.mp3',
      volume: 0.76,
      preload: 'auto',
    },
    notification: {
      src: '/audio/notification_sound.mp3',
      volume: 0.72,
      preload: 'auto',
    },
    game_over: {
      src: '/audio/game_over_sound.mp3',
      volume: 0.78,
      preload: 'auto',
    },
    join_lobby: {
      src: '/audio/join_lobby_sound.mp3',
      volume: 0.74,
      preload: 'auto',
    },
    leave_lobby: {
      src: '/audio/leave_lobby_sound.mp3',
      volume: 0.74,
      preload: 'auto',
    },
  };

  private readonly baseAudio = new Map<SoundEffectId, HTMLAudioElement>();
  private readonly activePlaybacks = new Set<HTMLAudioElement>();
  private readonly pendingQueue: Array<{ effectId: SoundEffectId; options?: PlayEffectOptions }> = [];

  private audioUnlocked = false;
  private unlockListenersBound = false;
  private soundEnabled = true;
  private masterVolume = 1;

  private readonly unlockAudio = () => {
    this.audioUnlocked = true;
    this.unbindUnlockListeners();
    this.flushPendingQueue();
  };

  constructor() {
    this.soundEnabled = this.readSoundEnabledPreference();
    this.masterVolume = this.readMasterVolumePreference();
    if (!this.canUseAudioApi()) return;

    this.audioUnlocked = this.hasUserActivation();
    if (!this.audioUnlocked) this.bindUnlockListeners();
    this.preloadRegisteredEffects();
  }

  playEffect(effectId: SoundEffectId, options?: PlayEffectOptions): Promise<void> {
    if (!this.canUseAudioApi()) return Promise.resolve();
    if (!this.soundEnabled) return Promise.resolve();
    if (document.hidden) return Promise.resolve();

    if (!this.audioUnlocked) {
      this.enqueuePendingEffect(effectId, options);
      return Promise.resolve();
    }

    return this.playNow(effectId, options);
  }

  isEnabled(): boolean {
    return this.soundEnabled;
  }

  setEnabled(enabled: boolean): void {
    this.soundEnabled = enabled;
    try {
      localStorage.setItem(
        SoundEffectsService.SOUND_ENABLED_STORAGE_KEY,
        enabled ? '1' : '0'
      );
    } catch {
      // ignore storage errors
    }
  }

  getMasterVolume(): number {
    return this.masterVolume;
  }

  setMasterVolume(value: number): void {
    const next = this.clampVolume(value);
    this.masterVolume = next;
    try {
      localStorage.setItem(
        SoundEffectsService.SOUND_MASTER_VOLUME_STORAGE_KEY,
        String(next)
      );
    } catch {
      // ignore storage errors
    }
  }

  private playNow(effectId: SoundEffectId, options?: PlayEffectOptions): Promise<void> {
    const definition = this.effects[effectId];
    const playbackAudio = new Audio(definition.src);
    playbackAudio.preload = definition.preload;
    playbackAudio.currentTime = 0;

    playbackAudio.volume = this.resolveVolume(definition.volume, options?.volumeMultiplier);
    const playbackRate = Number(options?.playbackRate);
    if (Number.isFinite(playbackRate) && playbackRate > 0) {
      playbackAudio.playbackRate = playbackRate;
    }
    this.trackPlayback(playbackAudio);

    return playbackAudio.play().then(() => undefined).catch((error: unknown) => {
      this.untrackPlayback(playbackAudio);
      if (this.isNotAllowedError(error)) {
        this.audioUnlocked = false;
        this.enqueuePendingEffect(effectId, options);
        this.bindUnlockListeners();
        return;
      }
      console.warn('[SoundEffectsService] Failed to play sound effect', effectId, error);
    });
  }

  private preloadRegisteredEffects(): void {
    const effectIds = Object.keys(this.effects) as SoundEffectId[];
    effectIds.forEach((effectId) => {
      this.getOrCreateBaseAudio(effectId, this.effects[effectId]);
    });
  }

  private getOrCreateBaseAudio(
    effectId: SoundEffectId,
    definition: SoundEffectDefinition
  ): HTMLAudioElement {
    const existing = this.baseAudio.get(effectId);
    if (existing) return existing;

    const audio = new Audio(definition.src);
    audio.preload = definition.preload;
    audio.volume = this.resolveVolume(definition.volume, 1);
    try {
      audio.load();
    } catch {
      // ignore preload errors
    }
    this.baseAudio.set(effectId, audio);
    return audio;
  }

  private resolveVolume(baseVolume: number, multiplier: number | undefined): number {
    const safeBase = Number.isFinite(baseVolume) ? baseVolume : 1;
    const safeMultiplier = Number.isFinite(multiplier) ? (multiplier as number) : 1;
    return Math.max(0, Math.min(1, safeBase * safeMultiplier * this.masterVolume));
  }

  private readSoundEnabledPreference(): boolean {
    if (typeof localStorage === 'undefined') return true;
    try {
      const raw = localStorage.getItem(SoundEffectsService.SOUND_ENABLED_STORAGE_KEY);
      if (raw == null) return true;
      return raw !== '0';
    } catch {
      return true;
    }
  }

  private readMasterVolumePreference(): number {
    if (typeof localStorage === 'undefined') return 1;
    try {
      const raw = localStorage.getItem(SoundEffectsService.SOUND_MASTER_VOLUME_STORAGE_KEY);
      if (raw == null) return 1;
      const parsed = Number(raw);
      return this.clampVolume(parsed);
    } catch {
      return 1;
    }
  }

  private clampVolume(value: number): number {
    if (!Number.isFinite(value)) return 1;
    return Math.max(0, Math.min(1, value));
  }

  private enqueuePendingEffect(effectId: SoundEffectId, options?: PlayEffectOptions): void {
    if (this.pendingQueue.length >= SoundEffectsService.MAX_PENDING_EFFECTS) {
      this.pendingQueue.shift();
    }
    this.pendingQueue.push({ effectId, options });
  }

  private flushPendingQueue(): void {
    if (!this.pendingQueue.length) return;
    const queued = this.pendingQueue.splice(0, this.pendingQueue.length);
    queued.forEach((entry) => {
      void this.playNow(entry.effectId, entry.options);
    });
  }

  private bindUnlockListeners(): void {
    if (!this.canUseAudioApi() || this.unlockListenersBound) return;
    this.unlockListenersBound = true;
    window.addEventListener('pointerdown', this.unlockAudio, { once: true, passive: true });
    window.addEventListener('mousedown', this.unlockAudio, { once: true, passive: true });
    window.addEventListener('touchstart', this.unlockAudio, { once: true, passive: true });
    window.addEventListener('keydown', this.unlockAudio, { once: true });
  }

  private unbindUnlockListeners(): void {
    if (!this.unlockListenersBound) return;
    this.unlockListenersBound = false;
    window.removeEventListener('pointerdown', this.unlockAudio);
    window.removeEventListener('mousedown', this.unlockAudio);
    window.removeEventListener('touchstart', this.unlockAudio);
    window.removeEventListener('keydown', this.unlockAudio);
  }

  private hasUserActivation(): boolean {
    const activation = navigator.userActivation;
    if (!activation) return true;
    return activation.hasBeenActive;
  }

  private canUseAudioApi(): boolean {
    return (
      typeof window !== 'undefined' &&
      typeof document !== 'undefined' &&
      typeof Audio !== 'undefined'
    );
  }

  private isNotAllowedError(error: unknown): boolean {
    if (!error || typeof error !== 'object') return false;
    const maybeError = error as { name?: string };
    return maybeError.name === 'NotAllowedError';
  }

  private trackPlayback(audio: HTMLAudioElement): void {
    this.activePlaybacks.add(audio);
    const release = () => this.untrackPlayback(audio);
    audio.addEventListener('ended', release, { once: true });
    audio.addEventListener('error', release, { once: true });
  }

  private untrackPlayback(audio: HTMLAudioElement): void {
    this.activePlaybacks.delete(audio);
  }
}
