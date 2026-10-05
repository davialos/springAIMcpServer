import { useState } from 'react';
import { engine } from '../api/engine';
import { Async, Card, PageHeader, ScopeBadge, useLoad } from '../components/ui';

type Tab = 'triggers' | 'channels' | 'templates' | 'endpoints';

/** How applications reach the groups (trigger points) and what is communicated afterwards (channels). Read-only for now. */
export function ChannelsPage() {
  const [tab, setTab] = useState<Tab>('triggers');
  const triggers = useLoad(engine.triggers, []);
  const channels = useLoad(engine.channels, []);
  const templates = useLoad(engine.emailTemplates, []);
  const endpoints = useLoad(engine.apiEndpoints, []);
  const tabs: Array<[Tab, string]> = [
    ['triggers', 'Trigger points'],
    ['channels', 'Channels'],
    ['templates', 'E-mail templates'],
    ['endpoints', 'API endpoints'],
  ];
  return (
    <>
      <PageHeader title="Triggers &amp; channels">
        A trigger point says “when form F gets action A, run group G”. A channel says “when group or rule X yields Y, send an
        e-mail, a push message or call an API”. Editing these is not part of this console yet.
      </PageHeader>
      <div className="tabs" role="tablist" aria-label="Setup areas">
        {tabs.map(([key, label]) => (
          <button key={key} role="tab" aria-selected={tab === key} onClick={() => setTab(key)}>
            {label}
          </button>
        ))}
      </div>
      {tab === 'triggers' && (
        <Async load={triggers}>
          {(list) =>
            list.length === 0 ? (
              <p className="empty">No trigger point yet.</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Trigger points</caption>
                  <thead>
                    <tr>
                      <th>Application</th>
                      <th>Form</th>
                      <th>On</th>
                      <th>Runs group</th>
                      <th className="num">Order</th>
                      <th>Scope</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.map((t) => (
                      <tr key={t.id}>
                        <td>
                          <code>{t.application}</code>
                        </td>
                        <td>{t.formCode}</td>
                        <td>
                          {t.type === 'FORM_FIELD' ? `change of ${t.fieldCode}` : t.actionCode}
                          {!t.enabled && <span className="badge"> off</span>}
                        </td>
                        <td>
                          {t.moduleCode} · <code>{t.groupCode}</code>
                        </td>
                        <td className="num">{t.sequence}</td>
                        <td>
                          <ScopeBadge value={t.scope} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
          }
        </Async>
      )}
      {tab === 'channels' && (
        <Async load={channels}>
          {(list) =>
            list.length === 0 ? (
              <p className="empty">No channel yet.</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Channels</caption>
                  <thead>
                    <tr>
                      <th>Bound to</th>
                      <th>When</th>
                      <th>Channel</th>
                      <th>Target</th>
                      <th>Recipient</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.map((c) => (
                      <tr key={c.id}>
                        <td>
                          {c.ownerType.toLowerCase()} <code>{c.ownerCode}</code>
                        </td>
                        <td>result is {c.onResult.toLowerCase()}</td>
                        <td>
                          <span className="badge badge-info">{c.channelType}</span>
                          {!c.enabled && <span className="badge"> off</span>}
                        </td>
                        <td>{c.emailTemplate ?? c.apiEndpoint ?? 'localized push message'}</td>
                        <td>{c.hasRecipientExpression ? 'from the facts (CEL)' : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
          }
        </Async>
      )}
      {tab === 'templates' && (
        <Async load={templates}>
          {(list) => (
            <Card>
              {list.length === 0 ? (
                <p className="empty">No e-mail template.</p>
              ) : (
                <div className="table-wrap">
                  <table>
                    <caption className="visually-hidden">E-mail templates</caption>
                    <thead>
                      <tr>
                        <th>Template id</th>
                        <th>Name</th>
                        <th>Active</th>
                      </tr>
                    </thead>
                    <tbody>
                      {list.map((t) => (
                        <tr key={t.id}>
                          <td>
                            <code>{t.templateRef}</code>
                          </td>
                          <td>{t.name}</td>
                          <td>{t.active ? 'yes' : 'no'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>
          )}
        </Async>
      )}
      {tab === 'endpoints' && (
        <Async load={endpoints}>
          {(list) => (
            <Card sub="An API endpoint is only called from its own environment: dev from dev, qa from qa, prod from prod. An external one needs a recorded confirmation.">
              {list.length === 0 ? (
                <p className="empty">No API endpoint.</p>
              ) : (
                <div className="table-wrap">
                  <table>
                    <caption className="visually-hidden">API endpoints</caption>
                    <thead>
                      <tr>
                        <th>Name</th>
                        <th>Call</th>
                        <th>Environment</th>
                        <th className="num">Timeout</th>
                      </tr>
                    </thead>
                    <tbody>
                      {list.map((e) => (
                        <tr key={e.id}>
                          <td>{e.name}</td>
                          <td>
                            {e.method} <code>{e.url}</code>
                          </td>
                          <td>
                            <span className="badge badge-info">{e.environment}</span>
                            {e.environment === 'EXTERNAL' && !e.externalConfirmed && <span className="badge badge-warn"> unconfirmed</span>}
                          </td>
                          <td className="num">{e.timeoutMs} ms</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>
          )}
        </Async>
      )}
    </>
  );
}
