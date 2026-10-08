import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ApiError } from '../core/api.service';

/** Decision / action badge with the meaning in text, not colour alone. */
@Component({
  selector: 'app-action',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="'badge badge-' + kind()">{{ value() }}</span>`,
})
export class ActionBadge {
  readonly value = input.required<string>();
  protected readonly kind = computed(() => {
    const v = this.value();
    return v === 'ALLOW' ? 'allow' : v === 'WARN' ? 'warn' : v === 'BLOCK' ? 'block' : 'info';
  });
}

@Component({
  selector: 'app-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="'badge badge-' + kind()">{{ value() }}</span>`,
})
export class StatusBadge {
  readonly value = input.required<string>();
  protected readonly kind = computed(() => (this.value() === 'ACTIVE' ? 'allow' : this.value() === 'DRAFT' ? 'warn' : 'info'));
}

@Component({
  selector: 'app-outcome',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="'badge badge-' + kind()">{{ value() }}</span>`,
})
export class OutcomeBadge {
  readonly value = input.required<string>();
  protected readonly kind = computed(() => (this.value() === 'TRUE' ? 'allow' : this.value() === 'FALSE' ? 'warn' : 'block'));
}

@Component({
  selector: 'app-scope',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge">{{ value() === 'TENANT' ? 'whole tenant' : 'my organization' }}</span>`,
})
export class ScopeBadge {
  readonly value = input.required<string>();
}

@Component({
  selector: 'app-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div>
        <h1>{{ title() }}</h1>
        <p><ng-content /></p>
      </div>
      <div class="actions"><ng-content select="[actions]" /></div>
    </header>
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
}

@Component({
  selector: 'app-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card">
      @if (title()) {
        <h2>{{ title() }}</h2>
      }
      @if (sub()) {
        <p class="card-sub">{{ sub() }}</p>
      }
      <ng-content />
    </section>
  `,
})
export class Card {
  readonly title = input<string>('');
  readonly sub = input<string>('');
}

/** The message of a failed request, announced to assistive technology. */
@Component({
  selector: 'app-error-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error()) {
      <div class="notice notice-error" role="alert">
        {{ message() }}
        @if (code()) {
          <span class="hint"> ({{ code() }})</span>
        }
      </div>
    }
  `,
})
export class ErrorNotice {
  readonly error = input<unknown>(null);
  protected readonly message = computed(() => {
    const e = this.error();
    return e instanceof Error ? e.message : 'Something went wrong';
  });
  protected readonly code = computed(() => {
    const e = this.error();
    return e instanceof ApiError && e.code !== 'invalid_request' ? e.code : null;
  });
}

@Component({
  selector: 'app-loading',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p role="status" aria-live="polite"><span class="spinner" aria-hidden="true"></span> <span>{{ what() }}…</span></p>`,
})
export class Loading {
  readonly what = input<string>('Loading');
}

/** A labelled form control: the projected control must carry the given {@code controlId}. */
@Component({
  selector: 'app-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      <label [for]="controlId()">{{ label() }}</label>
      <ng-content />
      @if (hint()) {
        <span class="hint">{{ hint() }}</span>
      }
      @if (error()) {
        <span class="field-error" role="alert">{{ error() }}</span>
      }
    </div>
  `,
})
export class Field {
  readonly controlId = input.required<string>();
  readonly label = input.required<string>();
  readonly hint = input<string | null>(null);
  readonly error = input<string | null | undefined>(null);
}
