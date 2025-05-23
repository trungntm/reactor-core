/*
 * Copyright (c) 2016-2025 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.assertj.core.api.Condition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Exceptions;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.fail;
import static reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST;

public class SchedulersTest {

	final static class TestSchedulers implements Schedulers.Factory {

		final Scheduler boundedElastic = Schedulers.Factory.super.newBoundedElastic(2, Integer.MAX_VALUE, Thread::new, 60);
		final Scheduler single         = Schedulers.Factory.super.newSingle(Thread::new);
		final Scheduler parallel       = Schedulers.Factory.super.newParallel(1, Thread::new);

		@Override
		public final Scheduler newBoundedElastic(int threadCap, int taskCap, ThreadFactory threadFactory, int ttlSeconds) {
			assertThat(((ReactorThreadFactory) threadFactory).get()).isEqualTo("unused");
			return boundedElastic;
		}

		@Override
		public final Scheduler newParallel(int parallelism, ThreadFactory threadFactory) {
			assertThat(((ReactorThreadFactory)threadFactory).get()).isEqualTo("unused");
			return parallel;
		}

		@Override
		public final Scheduler newSingle(ThreadFactory threadFactory) {
			assertThat(((ReactorThreadFactory)threadFactory).get()).isEqualTo("unused");
			return single;
		}
	}

	final static Condition<Scheduler> CACHED_SCHEDULER = new Condition<>(
			s -> s instanceof Schedulers.CachedScheduler, "a cached scheduler");

	private final AtomicReference<Throwable> exceptionThrown = new AtomicReference<>();

	@AfterEach
	public void resetSchedulers() {
		Schedulers.resetFactory();
		Schedulers.DECORATORS.clear();
	}

	@Test
	public void schedulerDecoratorIsAdditive() throws InterruptedException {
		AtomicInteger tracker = new AtomicInteger();
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator1 = (scheduler, serv) -> {
			tracker.addAndGet(1);
			return serv;
		};
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator2 = (scheduler, serv) -> {
			tracker.addAndGet(10);
			return serv;
		};
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator3 = (scheduler, serv) -> {
			tracker.addAndGet(100);
			return serv;
		};
		//decorators are cleared after test
		Schedulers.addExecutorServiceDecorator("k1", decorator1);
		Schedulers.addExecutorServiceDecorator("k2", decorator2);
		Schedulers.addExecutorServiceDecorator("k3", decorator3);

		//trigger the decorators
		Schedulers.newSingle("foo").dispose();

		assertThat(tracker).as("3 decorators invoked").hasValue(111);
	}

	@Test
	public void schedulerDecoratorIsReplaceable() throws InterruptedException {
		AtomicInteger tracker = new AtomicInteger();
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator1 = (scheduler, serv) -> {
			tracker.addAndGet(1);
			return serv;
		};
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator2 = (scheduler, serv) -> {
			tracker.addAndGet(10);
			return serv;
		};
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator3 = (scheduler, serv) -> {
			tracker.addAndGet(100);
			return serv;
		};
		//decorators are cleared after test
		Schedulers.setExecutorServiceDecorator("k1", decorator1);
		Schedulers.setExecutorServiceDecorator("k1", decorator2);
		Schedulers.setExecutorServiceDecorator("k1", decorator3);

		//trigger the decorators
		Schedulers.newSingle("foo").dispose();

		assertThat(tracker).as("3 decorators invoked").hasValue(100);
	}

	@Test
	public void schedulerDecoratorAddsSameIfDifferentKeys() {
		AtomicInteger tracker = new AtomicInteger();
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator = (scheduler, serv) -> {
			tracker.addAndGet(1);
			return serv;
		};

		//decorators are cleared after test
		Schedulers.addExecutorServiceDecorator("k1", decorator);
		Schedulers.addExecutorServiceDecorator("k2", decorator);
		Schedulers.addExecutorServiceDecorator("k3", decorator);

		//trigger the decorators
		Schedulers.newSingle("foo").dispose();

		assertThat(tracker).as("decorator invoked three times").hasValue(3);
	}

	@Test
	public void schedulerDecoratorAddsOnceIfSameKey() {
		AtomicInteger tracker = new AtomicInteger();
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator1 = (scheduler, serv) -> {
			tracker.addAndGet(1);
			return serv;
		};
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator2 = (scheduler, serv) -> {
			tracker.addAndGet(10);
			return serv;
		};

		//decorators are cleared after test
		Schedulers.addExecutorServiceDecorator("k1", decorator1);
		Schedulers.addExecutorServiceDecorator("k1", decorator2);

		//trigger the decorators
		Schedulers.newSingle("foo").dispose();

		assertThat(tracker).as("decorator invoked once").hasValue(1);
	}

	@Test
	public void schedulerDecoratorDisposedWhenRemoved() {
		AtomicBoolean disposeTracker = new AtomicBoolean();

		class DisposableDecorator implements BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService>,
		                                     Disposable {

			@Override
			public ScheduledExecutorService apply(Scheduler scheduler,
					ScheduledExecutorService service) {
				return service;
			}

			@Override
			public void dispose() {
				disposeTracker.set(true);
			}
		}

		DisposableDecorator decorator = new DisposableDecorator();

		Schedulers.addExecutorServiceDecorator("k1", decorator);

		assertThat(Schedulers.removeExecutorServiceDecorator("k1"))
				.as("decorator removed")
				.isSameAs(decorator);

		assertThat(disposeTracker)
				.as("decorator disposed")
				.isTrue();
	}

	@Test
	public void schedulerDecoratorEmptyDecorators() {
		assertThat(Schedulers.DECORATORS).isEmpty();
		assertThatCode(() -> Schedulers.newSingle("foo").dispose())
				.doesNotThrowAnyException();
	}

	@Test
	public void schedulerDecoratorRemovesKnown() {
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator1 = (scheduler, serv) -> serv;
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator2 = (scheduler, serv) -> serv;
		BiFunction<Scheduler, ScheduledExecutorService, ScheduledExecutorService> decorator3 = (scheduler, serv) -> serv;

		Schedulers.DECORATORS.put("k1", decorator1);
		Schedulers.DECORATORS.put("k2", decorator2);
		Schedulers.DECORATORS.put("k3", decorator3);

		assertThat(Schedulers.DECORATORS).hasSize(3);

		assertThat(Schedulers.removeExecutorServiceDecorator("k1")).as("decorator1 when present").isSameAs(decorator1);
		assertThat(Schedulers.removeExecutorServiceDecorator("k1")).as("decorator1 once removed").isNull();
		assertThat(Schedulers.removeExecutorServiceDecorator("k2")).as("decorator2").isSameAs(decorator2);
		assertThat(Schedulers.removeExecutorServiceDecorator("k3")).as("decorator3").isSameAs(decorator3);

		assertThat(Schedulers.DECORATORS).isEmpty();
	}

	@Test
	public void schedulerDecoratorRemoveUnknownIgnored() {
		assertThat(Schedulers.removeExecutorServiceDecorator("keyfoo"))
				.as("unknown decorator ignored")
				.isNull();
	}

	@Test
	public void parallelSchedulerDefaultNonBlocking() throws InterruptedException {
		Scheduler scheduler = Schedulers.newParallel("parallelSchedulerDefaultNonBlocking");
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Throwable> errorRef = new AtomicReference<>();
		try {
			scheduler.schedule(() -> {
				try {
					Mono.just("foo")
					    .hide()
					    .block();
				}
				catch (Throwable t) {
					errorRef.set(t);
				}
				finally {
					latch.countDown();
				}
			});
			latch.await();
		}
		finally {
			scheduler.dispose();
		}

		assertThat(errorRef.get())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageStartingWith("block()/blockFirst()/blockLast() are blocking, which is not supported in thread parallelSchedulerDefaultNonBlocking-");
	}

	@Test
	public void singleSchedulerDefaultNonBlocking() throws InterruptedException {
		Scheduler scheduler = Schedulers.newSingle("singleSchedulerDefaultNonBlocking");
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Throwable> errorRef = new AtomicReference<>();
		try {
			scheduler.schedule(() -> {
				try {
					Mono.just("foo")
					    .hide()
					    .block();
				}
				catch (Throwable t) {
					errorRef.set(t);
				}
				finally {
					latch.countDown();
				}
			});
			latch.await();
		}
		finally {
			scheduler.dispose();
		}

		assertThat(errorRef.get())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageStartingWith("block()/blockFirst()/blockLast() are blocking, which is not supported in thread singleSchedulerDefaultNonBlocking-");
	}

	@Test
	public void boundedElasticSchedulerDefaultBlockingOk() throws InterruptedException {
		Scheduler scheduler = Schedulers.newBoundedElastic(4, Integer.MAX_VALUE, "boundedElasticSchedulerDefaultNonBlocking");
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Throwable> errorRef = new AtomicReference<>();
		try {
			scheduler.schedule(() -> {
				try {
					Mono.just("foo")
					    .hide()
					    .block();
				}
				catch (Throwable t) {
					errorRef.set(t);
				}
				finally {
					latch.countDown();
				}
			});
			latch.await();
		}
		finally {
			scheduler.dispose();
		}

		assertThat(errorRef.get()).isNull();
	}

	@Test
	public void isInNonBlockingThreadFalse() {
		assertThat(Thread.currentThread()).isNotInstanceOf(NonBlocking.class);

		assertThat(Schedulers.isInNonBlockingThread()).as("isInNonBlockingThread").isFalse();
	}

	@Test
	public void isNonBlockingThreadInstanceOf() {
		Thread nonBlocking = new ReactorThreadFactory.NonBlockingThread(() -> {}, "isNonBlockingThreadInstanceOf_nonBlocking");
		Thread thread = new Thread(() -> {}, "isNonBlockingThreadInstanceOf_blocking");

		assertThat(Schedulers.isNonBlockingThread(nonBlocking)).as("nonBlocking").isTrue();
		assertThat(Schedulers.isNonBlockingThread(thread)).as("thread").isFalse();
	}

	@Test
	public void isInNonBlockingThreadTrue() {
		assertNonBlockingThread(ReactorThreadFactory.NonBlockingThread::new, true);
	}

	@Test
	public void customNonBlockingThreadPredicate() {
		assertThat(Schedulers.nonBlockingThreadPredicate)
				.as("nonBlockingThreadPredicate")
				.isSameAs(Schedulers.DEFAULT_NON_BLOCKING_THREAD_PREDICATE);

		// The custom `Predicate` is not registered yet,
		// so `CustomNonBlockingThread` will be considered blocking.
		assertNonBlockingThread(CustomNonBlockingThread::new, false);

		// Now register the `Predicate` and ensure `CustomNonBlockingThread` is non-blocking.
		Schedulers.registerNonBlockingThreadPredicate(t -> t instanceof CustomNonBlockingThread);
		try {
			assertNonBlockingThread(CustomNonBlockingThread::new, true);
		} finally {
			// Restore the global predicate.
			Schedulers.resetNonBlockingThreadPredicate();
		}

		assertThat(Schedulers.nonBlockingThreadPredicate)
				.as("nonBlockingThreadPredicate (after reset)")
				.isSameAs(Schedulers.DEFAULT_NON_BLOCKING_THREAD_PREDICATE);
	}

	private static void assertNonBlockingThread(BiFunction<Runnable, String, Thread> threadFactory,
												boolean expectedNonBlocking) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		Thread thread = threadFactory.apply(() -> {
			try {
				assertThat(Schedulers.isInNonBlockingThread())
						.as("isInNonBlockingThread")
						.isEqualTo(expectedNonBlocking);
				future.complete(null);
			} catch (Throwable cause) {
				future.completeExceptionally(cause);
			}
		}, "assertNonBlockingThread");

		assertThat(Schedulers.isNonBlockingThread(thread))
				.as("isNonBlockingThread")
				.isEqualTo(expectedNonBlocking);

		thread.start();
		future.join();
	}

	@Test
	public void handleErrorWithJvmFatalForwardsToUncaughtHandlerFusedCallable() {
		AtomicBoolean handlerCaught = new AtomicBoolean();
		Scheduler scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r);
			thread.setUncaughtExceptionHandler((t, ex) -> {
				handlerCaught.set(true);
				System.err.println("from uncaught handler: " + ex.toString());
			});
			return thread;
		}));

		final StepVerifier stepVerifier =
				StepVerifier.create(Mono.<String>fromCallable(() -> {
					throw new StackOverflowError("boom");
				}).subscribeOn(scheduler))
				            .expectFusion()
				            .expectErrorMessage("boom");

		//the exception is still fatal, so the StepVerifier should time out.
		assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> stepVerifier.verify(Duration.ofMillis(100)))
				.withMessageStartingWith("VerifySubscriber timed out on ");

		//nonetheless, the uncaught exception handler should have been invoked
		assertThat(handlerCaught).as("uncaughtExceptionHandler used").isTrue();
	}

	@Test
	public void handleErrorWithJvmFatalForwardsToUncaughtHandlerSyncCallable() {
		AtomicBoolean handlerCaught = new AtomicBoolean();
		Scheduler scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r);
			thread.setUncaughtExceptionHandler((t, ex) -> {
				handlerCaught.set(true);
				System.err.println("from uncaught handler: " + ex.toString());
			});
			return thread;
		}));

		final StepVerifier stepVerifier =
				StepVerifier.create(Mono.<String>fromCallable(() -> {
					throw new StackOverflowError("boom");
				}).hide()
				  .subscribeOn(scheduler))
				            .expectNoFusionSupport()
				            .expectErrorMessage("boom"); //ignored

		//the exception is still fatal, so the StepVerifier should time out.
		assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> stepVerifier.verify(Duration.ofMillis(100)))
				.withMessageStartingWith("VerifySubscriber timed out on ");

		//nonetheless, the uncaught exception handler should have been invoked
		assertThat(handlerCaught).as("uncaughtExceptionHandler used").isTrue();
	}

	@Test
	public void handleErrorWithJvmFatalForwardsToUncaughtHandlerSyncInnerCallable() {
		AtomicBoolean handlerCaught = new AtomicBoolean();
		Scheduler scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r);
			thread.setUncaughtExceptionHandler((t, ex) -> {
				handlerCaught.set(true);
				System.err.println("from uncaught handler: " + ex.toString());
			});
			return thread;
		}));

		final StepVerifier stepVerifier =
				StepVerifier.create(
						Flux.just("hi")
						    .flatMap(item -> Mono.<String>fromCallable(() -> {
							    throw new StackOverflowError("boom");
						    })
								    .hide()
								    .subscribeOn(scheduler))
				)
				            .expectNoFusionSupport()
				            .expectErrorMessage("boom"); //ignored

		//the exception is still fatal, so the StepVerifier should time out.
		assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> stepVerifier.verify(Duration.ofMillis(100)))
				.withMessageStartingWith("VerifySubscriber timed out on ");

		//nonetheless, the uncaught exception handler should have been invoked
		assertThat(handlerCaught).as("uncaughtExceptionHandler used").isTrue();
	}

	@Test
	public void handleErrorWithJvmFatalForwardsToUncaughtHandlerFusedInnerCallable() {
		AtomicBoolean handlerCaught = new AtomicBoolean();
		Scheduler scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r);
			thread.setUncaughtExceptionHandler((t, ex) -> {
				handlerCaught.set(true);
				System.err.println("from uncaught handler: " + ex.toString());
			});
			return thread;
		}));

		final StepVerifier stepVerifier =
				StepVerifier.create(
						Flux.just("hi")
						    .flatMap(item -> Mono.<String>fromCallable(() -> {
							    throw new StackOverflowError("boom");
						    })
								    .subscribeOn(scheduler))
				)
				            .expectFusion()
				            .expectErrorMessage("boom"); //ignored

		//the exception is still fatal, so the StepVerifier should time out.
		assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> stepVerifier.verify(Duration.ofMillis(100)))
				.withMessageStartingWith("VerifySubscriber timed out on ");

		//nonetheless, the uncaught exception handler should have been invoked
		assertThat(handlerCaught).as("uncaughtExceptionHandler used").isTrue();
	}

	@Test
	public void testOverride() {

		TestSchedulers ts = new TestSchedulers();
		Schedulers.setFactory(ts);

		assertThat(Schedulers.newSingle("unused")).isEqualTo(ts.single);
		assertThat(Schedulers.newBoundedElastic(4, Integer.MAX_VALUE, "unused")).isEqualTo(ts.boundedElastic);
		assertThat(Schedulers.newParallel("unused")).isEqualTo(ts.parallel);

		Schedulers.resetFactory();

		Scheduler s = Schedulers.newSingle("unused");
		s.dispose();

		assertThat(s).isNotSameAs(ts.single);
	}

	@Test
	public void testShutdownOldOnSetFactory() {
		Schedulers.Factory ts1 = new Schedulers.Factory() { };
		Schedulers.Factory ts2 = new TestSchedulers();
		Schedulers.setFactory(ts1);
		Scheduler cachedTimerOld = Schedulers.single();
		Scheduler standaloneTimer = Schedulers.newSingle("standaloneTimer");


		assertThat(standaloneTimer).isNotSameAs(cachedTimerOld);
		assertThat(cachedTimerOld.schedule(() -> {})).isNotNull();
		assertThat(standaloneTimer.schedule(() -> {})).isNotNull();

		Schedulers.setFactory(ts2);
		Scheduler cachedTimerNew = Schedulers.newSingle("unused");

		assertThat(cachedTimerOld).isNotSameAs(cachedTimerNew);
		//assert that the old factory"s cached scheduler was shut down
		assertThatExceptionOfType(RejectedExecutionException.class).isThrownBy(() -> cachedTimerOld.schedule(() -> { }));
		//independently created schedulers are still the programmer"s responsibility
		assertThat(standaloneTimer.schedule(() -> {})).isNotNull();
		//new factory = new alive cached scheduler
		assertThat(cachedTimerNew.schedule(() -> {})).isNotNull();
	}

	@Test
	public void shutdownNowClosesAllCachedSchedulers() {
		Scheduler oldSingle = Schedulers.single();
		Scheduler oldBoundedElastic = Schedulers.boundedElastic();
		Scheduler oldParallel = Schedulers.parallel();

		Schedulers.shutdownNow();

		assertThat(oldSingle.isDisposed()).as("single() disposed").isTrue();
		assertThat(oldBoundedElastic.isDisposed()).as("boundedElastic() disposed").isTrue();
		assertThat(oldParallel.isDisposed()).as("parallel() disposed").isTrue();
	}

	@Test
	public void testUncaughtHookCalledWhenOnErrorNotImplemented() {
		AtomicBoolean handled = new AtomicBoolean(false);
		Schedulers.onHandleError((t, e) -> handled.set(true));

		try {
			Schedulers.handleError(Exceptions.errorCallbackNotImplemented(new IllegalArgumentException()));
		} finally {
			Schedulers.resetOnHandleError();
		}
		assertThat(handled.get()).as("errorCallbackNotImplemented not handled").isTrue();
	}

	@Test
	public void testUncaughtHookCalledWhenCommonException() {
		AtomicBoolean handled = new AtomicBoolean(false);
		Schedulers.onHandleError((t, e) -> handled.set(true));

		try {
			Schedulers.handleError(new IllegalArgumentException());
		} finally {
			Schedulers.resetOnHandleError();
		}
		assertThat(handled.get()).as("IllegalArgumentException not handled").isTrue();
	}

	@Test
	public void testUncaughtHooksCalledWhenThreadDeath() {
		AtomicReference<Throwable> onHandleErrorInvoked = new AtomicReference<>();
		AtomicReference<Throwable> globalUncaughtInvoked = new AtomicReference<>();

		Schedulers.onHandleError((t, e) -> onHandleErrorInvoked.set(e));
		Thread.setDefaultUncaughtExceptionHandler((t, e) -> globalUncaughtInvoked.set(e));

		ThreadDeath fatal = new ThreadDeath();

		//written that way so that we can always reset the hook
		Throwable thrown = catchThrowable(() -> Schedulers.handleError(fatal));
		Schedulers.resetOnHandleError();

		assertThat(thrown)
				.as("fatal exceptions not thrown")
				.isNull();

		assertThat(onHandleErrorInvoked).as("onHandleError invoked")
		                                .hasValue(fatal);
		assertThat(globalUncaughtInvoked).as("global uncaught handler invoked")
		                                 .hasValue(fatal);
	}

	@Test
	@Tag("slow")
	public void testRejectingSingleScheduler() {
		assertRejectingScheduler(Schedulers.newSingle("test"));
	}

	@Test
	@Tag("slow")
	public void testRejectingParallelScheduler() {
		assertRejectingScheduler(Schedulers.newParallel("test"));
	}

	@Test
	@Tag("slow")
	public void testRejectingExecutorServiceScheduler() {
		assertRejectingScheduler(Schedulers.fromExecutorService(Executors.newSingleThreadExecutor()));
	}

	private void assertRejectingScheduler(Scheduler scheduler) {
		try {
			Sinks.Many<String> p = Sinks.unsafe().many().multicast().directBestEffort();

			AtomicReference<String> r = new AtomicReference<>();
			CountDownLatch l = new CountDownLatch(1);

			p.asFlux()
			 .publishOn(scheduler)
			 .log()
			 .subscribe(r::set, null, l::countDown);

			scheduler.dispose();

			p.emitNext("reject me", FAIL_FAST);
			l.await(3, TimeUnit.SECONDS);
		}
		catch (Exception ree) {
			ree.printStackTrace();
			Throwable throwable = Exceptions.unwrap(ree);
			if (throwable instanceof RejectedExecutionException) {
				return;
			}
			fail(throwable + " is not a RejectedExecutionException");
		}
		finally {
			scheduler.dispose();
		}
	}

	@Test
	public void testDispatch() throws Exception {
		Scheduler service = Schedulers.newSingle(r -> {
			Thread t = new Thread(r, "dispatcher");
			t.setUncaughtExceptionHandler((t1, e) -> exceptionThrown.set(e));
			return t;
		});

		service.dispose();
	}

	@Test
	public void immediateTaskIsExecuted() throws Exception {
		Scheduler serviceRB = Schedulers.newSingle("rbWork");
		Scheduler.Worker r = serviceRB.createWorker();

		long start = System.currentTimeMillis();
		AtomicInteger latch = new AtomicInteger(1);
		Consumer<String> c =  ev -> {
			latch.decrementAndGet();
			try {
				System.out.println("ev: "+ev);
				Thread.sleep(1000);
			}
			catch(InterruptedException ie){
				throw Exceptions.propagate(ie);
			}
		};
		r.schedule(() -> c.accept("Hello World!"));

		Thread.sleep(1200);
		long end = System.currentTimeMillis();

		serviceRB.dispose();

		assertThat(latch.intValue() == 0).as("Event missed").isTrue();
		assertThat((end - start) >= 1000).as("Timeout too long").isTrue();
	}

	@Test
	public void singleSchedulerPipelining() throws Exception {
		Scheduler serviceRB = Schedulers.newSingle("rb", true);
		Scheduler.Worker dispatcher = serviceRB.createWorker();

		try {
			Thread t1 = Thread.currentThread();
			Thread[] t2 = { null };

			CountDownLatch cdl = new CountDownLatch(1);

			dispatcher.schedule(() -> { t2[0] = Thread.currentThread(); cdl.countDown(); });

			if (!cdl.await(5, TimeUnit.SECONDS)) {
				fail("single timed out");
			}

			assertThat(t2[0]).isNotSameAs(t1);
		} finally {
			dispatcher.dispose();
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	public void testCachedSchedulerDelegates() {
		Scheduler mock = new Scheduler() {
			@Override
			public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
				throw new IllegalStateException("scheduleTaskDelay");
			}

			@Override
			public Disposable schedulePeriodically(Runnable task, long initialDelay,
					long period, TimeUnit unit) {
				throw new IllegalStateException("schedulePeriodically");
			}

			@Override
			public Worker createWorker() {
				throw new IllegalStateException("createWorker");
			}

			@Override
			public Disposable schedule(Runnable task) {
				throw new IllegalStateException("scheduleTask");
			}

			@Override
			public boolean isDisposed() {
				throw new IllegalStateException("isDisposed");
			}

			@Override
			public void dispose() {
				throw new IllegalStateException("dispose");
			}

			@Override
			public long now(TimeUnit unit) {
				throw new IllegalStateException("now");
			}

			@Override
			public void start() {
				throw new IllegalStateException("start");
			}
		};

		Schedulers.CachedScheduler cached = new Schedulers.CachedScheduler("cached", mock);

		//dispose is bypassed by the cached version
		cached.dispose();
		cached.dispose();

		//other methods delegate
		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> cached.schedule(null))
	            .withMessage("scheduleTask");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> cached.schedule(null, 1000, TimeUnit.MILLISECONDS))
	            .withMessage("scheduleTaskDelay");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> cached.schedulePeriodically(null, 1000, 1000, TimeUnit.MILLISECONDS))
	            .withMessage("schedulePeriodically");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> cached.now(TimeUnit.MILLISECONDS))
	            .withMessage("now");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(cached::start)
	            .withMessage("start");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(cached::init)
//				.withMessage("init");
				// TODO: in 3.6.x uncomment the above and add implementation to the mock.
				//       To ease the transition, default init() delegates to start().
				.withMessage("start");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(cached::createWorker)
	            .withMessage("createWorker");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(cached::isDisposed)
	            .withMessage("isDisposed");
	}


	@Test
	@Timeout(5)
	public void parallelSchedulerThreadCheck() throws Exception{
		Scheduler s = Schedulers.newParallel("work", 2);
		try {
			Scheduler.Worker w = s.createWorker();

			Thread currentThread = Thread.currentThread();
			AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
			CountDownLatch latch = new CountDownLatch(1);

			w.schedule(() -> {
				taskThread.set(Thread.currentThread());
				latch.countDown();
			});

			latch.await();

			assertThat(taskThread.get()).isNotEqualTo(currentThread);
		}
		finally {
			s.dispose();
		}
	}

	@Test
	@Timeout(5)
	public void singleSchedulerThreadCheck() throws Exception{
		Scheduler s = Schedulers.newSingle("work");
		try {
			Scheduler.Worker w = s.createWorker();

			Thread currentThread = Thread.currentThread();
			AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
			CountDownLatch latch = new CountDownLatch(1);

			w.schedule(() -> {
				taskThread.set(Thread.currentThread());
				latch.countDown();
			});

			latch.await();

			assertThat(taskThread.get()).isNotEqualTo(currentThread);
		}
		finally {
			s.dispose();
		}
	}

	@Test
	@Timeout(5)
	public void boundedElasticSchedulerThreadCheck() throws Exception {
		Scheduler s = Schedulers.newBoundedElastic(4, Integer.MAX_VALUE,"boundedElasticSchedulerThreadCheck");
		try {
			Scheduler.Worker w = s.createWorker();

			Thread currentThread = Thread.currentThread();
			AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
			CountDownLatch latch = new CountDownLatch(1);

			w.schedule(() -> {
				taskThread.set(Thread.currentThread());
				latch.countDown();
			});

			latch.await();

			assertThat(taskThread.get()).isNotEqualTo(currentThread);
		}
		finally {
			s.dispose();
		}
	}

	@Test
	@Timeout(5)
	public void executorThreadCheck() throws Exception{
		ExecutorService es = Executors.newSingleThreadExecutor();
		Scheduler s = Schedulers.fromExecutor(es::execute);

		try {
			Scheduler.Worker w = s.createWorker();

			Thread currentThread = Thread.currentThread();
			AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
			CountDownLatch latch = new CountDownLatch(1);

			w.schedule(() -> {
				taskThread.set(Thread.currentThread());
				latch.countDown();
			});

			latch.await();

			assertThat(taskThread.get()).isNotEqualTo(currentThread);
		}
		finally {
			s.dispose();
			es.shutdownNow();
		}
	}

	@Test
	@Timeout(5)
	public void executorThreadCheck2() throws Exception{
		ExecutorService es = Executors.newSingleThreadExecutor();
		Scheduler s = Schedulers.fromExecutor(es::execute, true);

		try {
			Scheduler.Worker w = s.createWorker();

			Thread currentThread = Thread.currentThread();
			AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
			CountDownLatch latch = new CountDownLatch(1);

			w.schedule(() -> {
				taskThread.set(Thread.currentThread());
				latch.countDown();
			});

			latch.await();

			assertThat(taskThread.get()).isNotEqualTo(currentThread);
		}
		finally {
			s.dispose();
			es.shutdownNow();
		}
	}

	@Test
	@Timeout(5)
	public void sharedSingleCheck() throws Exception{
		Scheduler p = Schedulers.newParallel("shared");
		Scheduler s = Schedulers.single(p);

		try {
			for(int i = 0; i < 3; i++) {
				Scheduler.Worker w = s.createWorker();

				Thread currentThread = Thread.currentThread();
				AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
				CountDownLatch latch = new CountDownLatch(1);

				w.schedule(() -> {
					taskThread.set(Thread.currentThread());
					latch.countDown();
				});

				latch.await();

				assertThat(taskThread.get()).isNotEqualTo(currentThread);
			}
		}
		finally {
			s.dispose();
			p.dispose();
		}
	}

	void recursiveCall(Scheduler.Worker w, CountDownLatch latch, int data){
		if (data < 2) {
			latch.countDown();
			w.schedule(() -> recursiveCall(w,  latch,data + 1));
		}
	}

	@Test
	public void recursiveParallelCall() throws Exception {
		Scheduler s = Schedulers.newParallel("work", 4);
		try {
			Scheduler.Worker w = s.createWorker();

			CountDownLatch latch = new CountDownLatch(2);

			w.schedule(() -> recursiveCall(w, latch, 0));

			latch.await();
		}
		finally {
			s.dispose();
		}
	}

	@Test
	public void pingPongParallelCall() throws Exception {
		Scheduler s = Schedulers.newParallel("work", 4);
		try {
			Scheduler.Worker w = s.createWorker();
			Thread t = Thread.currentThread();
			AtomicReference<Thread> t1 = new AtomicReference<>(t);
			AtomicReference<Thread> t2 = new AtomicReference<>(t);

			CountDownLatch latch = new CountDownLatch(4);

			AtomicReference<Runnable> pong = new AtomicReference<>();

			Runnable ping = () -> {
				if(latch.getCount() > 0){
					t1.set(Thread.currentThread());
					w.schedule(pong.get());
					latch.countDown();
				}
			};

			pong.set(() -> {
				if(latch.getCount() > 0){
					t2.set(Thread.currentThread());
					w.schedule(ping);
					latch.countDown();
				}
			});

			w.schedule(ping);

			latch.await();

			assertThat(t).isNotEqualTo(t1.get());
			assertThat(t).isNotEqualTo(t2.get());
		}
		finally {
			s.dispose();
		}
	}

	@Test
	public void restartParallel() {
		restart(Schedulers.newParallel("test"));
	}

	@Test
	public void restartBoundedElastic() {
		restart(Schedulers.newBoundedElastic(1, 10, "test"));
	}

	@Test
	public void restartSingle(){
		restart(Schedulers.newSingle("test"));
	}

	@SuppressWarnings("deprecation")
	void restart(Scheduler s){
		Thread t = Mono.fromCallable(Thread::currentThread)
		               .subscribeOn(s)
		               .block();

		s.dispose();
		// TODO: in 3.6.x: remove restart capability and this validation
		s.start();

		Thread t2 = Mono.fromCallable(Thread::currentThread)
		                .subscribeOn(s)
		                .block();

		assertThat(t).isNotEqualTo(Thread.currentThread());
		assertThat(t).isNotEqualTo(t2);
	}

	@Test
	@SuppressWarnings("deprecation")
	public void testDefaultMethods(){
		EmptyScheduler s = new EmptyScheduler();

		s.dispose();
		assertThat(s.disposeCalled).isTrue();

		EmptyScheduler.EmptyWorker w = s.createWorker();
		w.dispose();
		assertThat(w.disposeCalled).isTrue();


		EmptyTimedScheduler ts = new EmptyTimedScheduler();
		ts.dispose();//noop
		ts.start();
		EmptyTimedScheduler.EmptyTimedWorker tw = ts.createWorker();
		tw.dispose();

		long beforeInNanos = System.nanoTime();
		long beforeInMillis = System.currentTimeMillis();

		assertThat(ts.now(TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(beforeInNanos)
		                                         .isLessThanOrEqualTo(System.nanoTime());

		assertThat(ts.now(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(beforeInMillis)
		                                        .isLessThanOrEqualTo(System.currentTimeMillis());

		//noop
		new Schedulers(){

		};

		Scheduler elastic = Schedulers.boundedElastic();
		//noop
		elastic.dispose();
	}

	@Test
	public void testDisposeGracefully() {
		EmptyScheduler s = new EmptyScheduler();

		s.disposeGracefully().timeout(Duration.ofSeconds(1)).subscribe();
		assertThat(s.disposeCalled).isTrue();

		EmptyScheduler.EmptyWorker w = s.createWorker();
		w.dispose();
		assertThat(w.disposeCalled).isTrue();
	}

	@Test
	public void scanExecutorCapacity() {
		Executor plain = Runnable::run;
		ExecutorService plainService = Executors.newSingleThreadExecutor();

		ExecutorService threadPool = Executors.newFixedThreadPool(3);
		ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(4);

		DelegateServiceScheduler.UnsupportedScheduledExecutorService unsupportedScheduledExecutorService =
				new DelegateServiceScheduler.UnsupportedScheduledExecutorService(threadPool);

		try {
			assertThat(Schedulers.scanExecutor(plain, Scannable.Attr.CAPACITY))
					.as("plain").isEqualTo(null);
			assertThat(Schedulers.scanExecutor(plainService, Scannable.Attr.CAPACITY))
					.as("plainService").isEqualTo(null);
			assertThat(Schedulers.scanExecutor(threadPool, Scannable.Attr.CAPACITY))
					.as("threadPool").isEqualTo(3);
			assertThat(Schedulers.scanExecutor(scheduledThreadPool, Scannable.Attr.CAPACITY))
					.as("scheduledThreadPool").isEqualTo(Integer.MAX_VALUE);

			assertThat(Schedulers.scanExecutor(unsupportedScheduledExecutorService, Scannable.Attr.CAPACITY))
					.as("unwrapped").isEqualTo(3);
		}
		finally {
			plainService.shutdownNow();
			unsupportedScheduledExecutorService.shutdownNow();
			threadPool.shutdownNow();
			scheduledThreadPool.shutdownNow();
		}
	}

	@Test
	public void scanSupportBuffered() throws InterruptedException {
		Executor plain = Runnable::run;
		ExecutorService plainService = Executors.newSingleThreadExecutor();

		ExecutorService threadPool = Executors.newFixedThreadPool(3);
		ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(4);

		DelegateServiceScheduler.UnsupportedScheduledExecutorService unsupportedScheduledExecutorService =
				new DelegateServiceScheduler.UnsupportedScheduledExecutorService(threadPool);

		try {
			assertThat(Schedulers.scanExecutor(plain, Scannable.Attr.BUFFERED))
					.as("plain").isEqualTo(null);
			assertThat(Schedulers.scanExecutor(plainService, Scannable.Attr.BUFFERED))
					.as("plainService").isEqualTo(null);

			scheduledThreadPool.schedule(() -> {}, 500, TimeUnit.MILLISECONDS);
			scheduledThreadPool.schedule(() -> {}, 500, TimeUnit.MILLISECONDS);
			Thread.sleep(50); //give some leeway for the pool to have consistent accounting

			assertThat(Schedulers.scanExecutor(scheduledThreadPool, Scannable.Attr.BUFFERED))
					.as("scheduledThreadPool").isEqualTo(2);

			threadPool.submit(() -> {
				try { Thread.sleep(200); } catch (InterruptedException e) { e.printStackTrace(); }
			});

			assertThat(Schedulers.scanExecutor(threadPool, Scannable.Attr.BUFFERED))
					.as("threadPool").isEqualTo(1);
			assertThat(Schedulers.scanExecutor(unsupportedScheduledExecutorService, Scannable.Attr.BUFFERED))
					.as("unwrapped").isEqualTo(1);

			Thread.sleep(400);

			assertThat(Schedulers.scanExecutor(unsupportedScheduledExecutorService, Scannable.Attr.BUFFERED))
					.as("unwrapped after task").isEqualTo(0);
		}
		finally {
			plainService.shutdownNow();
			unsupportedScheduledExecutorService.shutdownNow();
			threadPool.shutdownNow();
			scheduledThreadPool.shutdownNow();
		}
	}

	@Test
	public void testDirectSchedulePeriodicallyCancelsSchedulerTask() throws Exception {
		try(TaskCheckingScheduledExecutor executorService = new TaskCheckingScheduledExecutor()) {
			CountDownLatch latch = new CountDownLatch(2);
			Disposable disposable = Schedulers.directSchedulePeriodically(executorService, () -> {
				latch.countDown();
			}, 0, 10, TimeUnit.MILLISECONDS);
			latch.await();

			disposable.dispose();

			assertThat(executorService.isAllTasksCancelled()).isTrue();
		}
	}

	@Test
	public void testDirectScheduleZeroPeriodicallyCancelsSchedulerTask() throws Exception {
		try(TaskCheckingScheduledExecutor executorService = new TaskCheckingScheduledExecutor()) {
			CountDownLatch latch = new CountDownLatch(2);
			Disposable disposable = Schedulers.directSchedulePeriodically(executorService,
					latch::countDown, 0, 0, TimeUnit.MILLISECONDS);
			latch.await();

			disposable.dispose();

//			avoid race of checking the status of futures vs cancelling said futures
			await().atMost(500, TimeUnit.MILLISECONDS)
			          .pollDelay(10, TimeUnit.MILLISECONDS)
			          .pollInterval(50, TimeUnit.MILLISECONDS)
			          .until(executorService::isAllTasksCancelledOrDone);
		}
	}

	@Test
	public void scheduleInstantTaskTest() throws Exception {
		try(TaskCheckingScheduledExecutor executorService = new TaskCheckingScheduledExecutor()) {
			CountDownLatch latch = new CountDownLatch(1);

			Schedulers.directSchedulePeriodically(executorService, latch::countDown, 0, 0, TimeUnit.MILLISECONDS);

			assertThat(latch.await(100, TimeUnit.MILLISECONDS)).isTrue();
		}
	}

	@Test
	public void scheduleInstantTaskWithDelayTest() throws Exception {
		try(TaskCheckingScheduledExecutor executorService = new TaskCheckingScheduledExecutor()) {
			CountDownLatch latch = new CountDownLatch(1);

			Schedulers.directSchedulePeriodically(executorService, latch::countDown, 50, 0, TimeUnit.MILLISECONDS);

			assertThat(latch.await(100, TimeUnit.MILLISECONDS)).isTrue();
		}
	}

	@Test
	public void testWorkerSchedulePeriodicallyCancelsSchedulerTask() throws Exception {
		try(TaskCheckingScheduledExecutor executorService = new TaskCheckingScheduledExecutor()) {
			AtomicInteger zeroDelayZeroPeriod = new AtomicInteger();
			AtomicInteger zeroPeriod = new AtomicInteger();
			AtomicInteger zeroDelayPeriodic = new AtomicInteger();
			AtomicInteger periodic = new AtomicInteger();

			Disposable.Composite tasks = Disposables.composite();

			Schedulers.workerSchedulePeriodically(executorService, tasks,
					() -> zeroDelayZeroPeriod.incrementAndGet(), 0, 0, TimeUnit.MINUTES);

			Schedulers.workerSchedulePeriodically(executorService, tasks,
					() -> zeroPeriod.incrementAndGet(), 1, 0, TimeUnit.MINUTES);

			Schedulers.workerSchedulePeriodically(executorService, tasks,
					() -> zeroDelayPeriodic.incrementAndGet(), 0, 1, TimeUnit.MINUTES);

			Schedulers.workerSchedulePeriodically(executorService, tasks,
					() -> periodic.incrementAndGet(), 1, 1, TimeUnit.MINUTES);

			Thread.sleep(100);
			tasks.dispose();

			await().atMost(50, TimeUnit.MILLISECONDS)
			       .pollInterval(10, TimeUnit.MILLISECONDS)
			       .alias("all tasks cancelled or done")
			       .until(executorService::isAllTasksCancelledOrDone);

			//when no initial delay, the periodic task(s) have time to be schedule. A 0 period results in a lot of schedules
			assertThat(zeroDelayZeroPeriod).as("zeroDelayZeroPeriod").hasPositiveValue();
			assertThat(zeroDelayPeriodic).as("zeroDelayPeriodic").hasValue(1);
			//the below have initial delays and as such shouldn't have had time to schedule
			assertThat(zeroPeriod).as("zeroDelayPeriodic").hasValue(0);
			assertThat(periodic).as("periodic").hasValue(0);
		}
	}

	@Test
	public void testWorkerScheduleRejectedWithDisposedParent() {
		try(TaskCheckingScheduledExecutor executorService = new TaskCheckingScheduledExecutor()) {
			Disposable.Composite tasks = Disposables.composite();
			tasks.dispose();

			assertThatExceptionOfType(RejectedExecutionException.class)
					.as("zero period, zero delay")
					.isThrownBy(() -> Schedulers.workerSchedulePeriodically(executorService, tasks, () -> {}, 0, 0, TimeUnit.MILLISECONDS));

			assertThatExceptionOfType(RejectedExecutionException.class)
					.as("zero period, some delay")
					.isThrownBy(() -> Schedulers.workerSchedulePeriodically(executorService, tasks, () -> {}, 10, 0, TimeUnit.MILLISECONDS));

			assertThatExceptionOfType(RejectedExecutionException.class)
					.as("periodic, zero delay")
					.isThrownBy(() -> Schedulers.workerSchedulePeriodically(executorService, tasks, () -> {}, 0, 10, TimeUnit.MILLISECONDS));

			assertThatExceptionOfType(RejectedExecutionException.class)
					.as("periodic, some delay")
					.isThrownBy(() -> Schedulers.workerSchedulePeriodically(executorService, tasks, () -> {}, 10, 10, TimeUnit.MILLISECONDS));

			assertThat(executorService.tasks).isEmpty();
		}
	}

	@Test
	public void testWorkerScheduleSupportZeroPeriodWithDelayPeriod() {
		try(TaskCheckingScheduledExecutor executorService = new TaskCheckingScheduledExecutor()) {
			Disposable.Composite tasks = Disposables.composite();
			Disposable disposable = Schedulers.workerSchedulePeriodically(executorService, tasks,
					() -> { }, 1000, 0, TimeUnit.MILLISECONDS);

			disposable.dispose();

			assertThat(executorService.isAllTasksCancelled()).isTrue();
		}
	}

	@Test
	public void testWorkerScheduleSupportZeroPeriod() throws InterruptedException {
		try(TaskCheckingScheduledExecutor executorService = new TaskCheckingScheduledExecutor()) {
			CountDownLatch latch = new CountDownLatch(2);
			Disposable.Composite tasks = Disposables.composite();
			Disposable disposable = Schedulers.workerSchedulePeriodically(executorService, tasks,
					latch::countDown, 0, 0, TimeUnit.MILLISECONDS);
			latch.await();

			disposable.dispose();

			Thread.sleep(100);

			int tasksBefore = executorService.tasks.size();

			Thread.sleep(100);

			int tasksAfter = executorService.tasks.size();

			assertThat(tasksAfter).isEqualTo(tasksBefore);
			assertThat(tasks.size()).isEqualTo(0);
		}
	}

	// === utility classes ===

	final static class EmptyScheduler implements Scheduler {

		boolean disposeCalled;

		@Override
		public void dispose() {
			disposeCalled = true;
		}

		@Override
		public Disposable schedule(Runnable task) {
			return null;
		}

		@Override
		public EmptyWorker createWorker() {
			return new EmptyWorker();
		}

		static class EmptyWorker implements Worker {

			boolean disposeCalled;

			@Override
			public Disposable schedule(Runnable task) {
				return null;
			}

			@Override
			public void dispose() {
				disposeCalled = true;
			}
		}
	}

	final static class TaskCheckingScheduledExecutor extends ScheduledThreadPoolExecutor implements AutoCloseable {

		private final List<RunnableScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();

		TaskCheckingScheduledExecutor() {
			super(1);
		}

		protected <V> RunnableScheduledFuture<V> decorateTask(
				Runnable r, RunnableScheduledFuture<V> task) {
			tasks.add(task);
			return task;
		}

		protected <V> RunnableScheduledFuture<V> decorateTask(
				Callable<V> c, RunnableScheduledFuture<V> task) {
			tasks.add(task);
			return task;
		}

		boolean isAllTasksCancelled() {
			for(RunnableScheduledFuture<?> task: tasks) {
				if (!task.isCancelled()) {
					return false;
				}
			}
			return true;
		}

		boolean isAllTasksCancelledOrDone() {
			for(RunnableScheduledFuture<?> task: tasks) {
				if (!task.isCancelled() && !task.isDone()) {
					return false;
				}
			}
			return true;
		}

		@Override
		public void close() {
			shutdown();
		}
	}

	final static class EmptyTimedScheduler implements Scheduler {

		@Override
		public Disposable schedule(Runnable task) {
			return null;
		}

		@Override
		public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
			return null;
		}

		@Override
		public EmptyTimedWorker createWorker() {
			return new EmptyTimedWorker();
		}

		static class EmptyTimedWorker implements Worker {

			@Override
			public Disposable schedule(Runnable task) {
				return null;
			}

			@Override
			public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
				return null;
			}

			@Override
			public Disposable schedulePeriodically(Runnable task,
					long initialDelay,
					long period,
					TimeUnit unit) {
				return null;
			}

			@Override
			public void dispose() {
			}
		}
	}

	final static class CustomNonBlockingThread extends Thread {
		CustomNonBlockingThread(Runnable target, String name) {
			super(target, name);
		}
	}
}
