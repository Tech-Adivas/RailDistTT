import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding, withViewTransitions } from '@angular/router';
import { provideHttpClient, withInterceptors, withFetch } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { correlationIdInterceptor } from './core/http/correlation-id.interceptor';
import { errorInterceptor } from './core/http/error.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    // Zoneless change detection — Angular 21 stable API.
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    provideHttpClient(
      withFetch(),
      withInterceptors([
        correlationIdInterceptor,  // must run first to mint the ID
        authInterceptor,           // adds Authorization header
        errorInterceptor,          // handles 401, 5xx
      ])
    ),
  ],
};
