import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService, toApiError } from '../core/api.service';
import type { Attribute, Decision } from '../core/types';
import { load } from '../shared/resource';
import { ActionBadge, Card, ErrorNotice, Field, Loading, OutcomeBadge, PageHeader } from '../shared/ui';

/** Converts what was typed into the JSON value of the parameter's type; blank means "not supplied". */
export function factValue(attribute: Attribute | undefined, text: string): unknown {
  const t = text.trim();
  if (t === '') {
    return undefined;
  }
  switch (attribute?.dataType) {
    case 'INT':
    case 'DOUBLE': {
      const n = Number(t);
      return Number.isNaN(n) ? t : n; // a non-number is sent as text and reported by the engine as INVALID_PARAMETER
    }
    case 'BOOL':
      return t === 'true';
    case 'LIST_STRING':
    case 'LIST_INT':
    case 'LIST_DOUBLE':
    case 'MAP':
    case 'ANY':
      try {
        return JSON.parse(t) as unknown;
      } catch {
        return t;
      }
    default:
      return t;
  }
}

export function formatMillis(micros: number): string {
  const ms = micros / 1000;
  return ms < 10 ? ms.toFixed(2) + ' ms' : ms.toFixed(1) + ' ms';
}

/**
 * The test bench: pick a group, fill in the parameters its rules read, see what the engine decides. Nothing is sent to
 * anyone, and the values you type are neither stored nor logged (the log keeps decisions and counts only).
 */
