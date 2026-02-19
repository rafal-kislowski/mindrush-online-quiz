import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

export type ToastTone = 'info' | 'success' | 'warning' | 'error';

export interface AppToast {
  id: number;
  tone: ToastTone;
  title: string;
  message: string;
  durationMs: number;
  leaving: boolean;
}

export interface ToastOptions {
  title?: string;
  durationMs?: number;
  dedupeKey?: string;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly maxVisible = 3;
  private readonly defaultDurationMs = 4600;
  private readonly minDurationMs = 2200;
  private readonly maxDurationMs = 12000;
  private readonly dedupeWindowMs = 4000;
  private readonly leaveAnimationMs = 240;

  private nextId = 1;
  private readonly toastsSubject = new BehaviorSubject<AppToast[]>([]);
  readonly toasts$ = this.toastsSubject.asObservable();

  private readonly dismissTimers = new Map<number, ReturnType<typeof setTimeout>>();
  private readonly removeTimers = new Map<number, ReturnType<typeof setTimeout>>();
  private readonly recentlyShown = new Map<string, number>();

  info(message: string, options?: ToastOptions): number | null {
    return this.show('info', message, options);
  }

  success(message: string, options?: ToastOptions): number | null {
    return this.show('success', message, options);
  }

  warning(message: string, options?: ToastOptions): number | null {
    return this.show('warning', message, options);
  }

  error(message: string, options?: ToastOptions): number | null {
    return this.show('error', message, options);
  }

  dismiss(id: number): void {
    const list = this.toastsSubject.value;
    const idx = list.findIndex((t) => t.id === id);
    if (idx === -1) return;

    const toast = list[idx];
    if (toast.leaving) return;

    this.clearDismissTimer(id);

    const next = [...list];
    next[idx] = { ...toast, leaving: true };
    this.toastsSubject.next(next);

    const removeTimer = setTimeout(() => this.remove(id), this.leaveAnimationMs);
    this.removeTimers.set(id, removeTimer);
  }

  clear(): void {
    for (const id of this.dismissTimers.keys()) this.clearDismissTimer(id);
    for (const id of this.removeTimers.keys()) this.clearRemoveTimer(id);
    this.toastsSubject.next([]);
  }

  private show(tone: ToastTone, rawMessage: string, options?: ToastOptions): number | null {
    const message = this.normalizeMessage(rawMessage);
    if (!message) return null;

    const dedupeKey = options?.dedupeKey?.trim() || `${tone}:${message}`;
    if (this.isDeduped(dedupeKey)) return null;

    const durationMs = this.normalizeDuration(options?.durationMs);
    const title = options?.title?.trim() || this.defaultTitleForTone(tone);
    const id = this.nextId++;

    const toast: AppToast = {
      id,
      tone,
      title,
      message,
      durationMs,
      leaving: false,
    };

    const active = this.toastsSubject.value.filter((t) => !t.leaving);
    const next = [toast, ...active.slice(0, this.maxVisible - 1)];
    this.toastsSubject.next(next);

    const dismissTimer = setTimeout(() => this.dismiss(id), durationMs);
    this.dismissTimers.set(id, dismissTimer);
    return id;
  }

  private remove(id: number): void {
    this.clearDismissTimer(id);
    this.clearRemoveTimer(id);
    this.toastsSubject.next(this.toastsSubject.value.filter((t) => t.id !== id));
  }

  private clearDismissTimer(id: number): void {
    const timer = this.dismissTimers.get(id);
    if (!timer) return;
    clearTimeout(timer);
    this.dismissTimers.delete(id);
  }

  private clearRemoveTimer(id: number): void {
    const timer = this.removeTimers.get(id);
    if (!timer) return;
    clearTimeout(timer);
    this.removeTimers.delete(id);
  }

  private normalizeDuration(value?: number): number {
    const n = Number(value ?? this.defaultDurationMs);
    if (!Number.isFinite(n)) return this.defaultDurationMs;
    return Math.max(this.minDurationMs, Math.min(this.maxDurationMs, Math.round(n)));
  }

  private defaultTitleForTone(tone: ToastTone): string {
    if (tone === 'success') return 'Success';
    if (tone === 'warning') return 'Warning';
    if (tone === 'error') return 'Error';
    return 'Info';
  }

  private normalizeMessage(value: string): string {
    return String(value ?? '').trim().replace(/\s+/g, ' ');
  }

  private isDeduped(key: string): boolean {
    const now = Date.now();
    const last = this.recentlyShown.get(key);
    const deduped = typeof last === 'number' && now - last < this.dedupeWindowMs;
    if (!deduped) {
      this.recentlyShown.set(key, now);
    }
    if (this.recentlyShown.size > 160) {
      const threshold = now - this.dedupeWindowMs * 3;
      for (const [k, ts] of this.recentlyShown.entries()) {
        if (ts < threshold) this.recentlyShown.delete(k);
      }
    }
    return deduped;
  }
}
