/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) Andrey Antukh <niwi@niwi.nz>
*/

/*
 * This class is based on ideas taken from the ConcurrencyReducer
 * class (in the spotify/completable-futures repository) but right now
 * is practically complete rewrite for make the algorithm usage more
 * clojure friendly. If you want to use this from pure java, consider
 * using the Spotify library instead.
*/

package promesa.exec;

import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import java.time.Instant;

public class ConcurrencyLimiter extends AFn implements IObj {
  final private Keyword KW_QUEUE = Keyword.intern("queue");
  final private Keyword KW_STATISTICS = Keyword.intern("statistics");
  final private Keyword KW_CURRENT_QUEUE_SIZE = Keyword.intern("current-queue-size");
  final private Keyword KW_CURRENT_CONCURRENCY = Keyword.intern("current-concurrency");
  final private Keyword KW_REMAINING_QUEUE_SIZE = Keyword.intern("remaining-queue-size");
  final private Keyword KW_REMAINING_CONCURRENCY = Keyword.intern("remaining-concurrency");

  private final BlockingQueue<Task> queue;
  private final ExecutorService executor;
  private final Semaphore limit;
  private final int maxConcurrency;
  private final int maxQueueSize;
  private IPersistentMap metadata = PersistentArrayMap.EMPTY;

  protected IFn onRunCallback;
  protected IFn onPollCallback;

  public ConcurrencyLimiter(final ExecutorService executor,
                            final int maxConcurrency,
                            final int maxQueueSize) {
    this.executor = executor;
    this.maxConcurrency = maxConcurrency;
    this.maxQueueSize = maxQueueSize;
    this.queue = new LinkedBlockingQueue<Task>(maxQueueSize);
    this.limit = new Semaphore(maxConcurrency);
  }

  public void setOnRunCallback(IFn f) {
    this.onRunCallback = f;
  }

  public void setOnPollCallback(IFn f) {
    this.onPollCallback = f;
  }

  public IObj withMeta(IPersistentMap meta) {
    this.metadata = meta;
    return this;
  }

  public IPersistentMap meta() {
    return this.metadata;
  }

  public CompletableFuture invoke(Object arg1) {
    final var callable = (Callable) arg1;
    final var result = new CompletableFuture();
    final var task = new Task(this, callable, result);

    if (!this.queue.offer(task)) {
      final var message = "Queue size has reached capacity: " + maxQueueSize;
      result.completeExceptionally(new CapacityLimitReachedException(message));
      return result;
    }

    this.executor.submit((Runnable)this);
    return result;
  }

  protected void release() {
    this.limit.release();
  }

  protected void releaseAndSchedule() {
    this.limit.release();
    this.executor.submit((Runnable)this);
  }

  public ExecutorService getExecutor() {
    return this.executor;
  }

  public int getCurrentQueueSize() {
    return this.queue.size();
  }

  public int getCurrentConcurrency() {
    return this.maxConcurrency - this.limit.availablePermits();
  }

  public int getRemainingQueueSize() {
    return this.queue.remainingCapacity();
  }

  public int getRemainingConcurrency() {
    return this.limit.availablePermits();
  }

  private Task poll() {
    if (!this.limit.tryAcquire()) {
      return null;
    }

    final Task task = this.queue.poll();
    if (task != null) {
      return task;
    }

    this.limit.release();
    return null;
  }

  @Override
  public void run() {
    Task task;
    while ((task = this.poll()) != null) {
      if (task.isCancelled()) {
        this.limit.release();
      } else {
        this.executor.submit(task);
      }
    }

    if (this.onRunCallback != null) {
      var stats = PersistentArrayMap.EMPTY
        .assoc(KW_CURRENT_CONCURRENCY, this.getCurrentConcurrency())
        .assoc(KW_CURRENT_QUEUE_SIZE, this.getCurrentQueueSize())
        .assoc(KW_REMAINING_CONCURRENCY, this.getRemainingConcurrency())
        .assoc(KW_REMAINING_QUEUE_SIZE, this.getRemainingQueueSize());
      this.onRunCallback.invoke(stats);
    }
  }

  private static class Task implements Runnable {
    private final ConcurrencyLimiter limiter;
    private final Callable callable;
    private final CompletableFuture result;
    private final Instant createdAt;

    public Task(final ConcurrencyLimiter limiter,
                final Callable callable,
                final CompletableFuture result) {
      this.createdAt = Instant.now();
      this.limiter = limiter;
      this.callable = callable;
      this.result = result;
    }

    public boolean isCancelled() {
      return this.result.isCancelled();
    }

    @SuppressWarnings("unchecked")
    public void run() {
      if (this.limiter.onPollCallback != null) {
        this.limiter.onPollCallback.invoke(this.createdAt);
      }

      final CompletionStage future;
      try {
        future = (CompletionStage)callable.call();
        if (future == null) {
          this.limiter.release();
          this.result.completeExceptionally(new NullPointerException());
          return;
        }
      } catch (Throwable e) {
        this.limiter.release();
        this.result.completeExceptionally(e);
        return;
      }

      future.whenComplete((result, t) -> {
          if (t != null) {
            this.limiter.releaseAndSchedule();
            this.result.completeExceptionally((Throwable)t);
          } else {
            this.limiter.releaseAndSchedule();
            this.result.complete(result);
          }
        });
    }
  }

  public static class CapacityLimitReachedException extends RuntimeException{
    public CapacityLimitReachedException(String msg) {
      super(msg);
    }
  }
}