@Component({
  selector: 'app-test-bench',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, PageHeader, Card, Field, ErrorNotice, Loading, ActionBadge, OutcomeBadge],
  template: `
    <app-page-header title="Test bench">
      Ask the engine what it decides for values you supply. Nothing is e-mailed or called; the values you type are not stored.
    </app-page-header>
    <app-error-notice [error]="groups.error()" />
    @if (groups.loading() && !groups.data()) { <app-loading what="Loading rule groups" /> }
    @if (groups.data(); as list) {
      <form (ngSubmit)="run()">
        <app-card title="1. Choose a group" sub="Only active groups can be evaluated.">
          <app-field controlId="bench-group" label="Rule group">
            <select id="bench-group" name="group" [ngModel]="selected()" (ngModelChange)="choose($event)">
              <option value="">Select…</option>
              @for (g of list; track g.id) { <option [value]="g.moduleCode + '/' + g.code">{{ g.moduleCode }} · {{ g.name }}</option> }
            </select>
          </app-field>
        </app-card>
        @if (group(); as g) {
          <app-card title="2. Supply values" [sub]="g.name + ' reads ' + needed().length + ' parameter(s). Leave one blank to see what a missing value does.'">
            <div class="form-grid">
              @for (n of needed(); track n) {
                <app-field [controlId]="'fact-' + n" [label]="n" [hint]="hintOf(n)">
                  @if (attributes().get(n)?.dataType === 'BOOL') {
                    <select [id]="'fact-' + n" [name]="'fact-' + n" [ngModel]="valueOf(n)" (ngModelChange)="setValue(n, $event)">
                      <option value="">(not supplied)</option><option value="true">true</option><option value="false">false</option>
                    </select>
                  } @else {
                    <input [id]="'fact-' + n" [name]="'fact-' + n" type="text" [attr.inputmode]="numeric(n) ? 'decimal' : 'text'"
                           [ngModel]="valueOf(n)" (ngModelChange)="setValue(n, $event)" />
                  }
                </app-field>
              }
            </div>
            <fieldset class="field texts">
              <legend class="label">Answer in</legend>
              <div class="actions">
                @for (l of languageChoices(); track l) {
                  <label class="check"><input type="checkbox" [name]="'lang-' + l" [checked]="languages().includes(l)" (change)="toggleLanguage(l, $any($event.target).checked)" /> {{ l }}</label>
                }
              </div>
              <span class="hint">The first language with a text wins; the others are fallbacks, in this order.</span>
            </fieldset>
            <div class="actions">
              <button class="btn btn-primary" type="submit" [disabled]="busy()">{{ busy() ? 'Evaluating…' : 'Evaluate' }}</button>
              <button class="btn" type="button" (click)="fillSamples()">Fill sample values</button>
            </div>
          </app-card>
        }
      </form>
    }
    <app-error-notice [error]="error()" />
    @if (result(); as r) {
      <app-card title="3. Decision">
        <div aria-live="polite">
          <p class="decision"><app-action [value]="r.decision" /> <span class="hint">{{ r.policy.replace('_', ' ').toLowerCase() }} · {{ millis(r.durationMicros) }}</span></p>
          @if (r.primaryMessage; as m) {
            <div class="notice" [class.notice-error]="r.decision === 'BLOCK'" [class.notice-warn]="r.decision === 'WARN'" [class.notice-ok]="r.decision === 'ALLOW'">
              {{ m.text }} <span class="badge">{{ m.language }}</span>
            </div>
          }
        </div>
        @if (r.messages.length) {
          <h3>Messages</h3>
          <ul>@for (m of r.messages; track $index) { <li><app-action [value]="m.action" /> {{ m.text }} <span class="badge">{{ m.language }}</span>@if (m.ruleCode) { <span class="hint mono"> {{ m.ruleCode }}</span> }</li> }</ul>
        }
        <h3>Rule by rule</h3>
        <div class="table-wrap"><table>
          <caption class="visually-hidden">Result of each rule</caption>
          <thead><tr><th class="num">#</th><th>Rule</th><th>Result</th><th>Action</th><th>Problem</th></tr></thead>
          <tbody>
            @for (x of r.results; track x.ruleCode) {
              <tr>
                <td class="num">{{ x.sequence }}</td>
                <td>{{ x.ruleName }} <span class="hint mono">{{ x.ruleCode }}</span></td>
                <td><app-outcome [value]="x.outcome" /></td>
                <td><app-action [value]="x.action" /></td>
                <td>{{ x.errorCode ? x.errorCode + (x.errorDetail ? ' — ' + x.errorDetail : '') : '—' }}</td>
              </tr>
            }
          </tbody>
        </table></div>
        @if (r.channels.length) {
          <h3>Communications this decision would trigger</h3>
          <p class="hint">Described only: nothing was sent, and recipients are never shown.</p>
          <ul>@for (c of r.channels; track $index) { <li><span class="badge badge-info">{{ c.type }}</span> when {{ c.onResult.toLowerCase() }}{{ c.target ? ' → ' + c.target : '' }}{{ c.recipientResolved ? ' (recipient resolved from the values)' : ' (no recipient)' }}</li> }</ul>
        }
      </app-card>
    }
  `,
})
export class TestBenchPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  /** {@code ?module=&group=}: a group to preselect (bound from the query string). */
  readonly module = input<string | undefined>();
  readonly groupCode = input<string | undefined>(undefined, { alias: 'group' });

  protected readonly groups = load(() => this.api.groups(undefined, 'ACTIVE'));
  private readonly rules = load(() => this.api.rules());
  private readonly library = load(() => this.api.library());
  private readonly setup = load(() => this.api.setup());
  protected readonly values = signal<Record<string, string>>({});
  protected readonly languages = signal<string[]>(['en']);
  protected readonly result = signal<Decision | null>(null);
  protected readonly error = signal<unknown>(null);
  protected readonly busy = signal(false);
  private readonly chosen = signal<string | null>(null);

  protected readonly selected = computed(() => this.chosen() ?? (this.groupCode() ? `${this.module()}/${this.groupCode()}` : ''));
  protected readonly group = computed(() => this.groups.data()?.find((g) => `${g.moduleCode}/${g.code}` === this.selected()) ?? null);
  protected readonly languageChoices = computed(() => this.setup.data()?.languages ?? ['en']);
  protected readonly attributes = computed(() => {
    const map = new Map<string, Attribute>();
    this.library.data()?.forEach((o) => o.attributes.forEach((a) => map.set(a.celName, a)));
    return map;
  });
  protected readonly needed = computed(() => {
    const g = this.group();
    const rules = this.rules.data();
    if (!g || !rules) return [] as string[];
    const names = new Set<string>();
    for (const m of g.rules) {
      rules.find((r) => r.code === m.ruleCode && r.moduleCode === g.moduleCode)?.parameters.forEach((p) => names.add(p));
    }
    return [...names].sort();
  });

  protected millis = formatMillis;

  protected choose(value: string): void {
    this.chosen.set(value);
    this.result.set(null);
    this.error.set(null);
    const [module, code] = value.split('/');
    void this.router.navigate([], { queryParams: module && code ? { module, group: code } : {}, replaceUrl: true });
  }

  protected valueOf(n: string): string {
    return this.values()[n] ?? '';
  }

  protected setValue(name: string, v: string): void {
    this.values.update((cur) => ({ ...cur, [name]: v }));
  }

  protected numeric(n: string): boolean {
    const t = this.attributes().get(n)?.dataType;
    return t === 'INT' || t === 'DOUBLE';
  }

  protected hintOf(n: string): string | null {
    const a = this.attributes().get(n);
    return a ? `${a.name} · ${a.dataType}` : null;
  }

  protected toggleLanguage(l: string, on: boolean): void {
    this.languages.update((cur) => (on ? [...cur, l] : cur.filter((x) => x !== l)));
  }

  protected fillSamples(): void {
    const next: Record<string, string> = {};
    this.needed().forEach((n) => {
      const sample = this.attributes().get(n)?.sampleValue;
      if (sample) next[n] = sample;
    });
    this.values.set(next);
  }

  protected async run(): Promise<void> {
    const g = this.group();
    if (!g) return;
    const facts: Record<string, unknown> = {};
    this.needed().forEach((n) => {
      const v = factValue(this.attributes().get(n), this.values()[n] ?? '');
      if (v !== undefined) facts[n] = v;
    });
    this.busy.set(true);
    this.error.set(null);
    try {
      this.result.set(await this.api.evaluate(g.moduleCode, g.code, facts, this.languages().length ? this.languages() : ['en']));
    } catch (e) {
      this.result.set(null);
      this.error.set(toApiError(e));
    } finally {
      this.busy.set(false);
    }
  }
}
