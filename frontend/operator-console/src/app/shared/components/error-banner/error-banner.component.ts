import { Component, inject } from '@angular/core';
import { ErrorBannerService } from '../../../core/http/error.interceptor';

@Component({
  selector: 'app-error-banner',
  standalone: true,
  template: `
    @if (banner.message()) {
      <div class="error-banner" role="alert">
        <span>{{ banner.message() }}</span>
        <button (click)="banner.clear()" aria-label="Dismiss">&#x2715;</button>
      </div>
    }
  `,
})
export class ErrorBannerComponent {
  readonly banner = inject(ErrorBannerService);
}
