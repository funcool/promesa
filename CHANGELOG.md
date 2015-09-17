# Changelog #

## Version 0.5.0 ##

Date: unreleased

- Update cats to 1.0.0
- Adapt code to cats 1.0.0 breaking changes.
- Add more tests.
- Remove spread operator beacuse it is no longer needed (you can use clojure
  destructuring with `then` combinator.
- Start using the `-name` protocol naming convention.
- Update bluebird to 2.10.0


## Version 0.4.0 ##

Date: 2015-08-18

- Update cats dependency to 0.6.1


## Version 0.3.0 ##

Date: 2015-08-02

- Update bluebird version to 2.9.34
- Update cljs compiler version to 1.7.28
- Start using cljs compiler own compilation facilities
  instead of lein-cljsbuld.
- Now requires the clojurescript >= 1.7.28


## Version 0.2.0 ##

Date: 2015-07-18

- Remove all method related to cancellable promises.
- Implement everything in terms of protocols.
- Update bluebird version to 2.9.33


## Version 0.1.3 ##

Date: 2015-06-13

- Go back to use leiningen.
- Update bluebird version to 2.9.27


## Version 0.1.2 ##

Date: 2015-05-16

- Update bluebird version to 2.9.25
- Start using boot instead of leiningen


## Version 0.1.1 ##

Date: 2015-04-16

- Update bluebird version to 2.9.23


## Version 0.1.0 ##

Date: 2015-03-28

- First relase.
