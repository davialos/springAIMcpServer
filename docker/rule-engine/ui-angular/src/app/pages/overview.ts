import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { SessionService } from '../core/session.service';
import { load } from '../shared/resource';
import { ActionBadge, Card, ErrorNotice, Loading, PageHeader, StatusBadge } from '../shared/ui';

/** What is set up for the signed-in tenant and organization, at a glance. */
@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PageHeader, Card, ErrorNotice, Loading, ActionBadge, StatusBadge],
  template: `
    <app-page-header title="Overview">
      The rules that decide for {{ session.session()?.tenantName }}{{ session.session()?.organizationName ? ' / ' + session.session()?.organizationName : '' }}.
      You see what is shared with the whole tenant plus what belongs to your own organization.
      <span actions>
        <a class="btn" routerLink="/test">Open test bench</a>
        <a class="btn btn-primary" routerLink="/groups/new">New rule group</a>
      </span>
    </app-page-header>
    <app-error-notice [error]="setup.error()" />
    @if (setup.loading() && !setup.data()) { <app-loading what="Loading the setup" /> }
    @if (setup.data(); as s) {
      <div class="grid grid-stats" aria-label="Setup summary">
        <div class="stat"><div class="stat-label">Rule groups</div><div class="stat-value">{{ s.groups }}</div><div class="stat-note">{{ s.activeGroups }} active</div></div>
        <div class="stat"><div class="stat-label">Rules</div><div class="stat-value">{{ s.rules }}</div><div class="stat-note">{{ s.activeRules }} active</div></div>
        <div class="stat"><div class="stat-label">Parameters</div><div class="stat-value">{{ s.parameters }}</div><div class="stat-note">{{ s.objects }} objects in the library</div></div>
        <div class="stat"><div class="stat-label">Trigger points</div><div class="stat-value">{{ s.triggers }}</div><div class="stat-note">forms and actions bound to groups</div></div>
        <div class="stat"><div class="stat-label">Channels</div><div class="stat-value">{{ s.channels }}</div><div class="stat-note">{{ s.emailTemplates }} e-mail templates · {{ s.apiEndpoints }} APIs</div></div>
        <div class="stat"><div class="stat-label">Languages</div><div class="stat-value">{{ s.languages.length }}</div><div class="stat-note">{{ s.languages.join(', ') || 'none yet' }}</div></div>
      </div>
    }
    <app-card title="Rule groups" sub="A group runs its rules by an evaluation policy and answers allow, warn or block.">
      <app-error-notice [error]="groups.error()" />
      @if (groups.loading() && !groups.data()) { <app-loading what="Loading rule groups" /> }
      @if (groups.data(); as list) {
        @if (list.length === 0) {
          <p class="empty">No rule group yet. <a routerLink="/groups/new">Create the first one.</a></p>
        } @else {
          <div class="table-wrap">
            <table>
              <caption class="visually-hidden">Rule groups</caption>
              <thead><tr><th>Group</th><th>Module</th><th>Policy</th><th class="num">Rules</th><th>Status</th><th>On error</th></tr></thead>
              <tbody>
                @for (g of list; track g.id) {
                  <tr>
                    <td><a routerLink="/groups" [queryParams]="{ open: g.id }">{{ g.name }}</a><div class="hint mono">{{ g.code }}</div></td>
                    <td>{{ g.moduleCode }}</td>
                    <td>{{ g.policy.replace('_', ' ').toLowerCase() }}</td>
                    <td class="num">{{ g.rules.length }}</td>
                    <td><app-status [value]="g.status" /></td>
                    <td><app-action [value]="g.onError" /></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }
    </app-card>
    <app-card title="Ask the assistant">
      <p>Open <strong>AI assistant</strong> in the menu to ask about these rules, explain one, or check a CEL expression before you save it.</p>
    </app-card>
  `,
})
export class OverviewPage {
  private readonly api = inject(ApiService);
  protected readonly session = inject(SessionService);
  protected readonly setup = load(() => this.api.setup());
  protected readonly groups = load(() => this.api.groups());
}
