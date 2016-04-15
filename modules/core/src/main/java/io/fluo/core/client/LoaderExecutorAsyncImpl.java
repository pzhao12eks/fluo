/*
 * Copyright 2016 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.core.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.util.concurrent.MoreExecutors;
import io.fluo.api.client.Loader;
import io.fluo.api.client.LoaderExecutor;
import io.fluo.api.config.FluoConfiguration;
import io.fluo.core.async.AsyncCommitObserver;
import io.fluo.core.async.AsyncTransaction;
import io.fluo.core.impl.Environment;
import io.fluo.core.impl.TransactionImpl;
import io.fluo.core.log.TracingTransaction;
import io.fluo.core.util.Counter;
import io.fluo.core.util.FluoThreadFactory;
import org.apache.commons.configuration.Configuration;
import org.slf4j.LoggerFactory;


public class LoaderExecutorAsyncImpl implements LoaderExecutor {
  private final ExecutorService executor;
  private final Semaphore semaphore;
  private final int semaphoreSize;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private final AtomicReference<Throwable> exceptionRef = new AtomicReference<>(null);
  private final Environment env;

  private final Counter commiting = new Counter();

  class LoaderCommitObserver implements AsyncCommitObserver, Runnable {

    AsyncTransaction txi;
    Loader loader;
    private boolean done = false;

    private void close() {
      txi = null;
      if (!done) {
        commiting.decrement();
        done = true;
      } else {
        // its only expected that this should be called once.. if its called multiple times in
        // indicates an error in asyn code
        LoggerFactory.getLogger(LoaderCommitObserver.class).error("Close called twice ",
            new Exception());
      }
    }


    public LoaderCommitObserver(Loader loader2) {
      this.loader = loader2;
    }


    @Override
    public void committed() {
      close();
    }

    @Override
    public void failed(Throwable t) {
      close();
      exceptionRef.compareAndSet(null, t);
    }

    @Override
    public void alreadyAcknowledged() {
      close();
      // should not happen
      LoggerFactory.getLogger(LoaderCommitObserver.class).error("Already ack called for loader ",
          new Exception());
    }

    @Override
    public void commitFailed() {
      txi = null;
      // retry transaction
      executor.submit(this);
    }

    @Override
    public void run() {
      txi = new TransactionImpl(env);

      if (TracingTransaction.isTracingEnabled()) {
        txi = new TracingTransaction(txi, loader.getClass());
      }

      Loader.Context context = new Loader.Context() {
        @Override
        public Configuration getAppConfiguration() {
          return env.getAppConfiguration();
        }
      };
      try {
        loader.load(txi, context);
      } catch (Exception e) {
        LoggerFactory.getLogger(LoaderCommitObserver.class).debug(e.getMessage(), e);
        exceptionRef.compareAndSet(null, e);
      }
      env.getSharedResources().getCommitManager().beginCommit(txi, loader.getClass(), this);
    }
  }

  private class QueueReleaseRunnable implements Runnable {

    LoaderCommitObserver loaderTask;

    QueueReleaseRunnable(LoaderCommitObserver loaderTask) {
      this.loaderTask = loaderTask;
    }

    @Override
    public void run() {
      // was just taken out of queue, release semaphore
      semaphore.release();
      loaderTask.run();
    }

  }

  public LoaderExecutorAsyncImpl(FluoConfiguration config, Environment env) {
    this(config, config.getLoaderThreads(), config.getLoaderQueueSize(), env);
  }

  private LoaderExecutorAsyncImpl(FluoConfiguration config, int numThreads, int queueSize,
      Environment env) {

    if (numThreads < 0 || (numThreads == 0 && queueSize != 0)) {
      throw new IllegalArgumentException(
          "numThreads must be positive OR numThreads and queueSize must both be 0");
    }

    if (queueSize < 0 || (numThreads != 0 && queueSize == 0)) {
      throw new IllegalArgumentException(
          "queueSize must be non-negative OR numThreads and queueSize must both be 0");
    }

    this.env = env;
    this.semaphoreSize = queueSize == 0 ? 1 : queueSize;
    this.semaphore = new Semaphore(semaphoreSize);
    if (numThreads == 0) {
      this.executor = MoreExecutors.sameThreadExecutor();
    } else {
      this.executor = Executors.newFixedThreadPool(numThreads, new FluoThreadFactory("loader"));
    }
  }

  @Override
  public void execute(Loader loader) {
    if (exceptionRef.get() != null) {
      throw new RuntimeException("Previous failure", exceptionRef.get());
    }

    try {
      while (!semaphore.tryAcquire(50, TimeUnit.MILLISECONDS)) {
        if (closed.get()) {
          throw new IllegalStateException("LoaderExecutor is closed");
        }
      }
    } catch (InterruptedException e1) {
      throw new RuntimeException(e1);
    }

    try {
      commiting.increment();
      executor.execute(new QueueReleaseRunnable(new LoaderCommitObserver(loader)));
    } catch (RejectedExecutionException rje) {
      semaphore.release();
      commiting.decrement();
      throw rje;
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      // wait for queue to empty and prevent anything else from being enqueued
      semaphore.acquireUninterruptibly(semaphoreSize);

      // wait for all asynchronously committing transactions to complete
      commiting.waitUntilZero();

      if (executor != null) {
        executor.shutdown();
        while (!executor.isTerminated()) {
          try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
      }

      if (exceptionRef.get() != null) {
        throw new RuntimeException(exceptionRef.get());
      }

      // wait for any async mutations that transactions write to flush
      env.getSharedResources().getBatchWriter().waitForAsyncFlush();
    }
  }

}
