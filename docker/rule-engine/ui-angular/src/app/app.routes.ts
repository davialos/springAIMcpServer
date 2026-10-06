import type { Routes } from '@angular/router';
import { adminGuard, authGuard } from './core/guards';

export const routes: Routes = [
  { path: 'login', title: 'Sign in · Rule engine console', loadComponent: () => import('./pages/login').then((m) => m.LoginPage) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./shell/shell').then((m) => m.Shell),
    children: [
      { path: '', pathMatch: 'full', title: 'Overview', loadComponent: () => import('./pages/overview').then((m) => m.OverviewPage) },
      { path: 'library', title: 'Parameter library', loadComponent: () => import('./pages/library').then((m) => m.LibraryPage) },
      { path: 'rules', title: 'Rules', loadComponent: () => import('./pages/rules').then((m) => m.RulesPage) },
      { path: 'rules/new', title: 'New rule', loadComponent: () => import('./pages/rule-form').then((m) => m.RuleFormPage) },
      { path: 'groups', title: 'Rule groups', loadComponent: () => import('./pages/groups').then((m) => m.GroupsPage) },
      { path: 'groups/new', title: 'New rule group', loadComponent: () => import('./pages/group-form').then((m) => m.GroupFormPage) },
      { path: 'groups/:id/edit', title: 'Edit rule group', loadComponent: () => import('./pages/group-form').then((m) => m.GroupFormPage) },
      { path: 'channels', title: 'Triggers & channels', loadComponent: () => import('./pages/channels').then((m) => m.ChannelsPage) },
      { path: 'test', title: 'Test bench', loadComponent: () => import('./pages/test-bench').then((m) => m.TestBenchPage) },
      { path: 'admin', canActivate: [adminGuard], title: 'Administration', loadComponent: () => import('./pages/admin').then((m) => m.AdminPage) },
    ],
  },
  { path: '**', redirectTo: '' },
];
