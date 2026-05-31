import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { environment } from '../../../environments/environment';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }

  if (!environment.production) {
    // DEV-ONLY: auto-authenticate with a stub token so local dev works without an OIDC provider.
    // The token below is a minimal JWT with sub="dev-operator" and roles=["TIMETABLE_AUTHOR","TIMETABLE_APPROVER"].
    // It is NOT validated; the gateway does not run in local mode.
    const devToken =
      'eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiJkZXYtb3BlcmF0b3IiLCJyb2xlcyI6WyJUSU1FVEFCTEVfQVVUSE9SIiwiVElNRVRBQkxFX0FQUFJPVkVSIl19.';
    auth.setToken(devToken);
    return true;
  }

  // TODO(config): Redirect to your OIDC provider's authorization endpoint.
  // See environment.prod.ts for PLACEHOLDER_OIDC_ISSUER_URL.
  router.navigate(['/']);
  return false;
};
