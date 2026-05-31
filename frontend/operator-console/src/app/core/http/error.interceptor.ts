import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

@Injectable({ providedIn: 'root' })
export class ErrorBannerService {
  readonly message = signal<string | null>(null);

  show(msg: string): void {
    this.message.set(msg);
  }

  clear(): void {
    this.message.set(null);
  }
}

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const banner = inject(ErrorBannerService);
  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 503) {
        banner.show('Service temporarily unavailable. Please try again shortly.');
      } else if (err.status >= 500) {
        banner.show('An unexpected server error occurred.');
      } else if (err.status === 0) {
        banner.show('Network error — check your connection.');
      }
      // 401 handled by authInterceptor; 403 handled by authGuard — no banner needed
      return throwError(() => err);
    })
  );
};
