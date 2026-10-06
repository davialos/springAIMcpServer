import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import type { Texts } from '../core/types';

const COMMON: Array<[string, string]> = [
  ['en', 'English'],
  ['hi', 'Hindi'],
  ['th', 'Thai'],
  ['pt-BR', 'Portuguese (Brazil)'],
  ['es', 'Spanish'],
  ['fr', 'French'],
  ['de', 'German'],
];

/** Drops blank rows (a language without text) before sending. */
export function cleanTexts(texts: Texts): Texts | undefined {
  const entries = Object.entries(texts)
    .map(([k, v]) => [k.trim(), v.trim()] as const)
    .filter(([k, v]) => k && v);
  return entries.length ? Object.fromEntries(entries) : undefined;
}

/**
 * One message in several languages: a row per language (tag + text). Empty rows are dropped by {@link cleanTexts}; the
 * service validates tags and length again, this is only for the experience.
 */
@Component({
  selector: 'app-texts-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <fieldset class="field texts">
      <legend class="label">{{ label() }}</legend>
      @if (rows().length === 0) {
        <p class="hint">No message: this outcome stays silent.</p>
      }
      @for (row of rows(); track $index) {
        <div class="texts-row">
          <input type="text" [attr.aria-label]="label() + ': language ' + ($index + 1)" [value]="row[0]"
                 (change)="rename($index, $any($event.target).value)" />
          <input type="text" [attr.aria-label]="label() + ': text (' + row[0] + ')'" [value]="row[1]"
                 (input)="retext($index, $any($event.target).value)" />
          <button type="button" class="btn btn-small btn-danger" [attr.aria-label]="'Remove ' + row[0] + ' message'"
                  (click)="remove($index)">Remove</button>
        </div>
      }
      <div class="actions">
        @for (c of unused(); track c[0]) {
          <button type="button" class="btn btn-small" (click)="add(c[0])">+ {{ c[1] }}</button>
        }
      </div>
    </fieldset>
  `,
})
export class TextsEditor {
  readonly label = input.required<string>();
  readonly value = model.required<Texts>();

  protected readonly rows = computed(() => Object.entries(this.value()));
  protected readonly unused = computed(() => COMMON.filter(([tag]) => !(tag in this.value())).slice(0, 3));

  private set(next: Array<[string, string]>): void {
    this.value.set(Object.fromEntries(next));
  }

  protected rename(i: number, tag: string): void {
    this.set(this.rows().map(([t, x], j) => (j === i ? [tag.trim(), x] : [t, x])));
  }

  protected retext(i: number, text: string): void {
    this.set(this.rows().map(([t, x], j) => (j === i ? [t, text] : [t, x])));
  }

  protected remove(i: number): void {
    this.set(this.rows().filter((_, j) => j !== i));
  }

  protected add(tag: string): void {
    this.set([...this.rows(), [tag, '']]);
  }
}
