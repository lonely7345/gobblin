/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package gobblin.writer;

import gobblin.commit.SpeculativeAttemptAwareConstruct;
import gobblin.configuration.State;
import gobblin.instrumented.Instrumented;
import gobblin.metrics.GobblinMetrics;
import gobblin.writer.exception.NonTransientException;
import gobblin.util.FinalState;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Meter;
import com.github.rholder.retry.Attempt;
import com.github.rholder.retry.RetryException;
import com.github.rholder.retry.RetryListener;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;

/**
 * Retry writer follows decorator pattern that retries on inner writer's failure.
 * @param <D>
 */
public class RetryWriter<D> implements DataWriter<D>, FinalState, SpeculativeAttemptAwareConstruct {
  private static final Logger LOG = LoggerFactory.getLogger(RetryWriter.class);
  public static final String RETRY_CONF_PREFIX = "gobblin.writer.retry.";
  public static final String FAILED_RETRY_WRITES_METER = RETRY_CONF_PREFIX + "failed_writes";
  public static final String RETRY_MULTIPLIER = RETRY_CONF_PREFIX + "multiplier";
  public static final String RETRY_MAX_WAIT_MS_PER_INTERVAL = RETRY_CONF_PREFIX + "max_wait_ms_per_interval";
  public static final String RETRY_MAX_ATTEMPTS = RETRY_CONF_PREFIX + "max_attempts";
  public static final String FAILED_WRITES_KEY = "FailedWrites";

  private final DataWriter<D> writer;
  private final Retryer<Void> retryer;
  private long failedWrites;

  public RetryWriter(DataWriter<D> writer, State state) {
    this.writer = writer;
    this.retryer = buildRetryer(state);
  }

  /**
   * Build Retryer.
   * - If Writer implements Retriable, it will use the RetryerBuilder from the writer.
   * - Otherwise, it will use DEFAULT writer builder.
   *
   * - If Gobblin metrics is enabled, it will emit all failure count in to metrics.
   *
   * @param state
   * @return
   */
  private Retryer<Void> buildRetryer(State state) {
    RetryerBuilder<Void> builder = null;
    if (writer instanceof Retriable) {
      builder = ((Retriable) writer).getRetryerBuilder();
    } else {
      builder = createRetryBuilder(state);
    }

    if (GobblinMetrics.isEnabled(state)) {
      final Optional<Meter> retryMeter = Optional.of(Instrumented.getMetricContext(state, getClass()).meter(FAILED_RETRY_WRITES_METER));

      builder.withRetryListener(new RetryListener() {
        @Override
        public <V> void onRetry(Attempt<V> attempt) {
          if (attempt.hasException()) {
            LOG.warn("Caught exception. This may be retried.", attempt.getExceptionCause());
            Instrumented.markMeter(retryMeter);
            failedWrites++;
          }
        }
      });
    }
    return builder.build();
  }

  @Override
  public void close() throws IOException {
    writer.close();
  }

  @Override
  public void write(final D record) throws IOException {
    //Need a Callable interface to be wrapped by Retryer.
    Callable<Void> writeCall = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        writer.write(record);
        return null;
      }
    };

    callWithRetry(writeCall);
  }

  @Override
  public void commit() throws IOException {
    Callable<Void> commitCall = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        writer.commit();
        return null;
      }
    };

    callWithRetry(commitCall);
  }

  private void callWithRetry(Callable<Void> callable) throws IOException {
    try {
      this.retryer.wrap(callable).call();
    } catch (ExecutionException | RetryException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void cleanup() throws IOException {
    writer.cleanup();
  }

  @Override
  public long recordsWritten() {
    return writer.recordsWritten();
  }

  @Override
  public long bytesWritten() throws IOException {
    return writer.bytesWritten();
  }

  /**
   * @return RetryerBuilder that retries on all exceptions except NonTransientException with exponential back off
   */
  public static RetryerBuilder<Void> createRetryBuilder(State state) {
    Predicate<Throwable> transients = new Predicate<Throwable>() {
      @Override
      public boolean apply(Throwable t) {
        return !(t instanceof NonTransientException);
      }
    };

    long multiplier = state.getPropAsLong(RETRY_MULTIPLIER, 500L);
    long maxWaitMsPerInterval = state.getPropAsLong(RETRY_MAX_WAIT_MS_PER_INTERVAL, 10000);
    int maxAttempts = state.getPropAsInt(RETRY_MAX_ATTEMPTS, 5);
    return RetryerBuilder.<Void> newBuilder()
        .retryIfException(transients)
        .withWaitStrategy(WaitStrategies.exponentialWait(multiplier, maxWaitMsPerInterval, TimeUnit.MILLISECONDS)) //1, 2, 4, 8, 16 seconds delay
        .withStopStrategy(StopStrategies.stopAfterAttempt(maxAttempts)); //Total 5 attempts and fail.
  }

  @Override
  public boolean isSpeculativeAttemptSafe() {
    if (this.writer instanceof SpeculativeAttemptAwareConstruct) {
      return ((SpeculativeAttemptAwareConstruct)this.writer).isSpeculativeAttemptSafe();
    }
    return false;
  }

  @Override
  public State getFinalState() {
    State state = new State();

    if (this.writer instanceof FinalState) {
      state.addAll(((FinalState)this.writer).getFinalState());
    } else {
      LOG.warn("Wrapped writer does not implement FinalState: " + this.writer.getClass());
    }

    state.setProp(FAILED_WRITES_KEY, this.failedWrites);
    return state;
  }
}
