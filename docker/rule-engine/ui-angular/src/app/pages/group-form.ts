import { ChangeDetectionStrategy, Component, computed, effect, forwardRef, inject, input, OnInit, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService, toApiError } from '../core/api.service';
import { SessionService } from '../core/session.service';
import type { Action, GroupRequest, GroupView, Policy, RuleView, Scope, Texts, TriggerSpec } from '../core/types';
import { load, type Loaded } from '../shared/resource';
import { cleanTexts, TextsEditor } from '../shared/texts-editor';
import { Card, ErrorNotice, Field, Loading, PageHeader, ScopeBadge, StatusBadge } from '../shared/ui';

const CODE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

const POLICIES: Array<{ value: Policy; title: string; text: string }> = [
  { value: 'COMPOSITE', title: 'Composite', text: 'Every rule must be true. One message and one action for the whole group.' },
  { value: 'FIRST_MATCH', title: 'First match', text: 'Stops at the first rule whose result matches the setting below.' },
  { value: 'ALL_MATCH', title: 'All matches', text: 'Reports every rule whose result matches the setting below.' },
  { value: 'EVALUATE_ALL', title: 'Evaluate all', text: 'Runs every rule and reports each result, true and false.' },
];

interface Member {
  ruleCode: string;
  enabled: boolean;
}

interface TriggerRow extends TriggerSpec {
  key: number;
}

/** Loads the group to edit (when the route has an id) and hands it to the editor. */
@Component({
  selector: 'app-group-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [forwardRef(() => GroupEditor), ErrorNotice, Loading],
  template: `
    @if (id() === undefined) {
      <app-group-editor [group]="null" />
    } @else if (existing(); as e) {
      @if (e.error()) {
        <app-error-notice [error]="e.error()" />
      } @else if (e.data(); as g) {
        <app-group-editor [group]="g" />
      } @else {
        <app-loading what="Loading the group" />
      }
    }
  `,
})
export class GroupFormPage implements OnInit {
  private readonly api = inject(ApiService);
  /** The route parameter {@code :id} (bound from the route); absent when creating. */
  readonly id = input<string | undefined>();
  protected readonly existing = signal<Loaded<GroupView> | null>(null);

  ngOnInit(): void {
    const id = this.id();
    if (id !== undefined) {
      this.existing.set(load(() => this.api.group(id)));
    }
  }
}

/**
 * Creates a rule group, or edits one: policy, which rules run in what order, the messages of a composite group, and the
 * trigger points that make an application call it. The service re-validates everything; this form explains the choices and
 * keeps the author from sending an obviously wrong request.
 */
