import { test } from 'node:test';
import assert from 'node:assert/strict';
import { highlight, languageLabel, supports } from '../../main/resources/META-INF/resources/dynamic-ai/ui/chat/highlight.js';

test('CEL: macros, uint, bytes, keywords, comments, and no keyword inside identifiers', () => {
  const html = highlight('exists_one(items, i, i.sku in ["A"]) // one\nb"\\x01" + 2u', 'cel');
  assert.ok(html.includes('<span class="tok-builtin">exists_one</span>'));
  assert.ok(html.includes('<span class="tok-keyword">in</span>'));
  assert.ok(html.includes('<span class="tok-comment">// one</span>'));
  assert.ok(html.includes('<span class="tok-string">b&quot;\\x01&quot;</span>'));
  assert.ok(html.includes('<span class="tok-number">2u</span>'));
  assert.equal(highlight('index', 'cel'), 'index');
});

test('output is always escaped, also for unknown languages', () => {
  assert.equal(highlight('<b>&', 'nope'), '&lt;b&gt;&amp;');
  assert.ok(!highlight('"<script>"', 'javascript').includes('<script>'));
});

test('common languages', () => {
  assert.ok(highlight('SELECT id FROM orders WHERE total > 10', 'sql').includes('<span class="tok-keyword">SELECT</span>'));
  assert.ok(highlight('@Service\npublic class A {}', 'java').includes('<span class="tok-annotation">@Service</span>'));
  assert.ok(highlight('name: x\nport: 80', 'yaml').includes('<span class="tok-property">name</span>'));
  assert.ok(highlight('def f(): return None', 'python').includes('<span class="tok-literal">None</span>'));
  assert.ok(highlight('echo $HOME', 'bash').includes('<span class="tok-variable">$HOME</span>'));
  assert.equal(languageLabel('cel'), 'CEL');
  assert.ok(supports('ts') && !supports('cobol'));
});
