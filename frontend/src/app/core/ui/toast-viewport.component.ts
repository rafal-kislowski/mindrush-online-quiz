import { AsyncPipe, NgFor } from '@angular/common';
import { Component, inject } from '@angular/core';
import { AppToast, ToastService } from './toast.service';

@Component({
  selector: 'app-toast-viewport',
  standalone: true,
  imports: [AsyncPipe, NgFor],
  templateUrl: './toast-viewport.component.html',
  styleUrl: './toast-viewport.component.scss',
})
export class ToastViewportComponent {
  private readonly toastService = inject(ToastService);
  readonly toasts$ = this.toastService.toasts$;

  trackByToastId(_: number, toast: AppToast): number {
    return toast.id;
  }

  dismiss(id: number): void {
    this.toastService.dismiss(id);
  }
}