@Component({
  selector: 'app-group-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, PageHeader, Card, Field, ErrorNotice, Loading, TextsEditor, ScopeBadge, StatusBadge],
  template: `
    <app-page-header [title]="editing ? 'Edit ' + group()!.name : 'New rule group'">
      A rule group bundles rules, says how their results combine, and which application events call it.
    </app-page-header>
    <form (ngSubmit)="submit()" novalidate>
      <app-error-notice [error]="failure()" />
      <app-card title="Basics">
        <div class="form-grid">
          <app-field controlId="group-module" label="Module" [hint]="editing ? 'A group keeps its module.' : null">
            <select id="group-module" name="module" [disabled]="editing" [ngModel]="module()" (ngModelChange)="changeModule($event)">
              @for (m of modules.data() ?? []; track m.code) { <option [value]="m.code">{{ m.name }}</option> }
            </select>
          </app-field>
          <app-field controlId="group-code" label="Code" [hint]="editing ? 'A group keeps its code.' : 'Applications refer to the group by module and code.'" [error]="errors()['code']">
            <input id="group-code" name="code" type="text" [readOnly]="editing" [(ngModel)]="code" [attr.aria-invalid]="!!errors()['code']" />
          </app-field>
          <app-field controlId="group-name" label="Name" [error]="errors()['name']">
            <input id="group-name" name="name" type="text" [(ngModel)]="name" [attr.aria-invalid]="!!errors()['name']" />
          </app-field>
        </div>
        <app-field controlId="group-description" label="Description (optional)">
          <input id="group-description" name="description" type="text" [(ngModel)]="description" />
        </app-field>
      </app-card>

      <app-card title="How the rules combine">
        <div class="choice-grid" role="radiogroup" aria-label="Evaluation policy">
          @for (p of policies; track p.value) {
            <label class="choice">
              <span class="check"><input type="radio" name="policy" [value]="p.value" [checked]="policy() === p.value" (change)="policy.set(p.value)" /><strong>{{ p.title }}</strong></span>
              <span>{{ p.text }}</span>
            </label>
          }
        </div>
        <div class="form-grid gap-top">
          @if (policy() === 'FIRST_MATCH' || policy() === 'ALL_MATCH') {
            <app-field controlId="group-match" label="Match on" hint="Which result counts as a match.">
              <select id="group-match" name="match" [(ngModel)]="matchOn"><option value="FALSE">A rule that fails (false)</option><option value="TRUE">A rule that holds (true)</option></select>
            </app-field>
          }
          <app-field controlId="group-on-error" label="If a rule cannot be evaluated" hint="A missing or invalid value is never silently skipped.">
            <select id="group-on-error" name="onError" [(ngModel)]="onError"><option value="BLOCK">Block (recommended)</option><option value="WARN">Warn</option><option value="ALLOW">Allow</option></select>
          </app-field>
        </div>
      </app-card>

      <app-card title="Rules" sub="They run in the order shown. Use the arrows to reorder.">
        <h3>In this group</h3>
        @if (members().length === 0) { <p class="hint">No rule yet: a group without rules always allows.</p> }
        <ol class="list-reset" aria-label="Rules in this group">
          @for (m of members(); track m.ruleCode; let i = $index) {
            <li class="rule-row">
              <span class="badge">{{ (i + 1) * 10 }}</span>
              <span>
                <strong>{{ byCode().get(m.ruleCode)?.name ?? m.ruleCode }}</strong> <span class="hint mono">{{ m.ruleCode }}</span>
                @if (byCode().get(m.ruleCode); as r) {
                  @if (r.status !== 'ACTIVE') { <app-status [value]="r.status" /> }
                  <div class="hint"><code>{{ r.expression }}</code></div>
                }
              </span>
              <label class="check"><input type="checkbox" [name]="'runs-' + m.ruleCode" [checked]="m.enabled" (change)="setEnabled(i, $any($event.target).checked)" /> runs</label>
              <span class="actions">
                <button type="button" class="btn btn-small" [attr.aria-label]="'Move ' + m.ruleCode + ' up'" [disabled]="i === 0" (click)="move(i, -1)">↑</button>
                <button type="button" class="btn btn-small" [attr.aria-label]="'Move ' + m.ruleCode + ' down'" [disabled]="i === members().length - 1" (click)="move(i, 1)">↓</button>
                <button type="button" class="btn btn-small btn-danger" [attr.aria-label]="'Remove ' + m.ruleCode" (click)="removeMember(i)">Remove</button>
              </span>
            </li>
          }
        </ol>
        <h3 class="gap-top">Available in {{ module() || 'the module' }}</h3>
        @if (rules.loading() && !rules.data()) { <app-loading what="Loading rules" /> }
        @if (rules.data()) {
          @if (candidates().length === 0) {
            <p class="hint">{{ members().length > 0 ? 'Every available rule is already in the group.' : 'No rule available yet.' }} <a routerLink="/rules/new">Create a rule</a></p>
          } @else {
            <ul class="list-reset">
              @for (r of candidates(); track r.id) {
                <li class="rule-row rule-row-pick">
                  <span><strong>{{ r.name }}</strong> <span class="hint mono">{{ r.code }}</span><div class="hint"><code>{{ r.expression }}</code></div></span>
                  <app-status [value]="r.status" />
                  <app-scope [value]="r.scope" />
                  <button type="button" class="btn btn-small" (click)="addMember(r.code)">Add</button>
                </li>
              }
            </ul>
          }
        }
      </app-card>

      @if (policy() === 'COMPOSITE') {
        <app-card title="Group message and action" sub="One answer for the whole group, in as many languages as you like.">
          <div class="form-grid">
            <app-field controlId="group-true-action" label="When every rule is true">
              <select id="group-true-action" name="trueAction" [(ngModel)]="trueAction"><option>ALLOW</option><option>WARN</option><option>BLOCK</option></select>
            </app-field>
            <app-field controlId="group-false-action" label="When any rule is false">
              <select id="group-false-action" name="falseAction" [(ngModel)]="falseAction"><option>BLOCK</option><option>WARN</option><option>ALLOW</option></select>
            </app-field>
          </div>
          <app-texts-editor label="Message when every rule is true" [(value)]="trueMessage" />
          <app-texts-editor label="Message when any rule is false" [(value)]="falseMessage" />
        </app-card>
      }

      <app-card title="Trigger points" sub="Optional. They make an application form action (or a field change) call this group.">
        @for (t of triggers(); track t.key; let i = $index) {
          <div class="panel">
            <div class="form-grid">
              <app-field [controlId]="'t' + i + '-app'" label="Application"><input [id]="'t' + i + '-app'" [name]="'t' + i + '-app'" type="text" [ngModel]="t.application" (ngModelChange)="patchTrigger(t.key, { application: $event })" /></app-field>
              <app-field [controlId]="'t' + i + '-type'" label="Fires on">
                <select [id]="'t' + i + '-type'" [name]="'t' + i + '-type'" [ngModel]="t.type" (ngModelChange)="patchTrigger(t.key, { type: $event })">
                  <option value="FORM_ACTION">A form action (submit, approve …)</option><option value="FORM_FIELD">A field change</option>
                </select>
              </app-field>
              <app-field [controlId]="'t' + i + '-form'" label="Form"><input [id]="'t' + i + '-form'" [name]="'t' + i + '-form'" type="text" [ngModel]="t.formCode" (ngModelChange)="patchTrigger(t.key, { formCode: $event })" /></app-field>
              <app-field [controlId]="'t' + i + '-action'" label="Action"><input [id]="'t' + i + '-action'" [name]="'t' + i + '-action'" type="text" [ngModel]="t.actionCode" (ngModelChange)="patchTrigger(t.key, { actionCode: $event })" /></app-field>
              @if (t.type === 'FORM_FIELD') {
                <app-field [controlId]="'t' + i + '-field'" label="Field"><input [id]="'t' + i + '-field'" [name]="'t' + i + '-field'" type="text" [ngModel]="t.fieldCode ?? ''" (ngModelChange)="patchTrigger(t.key, { fieldCode: $event })" /></app-field>
              }
            </div>
            @if (errors()['trigger' + i]) { <p class="field-error" role="alert">{{ errors()['trigger' + i] }}</p> }
            <button type="button" class="btn btn-small btn-danger" (click)="removeTrigger(t.key)">Remove trigger</button>
          </div>
        }
        <button type="button" class="btn" (click)="addTrigger()">+ Add a trigger point</button>
      </app-card>

      <app-card title="Visibility and status">
        <div class="form-grid">
          <app-field controlId="group-scope" label="Who can use this group" [hint]="organizationId && !admin ? 'Only an administrator can share a group with the whole tenant.' : null">
            <select id="group-scope" name="scope" [disabled]="editing" [(ngModel)]="scope">
              @if (organizationId) { <option value="ORGANIZATION">My organization ({{ organizationName }})</option> }
              @if (admin || !organizationId) { <option value="TENANT">The whole tenant</option> }
            </select>
          </app-field>
          <app-field controlId="group-status" label="Status">
            <select id="group-status" name="status" [(ngModel)]="status"><option value="ACTIVE">Active — applications get its decisions</option><option value="DRAFT">Draft — saved, never evaluated</option></select>
          </app-field>
        </div>
      </app-card>

      <div class="actions">
        <button class="btn btn-primary" type="submit" [disabled]="busy()">{{ busy() ? 'Saving…' : editing ? 'Save changes' : 'Create rule group' }}</button>
        <a class="btn" routerLink="/groups">Cancel</a>
      </div>
    </form>
  `,
})
export class GroupEditor implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly sessionService = inject(SessionService);

  readonly group = input.required<GroupView | null>();
  protected readonly policies = POLICIES;
  protected readonly organizationId = this.sessionService.session()?.organizationId || null;
  protected readonly organizationName = this.sessionService.session()?.organizationName ?? '';
  protected readonly admin = this.sessionService.isAdmin();
  protected editing = false;

  protected readonly modules = load(() => this.api.modules());
  protected readonly moduleCode = signal('');
  protected readonly code = signal('');
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly policy = signal<Policy>('COMPOSITE');
  protected readonly matchOn = signal<'TRUE' | 'FALSE'>('FALSE');
  protected readonly onError = signal<Action>('BLOCK');
  protected readonly trueAction = signal<Action>('ALLOW');
  protected readonly falseAction = signal<Action>('BLOCK');
  protected readonly trueMessage = signal<Texts>({ en: '' });
  protected readonly falseMessage = signal<Texts>({ en: '' });
  protected readonly members = signal<Member[]>([]);
  protected readonly triggers = signal<TriggerRow[]>([]);
  protected readonly status = signal<'ACTIVE' | 'DRAFT'>('ACTIVE');
  protected readonly scope = signal<Scope>(this.organizationId ? 'ORGANIZATION' : 'TENANT');
  protected readonly errors = signal<Record<string, string>>({});
  protected readonly failure = signal<unknown>(null);
  protected readonly busy = signal(false);

  protected readonly module = computed(() => this.moduleCode() || this.modules.data()?.[0]?.code || '');
  protected readonly rules = load(() => (this.module() ? this.api.rules(this.module()) : Promise.resolve([] as RuleView[])));
  protected readonly byCode = computed(() => new Map((this.rules.data() ?? []).map((r) => [r.code, r])));
  // a rule of this organization cannot be part of a group shared by the whole tenant
  protected readonly candidates = computed(() =>
    (this.rules.data() ?? []).filter((r) => !this.members().some((m) => m.ruleCode === r.code) && (this.scope() === 'ORGANIZATION' || r.scope === 'TENANT')),
  );

  constructor() {
    // the rules of the chosen module are loaded again when the module changes
    effect(() => {
      this.module();
      untracked(() => this.rules.reload());
    });
    effect(() => {
      if (this.scope() === 'TENANT') {
        const by = this.byCode();
        untracked(() => this.members.update((m) => m.filter((x) => by.get(x.ruleCode)?.scope !== 'ORGANIZATION')));
      }
    });
  }

  ngOnInit(): void {
    const g = this.group();
    if (!g) {
      return;
    }
    this.editing = true;
    this.moduleCode.set(g.moduleCode);
    this.code.set(g.code);
    this.name.set(g.name);
    this.description.set(g.description ?? '');
    this.policy.set(g.policy);
    this.matchOn.set(g.matchOn);
    this.onError.set(g.onError);
    this.trueAction.set(g.compositeTrueAction);
    this.falseAction.set(g.compositeFalseAction);
    this.trueMessage.set(Object.keys(g.compositeTrueMessage).length ? g.compositeTrueMessage : { en: '' });
    this.falseMessage.set(Object.keys(g.compositeFalseMessage).length ? g.compositeFalseMessage : { en: '' });
    this.members.set(g.rules.map((r) => ({ ruleCode: r.ruleCode, enabled: r.enabled })));
    this.triggers.set(g.triggers.map((t, i) => ({ key: i, application: t.application, type: t.type, formCode: t.formCode, actionCode: t.actionCode, fieldCode: t.fieldCode ?? undefined })));
    this.status.set(g.status === 'DRAFT' ? 'DRAFT' : 'ACTIVE');
    this.scope.set(g.scope);
  }

  protected changeModule(m: string): void {
    this.moduleCode.set(m);
    this.members.set([]);
  }

  protected addMember(ruleCode: string): void {
    this.members.update((l) => [...l, { ruleCode, enabled: true }]);
  }

  protected removeMember(i: number): void {
    this.members.update((l) => l.filter((_, j) => j !== i));
  }

  protected setEnabled(i: number, enabled: boolean): void {
    this.members.update((l) => l.map((x, j) => (j === i ? { ...x, enabled } : x)));
  }

  protected move(i: number, by: number): void {
    this.members.update((list) => {
      const next = [...list];
      const a = next[i];
      const b = next[i + by];
      if (!a || !b) return list;
      next[i] = b;
      next[i + by] = a;
      return next;
    });
  }

  protected addTrigger(): void {
    this.triggers.update((l) => [...l, { key: Date.now(), application: '', type: 'FORM_ACTION', formCode: '', actionCode: '' }]);
  }

  protected removeTrigger(key: number): void {
    this.triggers.update((l) => l.filter((x) => x.key !== key));
  }

  protected patchTrigger(key: number, patch: Partial<TriggerSpec>): void {
    this.triggers.update((l) => l.map((x) => (x.key === key ? { ...x, ...patch } : x)));
  }

  protected async submit(): Promise<void> {
    const found: Record<string, string> = {};
    if (!CODE.test(this.code())) found['code'] = 'Use letters, digits, ".", "_" or "-" (1–64 characters).';
    if (!this.name().trim()) found['name'] = 'Give the group a name.';
    this.triggers().forEach((t, i) => {
      if (!t.application || !t.formCode || !t.actionCode) found['trigger' + i] = 'A trigger needs an application, a form and an action.';
      if (t.type === 'FORM_FIELD' && !t.fieldCode) found['trigger' + i] = 'A field trigger needs the field code.';
    });
    this.errors.set(found);
    this.failure.set(null);
    if (Object.keys(found).length) {
      return;
    }
    const p = this.policy();
    const existing = this.group();
    const request: GroupRequest = {
      moduleCode: this.module(),
      code: this.code(),
      name: this.name().trim(),
      description: this.description().trim() || undefined,
      policy: p,
      matchOn: p === 'FIRST_MATCH' || p === 'ALL_MATCH' ? this.matchOn() : 'TRUE',
      onError: this.onError(),
      compositeTrueAction: this.trueAction(),
      compositeFalseAction: this.falseAction(),
      compositeTrueMessage: p === 'COMPOSITE' ? cleanTexts(this.trueMessage()) : undefined,
      compositeFalseMessage: p === 'COMPOSITE' ? cleanTexts(this.falseMessage()) : undefined,
      rules: this.members().map((m, i) => ({ ruleCode: m.ruleCode, sequence: (i + 1) * 10, enabled: m.enabled })),
      triggers: this.triggers().map(({ key: _key, ...t }) => ({ ...t, fieldCode: t.type === 'FORM_FIELD' ? t.fieldCode : undefined })),
      status: this.status(),
      scope: this.scope(),
      expectedRowVersion: existing?.rowVersion,
    };
    this.busy.set(true);
    try {
      const written = existing ? await this.api.replaceGroup(existing.id, request) : await this.api.createGroup(request);
      await this.router.navigate(['/groups'], { queryParams: { open: written.value.id }, state: { warnings: written.warnings, saved: written.value.name } });
    } catch (e) {
      const err = toApiError(e);
      if (err.code === 'duplicate') {
        this.errors.set({ code: 'A group with this code already exists in this module and scope.' });
      } else {
        this.failure.set(err);
      }
    } finally {
      this.busy.set(false);
    }
  }
}
