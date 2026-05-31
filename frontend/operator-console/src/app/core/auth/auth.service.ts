// TODO(config): Replace stub with real OIDC silent refresh using your IdP's token_endpoint.
// See environment.prod.ts for PLACEHOLDER_OIDC_ISSUER_URL.
import { Injectable, inject, signal, computed } from '@angular/core';
import { Router } from '@angular/router';
import { EMPTY, Observable } from 'rxjs';

function parseJwtSub(token: string): string {
  try {
    const payload = token.split('.')[1];
    const decoded = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    const parsed = JSON.parse(decoded);
    return parsed.sub ?? '';
  } catch {
    return '';
  }
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly router = inject(Router);

  readonly currentUser = signal<string>('');
  readonly token = signal<string | null>(null);
  readonly isAuthenticated = computed(() => this.token() !== null);

  constructor() {
    const stored = localStorage.getItem('auth_token');
    if (stored) {
      this.token.set(stored);
      this.currentUser.set(parseJwtSub(stored));
    }
  }

  getToken(): string | null {
    return this.token();
  }

  setToken(token: string): void {
    localStorage.setItem('auth_token', token);
    this.token.set(token);
    this.currentUser.set(parseJwtSub(token));
  }

  logout(): void {
    localStorage.removeItem('auth_token');
    this.token.set(null);
    this.currentUser.set('');
    this.router.navigate(['/']);
  }

  // TODO(config): Replace stub with real OIDC silent refresh using your IdP's token_endpoint.
  // See environment.prod.ts for PLACEHOLDER_OIDC_ISSUER_URL.
  silentRefresh(): Observable<void> {
    console.warn('[AuthService] silentRefresh() is a stub — real OIDC integration requires a token_endpoint. PLACEHOLDER.');
    return EMPTY;
  }
}
