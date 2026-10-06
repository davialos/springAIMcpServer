import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';
import { SessionService } from './session.service';

/** Signed-in users only. */
export const authGuard: CanActivateFn = () => {
  const s = inject(SessionService);
  return s.signedIn() ? true : inject(Router).createUrlTree(['/login']);
};

/** Administrators only (the service enforces it too; this only keeps the way out of a dead end). */
export const adminGuard: CanActivateFn = () => {
  const s = inject(SessionService);
  if (!s.signedIn()) {
    return inject(Router).createUrlTree(['/login']);
  }
  return s.isAdmin() ? true : inject(Router).createUrlTree(['/']);
};
