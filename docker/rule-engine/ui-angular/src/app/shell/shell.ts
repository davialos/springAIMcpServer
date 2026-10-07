import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ChatPanel } from '../chat/chat-panel';
import { AuthService } from '../core/auth.service';
import { SessionService } from '../core/session.service';

/** The signed-in frame: navigation by what the user may do, who and where they are, the assistant, and the way out. */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ChatPanel],
  template: `
    <div class="shell" [class.with-chat]="chatOpen()">
      <a class="skip-link" href="#content">Skip to content</a>
      <aside class="sidebar">
        <div class="brand"><span class="brand-mark" aria-hidden="true">R</span><span>Rule engine</span></div>
        <nav aria-label="Main">
          <div class="nav-group-title">Setup</div>
          <div class="nav">
            <a routerLink="/" routerLinkActive ariaCurrentWhenActive="page" [routerLinkActiveOptions]="{ exact: true }">Overview</a>
            <a routerLink="/library" routerLinkActive ariaCurrentWhenActive="page">Parameter library</a>
            <a routerLink="/rules" routerLinkActive ariaCurrentWhenActive="page">Rules</a>
            <a routerLink="/groups" routerLinkActive ariaCurrentWhenActive="page">Rule groups</a>
            <a routerLink="/channels" routerLinkActive ariaCurrentWhenActive="page">Triggers &amp; channels</a>
          </div>
          <div class="nav-group-title">Try</div>
          <div class="nav">
            <a routerLink="/test" routerLinkActive ariaCurrentWhenActive="page">Test bench</a>
            <button type="button" class="nav-button" [attr.aria-pressed]="chatOpen()" (click)="chatOpen.set(!chatOpen())">
              AI assistant
            </button>
          </div>
          @if (session.isAdmin()) {
            <div class="nav-group-title">Administration</div>
            <div class="nav"><a routerLink="/admin" routerLinkActive ariaCurrentWhenActive="page">Logs &amp; AI conversations</a></div>
          }
        </nav>
        <div class="sidebar-footer">
          <div class="user-chip">
            <strong>{{ session.session()?.displayName || session.session()?.username }}</strong>
            <span>
              {{ session.session()?.tenantName }}{{ session.session()?.organizationName ? ' · ' + session.session()?.organizationName : ' · all organizations' }}
            </span>
            <span><span class="badge" [class.badge-info]="session.isAdmin()">{{ session.isAdmin() ? 'Administrator' : 'User' }}</span></span>
          </div>
          <button type="button" class="btn" (click)="signOut()">Sign out</button>
        </div>
      </aside>
      <main id="content" class="main" tabindex="-1"><router-outlet /></main>
      @if (chatOpen()) {
        <app-chat-panel (closed)="chatOpen.set(false)" />
      }
    </div>
  `,
})
export class Shell {
  protected readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly chatOpen = signal(false);

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigateByUrl('/login');
  }
}
