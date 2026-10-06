import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { Inline } from '../shared/markdown';

/** Renders the inline pieces of an answer line as elements, never as HTML. */
@Component({
  selector: 'app-inline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@for (p of pieces(); track $index) {@switch (p.kind) {@case ('code') {<code>{{ p.text }}</code>}@case ('bold') {<strong>{{ p.text }}</strong>}@case ('italic') {<em>{{ p.text }}</em>}@default {{{ p.text }}}}}`,
})
export class InlineText {
  readonly pieces = input.required<Inline[]>();
}
