package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

/**
 * Generates the JavaScript prelude that pins every non-deterministic source to the
 * evaluation seed, so the same (script version, input, seed) triple always yields
 * identical results.
 *
 * <p>It replaces:</p>
 * <ul>
 *   <li>{@code Math.random()} with a deterministic mulberry32 PRNG seeded per evaluation;</li>
 *   <li>{@code Date} and {@code Date.now()} with a fixed epoch derived from the seed;</li>
 *   <li>{@code performance.now()} (if present) with the same fixed clock.</li>
 * </ul>
 *
 * The prelude is frozen so script code cannot restore the real implementations.
 */
public final class DeterminismPrelude {

    private DeterminismPrelude() {
    }

    public static String build(long seed, long fixedEpochMillis) {
        return """
                (function(__seed, __now) {
                  'use strict';
                  var _state = __seed >>> 0;
                  function _rand() {
                    _state |= 0; _state = (_state + 0x6D2B79F5) | 0;
                    var t = Math.imul(_state ^ (_state >>> 15), 1 | _state);
                    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
                    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
                  }
                  Object.defineProperty(Math, 'random', {
                    value: function() { return _rand(); },
                    writable: false, configurable: false
                  });
                  function _FixedDate(y, m, d, h, mi, s, ms) {
                    if (arguments.length === 0) return new _RealDate(__now);
                    return new (Function.prototype.bind.apply(_RealDate,
                      [null].concat(Array.prototype.slice.call(arguments))))();
                  }
                  var _RealDate = Date;
                  _FixedDate.now = function() { return __now; };
                  _FixedDate.UTC = _RealDate.UTC;
                  _FixedDate.parse = _RealDate.parse;
                  _FixedDate.prototype = _RealDate.prototype;
                  Object.defineProperty(globalThis, 'Date', {
                    value: _FixedDate, writable: false, configurable: false
                  });
                  if (typeof performance !== 'undefined' && performance) {
                    try {
                      Object.defineProperty(performance, 'now', {
                        value: function() { return __now; },
                        writable: false, configurable: false
                      });
                    } catch (e) { /* performance may not exist in some engines */ }
                  }
                  Object.freeze(Math);
                })(%d, %d);
                """.formatted(seed >>> 0, fixedEpochMillis);
    }
}
