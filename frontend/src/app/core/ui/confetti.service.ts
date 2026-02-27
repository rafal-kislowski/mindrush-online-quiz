import { Injectable } from '@angular/core';

type ConfettiCallable = (options?: unknown) => Promise<undefined> | null;

@Injectable({ providedIn: 'root' })
export class ConfettiService {
  private confettiPromise: Promise<ConfettiCallable> | null = null;
  private running = false;

  async makeItRain(durationMs: number = 2000): Promise<void> {
    if (!this.canRun() || this.running) return;
    this.running = true;

    try {
      const confetti = await this.loadConfetti();
      const endAt = performance.now() + Math.max(500, Math.trunc(durationMs));
      const colors = ['#ffcd4d', '#38bdf8', '#b96bff', '#ffffff'];

      await new Promise<void>((resolve) => {
        const frame = () => {
          if (!this.canRun()) {
            resolve();
            return;
          }

          confetti({
            particleCount: 2,
            angle: 60,
            spread: 55,
            startVelocity: 28,
            origin: { x: 0, y: this.randomInRange(0.45, 0.7) },
            colors,
            zIndex: 1200,
            disableForReducedMotion: true,
          });

          confetti({
            particleCount: 2,
            angle: 120,
            spread: 55,
            startVelocity: 28,
            origin: { x: 1, y: this.randomInRange(0.45, 0.7) },
            colors,
            zIndex: 1200,
            disableForReducedMotion: true,
          });

          if (performance.now() < endAt) {
            requestAnimationFrame(frame);
            return;
          }
          resolve();
        };

        frame();
      });
    } finally {
      this.running = false;
    }
  }

  private loadConfetti(): Promise<ConfettiCallable> {
    if (!this.confettiPromise) {
      this.confettiPromise = import('canvas-confetti').then((mod) => {
        const maybeDefault = mod as unknown as { default?: ConfettiCallable };
        return maybeDefault.default ?? (mod as unknown as ConfettiCallable);
      });
    }
    return this.confettiPromise;
  }

  private canRun(): boolean {
    if (typeof window === 'undefined' || typeof document === 'undefined') return false;
    if (document.hidden) return false;
    return true;
  }

  private randomInRange(min: number, max: number): number {
    return Math.random() * (max - min) + min;
  }
}
