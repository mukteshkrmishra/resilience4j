/*
 * Copyright 2018 Julien Hoarau
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.resilience4j.reactor.ratelimiter.operator;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.Permit;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.BaseSubscriber;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * A Reactor {@link Subscriber} to wrap another subscriber in a bulkhead.
 *
 * @param <T> the value type of the upstream and downstream
 */
class RateLimiterSubscriber<T> extends BaseSubscriber<T> {

    private final CoreSubscriber<? super T> actual;
    private final RateLimiter rateLimiter;
    private final AtomicReference<Permit> permitted = new AtomicReference<>(Permit.PENDING);
    private final AtomicBoolean firstEvent = new AtomicBoolean(true);

    public RateLimiterSubscriber(RateLimiter rateLimiter,
                                 CoreSubscriber<? super T> actual) {
        this.actual = actual;
        this.rateLimiter = requireNonNull(rateLimiter);
    }

    @Override
    public void hookOnSubscribe(Subscription subscription) {
        if (acquireCallPermit()) {
            actual.onSubscribe(this);
        } else {
            cancel();
            actual.onSubscribe(this);
            actual.onError(rateLimitExceededException());
        }
    }

    @Override
    public void hookOnNext(T t) {
        if (notCancelled() && wasCallPermitted()) {
            if (firstEvent.getAndSet(false) || rateLimiter.getPermission(rateLimiter.getRateLimiterConfig().getTimeoutDuration())) {
                actual.onNext(t);
            } else {
                cancel();
                actual.onError(rateLimitExceededException());
            }
        }
    }

    @Override
    public void hookOnError(Throwable t) {
        if (wasCallPermitted()) {
            actual.onError(t);
        }
    }

    @Override
    public void hookOnComplete() {
        if (wasCallPermitted()) {
            actual.onComplete();
        }
    }

    private boolean acquireCallPermit() {
        boolean callPermitted = false;
        if (permitted.compareAndSet(Permit.PENDING, Permit.ACQUIRED)) {
            callPermitted = rateLimiter.getPermission(rateLimiter.getRateLimiterConfig().getTimeoutDuration());
            if (!callPermitted) {
                permitted.set(Permit.REJECTED);
            }
        }
        return callPermitted;
    }

    private boolean notCancelled() {
        return !this.isDisposed();
    }

    private boolean wasCallPermitted() {
        return permitted.get() == Permit.ACQUIRED;
    }

    private Exception rateLimitExceededException() {
        return new RequestNotPermitted("Request not permitted for limiter: " + rateLimiter.getName());
    }
}
