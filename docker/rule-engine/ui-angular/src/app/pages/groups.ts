import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService, toApiError } from '../core/api.service';
import type { GroupView } from '../core/types';
import { load } from '../shared/resource';
import { ActionBadge, ErrorNotice, Loading, PageHeader, ScopeBadge, StatusBadge } from '../shared/ui';

/** Rule groups with their rules in running order, trigger points and messages. */
@Component({
  selector: 'app-groups',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PageHeader, ErrorNotice, Loading, ActionBadge, ScopeBadge, StatusBadge],
  template: `
    <app-page-header title="Rule groups">
      A group runs its rules by an evaluation policy and answers allow, warn or block. Applications call a group directly or
      through a trigger point.
      <span actions><a class="btn btn-primary" routerLink="/groups/new">New rule group</a></span>
    </app-page-header>
    @if (saved()) {
      <div class="notice notice-ok" role="status">Saved “{{ saved() }}”.
        @if (warnings().length) { <ul>@for (w of warnings(); track w) { <li>{{ w }}</li> }</ul> }
      </div>
    }
    <app-error-notice [error]="actionError()" />
    <app-error-notice [error]="groups.error()" />
    @if (groups.loading() && !groups.data()) { <app-loading what="Loading rule groups" /> }
    @if (groups.data(); as list) {
      @if (list.length === 0) {
        <p class="empty">No rule group yet. <a routerLink="/groups/new">Create the first one.</a></p>
      } @else {
        <div class="table-wrap">
          <table>
            <caption class="visually-hidden">Rule groups</caption>
            <thead><tr><th>Group</th><th>Policy</th><th class="num">Rules</th><th class="num">Triggers</th><th>Scope</th><th>Status</th></tr></thead>
            <tbody>
              @for (g of list; track g.id) {
                <tr class="clickable" (click)="toggle(g.id)">
                  <td>
                    <button type="button" class="btn-link" [attr.aria-expanded]="current() === g.id" (click)="$event.stopPropagation(); toggle(g.id)">{{ g.name }}</button>
                    <div class="hint mono">{{ g.moduleCode }} · {{ g.code }}</div>
                  </td>
                  <td>{{ policyLabel(g) }}</td>
                  <td class="num">{{ g.rules.length }}</td>
                  <td class="num">{{ g.triggers.length }}</td>
                  <td><app-scope [value]="g.scope" /></td>
                  <td><app-status [value]="g.status" /></td>
                </tr>
                @if (current() === g.id) {
                  <tr>
                    <td colspan="6">
                      <div class="panel">
                        @if (g.description) { <p>{{ g.description }}</p> }
                        <h3>Rules, in running order</h3>
                        @if (g.rules.length === 0) {
                          <p class="hint">None: this group always allows.</p>
                        } @else {
                          <ol>
                            @for (r of g.rules; track r.ruleCode) {
                              <li>{{ r.ruleName }} <span class="hint mono">{{ r.ruleCode }}</span>
                                @if (r.ruleStatus !== 'ACTIVE') { <app-status [value]="r.ruleStatus" /> }
                                @if (!r.enabled) { <span class="badge">switched off</span> }
                              </li>
                            }
                          </ol>
                        }
                        <dl class="kv">
                          <dt>If a rule errors</dt><dd><app-action [value]="g.onError" /></dd>
                          @if (g.policy === 'COMPOSITE') {
                            <dt>All true</dt>
                            <dd><app-action [value]="g.compositeTrueAction" />
                              @for (t of entries(g.compositeTrueMessage); track t[0]) { <div><span class="badge">{{ t[0] }}</span> {{ t[1] }}</div> }</dd>
                            <dt>Any false</dt>
                            <dd><app-action [value]="g.compositeFalseAction" />
                              @for (t of entries(g.compositeFalseMessage); track t[0]) { <div><span class="badge">{{ t[0] }}</span> {{ t[1] }}</div> }</dd>
                          }
                          <dt>Triggers</dt>
                          <dd>
                            @for (t of g.triggers; track t.id) {
                              <div><code>{{ t.application }}</code> · {{ t.formCode }} · {{ t.actionCode }}{{ t.fieldCode ? ' · field ' + t.fieldCode : '' }}</div>
                            } @empty { none: call the group directly }
                          </dd>
                          <dt>Channels</dt><dd>{{ g.channelCount }} communication(s) bound to this group</dd>
                        </dl>
                        <div class="actions">
                          <a class="btn btn-small" routerLink="/test" [queryParams]="{ module: g.moduleCode, group: g.code }">Test</a>
                          <a class="btn btn-small" [routerLink]="['/groups', g.id, 'edit']">Edit</a>
                          @if (g.status !== 'ACTIVE') { <button type="button" class="btn btn-small" (click)="setStatus(g, 'ACTIVE')">Activate</button> }
                          @if (g.status === 'ACTIVE') { <button type="button" class="btn btn-small" (click)="setStatus(g, 'DRAFT')">Move to draft</button> }
                          @if (g.status !== 'RETIRED') { <button type="button" class="btn btn-small btn-danger" (click)="setStatus(g, 'RETIRED')">Retire</button> }
                        </div>
                      </div>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      }
    }
  `,
})
export class GroupsPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  /** {@code ?open=<id>}: a group to show expanded (bound from the query string). */
  readonly open = input<string | undefined>();
  protected readonly groups = load(() => this.api.groups());
  protected readonly actionError = signal<unknown>(null);
  private readonly toggled = signal<string | null | undefined>(undefined);
  protected readonly current = computed(() => (this.toggled() === undefined ? (this.open() ?? null) : this.toggled()));
  private readonly flash = (this.router.getCurrentNavigation()?.extras.state ?? history.state) as { warnings?: string[]; saved?: string } | null;
  protected readonly saved = signal(this.flash?.saved ?? '');
  protected readonly warnings = signal<string[]>(this.flash?.warnings ?? []);

  protected toggle(id: string): void {
    this.toggled.set(this.current() === id ? null : id);
  }

  protected policyLabel(g: GroupView): string {
    return g.policy.replace('_', ' ').toLowerCase() + (g.policy === 'FIRST_MATCH' || g.policy === 'ALL_MATCH' ? ` (on ${g.matchOn.toLowerCase()})` : '');
  }

  protected entries(texts: Record<string, string>): Array<[string, string]> {
    return Object.entries(texts);
  }

  protected async setStatus(g: GroupView, status: string): Promise<void> {
    this.actionError.set(null);
    try {
      await this.api.setGroupStatus(g.id, status);
      this.groups.reload();
    } catch (e) {
      this.actionError.set(toApiError(e));
    }
  }
}
