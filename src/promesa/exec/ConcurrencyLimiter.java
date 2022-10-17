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
import clojure.lang.PersistentArrayMap;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

public class ConcurrencyLimiter implements Runnable, IObj {
  private final BlockingQueue<Task> queue;
  private final ExecutorService executor;
  private final Semaphore limit;
  private final int maxConcurrencyCapacity;
  private final int maxQueueSize;
  private IPersistentMap metadata = PersistentArrayMap.EMPTY;

  public static ConcurrencyLimiter create(final ExecutorService executor,
                                                           final int maxConcurrencyCapacity,
                                                           final int maxQueueSize) {
    return new ConcurrencyLimiter(executor, maxConcurrencyCapacity, maxQueueSize);
  }

  public static ConcurrencyLimiter create(final ExecutorService executor,
                                                           final int maxConcurrencyCapacity) {
    return new ConcurrencyLimiter(executor, maxConcurrencyCapacity, Integer.MAX_VALUE);
  }

  private ConcurrencyLimiter(final ExecutorService executor,
                             final int maxConcurrencyCapacity,
                             final int maxQueueSize) {
    if (maxConcurrencyCapacity <= 0) {
      throw new IllegalArgumentException("maxConcurrencyCapacity must be at least 0");
    }

    if (maxQueueSize <= 0) {
      throw new IllegalArgumentException("maxQueueSize must be at least 0");
    }

    this.executor = executor;
    this.maxConcurrencyCapacity = maxConcurrencyCapacity;
    this.maxQueueSize = maxQueueSize;
    this.queue = new LinkedBlockingQueue<>(maxQueueSize);
    this.limit = new Semaphore(maxConcurrencyCapacity);
  }

  public IObj withMeta(IPersistentMap meta) {
    this.metadata = meta;
    return this;
  }

  public IPersistentMap meta() {
    return this.metadata;
  }

  public CompletableFuture invoke(final Callable<CompletionStage<Object>> callable) {
    final var response = new CompletableFuture<Object>();
    final var task = new Task(this, callable, response);

    if (!this.queue.offer(task)) {
      final var message = "Queue size has reached capacity: " + maxQueueSize;
      final var result = new CompletableFuture();
      result.completeExceptionally(new CapacityReachedException(message));
      return result;
    }
    this.executor.submit(this);
    return response;
  }

  protected void release() {
    this.limit.release();
  }

  protected void releaseAndSchedule() {
    this.limit.release();
    this.executor.submit(this);
  }

  public int getCurrentQueueSize() {
    return this.queue.size();
  }

  public int getCurrentConcurrencyCapacity() {
    return this.maxConcurrencyCapacity - this.limit.availablePermits();
  }

  public int getRemainingQueueSize() {
    return this.queue.remainingCapacity();
  }

  public int getRemainingConcurrencyCapacity() {
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

  public void run() {
    Task task;
    while ((task = this.poll()) != null) {
      if (task.isCancelled()) {
        this.limit.release();
      } else {
        this.executor.submit(task);
      }
    }
  }

  private static class Task implements Runnable {
    private final ConcurrencyLimiter limiter;
    private final Callable<CompletionStage<Object>> callable;
    private final CompletableFuture<Object> response;

    public Task(final ConcurrencyLimiter limiter,
                final Callable<CompletionStage<Object>> callable,
                final CompletableFuture<Object> response) {
      this.limiter = limiter;
      this.callable = callable;
      this.response = response;
    }

    public boolean isCancelled() {
      return this.response.isCancelled();
    }

    public void run() {
      final CompletionStage<Object> future;
      try {
        future = callable.call();
        if (future == null) {
          this.limiter.release();
          this.response.completeExceptionally(new NullPointerException());
          return;
        }
      } catch (Throwable e) {
        this.limiter.release();
        this.response.completeExceptionally(e);
        return;
      }

      future.whenComplete((result, t) -> {
          if (t != null) {
            this.limiter.releaseAndSchedule();
            this.response.completeExceptionally(t);
          } else {
            this.limiter.releaseAndSchedule();
            this.response.complete(result);
          }
        });
    }
  }

  public static class CapacityReachedException extends RuntimeException {
    public CapacityReachedException(String msg) {
      super(msg);
    }
  }
}
