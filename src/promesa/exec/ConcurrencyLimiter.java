/*
 * Copyright (c) Andrey Antukh
 * Copyright (c) 2016-2020 Spotify AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

/*
 * This is a port with customizations of ConcurrencyReducer class from
 * the spotify/completable-futures repository.
*/

package promesa.exec;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

public class ConcurrencyLimiter<T> implements Runnable {
  private final BlockingQueue<Job<T>> queue;
  private final ExecutorService executor;
  private final Semaphore limit;
  private final int maxConcurrency;
  private final int maxQueueSize;

  public static <T> ConcurrencyLimiter<T> create(final ExecutorService executor,
                                                 final int maxConcurrency,
                                                 final int maxQueueSize) {
    return new ConcurrencyLimiter<>(executor, maxConcurrency, maxQueueSize);
  }

  public static <T> ConcurrencyLimiter<T> create(final ExecutorService executor,
                                                 final int maxConcurrency) {
    return new ConcurrencyLimiter<>(executor, maxConcurrency, Integer.MAX_VALUE);
  }

  private ConcurrencyLimiter(final ExecutorService executor,
                             final int maxConcurrency,
                             final int maxQueueSize) {
    if (maxConcurrency <= 0) {
      throw new IllegalArgumentException("maxConcurrency must be at least 0");
    }

    if (maxQueueSize <= 0) {
      throw new IllegalArgumentException("maxQueueSize must be at least 0");
    }

    this.executor = executor;
    this.maxConcurrency = maxConcurrency;
    this.maxQueueSize = maxQueueSize;
    this.queue = new LinkedBlockingQueue<>(maxQueueSize);
    this.limit = new Semaphore(maxConcurrency);
  }

  public CompletableFuture<T> add(final Callable<? extends CompletionStage<T>> callable) {
    requireNonNull(callable);
    final var response = new CompletableFuture<T>();
    final var job = new Job<T>(this, callable, response);

    if (!this.queue.offer(job)) {
      final var message = "Queue size has reached capacity: " + maxQueueSize;
      final var result = new CompletableFuture<T>();
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

  public int numQueued() {
    return this.queue.size();
  }

  public int numActive() {
    return this.maxConcurrency - this.limit.availablePermits();
  }

  public int remainingQueueCapacity() {
    return this.queue.remainingCapacity();
  }

  public int remainingActiveCapacity() {
    return this.limit.availablePermits();
  }

  private Job<T> grabJob() {
    if (!this.limit.tryAcquire()) {
      return null;
    }

    final Job<T> job = this.queue.poll();
    if (job != null) {
      return job;
    }

    this.limit.release();
    return null;
  }

  public void run() {
    Job<T> job;
    while ((job = this.grabJob()) != null) {
      if (job.isCancelled()) {
        this.limit.release();
      } else {
        this.executor.submit(job);
      }
    }
  }

  private static class Job<T> implements Runnable {
    private final ConcurrencyLimiter limiter;
    private final Callable<? extends CompletionStage<T>> callable;
    private final CompletableFuture<T> response;

    public Job(final ConcurrencyLimiter limiter,
               final Callable<? extends CompletionStage<T>> callable,
               final CompletableFuture<T> response) {
      this.limiter = limiter;
      this.callable = callable;
      this.response = response;
    }

    public boolean isCancelled() {
      return this.response.isCancelled();
    }

    public void run() {
      final CompletionStage<T> future;
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
    public CapacityReachedException(String errorMessage) {
      super(errorMessage);
    }
  }
}
