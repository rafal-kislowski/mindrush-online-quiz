import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const AUTH_PATHS = new Set([
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/auth/logout',
]);

export const authRefreshInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);

  const url = req.url ?? '';
  const isApi = url.startsWith('/api/');
  const isAuthPath = AUTH_PATHS.has(url);

  return next(req).pipe(
    catchError((err: unknown) => {
      if (!isApi || isAuthPath) return throwError(() => err);
      if (!(err instanceof HttpErrorResponse)) return throwError(() => err);
      if (err.status !== 401) return throwError(() => err);
      if (!auth.snapshot) return throwError(() => err);

      return auth.refreshOnce().pipe(
        catchError(() => {
          auth.dropLocalAuth();
          return throwError(() => err);
        }),
        switchMap(() =>
          next(req).pipe(
            catchError((retryErr: unknown) => {
              if (retryErr instanceof HttpErrorResponse && retryErr.status === 401) {
                auth.dropLocalAuth();
              }
              return throwError(() => retryErr);
            })
          )
        )
      );
    })
  );
};
