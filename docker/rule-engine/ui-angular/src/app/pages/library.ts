import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { load } from '../shared/resource';
import { Card, ErrorNotice, Field, Loading, PageHeader } from '../shared/ui';

/** The parameter library: every `object.attribute` a rule may read, with its type and how many rules use it. */
@Component({
  selector: 'app-library',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, PageHeader, Card, ErrorNotice, Loading, Field],
  template: `
    <app-page-header title="Parameter library">
      A rule is a CEL expression over these typed parameters, for example <code>customer.age &gt;= 18</code>. An application sends a
      value for each parameter a rule reads; a rule can only read parameters that are listed here.
    </app-page-header>
    <div class="toolbar">
      <app-field controlId="library-filter" label="Find a parameter">
        <input id="library-filter" type="text" [(ngModel)]="filter" />
      </app-field>
    </div>
    <app-error-notice [error]="library.error()" />
    @if (library.loading() && !library.data()) { <app-loading what="Loading the library" /> }
    @if (library.data()) {
      @for (o of shown(); track o.code) {
        <app-card [title]="o.name + ' (' + o.code + ')'" [sub]="o.moduleCode ? 'Module ' + o.moduleCode : 'Shared by every module'">
          <div class="table-wrap">
            <table>
              <caption class="visually-hidden">Parameters of {{ o.name }}</caption>
              <thead><tr><th>CEL name</th><th>Meaning</th><th>Type</th><th>Required</th><th>Sample</th><th class="num">Used by rules</th></tr></thead>
              <tbody>
                @for (a of o.attributes; track a.celName) {
                  <tr>
                    <td><code>{{ a.celName }}</code></td>
                    <td>{{ a.name }}</td>
                    <td><span class="badge badge-info">{{ a.dataType }}</span></td>
                    <td>{{ a.required ? 'yes' : 'no' }}</td>
                    <td class="mono">{{ a.sampleValue ?? '—' }}</td>
                    <td class="num">{{ a.usedByRules }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </app-card>
      } @empty {
        <div class="empty">No parameter matches “{{ filter() }}”.</div>
      }
    }
  `,
})
export class LibraryPage {
  private readonly api = inject(ApiService);
  protected readonly library = load(() => this.api.library());
  protected readonly filter = signal('');
  protected readonly shown = computed(() => {
    const needle = this.filter().trim().toLowerCase();
    return (this.library.data() ?? [])
      .map((o) => ({
        ...o,
        attributes: o.attributes.filter((a) => !needle || a.celName.toLowerCase().includes(needle) || a.name.toLowerCase().includes(needle)),
      }))
      .filter((o) => o.attributes.length > 0);
  });
}
