import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderMarkdown, inline, safeUrl } from '../../main/resources/META-INF/resources/dynamic-ai/ui/chat/markdown.js';

test('headings, emphasis, strike, inline code and line breaks', () => {
  const html = renderMarkdown('# Title\nSome **bold**, *it*, _it2_, ~~gone~~ and `a<b>`\nnext line');
  assert.equal(html, '<h1>Title</h1><p>Some <strong>bold</strong>, <em>it</em>, <em>it2</em>, <del>gone</del> and '
    + '<code>a&lt;b&gt;</code><br>next line</p>');
});

test('raw HTML is always escaped', () => {
  const html = renderMarkdown('<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>');
  assert.ok(!html.includes('<script>'));
  assert.ok(!html.includes('<img'));
  assert.ok(html.includes('&lt;script&gt;'));
});

test('links are limited to http(s) and mailto and open safely', () => {
  assert.equal(inline('[docs](https://example.com/a?b=1)'),
    '<a href="https://example.com/a?b=1" title="https://example.com/a?b=1" target="_blank" rel="noopener noreferrer nofollow">docs</a>');
  assert.equal(inline('[x](javascript:alert(1))'), 'x');
  assert.equal(safeUrl('data:text/html,hi'), null);
  assert.ok(inline('see https://example.com.').includes('href="https://example.com"'));
});

test('images are click-to-load placeholders unless the host is allow-listed', () => {
  const blocked = inline('![chart](https://cdn.example.com/c.png)');
  assert.ok(blocked.includes('class="image-placeholder"'));
  assert.ok(!blocked.includes('<img'));
  const allowed = inline('![chart](https://cdn.example.com/c.png)', { imageHosts: ['cdn.example.com'] });
  assert.ok(allowed.startsWith('<img src="https://cdn.example.com/c.png"'));
});

test('nested and ordered lists with task items', () => {
  const html = renderMarkdown('- one\n  - nested\n- [x] done\n- [ ] todo\n\n3. three\n4. four');
  assert.equal(html, '<ul><li>one<ul><li>nested</li></ul></li><li class="task"><input type="checkbox" disabled checked> '
    + 'done</li><li class="task"><input type="checkbox" disabled> todo</li></ul><ol start="3"><li>three</li><li>four</li></ol>');
});

test('tables with alignment, escaped pipes and inline markup', () => {
  const html = renderMarkdown('| Order | Total | Note |\n|:--|--:|:-:|\n| PO-1 | 12.50 | **rush** |\n| PO-2 | 3 | a \\| b |');
  assert.ok(html.startsWith('<div class="table-wrap"><table><thead><tr><th style="text-align:left">Order</th>'));
  assert.ok(html.includes('<td style="text-align:right">12.50</td>'));
  assert.ok(html.includes('<td style="text-align:center"><strong>rush</strong></td>'));
  assert.ok(html.includes('a | b'));
});

test('fenced code is highlighted, labelled and copyable; CEL is supported', () => {
  const html = renderMarkdown('```cel\nhas(order.total) && order.total > 100u\n```');
  assert.ok(html.includes('data-lang="cel"'));
  assert.ok(html.includes('<span class="code-lang">CEL</span>'));
  assert.ok(html.includes('class="copy-code"'));
  assert.ok(html.includes('<span class="tok-builtin">has</span>'));
  assert.ok(html.includes('data-closed="true"'));
});

test('an unclosed fence while streaming renders as an open code block', () => {
  const html = renderMarkdown('Here:\n```json\n{"a": 1');
  assert.ok(html.includes('data-closed="false"'));
  assert.ok(html.includes('<span class="tok-property">&quot;a&quot;</span>'));
});

test('mermaid fences become diagram blocks with escaped source', () => {
  const html = renderMarkdown('```mermaid\ngraph TD; A-->B<script>\n```');
  assert.ok(html.startsWith('<div class="mermaid-block" data-closed="true">'));
  assert.ok(html.includes('A--&gt;B&lt;script&gt;'));
});

test('block quotes, thematic breaks and a half-written table stay readable', () => {
  assert.equal(renderMarkdown('> quoted\n> **text**\n\n---'), '<blockquote><p>quoted<br><strong>text</strong></p></blockquote><hr>');
  assert.equal(renderMarkdown('| a | b |'), '<p>| a | b |</p>');
});
