async function post(path, body) {
  const res = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch (e) { json = { error: text }; }
  if (!res.ok) throw new Error(json.error ?? 'HTTP ' + res.status);
  return json;
}

export const api = {
  example: async () => (await fetch('/api/example')).json(),
  localServices: async () => { try { return (await (await fetch('/api/local-services')).json()).services ?? []; } catch (e) { return []; } },
  importCurl: (curl) => post('/api/import/curl', { curl }),
  importOpenApi: (url, spec) => post('/api/import/openapi', { url, spec }),
  fake: (apiSpec, seed, count) => post('/api/fake', { api: apiSpec, seed, count }),
  send: (req) => post('/api/send', req),
  analyze: (payload, object, mapPaths) => post('/api/analyze', { payload, object, mapPaths }),
  attributeMap: (candidates, seed) => post('/api/attribute-map', { candidates, seed }),
  expressions: (candidates, valueMap, seed, options) => post('/api/expressions', { candidates, valueMap, seed, options }),
  cases: (candidates, valueMap, seed, expression, max) => post('/api/cases', { candidates, valueMap, seed, expression, max }),
  propose: (contract) => post('/api/workflow/propose', { contract }),
  validate: (contract, workflow) => post('/api/workflow/validate', { contract, workflow }),
  generate: (body) => post('/api/generate', body),
  async zip(body) {
    const res = await fetch('/api/generate.zip', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!res.ok) throw new Error((await res.json()).error ?? 'HTTP ' + res.status);
    return res.blob();
  },
};
