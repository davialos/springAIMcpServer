import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiError, ApiService, toApiError } from '../core/api.service';
import { SessionService } from '../core/session.service';
import type { Action, CreateRuleRequest, ExpressionCheck, Scope, Texts } from '../core/types';
import { load } from '../shared/resource';
import { cleanTexts, TextsEditor } from '../shared/texts-editor';
import { Card, ErrorNotice, Field, Loading, PageHeader } from '../shared/ui';

const CODE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

/**
 * Creates a rule. The expression is compiled against the parameter library by the service, live while the user types
 * (a short pause after the last key) and again when the rule is saved; its message is shown on the expression field.
 */
@Component({
  selector: 'app-rule-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, PageHeader, Card, Field, ErrorNotice, Loading, TextsEditor],
  template: `
    <app-page-header title="New rule">
      The expression must be a true/false CEL expression over the parameters of the library, for example
      <code>customer.age &gt;= 18 &amp;&amp; loan.amount &lt;= 500000.0</code>.
    </app-page-header>
    <form (ngSubmit)="submit()" novalidate>
      <app-error-notice [error]="failure()" />
      <app-card title="Identity">
        <div class="form-grid">
          <app-field controlId="rule-module" label="Module">
            <select id="rule-module" name="module" [ngModel]="module()" (ngModelChange)="moduleCode.set($event)">
              @for (m of modules.data() ?? []; track m.code) { <option [value]="m.code">{{ m.name }}</option> }
            </select>
          </app-field>
          <app-field controlId="rule-code" label="Code" hint="Unique inside the module. Used by groups to refer to the rule." [error]="errors()['code']">
            <input id="rule-code" name="code" type="text" [(ngModel)]="code" [attr.aria-invalid]="!!errors()['code']" />
          </app-field>
          <app-field controlId="rule-name" label="Name" [error]="errors()['name']">
            <input id="rule-name" name="name" type="text" [(ngModel)]="name" [attr.aria-invalid]="!!errors()['name']" />
          </app-field>
        </div>
        <app-field controlId="rule-description" label="Description (optional)">
          <input id="rule-description" name="description" type="text" [(ngModel)]="description" />
        </app-field>
      </app-card>
      <app-card title="Expression" sub="Click a parameter to insert it. The expression is checked as you type.">
        <app-field controlId="rule-expression" label="CEL expression" [error]="errors()['expression']">
          <textarea id="rule-expression" name="expression" class="code" spellcheck="false" [ngModel]="expression()"
                    (ngModelChange)="onExpression($event)" [attr.aria-invalid]="!!errors()['expression']"></textarea>
        </app-field>
        @if (check(); as c) {
          @if (c.valid) {
            <div class="notice notice-ok" role="status">Valid. It reads: {{ c.parameters.join(', ') || 'no parameter' }}.</div>
          } @else {
            <div class="notice notice-warn" role="status">{{ c.error }}</div>
          }
        }
        @if (library.loading() && !library.data()) { <app-loading what="Loading the library" /> }
        <div class="chips" role="group" aria-label="Insert a parameter">
          @for (a of attributes(); track a.celName) {
            <button type="button" class="chip" [title]="a.name + ' (' + a.dataType + ')'" (click)="insert(a.celName)">{{ a.celName }}</button>
          }
        </div>
      </app-card>
      <app-card title="Outcomes" sub="What the rule says and does when it is true or false. A blank message keeps that outcome silent.">
        <div class="form-grid">
          <app-field controlId="true-action" label="If true">
            <select id="true-action" name="trueAction" [(ngModel)]="trueAction"><option>ALLOW</option><option>WARN</option><option>BLOCK</option></select>
          </app-field>
          <app-field controlId="false-action" label="If false">
            <select id="false-action" name="falseAction" [(ngModel)]="falseAction"><option>ALLOW</option><option>WARN</option><option>BLOCK</option></select>
          </app-field>
        </div>
        <app-texts-editor label="Message when true" [(value)]="trueMessage" />
        <app-texts-editor label="Message when false" [(value)]="falseMessage" />
      </app-card>
      <app-card title="Visibility">
        <div class="form-grid">
          <app-field controlId="rule-scope" label="Who can use this rule"
                     [hint]="organizationId && !admin ? 'Only an administrator can share a rule with the whole tenant.' : 'Shared rules can be used by every organization of the tenant.'">
            <select id="rule-scope" name="scope" [(ngModel)]="scope">
              @if (organizationId) { <option value="ORGANIZATION">My organization ({{ organizationName }})</option> }
              @if (admin || !organizationId) { <option value="TENANT">The whole tenant</option> }
            </select>
          </app-field>
          <app-field controlId="rule-status" label="Start as">
            <select id="rule-status" name="status" [(ngModel)]="status">
              <option value="ACTIVE">Active — runs as soon as a group uses it</option>
              <option value="DRAFT">Draft — saved but never runs</option>
            </select>
          </app-field>
        </div>
      </app-card>
      <div class="actions">
        <button class="btn btn-primary" type="submit" [disabled]="busy()">{{ busy() ? 'Saving…' : 'Save rule' }}</button>
        <a class="btn" routerLink="/rules">Cancel</a>
      </div>
    </form>
  `,
})
export class RuleFormPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly sessionService = inject(SessionService);
  protected readonly organizationId = this.sessionService.session()?.organizationId || null;
  protected readonly organizationName = this.sessionService.session()?.organizationName ?? '';
  protected readonly admin = this.sessionService.isAdmin();

  protected readonly modules = load(() => this.api.modules());
  protected readonly library = load(() => this.api.library());
  protected readonly moduleCode = signal('');
  protected readonly module = computed(() => this.moduleCode() || this.modules.data()?.[0]?.code || '');
  protected readonly attributes = computed(() => (this.library.data() ?? []).flatMap((o) => o.attributes));
  protected readonly code = signal('');
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly expression = signal('');
  protected readonly trueAction = signal<Action>('ALLOW');
  protected readonly falseAction = signal<Action>('BLOCK');
  protected readonly trueMessage = signal<Texts>({ en: '' });
  protected readonly falseMessage = signal<Texts>({ en: '' });
  protected readonly status = signal<'ACTIVE' | 'DRAFT'>('ACTIVE');
  protected readonly scope = signal<Scope>(this.organizationId ? 'ORGANIZATION' : 'TENANT');
  protected readonly errors = signal<Record<string, string>>({});
  protected readonly failure = signal<unknown>(null);
  protected readonly busy = signal(false);
  protected readonly check = signal<ExpressionCheck | null>(null);
  private timer: ReturnType<typeof setTimeout> | undefined;
  private checking = 0;

  /** Live validation: ask the service shortly after the last key; a newer answer wins. */
  protected onExpression(value: string): void {
    this.expression.set(value);
    clearTimeout(this.timer);
    if (!value.trim()) {
      this.check.set(null);
      return;
    }
    this.timer = setTimeout(async () => {
      const mine = ++this.checking;
      try {
        const result = await this.api.checkExpression(value.trim());
        if (mine === this.checking) {
          this.check.set(result);
        }
      } catch {
        if (mine === this.checking) {
          this.check.set(null); // the save will report a real failure
        }
      }
    }, 400);
  }

  protected insert(celName: string): void {
    this.onExpression((this.expression() ? this.expression().replace(/\s*$/, ' ') : '') + celName);
  }

  protected async submit(): Promise<void> {
    const found: Record<string, string> = {};
    if (!CODE.test(this.code())) found['code'] = 'Use letters, digits, ".", "_" or "-" (1–64 characters).';
    if (!this.name().trim()) found['name'] = 'Give the rule a name.';
    if (!this.expression().trim()) found['expression'] = 'Write the CEL expression.';
    this.errors.set(found);
    this.failure.set(null);
    if (Object.keys(found).length) {
      return;
    }
    const request: CreateRuleRequest = {
      moduleCode: this.module(),
      code: this.code(),
      name: this.name().trim(),
      description: this.description().trim() || undefined,
      expression: this.expression().trim(),
      trueMessage: cleanTexts(this.trueMessage()),
      falseMessage: cleanTexts(this.falseMessage()),
      trueAction: this.trueAction(),
      falseAction: this.falseAction(),
      status: this.status(),
      scope: this.scope(),
    };
    this.busy.set(true);
    try {
      await this.api.createRule(request);
      await this.router.navigateByUrl('/rules');
    } catch (e) {
      const err = toApiError(e);
      if (err instanceof ApiError && err.code === 'invalid_expression') {
        this.errors.set({ expression: err.message }); // the compiler's message, shown on the field it is about
      } else if (err.code === 'duplicate') {
        this.errors.set({ code: 'A rule with this code already exists in this module and scope.' });
      } else {
        this.failure.set(err);
      }
    } finally {
      this.busy.set(false);
    }
  }
}
