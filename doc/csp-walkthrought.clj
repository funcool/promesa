;; This walkthrough introduces the core concepts of promesa channels & CSP patterns (based on the
;; core.async walkthrough).

;; Require the public API
(require '[promesa.core :as p])
(require '[promesa.exec.csp :as sp])

;;;; CHANNELS

;; Data is transmitted on queue-like channels. By default channels are unbuffered (0-length) - they
;; require producer and consumer to rendezvous for the transfer of a value through the channel.

;; Use `chan` to make an unbuffered channel:
(sp/chan)

;; Pass a number to create a channel with a fixed buffer:
(sp/chan 10)

;; You also can use a specialized buffer:
(sp/chan (sp/sliding-buffer 10))

;; Or using a transducer
(sp/chan 10 (map inc))

;; `close!` a channel to stop accepting puts. Remaining values are still available to take and
;; pending puts are cancelled. Drained channels return nil on take. Nils may not be sent over a
;; channel explicitly.

(let [c (sp/chan)]
  (sp/close! c)
  (assert (true? (sp/closed? c))))

;; The put and take operations; we use `>!` (blocking put) and `<!` (blocking take) to communicate
;; via channels

(let [c (sp/chan 1)]
  (sp/>! c "hello")
  (assert (= "hello" (sp/<! c)))
  (sp/close! c))

;; Because these are blocking calls, if we try to put on an unbuffered channel, we will block the
;; main thread. We can use `p/thread` (like `future`) to execute a body in a separated thread. Here
;; we launch a background task to put "hello" on a channel, then read that value in the current
;; thread.

(let [c (sp/chan)]
  (p/thread (sp/>! c "hello"))
  (assert (= "hello" (sp/<! c)))
  (sp/close! c))

;; There are non-blocking promise returning API for put & take operations, we use the clojure
;; standard way do get a future value: with `@` or `deref`.

(let [c (sp/chan)]
  ;; this does not blocks, just returns a promise (completable future)
  ;; what we ignore in this concrete example
  (sp/put! c "hello")
  (assert (= "hello" @(sp/take! c)))
  (sp/close! c))

;;;; GO BLOCKS

;; The `go` macro asynchronously executes its body in a virtual thread. Channel operations that
;; would block will pause execution instead, blocking no real system threads. There are no
;; difference with normal threads, you continue using the standard blocking API.

;; Here we convert our prior channel example to use go blocks:
(let [c (sp/chan)]
  (sp/go (sp/>! c "hello"))
  (assert (= "hello" (sp/<! c)))
  (sp/close! c))

;; The go block/macro returns a promise which will be eventually resolved with last-expr/return
;; value inside the block:

@(sp/go
   ;; blocks the virtual thread for 1s
   (sp/<! (sp/timeout-chan 1000))

   ;; return value
   1)
;; => 1


;;;; ALTS

;; One killer feature for channels over queues is the ability to wait on many channels at the same
;; time (like a socket select). This is done with `alts!` blocking call or `alts` (returns a
;; promise)

;; Lets combine inputs on either of two channels; `alts!` takes a set of operations to perform -
;; either a channel to take from or a [channel value] to put and returns the value (nil for put) and
;; channel that succeeded:

(let [c1 (sp/chan)
      c2 (sp/chan)]
  (sp/go-loop []
    (let [[v ch] (sp/alts! [c1 c2])]
      (when v
        (println "Read" v "from" ch)
        (recur))))

  (sp/>! c1 "hi")
  (sp/>! c2 "there")

  (sp/close! c1)
  (sp/close! c2))

;; Prints (on stdout, possibly not visible at your repl):
;;   Read hi from #<promesa.exec.csp.channel.Channel ...>
;;   Read there from #<promesa.exec.csp.channel.Channel ...>

;; Since go blocks are lightweight processes not bound to threads, we
;; can have LOTS of them! Here we create 1000 go blocks that say hi on
;; 1000 channels. We use alts! to read them as they're ready.

(let [n     1000
      cs    (repeatedly n sp/chan)
      begin (System/currentTimeMillis)]

  ;; Add pending put operation to all channels
  (doseq [c cs] (sp/put! c "hi"))

  (dotimes [i n]
    (let [[v c] (sp/alts! cs)]
      (assert (= "hi" v))))

  ;; Close all channels
  (run! sp/close! cs)

  (println "Read" n "msgs in" (- (System/currentTimeMillis) begin) "ms"))

;; `timeout-chan` creates a channel that waits for a specified ms, then closes:

(let [t     (sp/timeout-chan 100)
      begin (System/currentTimeMillis)]
  (sp/<! t)
  (println "Waited" (- (System/currentTimeMillis) begin)))

;; We can combine timeout with `alts!` to do timed channel waits.  Here we wait for 100 ms for a
;; value to arrive on the channel, then give up:

(let [c     (sp/chan)
      begin (System/currentTimeMillis)]
  (sp/alts! [c (sp/timeout-chan 100)])
  (println "Gave up after" (- (System/currentTimeMillis) begin)))
