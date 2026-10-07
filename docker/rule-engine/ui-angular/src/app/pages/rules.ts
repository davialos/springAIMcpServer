import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, toApiError } from '../core/api.service';
import type { RuleView } from '../core/types';
import { load } from '../shared/resource';
import { ActionBadge, ErrorNotice, Field, Loading, PageHeader, ScopeBadge, StatusBadge } from '../shared/ui';

/** Every rule the caller may see, with its expression, messages, the parameters it reads and the groups that use it. */
@Component({
  selector: 'app-rules',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PageHeader, ErrorNotice, Field, Loading, ActionBadge, ScopeBadge, StatusBadge],
  template: `
    <app-page-header title="Rules">
      A rule is one CEL expression with what to say and do when it is true or false. Rules are checked against the parameter
      library when they are saved.
      <span actions><a class="btn btn-primary" routerLink="/rules/new">New rule</a></span>
    </app-page-header>
    <div class="toolbar">
      <app-field controlId="rule-module" label="Module">
        <select id="rule-module" (change)="module.set($any($event.target).value); rules.reload()">
          <option value="">All modules</option>
          @for (m of modules.data() ?? []; track m.code) { <option [value]="m.code">{{ m.name }}</option> }
        </select>
      </app-field>
      <app-field controlId="rule-status" label="Status">
        <select id="rule-status" (change)="status.set($any($event.target).value); rules.reload()">
          <option value="">Any</option>
          <option value="ACTIVE">Active</option>
          <option value="DRAFT">Draft</option>
          <option value="RETIRED">Retired</option>
        </select>
      </app-field>
    </div>
    <app-error-notice [error]="actionError()" />
    <app-error-notice [error]="rules.error()" />
    @if (rules.loading() && !rules.data()) { <app-loading what="Loading rules" /> }
    @if (rules.data(); as list) {
      @if (list.length === 0) {
        <p class="empty">No rule matches. <a routerLink="/rules/new">Create one.</a></p>
      } @else {
        <div class="table-wrap">
          <table>
            <caption class="visually-hidden">Rules</caption>
            <thead><tr><th>Rule</th><th>Expression</th><th>If true</th><th>If false</th><th>Scope</th><th>Status</th></tr></thead>
            <tbody>
              @for (r of list; track r.id) {
                <tr class="clickable" (click)="toggle(r.id)">
                  <td>
                    <button type="button" class="btn-link" [attr.aria-expanded]="open() === r.id" (click)="$event.stopPropagation(); toggle(r.id)">{{ r.name }}</button>
                    <div class="hint mono">{{ r.moduleCode }} · {{ r.code }}</div>
                  </td>
                  <td><code>{{ r.expression }}</code></td>
                  <td><app-action [value]="r.trueAction" /></td>
                  <td><app-action [value]="r.falseAction" /></td>
                  <td><app-scope [value]="r.scope" /></td>
                  <td><app-status [value]="r.status" /></td>
                </tr>
                @if (open() === r.id) {
                  <tr>
                    <td colspan="6">
                      <div class="panel">
                        <dl class="kv">
                          <dt>Reads</dt>
                          <dd>@for (p of r.parameters; track p) { <code>{{ p }} </code> } @empty { — }</dd>
                          <dt>Used in groups</dt>
                          <dd>{{ r.groups.length ? r.groups.join(', ') : 'none yet' }}</dd>
                          <dt>Message if true</dt>
                          <dd>@for (t of entries(r.trueMessage); track t[0]) { <div><span class="badge">{{ t[0] }}</span> {{ t[1] }}</div> } @empty { silent }</dd>
                          <dt>Message if false</dt>
                          <dd>@for (t of entries(r.falseMessage); track t[0]) { <div><span class="badge">{{ t[0] }}</span> {{ t[1] }}</div> } @empty { silent }</dd>
                        </dl>
                        <div class="actions">
                          @if (r.status !== 'ACTIVE') { <button type="button" class="btn btn-small" (click)="change(r, 'ACTIVE')">Activate</button> }
                          @if (r.status === 'ACTIVE') { <button type="button" class="btn btn-small" (click)="change(r, 'DRAFT')">Move to draft</button> }
                          @if (r.status !== 'RETIRED') { <button type="button" class="btn btn-small btn-danger" (click)="change(r, 'RETIRED')">Retire</button> }
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
export class RulesPage {
  private readonly api = inject(ApiService);
  protected readonly module = signal('');
  protected readonly status = signal('');
  protected readonly open = signal<string | null>(null);
  protected readonly actionError = signal<unknown>(null);
  protected readonly modules = load(() => this.api.modules());
  protected readonly rules = load(() => this.api.rules(this.module() || undefined, this.status() || undefined));

  protected toggle(id: string): void {
    this.open.set(this.open() === id ? null : id);
  }

  protected entries(texts: Record<string, string>): Array<[string, string]> {
    return Object.entries(texts);
  }

  protected async change(rule: RuleView, next: string): Promise<void> {
    this.actionError.set(null);
    try {
      await this.api.setRuleStatus(rule.id, next);
      this.rules.reload();
    } catch (e) {
      this.actionError.set(toApiError(e));
    }
  }
}
