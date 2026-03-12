import { Routes } from '@angular/router';
import { adminGuard } from './core/auth/admin.guard';
import { authGuard } from './core/auth/auth.guard';
import { lobbyDeactivateGuard } from './pages/lobby/lobby-deactivate.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./pages/home/home.component').then(m => m.HomeComponent) },
  { path: 'login', loadComponent: () => import('./pages/auth/auth.component').then(m => m.AuthComponent) },
  { path: 'register', loadComponent: () => import('./pages/auth/auth.component').then(m => m.AuthComponent) },
  { path: 'forgot-password', loadComponent: () => import('./pages/auth/auth.component').then(m => m.AuthComponent) },
  { path: 'reset-password', loadComponent: () => import('./pages/auth/auth.component').then(m => m.AuthComponent) },
  { path: 'verify-email', loadComponent: () => import('./pages/auth/auth.component').then(m => m.AuthComponent) },
  { path: 'play-solo', loadComponent: () => import('./pages/casual/casual.component').then(m => m.CasualComponent) },
  { path: 'play-ranked', loadComponent: () => import('./pages/placeholder/placeholder.component').then(m => m.PlaceholderComponent), data: { title: 'Ranked' } },
  { path: 'play-random', loadComponent: () => import('./pages/placeholder/placeholder.component').then(m => m.PlaceholderComponent), data: { title: 'Random' } },
  { path: 'shop', loadComponent: () => import('./pages/placeholder/placeholder.component').then(m => m.PlaceholderComponent), data: { title: 'Shop' } },
  { path: 'news', loadComponent: () => import('./pages/placeholder/placeholder.component').then(m => m.PlaceholderComponent), data: { title: 'News' } },
  { path: 'profile', canActivate: [authGuard], loadComponent: () => import('./pages/profile/profile.component').then(m => m.ProfileComponent) },
  { path: 'lobbies', loadComponent: () => import('./pages/lobbies/lobbies.component').then(m => m.LobbiesComponent) },
  { path: 'forum', loadComponent: () => import('./pages/placeholder/placeholder.component').then(m => m.PlaceholderComponent), data: { title: 'Forum' } },
  { path: 'settings/display-name', redirectTo: 'settings', pathMatch: 'full' },
  { path: 'settings', canActivate: [authGuard], loadComponent: () => import('./pages/settings/settings.component').then(m => m.SettingsComponent) },
  { path: 'leaderboards', loadComponent: () => import('./pages/leaderboards/leaderboards.component').then(m => m.LeaderboardsComponent) },
  { path: 'library', canActivate: [authGuard], loadComponent: () => import('./pages/library/library.component').then(m => m.LibraryComponent) },
  { path: 'create-quiz', canActivate: [adminGuard], loadComponent: () => import('./pages/admin/admin-quiz.component').then(m => m.AdminQuizComponent) },
  { path: 'admin/quiz-submissions', canActivate: [adminGuard], loadComponent: () => import('./pages/admin/admin-quiz-submissions.component').then(m => m.AdminQuizSubmissionsComponent) },
  {
    path: 'lobby/:code',
    canDeactivate: [lobbyDeactivateGuard],
    loadComponent: () => import('./pages/lobby/lobby.component').then(m => m.LobbyComponent),
  },
  { path: 'lobby/:code/game', loadComponent: () => import('./pages/game/game.component').then(m => m.GameComponent) },
  { path: 'solo-game/:sessionId', loadComponent: () => import('./pages/game/game.component').then(m => m.GameComponent) },
  { path: '**', redirectTo: '' }
];
