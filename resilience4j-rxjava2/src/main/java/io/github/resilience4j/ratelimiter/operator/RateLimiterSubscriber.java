package io.github.resilience4j.ratelimiter.operator;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.resilience4j.adapter.Permit;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.reactivex.internal.subscriptions.SubscriptionHelper;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A RxJava {@link Subscriber} to protect another subscriber by a {@link RateLimiter}.
 * Consumes one permit when subscribed and one permit per emitted event except the first one.
 *
 * @param <T> the value type of the upstream and downstream
 */
final class RateLimiterSubscriber<T> extends AtomicReference<Subscription> implements Subscriber<T>, Subscription {
    private final RateLimiter rateLimiter;
    private final Subscriber<? super T> childSubscriber;
    private final AtomicReference<Permit> permitted = new AtomicReference<>(Permit.PENDING);
    private final AtomicBoolean firstEvent = new AtomicBoolean(true);

    RateLimiterSubscriber(RateLimiter rateLimiter, Subscriber<? super T> childSubscriber) {
        this.rateLimiter = requireNonNull(rateLimiter);
        this.childSubscriber = requireNonNull(childSubscriber);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        if (SubscriptionHelper.setOnce(this, subscription)) {
            if (acquireCallPermit()) {
                childSubscriber.onSubscribe(this);
            } else {
                cancel();
                childSubscriber.onSubscribe(this);
                childSubscriber.onError(rateLimitExceededException());
            }
        }
    }

    @Override
    public void onNext(T event) {
        if (isInvocationPermitted()) {
            if (firstEvent.getAndSet(false) || rateLimiter.getPermission(rateLimiter.getRateLimiterConfig().getTimeoutDuration())) {
                childSubscriber.onNext(event);
            } else {
                cancel();
                childSubscriber.onError(rateLimitExceededException());
            }
        }
    }

    @Override
    public void onError(Throwable e) {
        if (isInvocationPermitted()) {
            childSubscriber.onError(e);
        }
    }

    @Override
    public void onComplete() {
        if (isInvocationPermitted()) {
            childSubscriber.onComplete();
        }
    }

    @Override
    public void request(long n) {
        this.get().request(n);
    }

    @Override
    public void cancel() {
        SubscriptionHelper.cancel(this);
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

    private boolean isInvocationPermitted() {
        return notCancelled() && wasCallPermitted();
    }

    private boolean notCancelled() {
        return !SubscriptionHelper.isCancelled(get());
    }

    private boolean wasCallPermitted() {
        return permitted.get() == Permit.ACQUIRED;
    }

    private Exception rateLimitExceededException() {
        return new RequestNotPermitted("Request not permitted for limiter: " + rateLimiter.getName());
    }
}
