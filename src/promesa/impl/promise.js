/**
 * promise
 *
 * Is a modified and google closure adapted promise implementation
 * from https://github.com/bucharest-gold/fidelity.
 *
 * It provides a simple inspectable and compliant Promise
 * implementation.
 *
 * @author Andrey Antukh <niwi@niwi.nz>, 2023
 * @author Lance Ball, 2015 (original author)
 * @license MIT License <https://opensource.org/licenses/MIT>
 */

"use strict";

goog.provide("promesa.impl.promise");
goog.provide("promesa.impl.promise.PromiseImpl");
goog.provide("promesa.impl.promise.DeferredImpl");

goog.scope(function() {
  const self = promesa.impl.promise;
  const root = goog.global;

  const PENDING = Symbol("pending");
  const FULFILLED = Symbol("fulfilled");
  const REJECTED = Symbol("rejected");
  const HANDLERS = Symbol("handlers");
  const QUEUE = Symbol("queue");
  const STATE = Symbol("state");
  const VALUE = Symbol("value");

  class PromiseImpl {
    constructor (fn) {
      this[QUEUE] = [];
      this[STATE] = PENDING;
      this[VALUE] = undefined;
      this[HANDLERS] = {fulfill: null, reject:null};

      const fnType = typeof fn;
      const self = this;

      if (fnType === "function") {
        try {
          fn(value => resolvePromise(self, value),
             cause => transition(self, REJECTED, cause));
        } catch (cause) {
          transition(promise, REJECTED, cause);
        }
      } else if (fnType !== "undefined") {
        resolvePromise(this, fn);
      }
    }

    get state () {
      return this[STATE];
    }

    get value () {
      return this[VALUE];
    }

    then (onFulfilled, onRejected) {
      const next = new PromiseImpl();
      if (typeof onFulfilled === "function") {
        next[HANDLERS].fulfill = onFulfilled;
      }
      if (typeof onRejected === "function") {
        next[HANDLERS].reject = onRejected;
      }
      this[QUEUE].push(next);
      process(this);
      return next;
    }

    catch (onRejected) {
      return this.then(null, onRejected);
    }

    static resolve (value) {
      if (value && value.then) return value;

      switch (value) {
        case null:
          return NULL;
        case true:
          return TRUE;
        case false:
          return FALSE;
        case 0:
          return ZERO;
        case "":
          return EMPTYSTRING;
      }

      const p = new PromiseImpl();
      p[STATE] = FULFILLED;
      p[VALUE] = value;
      return p;
    }

    static reject (reason) {
      const p = new PromiseImpl();
      p[STATE] = REJECTED;
      p[VALUE] = reason;
      return p;
    }

    static all (/* promises - an iterable */) {
      const results = [];
      const promises = Array.from(arguments).reduce((a, b) => a.concat(b), []);
      const merged = promises.reduce(
        (acc, p) => acc.then(() => p).then(r => results.push(r)),
        Promise.resolve(null));
      return merged.then(_ => results);
    }

    static race (/* promises - an iterable */) {
      const promises = Array.from(arguments).reduce((a, b) => a.concat(b), []);
      return new PromiseImpl((resolve, reject) => {
        promises.forEach(p => p.then(resolve).catch(reject));
      });
    }
  }

  class DeferredImpl extends PromiseImpl {
    constructor() {
      super();
    }

    resolve(v) {
      if (this[STATE] === PENDING) {
        resolvePromise(this, v);
      }
      return null;
    }

    reject(cause) {
      if (this[STATE] === PENDING) {
        transition(this, REJECTED, cause);
      }
      return null;
    }
  }

  const nextTick = (() => {
    if (typeof root.queueMicrotask === "function") {
      return function queueMicrotask (f, p) {
        root.queueMicrotask(() => f(p));
      };
    } else if (root.process && typeof root.process.nextTick === "function") {
      return root.process.nextTick;
    } else if (typeof root.setImmediate === "function") {
      return root.setImmediate;
    } else if (typeof root.Promise === "function") {
      return function queueMicrotaskWithPromise(f, p) {
        root.Promise.resolve(null).then(() => f(p));
      };
    } else if (typeof root.setTimeout === "function") {
      return (f, p) => root.setTimeout(f, 0, p);
    } else {
      console.error("No nextTick. How we gonna do this?");
      return (f, p) => f.call(this, p);
    }
  })();

  const TRUE = new PromiseImpl(true);
  const FALSE = new PromiseImpl(false);
  const NULL = new PromiseImpl(null);
  const ZERO = new PromiseImpl(0);
  const EMPTYSTRING = new PromiseImpl("");

  function resolvePromise(p, x) {
    if (x === p) {
      transition(p, REJECTED, new TypeError("The promise and its value are the same."));
      return;
    }

    const typeOfX = typeof x;
    if (x && ((typeOfX === "function") || (typeOfX === "object"))) {
      let called = false;
      try {
        const thenFunction = x.then;
        if (thenFunction && (typeof thenFunction === "function")) {
          thenFunction.call(x, (y) => {
            if (!called) {
              resolvePromise(p, y);
              called = true;
            }
          }, (r) => {
            if (!called) {
              transition(p, REJECTED, r);
              called = true;
            }
          });
        } else {
          transition(p, FULFILLED, x);
          called = true;
        }
      } catch (e) {
        if (!called) {
          transition(p, REJECTED, e);
          called = true;
        }
      }
    } else {
      transition(p, FULFILLED, x);
    }
  }

  function process (p) {
    if (p[STATE] === PENDING) return;
    nextTick(processNextTick, p);
    return p;
  }

  function processNextTick(p) {
    let handler, qp;
    while (p[QUEUE].length) {
      qp = p[QUEUE].shift();
      if (p[STATE] === FULFILLED) {
        handler = qp[HANDLERS].fulfill || ((v) => v);
      } else if (p[STATE] === REJECTED) {
        handler = qp[HANDLERS].reject || ((r) => {
          throw r;
        });
      }
      try {
        resolvePromise(qp, handler(p[VALUE]));
      } catch (e) {
        transition(qp, REJECTED, e);
        continue;
      }
    }
  }

  function transition (p, state, value) {
    if (p[STATE] === state ||
        p[STATE] !== PENDING) {
      return;
    }
    p[STATE] = state;
    p[VALUE] = value;
    return process(p);
  }

  self.PromiseImpl = PromiseImpl;
  self.DeferredImpl = DeferredImpl;

  self.deferred = () => {
    return new DeferredImpl();
  };

  self.PENDING = PENDING;
  self.FULFILLED = FULFILLED;
  self.REJECTED = REJECTED;
});
