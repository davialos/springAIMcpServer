import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const ui = new URL('../../main/resources/META-INF/resources/celfaker/ui/', import.meta.url);

// Every UI module must at least parse; DOM-free modules must also import. (A syntax error would otherwise blank the dashboard.)
for (const file of readdirSync(ui).filter((f) => f.endsWith('.js'))) {
  test('parses ' + file, async () => {
    const { execFileSync } = await import('node:child_process');
    const src = (await import('node:fs')).readFileSync(new URL(file, ui), 'utf8');
    execFileSync(process.execPath, ['--check', '--input-type=module'], { input: src });
    assert.ok(src.length > 0);
  });
}
