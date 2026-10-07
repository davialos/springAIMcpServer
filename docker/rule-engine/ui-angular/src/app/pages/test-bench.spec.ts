import { describe, expect, it } from 'vitest';
import type { Attribute } from '../core/types';
import { factValue, formatMillis } from './test-bench';

const attr = (dataType: string): Attribute => ({ code: 'x', name: 'x', dataType, required: false, sampleValue: null, celName: 'o.x', usedByRules: 0 });

describe('test bench values', () => {
  it('converts typed text to the value of the parameter type; blank is not supplied', () => {
    expect(factValue(attr('INT'), ' 42 ')).toBe(42);
    expect(factValue(attr('DOUBLE'), '1.5')).toBe(1.5);
    expect(factValue(attr('INT'), 'abc')).toBe('abc'); // sent as text: the engine reports INVALID_PARAMETER
    expect(factValue(attr('BOOL'), 'true')).toBe(true);
    expect(factValue(attr('LIST_STRING'), '["a","b"]')).toEqual(['a', 'b']);
    expect(factValue(attr('STRING'), '  VERIFIED ')).toBe('VERIFIED');
    expect(factValue(attr('STRING'), '   ')).toBeUndefined();
    expect(factValue(undefined, 'free')).toBe('free');
  });

  it('formats microseconds', () => {
    expect(formatMillis(2_420)).toBe('2.42 ms');
    expect(formatMillis(25_000)).toBe('25.0 ms');
  });
});
