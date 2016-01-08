/**
 * @constructor
 */
var Promise = function() {};

/**
 * @this {Promise}
 * @return {null}
 */
Promise.prototype.config = function() {};
/**
 * @this {Promise}
 * @return {null}
 */
Promise.prototype.cancel = function() {};

/**
 * @this {Promise}
 * @return {Promise}
 */
Promise.prototype.finally = function() {};
/**
 * @this {Promise}
 * @return {Promise}
 */
Promise.prototype.then = function() {};

/**
 * @this {Promise}
 * @return {Promise}
 */
Promise.prototype.catch = function() {};

/**
 * @this {Promise}
 * @return {boolean}
 */
Promise.prototype.isRejected = function() {};

/**
 * @this {Promise}
 * @return {boolean}
 */
Promise.prototype.isFulfilled = function() {};

/**
 * @this {Promise}
 * @return {boolean}
 */
Promise.prototype.isPending = function() {};

/**
 * @this {Promise}
 * @return {object}
 */
Promise.prototype.value = function() {};

/**
 * @this {Promise}
 * @return {Error|object}
 */
Promise.prototype.reason = function() {};

/**
 * @this {Promise}
 * @return {Promise}
 */
Promise.prototype.timeout = function() {};

/**
 * @this {null}
 * @return {Promise}
 */
Promise.all = function() {};

/**
 * @this {null}
 * @return {Promise}
 */
Promise.any = function() {};

/**
 * @this {null}
 * @return {Promise}
 */
Promise.resolve = function() {};

/**
 * @this {null}
 * @return {Promise}
 */
Promise.reject = function() {};

/**
 * @this {null}
 * @return {Promise}
 */
Promise.delay = function() {};
