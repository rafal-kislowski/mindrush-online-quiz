import { Injectable } from '@angular/core';

declare var particlesJS: any;
declare const pJSDom: any;

@Injectable({
  providedIn: 'root',
})
export class ParticlesService {
  private initialized = false;

  initParticles() {
    if (this.initialized) return;
    if (typeof window === 'undefined' || typeof document === 'undefined') return;
    if (typeof particlesJS !== 'function') return;

    particlesJS('particles-js', {
      particles: {
        number: { value: 80, density: { enable: false, value_area: 800 } },
        color: { value: ['#67C8FF', '#8CA4FF', '#7EE8D8'] },
        shape: { type: 'circle' },
        opacity: { value: 0.42 },
        size: { value: 2.8, random: true },
        line_linked: {
          enable: true,
          distance: 150,
          color: '#6FC8FF',
          opacity: 0.14,
          width: 1,
        },
        move: { enable: true, speed: 2, out_mode: 'out' },
      },
      interactivity: {
        detect_on: 'canvas',
        events: {
          onhover: { enable: true, mode: 'grab' },
          onclick: { enable: true, mode: 'push' },
          resize: false,
        },
        modes: {
          grab: { distance: 140, line_linked: { opacity: 0.5 } },
          push: { particles_nb: 4 },
        },
      },
      retina_detect: false,
    });

    this.initialized = true;

    window.requestAnimationFrame(() => {
      this.applyFixedCanvasSizing();
    });
  }

  private applyFixedCanvasSizing() {
    const instance = pJSDom?.[0];
    const canvasEl: HTMLCanvasElement | undefined = instance?.pJS?.canvas?.el;
    if (!canvasEl) return;

    const container = document.getElementById('particles-js');
    if (!container) return;

    const width = Math.max(960, Math.min(1920, window.innerWidth));
    const height = Math.max(540, Math.min(1080, window.innerHeight));

    canvasEl.width = width;
    canvasEl.height = height;

    if (instance?.pJS?.canvas) {
      instance.pJS.canvas.w = width;
      instance.pJS.canvas.h = height;
    }

    canvasEl.style.position = 'absolute';
    canvasEl.style.left = '50%';
    canvasEl.style.top = '50%';
    canvasEl.style.width = `${width}px`;
    canvasEl.style.height = `${height}px`;
    canvasEl.style.transform = 'translate(-50%, -50%)';
    canvasEl.style.transformOrigin = 'center';
  }
}
