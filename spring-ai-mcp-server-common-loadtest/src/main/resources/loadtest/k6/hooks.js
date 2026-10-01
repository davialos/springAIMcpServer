// Your customisations. Created once by the generator and never overwritten — edit freely.
//
// beforeRequest(apiId, req, ctx): change the request built for an API. req = { path, query, headers, body }.
//   Return the request (or nothing to keep the modified one).
// afterResponse(apiId, res, req, ctx): inspect the response, e.g. remember created ids for later calls.
//
// Example — reuse ids created during the test:
//   const created = [];
//   export function afterResponse(apiId, res) {
//     if (apiId === 'createOrder' && res.status === 201) created.push(res.json('id'));
//   }
//   export function beforeRequest(apiId, req) {
//     if (apiId === 'getOrder' && created.length) req.path.id = created[Math.floor(Math.random() * created.length)];
//     return req;
//   }

export function beforeRequest(apiId, req, ctx) {
  return req;
}

export function afterResponse(apiId, res, req, ctx) {
}
