import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.ensureLoaded().pipe(
    map((u) => {
      if (u) return true;
      const redirect = state?.url;
      return router.createUrlTree(['/login'], {
        queryParams: redirect ? { redirect } : undefined,
      });
    })
  );
};
