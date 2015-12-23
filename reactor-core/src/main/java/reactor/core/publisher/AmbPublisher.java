/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.error.Exceptions;
import reactor.core.error.ReactorFatalException;
import reactor.core.error.SpecificationExceptions;
import reactor.core.subscriber.BaseSubscriber;
import reactor.core.support.BackpressureUtils;
import reactor.core.support.ReactiveState;
import reactor.core.support.SignalType;
import reactor.core.support.internal.PlatformDependent;
import reactor.fn.Supplier;

/**
 * A zip operator to combine 1 by 1 each upstream in parallel given the combinator function.
 * @author Stephane Maldini
 * @since 2.1
 */
public final class AmbPublisher<T>
		implements Publisher<T>, ReactiveState.Factory, ReactiveState.LinkedUpstreams {

	final Publisher[]                          sources;

	public AmbPublisher(final Publisher[] sources) {
		this.sources = sources;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		if (s == null) {
			throw SpecificationExceptions.spec_2_13_exception();
		}
		try {
			if (sources == null || sources.length == 0) {
				s.onSubscribe(SignalType.NOOP_SUBSCRIPTION);
				s.onComplete();
				return;
			}

			AmbBarrier<T> barrier = new AmbBarrier<>(s, this);
			barrier.start();
			s.onSubscribe(barrier);
		}
		catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			s.onError(t);
		}
	}

	@Override
	public Iterator<?> upstreams() {
		return Arrays.asList(sources)
		             .iterator();
	}

	@Override
	public long upstreamsCount() {
		return sources != null ? sources.length : 0;
	}

	static final class AmbBarrier<V>
			implements Subscription,
			           LinkedUpstreams,
			           ActiveDownstream,
			           ActiveUpstream,
			           DownstreamDemand,
			           FailState {

		final AmbPublisher<V> parent;
		final ZipState<?>[]          subscribers;
		final Subscriber<? super V>  actual;

		@SuppressWarnings("unused")
		private volatile Throwable error;

		private volatile boolean cancelled;

		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<AmbBarrier, Throwable> ERROR =
				PlatformDependent.newAtomicReferenceFieldUpdater(AmbBarrier.class, "error");

		@SuppressWarnings("unused")
		private volatile       long                               requested = 0L;
		@SuppressWarnings("rawtypes")
		protected static final AtomicLongFieldUpdater<AmbBarrier> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(AmbBarrier.class, "requested");

		@SuppressWarnings("unused")
		private volatile int running;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<AmbBarrier> RUNNING =
				AtomicIntegerFieldUpdater.newUpdater(AmbBarrier.class, "running");

		Object[] valueCache;

		final static Object[] TERMINATED_CACHE = new Object[0];

		public AmbBarrier(Subscriber<? super V> actual, AmbPublisher<V> parent) {
			this.actual = actual;
			this.parent = parent;
			this.subscribers = new ZipState[parent.sources.length];
		}

		@SuppressWarnings("unchecked")
		void start() {
			if (cancelled) {
				return;
			}

			ZipState[] subscribers = this.subscribers;
			valueCache = new Object[subscribers.length];

			int i;
			ZipState<?> inner;
			Publisher pub;
			for (i = 0; i < subscribers.length; i++) {
				pub = parent.sources[i];
				if (pub instanceof Supplier) {
					inner = new ScalarState(((Supplier<?>) pub).get());
					subscribers[i] = inner;
				}
				else {
					inner = new BufferSubscriber(this);
					subscribers[i] = inner;
				}
			}

			for (i = 0; i < subscribers.length; i++) {
				subscribers[i].subscribeTo(parent.sources[i]);
			}

			drain();
		}

		@Override
		public void request(long n) {
			BackpressureUtils.getAndAdd(REQUESTED, this, n);
			drain();
		}

		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;
				if (RUNNING.getAndIncrement(this) == 0) {
					cancelStates();
				}
			}
		}

		void reportError(Throwable throwable) {
			if (!ERROR.compareAndSet(this, null, throwable)) {
				throw ReactorFatalException.create(throwable);
			}
			actual.onError(throwable);
		}

		@Override
		public Iterator<?> upstreams() {
			return Arrays.asList(subscribers)
			             .iterator();
		}

		@Override
		public long upstreamsCount() {
			return subscribers.length;
		}

		@Override
		public Throwable getError() {
			return error;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean isStarted() {
			return TERMINATED_CACHE != valueCache;
		}

		@Override
		public boolean isTerminated() {
			return TERMINATED_CACHE == valueCache;
		}

		@Override
		public long requestedFromDownstream() {
			return requested;
		}

		void drain() {
			ZipState[] subscribers = this.subscribers;
			if (subscribers == null) {
				return;
			}
			if (RUNNING.getAndIncrement(this) == 0) {
				drainLoop(subscribers);
			}
		}

		@SuppressWarnings("unchecked")
		void drainLoop(ZipState[] inner) {

			final Subscriber<? super V> actual = this.actual;
			int missed = 1;
			for (; ; ) {
				if (checkImmediateTerminate()) {
					return;
				}

				int n = inner.length;
				int replenishMain = 0;
				long r = requested;

				ZipState<?> state;

				for (; ; ) {

					final Object[] tuple = valueCache;
					if(TERMINATED_CACHE == tuple) return;
					boolean completeTuple = true;
					int i;
					for (i = 0; i < n; i++) {
						state = inner[i];

						Object next = state.readNext();

						if (next == null) {
							if (state.isTerminated()) {
								actual.onComplete();
								cancelStates();
								return;
							}

							completeTuple = false;
							continue;
						}

						if (r != 0) {
							tuple[i] = next;
						}
					}

					if (r != 0 && completeTuple) {
						try {
							//actual.onNext(tuple);
							if (r != Long.MAX_VALUE) {
								r--;
							}
							replenishMain++;
						}
						catch (Throwable e) {
							Exceptions.throwIfFatal(e);
							actual.onError(Exceptions.addValueAsLastCause(e, tuple));
							return;
						}

						// consume 1 from each and check if complete

						for (i = 0; i < n; i++) {

							if (checkImmediateTerminate()) {
								return;
							}

							state = inner[i];
							state.requestMore();

							if (state.readNext() == null && state.isTerminated()) {
								actual.onComplete();
								cancelStates();
								return;
							}
						}

						valueCache = new Object[n];
					}
					else {
						break;
					}
				}

				if (replenishMain > 0) {
					BackpressureUtils.getAndSub(REQUESTED, this, replenishMain);
				}

				missed = RUNNING.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}

		void cancelStates() {
			valueCache = TERMINATED_CACHE;
			for (int i = 0; i < subscribers.length; i++) {
				subscribers[i].cancel();
			}
		}

		boolean checkImmediateTerminate() {
			if (cancelled) {
				cancelStates();
				return true;
			}
			Throwable e = error;
			if (e != null) {
				try {
					actual.onError(error);
				}
				finally {
					cancelStates();
				}
				return true;
			}
			return false;
		}
	}

	interface ZipState<V> extends ActiveDownstream, Buffering,
	                              ActiveUpstream, Inner {

		V readNext();

		boolean isTerminated();

		void requestMore();

		void cancel();

		void subscribeTo(Publisher<?> o);
	}

	static final class ScalarState implements ZipState<Object> {

		final Object val;

		boolean read = false;

		ScalarState(Object val) {
			this.val = val;
		}

		@Override
		public Object readNext() {
			return read ? null : val;
		}

		@Override
		public boolean isTerminated() {
			return read;
		}

		@Override
		public boolean isCancelled() {
			return read;
		}

		@Override
		public boolean isStarted() {
			return !read;
		}

		@Override
		public void subscribeTo(Publisher<?> o) {
			//IGNORE
		}

		@Override
		public void requestMore() {
			read = true;
		}

		@Override
		public void cancel() {
			read = true;
		}

		@Override
		public long pending() {
			return read ? 0L : 1L;
		}

		@Override
		public long getCapacity() {
			return 1L;
		}

		@Override
		public String toString() {
			return "ScalarState{" +
					"read=" + read +
					", val=" + val +
					'}';
		}
	}

	static final class BufferSubscriber<V> extends BaseSubscriber<Object>
			implements Subscriber<Object>, Bounded, ZipState<Object>, Upstream,
			           ActiveUpstream,
			           Downstream {

		final AmbBarrier<V> parent;

		@SuppressWarnings("unused")
		volatile Subscription subscription;
		final static AtomicReferenceFieldUpdater<BufferSubscriber, Subscription> SUBSCRIPTION =
				PlatformDependent.newAtomicReferenceFieldUpdater(BufferSubscriber.class, "subscription");

		volatile boolean done;
		int outstanding;

		public BufferSubscriber(AmbBarrier<V> parent) {
			this.parent = parent;
		}

		@Override
		public Object upstream() {
			return subscription;
		}

		@Override
		public boolean isCancelled() {
			return parent.isCancelled();
		}


		@Override
		public Object downstream() {
			return parent;
		}

		@Override
		public Object readNext() {
			return null;
		}

		@Override
		public boolean isTerminated() {
			return false;
		}

		@Override
		public void requestMore() {

		}

		@Override
		public boolean isStarted() {
			return false;
		}

		@Override
		public long pending() {
			return 0;
		}

		@Override
		public long getCapacity() {
			return 0;
		}

		@Override
		public void subscribeTo(Publisher<?> o) {
			o.subscribe(this);
		}

		@Override
		public void onSubscribe(Subscription s) {
			super.onSubscribe(s);

			if(parent.cancelled){
				s.cancel();
				return;
			}

			if (!SUBSCRIPTION.compareAndSet(this, null, s)) {
				s.cancel();
				return;
			}
			s.request(outstanding);
		}

		@Override
		public void onNext(Object x) {
			super.onNext(x);
			try {
				parent.drain();
			}
			catch (Throwable t) {
				Exceptions.throwIfFatal(t);
				parent.reportError(t);
			}
		}

		@Override
		public void onError(Throwable t) {
			super.onError(t);
			parent.reportError(t);
		}

		@Override
		public void onComplete() {
			done = true;
			parent.drain();
		}

		@Override
		public void cancel() {
			Subscription s = SUBSCRIPTION.get(this);
			if (s != SignalType.NOOP_SUBSCRIPTION) {
				s = SUBSCRIPTION.getAndSet(this, SignalType.NOOP_SUBSCRIPTION);
				if (s != SignalType.NOOP_SUBSCRIPTION && s != null) {
					s.cancel();
				}
			}
		}

		@Override
		public String toString() {
			return "AmbSubscriber{" +
					", done=" + done +
					", outstanding=" + outstanding +
					", subscription=" + subscription +
					'}';
		}
	}

}