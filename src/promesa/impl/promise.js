/**
 * promise - a faster implementation of promise abstraction
 *
 * Is a modified and google closure adapted version of
 * https://github.com/bluejava/zousan
 *
 * @author Andrey Antukh <niwi@niwi.nz>, 2016
 * @author: Glenn Crownover <glenn@bluejava.com>
 * @license MIT License <https://opensource.org/licenses/MIT>
 */

goog.provide("promesa.impl.promise");
goog.provide("promesa.impl.promise.Promise");
goog.require("promesa.impl.soon");

goog.scope(function() {
  var soon = promesa.impl.soon;
  var module = promesa.impl.promise;

  var _undefined;
  var STATE_PENDING;
  var STATE_FULFILLED = 1;
  var STATE_REJECTED = 2;

  function resolveClient(c, arg) {
    if(typeof c.y === "function") {
      try {
        var yret = c.y.call(_undefined,arg);
        c.p.resolve(yret);
      } catch(err) { c.p.reject(err) }
    } else {
      c.p.resolve(arg); // pass this along...
    }
  }

  function rejectClient(c, reason) {
    if(typeof c.n === "function") {
      try {
        var yret = c.n.call(_undefined, reason);
        c.p.resolve(yret);
      } catch(err) { c.p.reject(err) }
    } else {
      c.p.reject(reason); // pass this along...
    }
  }

  function Promise(func) {
    if (func) {
      func(this.resolve.bind(this), this.reject.bind(this));
    }
  }

  Promise.prototype.resolve = function(value) {
    if (this.state !== STATE_PENDING) {
      return;
    }

    if (value === this) {
      return this.reject(new TypeError("Attempt to resolve promise with self"));
    }

    var that = this;

    if (value && (typeof value === "function" || typeof value === "object")) {
      try {
        var first = true;
        var then = value.then;
        if (typeof then === "function") {
          then.call(value,
                    function(ra) { if(first) { first=false; that.resolve(ra); } },
                    function(rr) { if(first) { first=false; that.reject(rr); } });
          return;
        }
      }
    }
    catch(e) {
      if(first) this.reject(e);
      return;
    }

    this.state = STATE_FULFILLED;
    this.v = value;

    if(this.c) {
      soon.invoke(function() {
        for(var n=0, l=that.c.length;n<l;n++)
          resolveClient(that.c[n],value);
      });
    }
  };

  Promise.prototype.reject = function() {
    if(this.state !== STATE_PENDING) {
      return;
    }

    this.state = STATE_REJECTED;
    this.v = reason;

    var clients = this.c;
    if(clients) {
      soon.invoke(function() {
        for(var n=0, l=clients.length;n<l;n++) {
          rejectClient(clients[n],reason);
        }
      });
    } else {
      // if(!Zousan.suppressUncaughtRejectionError && global.console) {
      //   global.console.log("You upset Zousan. Please catch rejections: ", reason,reason ? reason.stack : null);
      // }
    }
  };

  then: function(onF,onR) {
    var p = new Zousan();
    var client = {y:onF, n:onR, p:p};

    if(this.state === STATE_PENDING) {
      if(this.c) {
        this.c.push(client);
      } else {
        this.c = [client];
      }
    } else  {
      var s = this.state;
      var a = this.v;

      soon.invoke(function() {
        if(s === STATE_FULFILLED) {
          resolveClient(client, a);
        } else {
          rejectClient(client, a);
        }
      });
    }

    return p;
  };

  Promise.prototype.catch = function(cfn) {
    return this.then(null, cfn);
  };

  Promise.prototype.finally = function(cfn) {
    return this.then(cfn, cfn);
  };

  Promise.prototype.isRejected = function() {
    return this.state === STATE_REJECTED;
  };

  Promise.prototype.isFulfilled = function() {
    return this.state === STATE_FULFILLED;
  };

  Promise.prototype.isPending = function() {
    return this.state === STATE_PENDING;
  };

  Promise.prototype.getValue = function() {
    if (this.state === STATE_FULFILLED) {
      return this.v;
    }
  };

  Promise.prototype.getCause = function() {
    if (this.state === STATE_REJECTED) {
      return this.v;
    }
  };

  Promise.resolve = function(v) {
    var z = new Promise();
    z.resolve(v);
    return z;
  };

  Promise.reject = function(v) {
    var z = new Promise();
    z.reject(v);
    return z;
  };

  Promise.all = function(pa) {
    var results = [];
    var rc = 0;
    var retP = new Promise(); // results and resolved count

    function rp(p, i) {
      if(!p || typeof p.then !== "function") {
        p = module.resolve(p);
      }

      p.then(function(yv) {
        results[i] = yv;
        rc++;
        if(rc == pa.length) retP.resolve(results);
      }, function(nv) {
        retP.reject(nv);
      });
    }

    for(var x=0; x<pa.length; x++) {
      rp(pa[x],x);
    }

    // For zero length arrays, resolve immediately
    if(!pa.length) {
      retP.resolve(results);
    }

    return retP;
  }

  module.Promise = Promise;
  module.promise = function(v) {
    return new Promise(v);
  };
}




