import { CanDeactivateFn } from '@angular/router';

// Lobby membership is no longer tied to staying on /lobby/:code route.
// Users can browse other app views while remaining in the lobby.
export const lobbyDeactivateGuard: CanDeactivateFn<unknown> = () => true;
