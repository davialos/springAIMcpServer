"""API Lab: discover a project's HTTP APIs, link request attributes to the response attributes that feed them
("threads"), generate realistic fake payloads, build and run API workflows, export them as k6 load tests.

Everything is plain JSON in folders under ``apilab_dir`` (default ~/localdev/apilab/<project>/):

  project.json            name, repo, service, base_url, discovery source
  apis/<api>.json         one endpoint: method, path, request fields, response fields
  links.json              threads: response field of API A -> request field of API B (+ status, confidence, reason)
  rules/<api>.json        fake-data rules per request field (set by AI, the UI or by hand)
  data/<api>.json         generated payloads (what a test will send)
  workflows/<wf>.json     ordered steps with bindings between steps
  runs/<run>.json         executed workflows: every request/response
  pool.json               values captured from real responses (ids ...), reused by later data
"""
