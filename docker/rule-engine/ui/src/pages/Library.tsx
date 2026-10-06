import { useMemo, useState } from 'react';
import { engine } from '../api/engine';
import { Async, Card, Empty, Field, PageHeader, useLoad } from '../components/ui';

/** The parameter library: every `object.attribute` a rule may read, with its type and how many rules use it. */
export function LibraryPage() {
  const library = useLoad(engine.library, []);
  const [filter, setFilter] = useState('');
  const needle = filter.trim().toLowerCase();
  const shown = useMemo(
    () =>
      (library.data ?? [])
        .map((o) => ({
          ...o,
          attributes: o.attributes.filter(
            (a) => !needle || a.celName.toLowerCase().includes(needle) || a.name.toLowerCase().includes(needle),
          ),
        }))
        .filter((o) => o.attributes.length > 0),
    [library.data, needle],
  );
  return (
    <>
      <PageHeader title="Parameter library">
        A rule is a CEL expression over these typed parameters, for example <code>customer.age &gt;= 18</code>. An application
        sends a value for each parameter a rule reads; a rule can only read parameters that are listed here.
      </PageHeader>
      <div className="toolbar">
        <Field id="library-filter" label="Find a parameter">
          <input id="library-filter" type="text" value={filter} onChange={(e) => setFilter(e.target.value)} />
        </Field>
      </div>
      <Async load={library} what="Loading the library">
        {() =>
          shown.length === 0 ? (
            <Empty>No parameter matches “{filter}”.</Empty>
          ) : (
            <>
              {shown.map((o) => (
                <Card key={o.code} title={`${o.name} (${o.code})`} sub={o.moduleCode ? `Module ${o.moduleCode}` : 'Shared by every module'}>
                  <div className="table-wrap">
                    <table>
                      <caption className="visually-hidden">Parameters of {o.name}</caption>
                      <thead>
                        <tr>
                          <th>CEL name</th>
                          <th>Meaning</th>
                          <th>Type</th>
                          <th>Required</th>
                          <th>Sample</th>
                          <th className="num">Used by rules</th>
                        </tr>
                      </thead>
                      <tbody>
                        {o.attributes.map((a) => (
                          <tr key={a.celName}>
                            <td>
                              <code>{a.celName}</code>
                            </td>
                            <td>{a.name}</td>
                            <td>
                              <span className="badge badge-info">{a.dataType}</span>
                            </td>
                            <td>{a.required ? 'yes' : 'no'}</td>
                            <td className="mono">{a.sampleValue ?? '—'}</td>
                            <td className="num">{a.usedByRules}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </Card>
              ))}
            </>
          )
        }
      </Async>
    </>
  );
}
