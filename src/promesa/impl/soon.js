/**
 * soon - a faster alternative to setImmediate
 *
 * Is a modified and google closure adapted soon function from
 * https://github.com/bluejava/zousan
 *
 * @author Andrey Antukh <niwi@niwi.nz>, 2016
 * @author: Glenn Crownover <glenn@bluejava.com>
 * @license MIT License <https://opensource.org/licenses/MIT>
 */

goog.provide("promesa.impl.soon");

goog.scope(function() {
  var module = promesa.impl.soon;

  var queue = [];
  var start = 0;
  var bufferSize = 1024;

  module._callQueue = function() {
    while(queue.length - start) {
      try {
        queue[start]()
      } catch (err) {
        if (global.console) { global.console.error(err); }
      }
      queue[start++] = undefined;
      if(start == bufferSize) {
        queue.splice(0, bufferSize);
        start = 0;
      }
    }
  };

  if(typeof MutationObserver !== "undefined") {
    var dd = document.createElement("div");
    var mo = new MutationObserver(module._callQueue);
    mo.observe(dd, { attributes: true });

    module._runQueue = function() { dd.setAttribute("a", 0); };
  } else if (typeof setImmediate !== "undefined") {
    module._runQueue = function() { setImmediate(module._callQueue); };
  } else {
    module._runQueue = function() { setTimeout(module._callQueue, 0); };
  }

  module.invoke = function invoke(fn) {
    queue.push(fn);
    if ((queue.length - start) === 1) {
      module._runQueue();
    }
  };
});
