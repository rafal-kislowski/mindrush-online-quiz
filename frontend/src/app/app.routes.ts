import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./pages/home/home.component').then(m => m.HomeComponent) },
  { path: 'register', loadComponent: () => import('./pages/register/register.component').then(m => m.RegisterComponent) },
  { path: 'play-solo', loadComponent: () => import('./pages/placeholder/placeholder.component').then(m => m.PlaceholderComponent), data: { title: 'Play Solo' } },
  { path: 'shop', loadComponent: () => import('./pages/placeholder/placeholder.component').then(m => m.PlaceholderComponent), data: { title: 'Shop' } },
  { path: 'news', loadComponent: () => import('./pages/placeholder/placeholder.component').then(m => m.PlaceholderComponent), data: { title: 'News' } },
  { path: 'settings', loadComponent: () => import('./pages/placeholder/placeholder.component').then(m => m.PlaceholderComponent), data: { title: 'Settings' } },
  { path: 'create-quiz', loadComponent: () => import('./pages/placeholder/placeholder.component').then(m => m.PlaceholderComponent), data: { title: 'Create Quiz' } },
  { path: 'lobby/:code', loadComponent: () => import('./pages/lobby/lobby.component').then(m => m.LobbyComponent) },
  { path: 'lobby/:code/game', loadComponent: () => import('./pages/game/game.component').then(m => m.GameComponent) },
  { path: '**', redirectTo: '' }
];
