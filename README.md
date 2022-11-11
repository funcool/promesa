# promesa #

[![Clojars Project](http://clojars.org/funcool/promesa/latest-version.svg)](http://clojars.org/funcool/promesa)

A promise library & concurrency toolkit for Clojure and ClojureScript.

This library exposes a bunch of usefull syntactic abstractions that
will considerably simplify to work with promises (in a very similar
way as you will do it in JS with async/await).

Here you have the [User Guide][0] and the [API documentation][1].


## Contributing

If you miss something, feel free to open an issue for a discussion. If
there is a clear use case for the proposed enhacement, the PR will be
more thank welcome.

## Testing

Run the Clojure (.clj) tests:

``` shell
clojure -X:dev:test
```

Run the ClojureScript (.cljs) tests:

``` shell
npm install
npm test
```

[0]: https://funcool.github.io/promesa/latest/user-guide.html
[1]: https://funcool.github.io/promesa/latest/
