import { describe, expect, it } from 'vitest';
import { inline, lines } from './markdown';

describe('markdown subset', () => {
  it('splits code, bold and italic and keeps the text between', () => {
    expect(inline('see `a.b >= 1` and **ADULT** now _really_')).toEqual([
      { kind: 'text', text: 'see ' },
      { kind: 'code', text: 'a.b >= 1' },
      { kind: 'text', text: ' and ' },
      { kind: 'bold', text: 'ADULT' },
      { kind: 'text', text: ' now ' },
      { kind: 'italic', text: 'really' },
    ]);
  });

  it('keeps identifiers with underscores intact and still italicises a phrase that contains one', () => {
    expect(inline('groups: LOAN_ALL_FAILURES, LOAN_ELIGIBILITY')).toEqual([{ kind: 'text', text: 'groups: LOAN_ALL_FAILURES, LOAN_ELIGIBILITY' }]);
    expect(inline('_Set ANTHROPIC_API_KEY to talk._')).toEqual([{ kind: 'italic', text: 'Set ANTHROPIC_API_KEY to talk.' }]);
  });

  it('leaves unclosed markers as plain text', () => {
    expect(inline('a ** b ` c _ d')).toEqual([{ kind: 'text', text: 'a ** b ` c _ d' }]);
  });

  it('never produces markup: html stays text', () => {
    const l = lines('<img src=x onerror=alert(1)> **b**');
    expect(l[0]?.inline[0]).toEqual({ kind: 'text', text: '<img src=x onerror=alert(1)> ' });
  });

  it('recognises bullets, continuation lines and blanks', () => {
    const l = lines('I found 2 rules:\n- **A** — one\n  `x > 1`\n\n_done_');
    expect(l.map((x) => x.kind)).toEqual(['p', 'bullet', 'cont', 'blank', 'p']);
    expect(l[2]?.inline).toEqual([{ kind: 'code', text: 'x > 1' }]);
  });
});
