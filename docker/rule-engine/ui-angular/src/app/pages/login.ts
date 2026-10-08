import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService, LoginError } from '../core/auth.service';
import { SessionService } from '../core/session.service';

declare const DEV_LOGIN_HINT: boolean;

const FRIENDLY: Record<string, string> = {
  bad_credentials: 'Wrong username or password.',
  locked: 'Too many failed attempts. Wait a few minutes and try again.',
  network: 'The sign-in service cannot be reached. Is the stack running?',
  invalid_request: 'Enter your username and password.',
};

/** Signs in with a protobuf LoginRequest; the session (user, tenant, organization) comes back as a protobuf message. */
@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <div class="login-wrap">
      <main class="login-card">
        <h1>Rule engine console</h1>
        <p class="hint">Sign in to view the rule setup, author rule groups, ask the assistant and, as an administrator, open the administration page.</p>
        <form (ngSubmit)="submit()" novalidate>
          <div class="field">
            <label for="username">Username</label>
            <input id="username" name="username" type="text" autocomplete="username" autofocus required [(ngModel)]="username" />
          </div>
          <div class="field">
            <label for="password">Password</label>
            <input id="password" name="password" type="password" autocomplete="current-password" required [(ngModel)]="password" />
          </div>
          @if (error()) {
            <div class="notice notice-error" role="alert">{{ error() }}</div>
          }
          <button class="btn btn-primary" type="submit" [disabled]="busy() || !username() || !password()">
            {{ busy() ? 'Signing in…' : 'Sign in' }}
          </button>
        </form>
        @if (hint) {
          <aside class="dev-hint" aria-label="Local development logins">
            <strong>Local stack logins</strong>
            <table>
              <thead><tr><th>User</th><th>Password</th><th>Role · tenant / organization</th></tr></thead>
              <tbody>
                <tr><td><code>admin</code></td><td><code>admin123</code></td><td>Admin · Acme Bank / Retail</td></tr>
                <tr><td><code>user</code></td><td><code>user123</code></td><td>User · Acme Bank / Retail</td></tr>
                <tr><td><code>corp.user</code></td><td><code>user123</code></td><td>User · Acme Bank / Corporate</td></tr>
                <tr><td><code>globex.admin</code></td><td><code>admin123</code></td><td>Admin · Globex (another tenant)</td></tr>
              </tbody>
            </table>
          </aside>
        }
      </main>
    </div>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly hint = typeof DEV_LOGIN_HINT !== 'undefined' && DEV_LOGIN_HINT;

  constructor() {
    if (inject(SessionService).signedIn()) {
      void this.router.navigateByUrl('/');
    }
  }

  protected async submit(): Promise<void> {
    if (!this.username() || !this.password()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.auth.login(this.username(), this.password());
      await this.router.navigateByUrl('/');
    } catch (e) {
      this.error.set(FRIENDLY[e instanceof LoginError ? e.code : ''] ?? (e instanceof Error ? e.message : 'Sign-in failed.'));
      this.password.set('');
    } finally {
      this.busy.set(false);
    }
  }
}
