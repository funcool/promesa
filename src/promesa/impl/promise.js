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

goog.scope(function() {
  const self = promesa.impl.promise;
  const root = goog.global;

  const PENDING = Symbol("state/pending");
  const FULFILLED = Symbol("state/fulfilled");
  const REJECTED = Symbol("state/rejected");

  const QUEUE = Symbol("queue");
  const STATE = Symbol("state");
  const VALUE = Symbol("value");

  // const HANDLERS = Symbol("handlers");
  const RESOLVE_TYPE_FLATTEN = Symbol("resolve-type/flatten");
  const RESOLVE_TYPE_BIND = Symbol("resolve-type/bind");
  const RESOLVE_TYPE_MAP = Symbol("resolve-type/map");

  const defaultFulfillHandler = (v) => v;
  const defaultRejectHandler = (c) => {throw c;};

  class PromiseImpl {
    constructor (val) {
      this[QUEUE] = [];
      this[STATE] = PENDING;
      this[VALUE] = undefined;
      // this[HANDLERS] = {
      //   type: null,
      //   fulfill: defaultFulfillHandler,
      //   reject: defaultRejectHandler
      // }

      if ((val ?? null) !== null) {
        transition(this, FULFILLED, val);
      }
    }

    get state () {
      return this[STATE];
    }

    get value () {
      return this[VALUE];
    }

    then (fulfill, reject) {
      const deferred = new PromiseImpl();

      this[QUEUE].push({
        deferred: deferred,
        type: RESOLVE_TYPE_FLATTEN,
        fulfill: fulfill ?? defaultFulfillHandler,
        reject: reject ?? defaultRejectHandler,
        complete: completeDeferredFn(deferred)
      });

      // console.log("then",
      //             "uid:", goog.getUid(this),
      //             "return-uid:", goog.getUid(deferred),
      //             "state:", this[STATE],
      //             "value:", fmtValue(this[VALUE]));

      process(this);
      return deferred;
    }

    catch (reject) {
      return this.then(null, reject);
    }

    fmap (fulfill, reject) {
      const deferred = new PromiseImpl();

      this[QUEUE].push({
        deferred: deferred,
        type: RESOLVE_TYPE_MAP,
        fulfill: fulfill ?? defaultFulfillHandler,
        reject: reject ?? defaultRejectHandler,
        complete: completeDeferredFn(deferred)
      });

      // console.log("fmap",
      //             "uid:", goog.getUid(this),
      //             "return-uid:", goog.getUid(deferred),
      //             "state:", this[STATE],
      //             "value:", fmtValue(this[VALUE]));

      process(this);
      return deferred;
    }

    fbind (fulfill, reject) {
      const deferred = new PromiseImpl();

      this[QUEUE].push({
        deferred: deferred,
        type: RESOLVE_TYPE_BIND,
        fulfill: fulfill ?? defaultFulfillHandler,
        reject: reject ?? defaultRejectHandler,
        complete: (v, c) => {
          if (c) {
            deferred.reject(c);
          } else {
            deferred.resolve(v);
          }
        }
      });

      // console.log("fbind",
      //             "uid:", goog.getUid(this),
      //             "return-uid:", goog.getUid(deferred),
      //             "state:", this[STATE],
      //             "value:", fmtValue(this[VALUE]));

      process(this);
      return deferred;
    }

    handle (fn, resolveType) {
      resolveType = resolveType ?? RESOLVE_TYPE_MAP;

      this[QUEUE].push({
        type: resolveType,
        fulfill: defaultFulfillHandler,
        reject: defaultRejectHandler,
        complete: fn
      });

      process(this);
    }

    // Deferred Methods

    resolve(value) {
      if (this[STATE] === PENDING) {
        // console.log(":: [deferred:resolve]",
        //             "uid:", goog.getUid(this),
        //             "value:", fmtValue(value));

        transition(this, FULFILLED, value);
      }
      return null;
    }

    reject(cause) {
      if (this[STATE] === PENDING) {
        // console.log(":: [deferred:reject]",
        //             "uid:", goog.getUid(this),
        //             "value:", fmtValue(cause));
        transition(this, REJECTED, cause);
      }
      return null;
    }

  }

  const nextTick = (() => {
    if (typeof root.queueMicrotask === "function") {
      return function queueMicrotask (f, p) {
        // console.log("!! [queueMicrotask]", goog.getUid(p))
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
      // console.error("No nextTick. How we gonna do this?");
      return (f, p) => f.call(this, p);
    }
  })();

  // const TRUE = new PromiseImpl(true);
  // const FALSE = new PromiseImpl(false);
  // const NULL = new PromiseImpl(null);
  // const ZERO = new PromiseImpl(0);
  // const EMPTYSTRING = new PromiseImpl("");

  function fmtValue (o) {
    if (isThenable(o)) {
      return `<PROMISE:${goog.getUid(o)}>`;
    } else if (o instanceof Error) {
      return `<EXCEPTION:'${o.message}'>`;
    } else if (o === null || o === undefined) {
      return `${o}`;
    } else if (typeof o === "function") {
      return `<FN:${goog.getUid(o)}>`;
    } else {
      return `${o.toString()}`;
    }
  }

  function isFunction (o) {
    return typeof o === "function";
  }

  function isThenable (o) {
    if (goog.isObject(o)) {
      const thenFn = o.then;
      return isFunction(thenFn);
    } else {
      return false;
    }
  }

  function constantly(v) {
    return () => v;
  }

  function identity (v) {
    return v;
  }

  function isPromiseImpl (v) {
    return v instanceof PromiseImpl;
  }

  function completeDeferredFn(deferred) {
    return (value, cause) => {
      if (cause) {
        deferred.reject(cause);
      } else {
        deferred.resolve(value);
      }
    };
  }

  function process (p) {
    if (p[STATE] === PENDING) return;
    processNextTick(p);
    // nextTick(processNextTick, p);
    return p;
  }

  function processNextTick(p) {
    if (p[QUEUE].length === 0) return;

    let handlers, task;
    let value, cause;

    // console.log(":: process:",
    //             "uid:", goog.getUid(p),
    //             "queue size:", p[QUEUE].length,
    //             "state:", p[STATE],
    //             "value:", fmtValue(p[VALUE]));

    while (p[QUEUE].length) {
      task = p[QUEUE].shift();

      // console.log(":: process-task:",
      //             "deferred-uid:", task.deferred ? goog.getUid(task.deferred) : null,
      //             "type:", task.type);

      try {
        if (p[STATE] === FULFILLED) {
          value = task.fulfill(p[VALUE])
        } else if (p[STATE] === REJECTED) {
          value = task.reject(p[VALUE])
        } else {
          cause = new TypeError("invalid state");
        }
      } catch (e) {
        cause = e;
      }

      resolveTask(task, value, cause);
    }
  }

  function resolveTask(task, value, cause) {
    if (cause) {
      task.complete(null, cause);
    } else {
      if (task.type === RESOLVE_TYPE_MAP) {
        // console.trace(task);
        task.complete(value, null);
      } else if (task.type === RESOLVE_TYPE_FLATTEN) {
        if (isPromiseImpl(value)) {
          value.handle((v, c) => {
            resolveTask(task, v, c);
          });
        } else if (isThenable(value)) {
          value.then((v) => {
            resolveTask(task, v, null);
          }, (c) => {
            resolveTask(task, null, c);
          });
        } else {
          task.complete(value, null);
        }
      } else if (task.type === RESOLVE_TYPE_BIND) {
        if (isPromiseImpl(value)) {
          value.handle((v, c) => {
            task.complete(v, c);
          });
        } else if (isThenable(value)) {
          value.then((v) => {
            task.complete(v, null);
          }, (c) => {
            task.complete(null, c);
          });
        } else {
          task.complete(null, new TypeError("expected thenable"));
        }
      }
    }
  }

  function transition (p, state, value) {
    // console.log(">> transition",
    //             "uid:", goog.getUid(p),
    //             "from-state:", p[STATE],
    //             "to-state:", state,
    //             "value:", fmtValue(value),
    //             "queue:", p[QUEUE].length);
    if (p[STATE] === state ||
        p[STATE] !== PENDING) {
      return;
    }

    p[STATE] = state;
    p[VALUE] = value;
    return process(p);
  }

  self.PromiseImpl = PromiseImpl;

  self.deferred = () => {
    return new PromiseImpl();
  };

  self.resolved = function resolved (value, flatten) {
    if (isThenable(value) && flatten) return value;

    // switch (value) {
    //   case null:
    //     return NULL;
    //   case true:
    //     return TRUE;
    //   case false:
    //     return FALSE;
    //   case 0:
    //     return ZERO;
    //   case "":
    //     return EMPTYSTRING;
    // }

    const p = new PromiseImpl();
    p[STATE] = FULFILLED;
    p[VALUE] = value;

    // console.log("++ [resolved]", "uid:", goog.getUid(p), "value:", value);

    return p;
  };

  self.rejected = function rejected (reason) {
    const p = new PromiseImpl();
    p[STATE] = REJECTED;
    p[VALUE] = reason;

    // console.log("++ [rejected]", "uid:", goog.getUid(p), "value:", fmtValue(reason));

    return p;
  };

  self.all = function all (promises) {
    return promises.reduce(function (acc, p) {
      return acc.then((results) => {
        return self.coerce(p).fmap((v) => {
          results.push(v);
          return results;
        });
      });
    }, self.resolved([]));
  };

  self.coerce = function coerce (promise) {
    if (promise instanceof PromiseImpl) {
      return promise;
    } else {
      const deferred = self.deferred();
      promise.then((v) => {
        deferred.resolve(v);
      }, (c) => {
        deferred.reject(v);
      });
      return deferred;
    }
  };

  self.race = function race (promises) {
    const deferred = self.deferred();

    promises.forEach((p) => {
      self.coerce(p).handle((v, c) => {
        if (c) {
          deferred.reject(c);
        } else {
          deferred.resolve(v);
        }
      });
    });

    return deferred;
  };

  self.PENDING = PENDING;
  self.FULFILLED = FULFILLED;
  self.REJECTED = REJECTED;
});
