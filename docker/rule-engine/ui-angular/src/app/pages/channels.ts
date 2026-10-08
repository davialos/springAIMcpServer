import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ApiService } from '../core/api.service';
import { load } from '../shared/resource';
import { Card, ErrorNotice, Loading, PageHeader, ScopeBadge } from '../shared/ui';

type Tab = 'triggers' | 'channels' | 'templates' | 'endpoints';

/** How applications reach the groups (trigger points) and what is communicated afterwards (channels). Read-only for now. */
@Component({
  selector: 'app-channels',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, Card, ErrorNotice, Loading, ScopeBadge],
  template: `
    <app-page-header title="Triggers &amp; channels">
      A trigger point says “when form F gets action A, run group G”. A channel says “when group or rule X yields Y, send an e-mail,
      a push message or call an API”. Editing these is not part of this console yet.
    </app-page-header>
    <div class="tabs" role="tablist" aria-label="Setup areas">
      @for (t of tabs; track t[0]) {
        <button type="button" role="tab" [attr.aria-selected]="tab() === t[0]" (click)="tab.set(t[0])">{{ t[1] }}</button>
      }
    </div>
    <app-error-notice [error]="triggers.error() ?? channels.error() ?? templates.error() ?? endpoints.error()" />
    @switch (tab()) {
      @case ('triggers') {
        @if (triggers.loading() && !triggers.data()) { <app-loading /> }
        @if (triggers.data(); as list) {
          @if (list.length === 0) { <p class="empty">No trigger point yet.</p> } @else {
            <div class="table-wrap"><table>
              <caption class="visually-hidden">Trigger points</caption>
              <thead><tr><th>Application</th><th>Form</th><th>On</th><th>Runs group</th><th class="num">Order</th><th>Scope</th></tr></thead>
              <tbody>
                @for (t of list; track t.id) {
                  <tr>
                    <td><code>{{ t.application }}</code></td>
                    <td>{{ t.formCode }}</td>
                    <td>{{ t.type === 'FORM_FIELD' ? 'change of ' + t.fieldCode : t.actionCode }}@if (!t.enabled) { <span class="badge"> off</span> }</td>
                    <td>{{ t.moduleCode }} · <code>{{ t.groupCode }}</code></td>
                    <td class="num">{{ t.sequence }}</td>
                    <td><app-scope [value]="t.scope" /></td>
                  </tr>
                }
              </tbody>
            </table></div>
          }
        }
      }
      @case ('channels') {
        @if (channels.loading() && !channels.data()) { <app-loading /> }
        @if (channels.data(); as list) {
          @if (list.length === 0) { <p class="empty">No channel yet.</p> } @else {
            <div class="table-wrap"><table>
              <caption class="visually-hidden">Channels</caption>
              <thead><tr><th>Bound to</th><th>When</th><th>Channel</th><th>Target</th><th>Recipient</th></tr></thead>
              <tbody>
                @for (c of list; track c.id) {
                  <tr>
                    <td>{{ c.ownerType.toLowerCase() }} <code>{{ c.ownerCode }}</code></td>
                    <td>result is {{ c.onResult.toLowerCase() }}</td>
                    <td><span class="badge badge-info">{{ c.channelType }}</span>@if (!c.enabled) { <span class="badge"> off</span> }</td>
                    <td>{{ c.emailTemplate ?? c.apiEndpoint ?? 'localized push message' }}</td>
                    <td>{{ c.hasRecipientExpression ? 'from the facts (CEL)' : '—' }}</td>
                  </tr>
                }
              </tbody>
            </table></div>
          }
        }
      }
      @case ('templates') {
        @if (templates.loading() && !templates.data()) { <app-loading /> }
        @if (templates.data(); as list) {
          <app-card>
            @if (list.length === 0) { <p class="empty">No e-mail template.</p> } @else {
              <div class="table-wrap"><table>
                <caption class="visually-hidden">E-mail templates</caption>
                <thead><tr><th>Template id</th><th>Name</th><th>Active</th></tr></thead>
                <tbody>@for (t of list; track t.id) { <tr><td><code>{{ t.templateRef }}</code></td><td>{{ t.name }}</td><td>{{ t.active ? 'yes' : 'no' }}</td></tr> }</tbody>
              </table></div>
            }
          </app-card>
        }
      }
      @case ('endpoints') {
        @if (endpoints.loading() && !endpoints.data()) { <app-loading /> }
        @if (endpoints.data(); as list) {
          <app-card sub="An API endpoint is only called from its own environment: dev from dev, qa from qa, prod from prod. An external one needs a recorded confirmation.">
            @if (list.length === 0) { <p class="empty">No API endpoint.</p> } @else {
              <div class="table-wrap"><table>
                <caption class="visually-hidden">API endpoints</caption>
                <thead><tr><th>Name</th><th>Call</th><th>Environment</th><th class="num">Timeout</th></tr></thead>
                <tbody>
                  @for (e of list; track e.id) {
                    <tr>
                      <td>{{ e.name }}</td>
                      <td>{{ e.method }} <code>{{ e.url }}</code></td>
                      <td><span class="badge badge-info">{{ e.environment }}</span>@if (e.environment === 'EXTERNAL' && !e.externalConfirmed) { <span class="badge badge-warn"> unconfirmed</span> }</td>
                      <td class="num">{{ e.timeoutMs }} ms</td>
                    </tr>
                  }
                </tbody>
              </table></div>
            }
          </app-card>
        }
      }
    }
  `,
})
export class ChannelsPage {
  private readonly api = inject(ApiService);
  protected readonly tab = signal<Tab>('triggers');
  protected readonly tabs: Array<[Tab, string]> = [
    ['triggers', 'Trigger points'],
    ['channels', 'Channels'],
    ['templates', 'E-mail templates'],
    ['endpoints', 'API endpoints'],
  ];
  protected readonly triggers = load(() => this.api.triggers());
  protected readonly channels = load(() => this.api.channels());
  protected readonly templates = load(() => this.api.emailTemplates());
  protected readonly endpoints = load(() => this.api.apiEndpoints());
}
