import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ApiService, toApiError } from '../core/api.service';
import type { ConversationDetail, ConversationLog } from '../core/types';
import { load, type Loaded } from '../shared/resource';
import { ActionBadge, Card, ErrorNotice, Loading, PageHeader } from '../shared/ui';
import { lines } from '../shared/markdown';
import { InlineText } from '../chat/inline-text';

type Tab = 'evaluations' | 'audit' | 'conversations';

/**
 * Administrators only (the service enforces it): what the engine decided, who changed what, and what people asked the AI
 * assistant. Everything is value-free: no input value, no message text of a rule is stored in these logs.
 */
@Component({
  selector: 'app-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, Card, ErrorNotice, Loading, ActionBadge, InlineText],
  template: `
    <app-page-header title="Logs &amp; AI conversations">
      Decisions, authoring changes and the conversations people had with the assistant, for your tenant.
    </app-page-header>
    <app-error-notice [error]="summary.error()" />
    @if (summary.data(); as s) {
      <div class="grid grid-stats" aria-label="Last 24 hours">
        <div class="stat"><div class="stat-label">Evaluations (24 h)</div><div class="stat-value">{{ s.evaluations }}</div><div class="stat-note">p95 {{ s.p95Millis }} ms</div></div>
        <div class="stat"><div class="stat-label">Blocked</div><div class="stat-value">{{ s.block }}</div><div class="stat-note">{{ s.warn }} warned · {{ s.allow }} allowed</div></div>
        <div class="stat"><div class="stat-label">With rule errors</div><div class="stat-value">{{ s.withErrors }}</div></div>
        <div class="stat"><div class="stat-label">Authoring changes</div><div class="stat-value">{{ s.authoringChanges }}</div></div>
        <div class="stat"><div class="stat-label">AI conversations</div><div class="stat-value">{{ s.conversations }}</div></div>
      </div>
    }
    <div class="tabs" role="tablist" aria-label="Logs">
      @for (t of tabs; track t[0]) {
        <button type="button" role="tab" [attr.aria-selected]="tab() === t[0]" (click)="tab.set(t[0])">{{ t[1] }}</button>
      }
    </div>
    @switch (tab()) {
      @case ('evaluations') {
        <app-error-notice [error]="evals().error()" />
        @if (evals().loading() && !evals().data()) { <app-loading /> }
        @if (evals().data(); as p) {
          <div class="table-wrap"><table>
            <caption class="visually-hidden">Evaluations</caption>
            <thead><tr><th>When</th><th>Group</th><th>Decision</th><th class="num">True / false / error</th><th class="num">Time</th></tr></thead>
            <tbody>
              @for (e of p.items; track e.id) {
                <tr>
                  <td class="mono">{{ e.at }}</td>
                  <td>{{ e.groupName ?? e.groupCode }} <span class="hint mono">{{ e.moduleCode }}</span></td>
                  <td><app-action [value]="e.decision" /></td>
                  <td class="num">{{ e.rulesTrue }} / {{ e.rulesFalse }} / {{ e.rulesError }}</td>
                  <td class="num">{{ (e.durationMicros / 1000).toFixed(2) }} ms</td>
                </tr>
              } @empty { <tr><td colspan="5" class="empty">No evaluation yet.</td></tr> }
            </tbody>
          </table></div>
          <nav class="pager" aria-label="Pagination">
            <button type="button" class="btn btn-small" [disabled]="evalPage() === 0" (click)="goto('evaluations', evalPage() - 1)">Previous</button>
            <span>Page {{ p.page + 1 }} of {{ pages(p.total, p.size) }} · {{ p.total }} evaluations</span>
            <button type="button" class="btn btn-small" [disabled]="(evalPage() + 1) * p.size >= p.total" (click)="goto('evaluations', evalPage() + 1)">Next</button>
          </nav>
        }
      }
      @case ('audit') {
        <app-error-notice [error]="audits().error()" />
        @if (audits().loading() && !audits().data()) { <app-loading /> }
        @if (audits().data(); as p) {
          <div class="table-wrap"><table>
            <caption class="visually-hidden">Authoring audit trail</caption>
            <thead><tr><th>When</th><th>Who</th><th>Action</th><th>What</th><th>Summary</th></tr></thead>
            <tbody>
              @for (a of p.items; track a.id) {
                <tr>
                  <td class="mono">{{ a.at }}</td>
                  <td>{{ a.actorName }} <span class="badge">{{ a.actorRole }}</span></td>
                  <td class="mono">{{ a.action }}</td>
                  <td>{{ a.entityType }} <span class="hint mono">{{ a.entityCode }}</span></td>
                  <td>{{ a.summary }}</td>
                </tr>
              } @empty { <tr><td colspan="5" class="empty">Nothing recorded yet.</td></tr> }
            </tbody>
          </table></div>
          <nav class="pager" aria-label="Pagination">
            <button type="button" class="btn btn-small" [disabled]="auditPage() === 0" (click)="goto('audit', auditPage() - 1)">Previous</button>
            <span>Page {{ p.page + 1 }} of {{ pages(p.total, p.size) }} · {{ p.total }} entries</span>
            <button type="button" class="btn btn-small" [disabled]="(auditPage() + 1) * p.size >= p.total" (click)="goto('audit', auditPage() + 1)">Next</button>
          </nav>
        }
      }
      @case ('conversations') {
        <app-error-notice [error]="chats.error()" />
        <app-error-notice [error]="transcriptError()" />
        @if (chats.loading() && !chats.data()) { <app-loading /> }
        @if (chats.data(); as p) {
          <div class="table-wrap"><table>
            <caption class="visually-hidden">AI conversations</caption>
            <thead><tr><th>Started</th><th>Title</th><th>Channel</th><th class="num">Messages</th><th></th></tr></thead>
            <tbody>
              @for (c of p.items; track c.id) {
                <tr>
                  <td class="mono">{{ c.startedAt }}</td>
                  <td>{{ c.title ?? '—' }}</td>
                  <td>{{ c.channel }}</td>
                  <td class="num">{{ c.messages }}</td>
                  <td><button type="button" class="btn btn-small" (click)="openTranscript(c)">Read</button></td>
                </tr>
              } @empty { <tr><td colspan="5" class="empty">No conversation yet.</td></tr> }
            </tbody>
          </table></div>
        }
        @if (transcript(); as t) {
          <app-card [title]="'Transcript: ' + (t.conversation.title ?? t.conversation.id)" sub="Stored redacted; personal data is masked before it is kept.">
            @for (m of t.messages; track m.seq) {
              <div class="bubble" [class.user]="m.role === 'USER'">
                <small>{{ m.role.toLowerCase() }}{{ m.redacted ? ' · redacted' : '' }}</small>
                @for (l of lines(m.content); track $index) { <div><app-inline [pieces]="l.inline" /></div> }
              </div>
            }
          </app-card>
        }
      }
    }
  `,
})
export class AdminPage {
  private readonly api = inject(ApiService);
  protected readonly tabs: Array<[Tab, string]> = [
    ['evaluations', 'Evaluations'],
    ['audit', 'Who changed what'],
    ['conversations', 'AI conversations'],
  ];
  protected readonly tab = signal<Tab>('evaluations');
  protected readonly summary = load(() => this.api.logSummary(24));
  protected readonly evalPage = signal(0);
  protected readonly auditPage = signal(0);
  protected readonly evals = signal<Loaded<Awaited<ReturnType<ApiService['evaluationLog']>>>>(load(() => this.api.evaluationLog(0)));
  protected readonly audits = signal<Loaded<Awaited<ReturnType<ApiService['auditLog']>>>>(load(() => this.api.auditLog(0)));
  protected readonly chats = load(() => this.api.conversations(0, 25));
  protected readonly transcript = signal<ConversationDetail | null>(null);
  protected readonly transcriptError = signal<unknown>(null);
  protected readonly lines = lines;

  protected pages(total: number, size: number): number {
    return Math.max(1, Math.ceil(total / size));
  }

  protected goto(which: 'evaluations' | 'audit', page: number): void {
    if (which === 'evaluations') {
      this.evalPage.set(page);
      this.evals.set(load(() => this.api.evaluationLog(page)));
    } else {
      this.auditPage.set(page);
      this.audits.set(load(() => this.api.auditLog(page)));
    }
  }

  protected async openTranscript(c: ConversationLog): Promise<void> {
    this.transcriptError.set(null);
    try {
      this.transcript.set(await this.api.conversation(c.id));
    } catch (e) {
      this.transcript.set(null);
      this.transcriptError.set(toApiError(e));
    }
  }
}
