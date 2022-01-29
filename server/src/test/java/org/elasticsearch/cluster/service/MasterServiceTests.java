/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.service;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStatePublicationEvent;
import org.elasticsearch.cluster.ClusterStateTaskConfig;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskExecutor.ClusterTasksResult;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.LocalMasterServiceTask;
import org.elasticsearch.cluster.ack.AckedRequest;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.coordination.ClusterStatePublisher;
import org.elasticsearch.cluster.coordination.FailedToCommitClusterStateException;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.BaseFuture;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.MockLogAppender;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class MasterServiceTests extends ESTestCase {

    private static ThreadPool threadPool;
    private static long relativeTimeInMillis;

    @BeforeClass
    public static void createThreadPool() {
        threadPool = new TestThreadPool(MasterServiceTests.class.getName()) {
            @Override
            public long relativeTimeInMillis() {
                return relativeTimeInMillis;
            }

            @Override
            public long rawRelativeTimeInMillis() {
                return relativeTimeInMillis();
            }
        };
    }

    @AfterClass
    public static void stopThreadPool() {
        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }
    }

    @Before
    public void randomizeCurrentTime() {
        relativeTimeInMillis = randomLongBetween(0L, 1L << 62);
    }

    private MasterService createMasterService(boolean makeMaster) {
        final DiscoveryNode localNode = new DiscoveryNode("node1", buildNewFakeTransportAddress(), emptyMap(), emptySet(), Version.CURRENT);
        final MasterService masterService = new MasterService(
            Settings.builder()
                .put(ClusterName.CLUSTER_NAME_SETTING.getKey(), MasterServiceTests.class.getSimpleName())
                .put(Node.NODE_NAME_SETTING.getKey(), "test_node")
                .build(),
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS),
            threadPool
        );
        final ClusterState initialClusterState = ClusterState.builder(new ClusterName(MasterServiceTests.class.getSimpleName()))
            .nodes(
                DiscoveryNodes.builder().add(localNode).localNodeId(localNode.getId()).masterNodeId(makeMaster ? localNode.getId() : null)
            )
            .blocks(ClusterBlocks.EMPTY_CLUSTER_BLOCK)
            .build();
        final AtomicReference<ClusterState> clusterStateRef = new AtomicReference<>(initialClusterState);
        masterService.setClusterStatePublisher((clusterStatePublicationEvent, publishListener, ackListener) -> {
            clusterStateRef.set(clusterStatePublicationEvent.getNewState());
            ClusterServiceUtils.setAllElapsedMillis(clusterStatePublicationEvent);
            publishListener.onResponse(null);
        });
        masterService.setClusterStateSupplier(clusterStateRef::get);
        masterService.start();
        return masterService;
    }

    public void testMasterAwareExecution() throws Exception {
        final MasterService nonMaster = createMasterService(false);

        final boolean[] taskFailed = { false };
        final CountDownLatch latch1 = new CountDownLatch(1);
        nonMaster.submitStateUpdateTask("test", new ClusterStateUpdateTask() {
            @Override
            public ClusterState execute(ClusterState currentState) {
                latch1.countDown();
                return currentState;
            }

            @Override
            public void onFailure(Exception e) {
                taskFailed[0] = true;
                latch1.countDown();
            }
        }, ClusterStateTaskExecutor.unbatched());

        latch1.await();
        assertTrue("cluster state update task was executed on a non-master", taskFailed[0]);

        final CountDownLatch latch2 = new CountDownLatch(1);
        new LocalMasterServiceTask(Priority.NORMAL) {
            @Override
            public void execute(ClusterState currentState) {
                taskFailed[0] = false;
                latch2.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                taskFailed[0] = true;
                latch2.countDown();
            }
        }.submit(nonMaster, "test");
        latch2.await();
        assertFalse("non-master cluster state update task was not executed", taskFailed[0]);

        nonMaster.close();
    }

    public void testThreadContext() throws InterruptedException {
        final MasterService master = createMasterService(true);
        final CountDownLatch latch = new CountDownLatch(1);

        try (ThreadContext.StoredContext ignored = threadPool.getThreadContext().stashContext()) {
            final Map<String, String> expectedHeaders = Collections.singletonMap("test", "test");
            final Map<String, List<String>> expectedResponseHeaders = Collections.singletonMap(
                "testResponse",
                Collections.singletonList("testResponse")
            );
            threadPool.getThreadContext().putHeader(expectedHeaders);

            final TimeValue ackTimeout = randomBoolean() ? TimeValue.ZERO : TimeValue.timeValueMillis(randomInt(10000));
            final TimeValue masterTimeout = randomBoolean() ? TimeValue.ZERO : TimeValue.timeValueMillis(randomInt(10000));

            master.submitStateUpdateTask("test", new AckedClusterStateUpdateTask(ackedRequest(ackTimeout, masterTimeout), null) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    assertTrue(threadPool.getThreadContext().isSystemContext());
                    assertEquals(Collections.emptyMap(), threadPool.getThreadContext().getHeaders());
                    threadPool.getThreadContext().addResponseHeader("testResponse", "testResponse");
                    assertEquals(expectedResponseHeaders, threadPool.getThreadContext().getResponseHeaders());

                    if (randomBoolean()) {
                        return ClusterState.builder(currentState).build();
                    } else if (randomBoolean()) {
                        return currentState;
                    } else {
                        throw new IllegalArgumentException("mock failure");
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    assertFalse(threadPool.getThreadContext().isSystemContext());
                    assertEquals(expectedHeaders, threadPool.getThreadContext().getHeaders());
                    assertEquals(expectedResponseHeaders, threadPool.getThreadContext().getResponseHeaders());
                    latch.countDown();
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    assertFalse(threadPool.getThreadContext().isSystemContext());
                    assertEquals(expectedHeaders, threadPool.getThreadContext().getHeaders());
                    assertEquals(expectedResponseHeaders, threadPool.getThreadContext().getResponseHeaders());
                    latch.countDown();
                }

                @Override
                public void onAllNodesAcked(@Nullable Exception e) {
                    assertFalse(threadPool.getThreadContext().isSystemContext());
                    assertEquals(expectedHeaders, threadPool.getThreadContext().getHeaders());
                    assertEquals(expectedResponseHeaders, threadPool.getThreadContext().getResponseHeaders());
                    latch.countDown();
                }

                @Override
                public void onAckTimeout() {
                    assertFalse(threadPool.getThreadContext().isSystemContext());
                    assertEquals(expectedHeaders, threadPool.getThreadContext().getHeaders());
                    assertEquals(expectedResponseHeaders, threadPool.getThreadContext().getResponseHeaders());
                    latch.countDown();
                }

            }, ClusterStateTaskExecutor.unbatched());

            assertFalse(threadPool.getThreadContext().isSystemContext());
            assertEquals(expectedHeaders, threadPool.getThreadContext().getHeaders());
            assertEquals(Collections.emptyMap(), threadPool.getThreadContext().getResponseHeaders());
        }

        latch.await();

        master.close();
    }

    /*
    * test that a listener throwing an exception while handling a
    * notification does not prevent publication notification to the
    * executor
    */
    public void testClusterStateTaskListenerThrowingExceptionIsOkay() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();

        try (MasterService masterService = createMasterService(true)) {
            ClusterStateTaskListener update = new ClusterStateTaskListener() {
                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    throw new RuntimeException("testing exception handling");
                }

                @Override
                public void onFailure(Exception e) {}
            };
            masterService.submitStateUpdateTask(
                "testClusterStateTaskListenerThrowingExceptionIsOkay",
                update,
                ClusterStateTaskConfig.build(Priority.NORMAL),
                new ClusterStateTaskExecutor<>() {
                    @Override
                    public ClusterTasksResult<ClusterStateTaskListener> execute(
                        ClusterState currentState,
                        List<ClusterStateTaskListener> tasks
                    ) {
                        ClusterState newClusterState = ClusterState.builder(currentState).build();
                        return ClusterTasksResult.<ClusterStateTaskListener>builder().successes(tasks).build(newClusterState);
                    }

                    @Override
                    public void clusterStatePublished(ClusterStatePublicationEvent clusterStatePublicationEvent) {
                        published.set(true);
                        latch.countDown();
                    }
                }
            );

            latch.await();
            assertTrue(published.get());
        }
    }

    @TestLogging(value = "org.elasticsearch.cluster.service:TRACE", reason = "to ensure that we log cluster state events on TRACE level")
    public void testClusterStateUpdateLogging() throws Exception {
        MockLogAppender mockAppender = new MockLogAppender();
        mockAppender.start();
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test1 start",
                MasterService.class.getCanonicalName(),
                Level.DEBUG,
                "executing cluster state update for [test1]"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test1 computation",
                MasterService.class.getCanonicalName(),
                Level.DEBUG,
                "took [1s] to compute cluster state update for [test1]"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test1 notification",
                MasterService.class.getCanonicalName(),
                Level.DEBUG,
                "took [0s] to notify listeners on unchanged cluster state for [test1]"
            )
        );

        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test2 start",
                MasterService.class.getCanonicalName(),
                Level.DEBUG,
                "executing cluster state update for [test2]"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test2 failure",
                MasterService.class.getCanonicalName(),
                Level.TRACE,
                "failed to execute cluster state update (on version: [*], uuid: [*]) for [test2]*"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test2 computation",
                MasterService.class.getCanonicalName(),
                Level.DEBUG,
                "took [2s] to compute cluster state update for [test2]"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test2 notification",
                MasterService.class.getCanonicalName(),
                Level.DEBUG,
                "took [0s] to notify listeners on unchanged cluster state for [test2]"
            )
        );

        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test3 start",
                MasterService.class.getCanonicalName(),
                Level.DEBUG,
                "executing cluster state update for [test3]"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test3 computation",
                MasterService.class.getCanonicalName(),
                Level.DEBUG,
                "took [3s] to compute cluster state update for [test3]"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test3 notification",
                MasterService.class.getCanonicalName(),
                Level.DEBUG,
                "took [4s] to notify listeners on successful publication of cluster state (version: *, uuid: *) for [test3]"
            )
        );

        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test4",
                MasterService.class.getCanonicalName(),
                Level.DEBUG,
                "executing cluster state update for [test4]"
            )
        );

        Logger clusterLogger = LogManager.getLogger(MasterService.class);
        Loggers.addAppender(clusterLogger, mockAppender);
        try (MasterService masterService = createMasterService(true)) {
            masterService.submitStateUpdateTask("test1", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    relativeTimeInMillis += TimeValue.timeValueSeconds(1).millis();
                    return currentState;
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {}

                @Override
                public void onFailure(Exception e) {
                    fail();
                }
            }, ClusterStateTaskExecutor.unbatched());
            masterService.submitStateUpdateTask("test2", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    relativeTimeInMillis += TimeValue.timeValueSeconds(2).millis();
                    throw new IllegalArgumentException("Testing handling of exceptions in the cluster state task");
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    fail();
                }

                @Override
                public void onFailure(Exception e) {}
            }, ClusterStateTaskExecutor.unbatched());
            masterService.submitStateUpdateTask("test3", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    relativeTimeInMillis += TimeValue.timeValueSeconds(3).millis();
                    return ClusterState.builder(currentState).incrementVersion().build();
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    relativeTimeInMillis += TimeValue.timeValueSeconds(4).millis();
                }

                @Override
                public void onFailure(Exception e) {
                    fail();
                }
            }, ClusterStateTaskExecutor.unbatched());
            masterService.submitStateUpdateTask("test4", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return currentState;
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {}

                @Override
                public void onFailure(Exception e) {
                    fail();
                }
            }, ClusterStateTaskExecutor.unbatched());
            assertBusy(mockAppender::assertAllExpectationsMatched);
        } finally {
            Loggers.removeAppender(clusterLogger, mockAppender);
            mockAppender.stop();
        }
    }

    public void testClusterStateBatchedUpdates() throws BrokenBarrierException, InterruptedException {

        AtomicInteger executedTasks = new AtomicInteger();
        AtomicInteger submittedTasks = new AtomicInteger();
        AtomicInteger processedStates = new AtomicInteger();
        SetOnce<CountDownLatch> processedStatesLatch = new SetOnce<>();

        class Task implements ClusterStateTaskListener {
            private final AtomicBoolean executed = new AtomicBoolean();
            private final int id;

            Task(int id) {
                this.id = id;
            }

            public void execute() {
                if (executed.compareAndSet(false, true) == false) {
                    throw new AssertionError("Task [" + id + "] should only be executed once");
                } else {
                    executedTasks.incrementAndGet();
                }
            }

            @Override
            public void onFailure(Exception e) {
                throw new AssertionError(e);
            }

            @Override
            public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                processedStates.incrementAndGet();
                processedStatesLatch.get().countDown();
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }
                Task task = (Task) o;
                return id == task.id;
            }

            @Override
            public int hashCode() {
                return id;
            }

            @Override
            public String toString() {
                return Integer.toString(id);
            }
        }

        final int numberOfThreads = randomIntBetween(2, 8);
        final int taskSubmissionsPerThread = randomIntBetween(1, 64);
        final int numberOfExecutors = Math.max(1, numberOfThreads / 4);
        final Semaphore semaphore = new Semaphore(1);

        class TaskExecutor implements ClusterStateTaskExecutor<Task> {

            private final AtomicInteger executed = new AtomicInteger();
            private final AtomicInteger assigned = new AtomicInteger();
            private final AtomicInteger batches = new AtomicInteger();
            private final AtomicInteger published = new AtomicInteger();
            private final List<Set<Task>> assignments = new ArrayList<>();

            @Override
            public ClusterTasksResult<Task> execute(ClusterState currentState, List<Task> tasks) throws Exception {
                int totalCount = 0;
                for (Set<Task> group : assignments) {
                    long count = tasks.stream().filter(group::contains).count();
                    assertThat(
                        "batched set should be executed together or not at all. Expected " + group + "s. Executing " + tasks,
                        count,
                        anyOf(equalTo(0L), equalTo((long) group.size()))
                    );
                    totalCount += count;
                }
                assertThat("All tasks should belong to this executor", totalCount, equalTo(tasks.size()));
                tasks.forEach(Task::execute);
                executed.addAndGet(tasks.size());
                ClusterState maybeUpdatedClusterState = currentState;
                if (randomBoolean()) {
                    maybeUpdatedClusterState = ClusterState.builder(currentState).build();
                    batches.incrementAndGet();
                    assertThat(
                        "All cluster state modifications should be executed on a single thread",
                        semaphore.tryAcquire(),
                        equalTo(true)
                    );
                }
                return ClusterTasksResult.<Task>builder().successes(tasks).build(maybeUpdatedClusterState);
            }

            @Override
            public void clusterStatePublished(ClusterStatePublicationEvent clusterPublicationEvent) {
                published.incrementAndGet();
                semaphore.release();
            }
        }

        List<TaskExecutor> executors = new ArrayList<>();
        for (int i = 0; i < numberOfExecutors; i++) {
            executors.add(new TaskExecutor());
        }

        // randomly assign tasks to executors
        List<Tuple<TaskExecutor, Set<Task>>> assignments = new ArrayList<>();
        AtomicInteger totalTasks = new AtomicInteger();
        for (int i = 0; i < numberOfThreads; i++) {
            for (int j = 0; j < taskSubmissionsPerThread; j++) {
                var executor = randomFrom(executors);
                var tasks = Set.copyOf(randomList(1, 3, () -> new Task(totalTasks.getAndIncrement())));

                assignments.add(Tuple.tuple(executor, tasks));
                executor.assigned.addAndGet(tasks.size());
                executor.assignments.add(tasks);
            }
        }
        processedStatesLatch.set(new CountDownLatch(totalTasks.get()));

        try (MasterService masterService = createMasterService(true)) {
            CyclicBarrier barrier = new CyclicBarrier(1 + numberOfThreads);
            for (int i = 0; i < numberOfThreads; i++) {
                final int index = i;
                Thread thread = new Thread(() -> {
                    final String threadName = Thread.currentThread().getName();
                    try {
                        barrier.await();
                        for (int j = 0; j < taskSubmissionsPerThread; j++) {
                            var assignment = assignments.get(index * taskSubmissionsPerThread + j);
                            var tasks = assignment.v2();
                            var executor = assignment.v1();
                            submittedTasks.addAndGet(tasks.size());
                            if (tasks.size() == 1) {
                                masterService.submitStateUpdateTask(
                                    threadName,
                                    tasks.iterator().next(),
                                    ClusterStateTaskConfig.build(randomFrom(Priority.values())),
                                    executor
                                );
                            } else {
                                masterService.submitStateUpdateTasks(
                                    threadName,
                                    tasks,
                                    ClusterStateTaskConfig.build(randomFrom(Priority.values())),
                                    executor
                                );
                            }
                        }
                        barrier.await();
                    } catch (BrokenBarrierException | InterruptedException e) {
                        throw new AssertionError(e);
                    }
                });
                thread.start();
            }

            // wait for all threads to be ready
            barrier.await();
            // wait for all threads to finish
            barrier.await();

            // wait until all the cluster state updates have been processed
            processedStatesLatch.get().await();
            // and until all the publication callbacks have completed
            semaphore.acquire();

            // assert the number of executed tasks is correct
            assertThat(submittedTasks.get(), equalTo(totalTasks.get()));
            assertThat(executedTasks.get(), equalTo(totalTasks.get()));
            assertThat(processedStates.get(), equalTo(totalTasks.get()));

            // assert each executor executed the correct number of tasks
            for (TaskExecutor executor : executors) {
                assertEquals(executor.assigned.get(), executor.executed.get());
                assertEquals(executor.batches.get(), executor.published.get());
            }
        }
    }

    public void testBlockingCallInClusterStateTaskListenerFails() throws InterruptedException {
        assumeTrue("assertions must be enabled for this test to work", BaseFuture.class.desiredAssertionStatus());
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AssertionError> assertionRef = new AtomicReference<>();

        try (MasterService masterService = createMasterService(true)) {
            ClusterStateTaskListener update = new ClusterStateTaskListener() {
                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    BaseFuture<Void> future = new BaseFuture<Void>() {
                    };
                    try {
                        if (randomBoolean()) {
                            future.get(1L, TimeUnit.SECONDS);
                        } else {
                            future.get();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } catch (AssertionError e) {
                        assertionRef.set(e);
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(Exception e) {}
            };
            masterService.submitStateUpdateTask(
                "testBlockingCallInClusterStateTaskListenerFails",
                update,
                ClusterStateTaskConfig.build(Priority.NORMAL),
                (currentState, tasks) -> {
                    ClusterState newClusterState = ClusterState.builder(currentState).build();
                    return ClusterTasksResult.<ClusterStateTaskListener>builder().successes(tasks).build(newClusterState);
                }
            );

            latch.await();
            assertNotNull(assertionRef.get());
            assertThat(assertionRef.get().getMessage(), containsString("Reason: [Blocking operation]"));
        }
    }

    @TestLogging(value = "org.elasticsearch.cluster.service:WARN", reason = "to ensure that we log cluster state events on WARN level")
    public void testLongClusterStateUpdateLogging() throws Exception {
        MockLogAppender mockAppender = new MockLogAppender();
        mockAppender.start();
        mockAppender.addExpectation(
            new MockLogAppender.UnseenEventExpectation(
                "test1 shouldn't log because it was fast enough",
                MasterService.class.getCanonicalName(),
                Level.WARN,
                "*took*test1*"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test2",
                MasterService.class.getCanonicalName(),
                Level.WARN,
                "*took [*] to compute cluster state update for [test2], which exceeds the warn threshold of [10s]"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test3",
                MasterService.class.getCanonicalName(),
                Level.WARN,
                "*took [*] to compute cluster state update for [test3], which exceeds the warn threshold of [10s]"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test4",
                MasterService.class.getCanonicalName(),
                Level.WARN,
                "*took [*] to compute cluster state update for [test4], which exceeds the warn threshold of [10s]"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.UnseenEventExpectation(
                "test5 should not log despite publishing slowly",
                MasterService.class.getCanonicalName(),
                Level.WARN,
                "*took*test5*"
            )
        );
        mockAppender.addExpectation(
            new MockLogAppender.SeenEventExpectation(
                "test6 should log due to slow and failing publication",
                MasterService.class.getCanonicalName(),
                Level.WARN,
                "took [*] and then failed to publish updated cluster state (version: *, uuid: *) for [test6]:*"
            )
        );

        Logger clusterLogger = LogManager.getLogger(MasterService.class);
        Loggers.addAppender(clusterLogger, mockAppender);
        try (
            MasterService masterService = new MasterService(
                Settings.builder()
                    .put(ClusterName.CLUSTER_NAME_SETTING.getKey(), MasterServiceTests.class.getSimpleName())
                    .put(Node.NODE_NAME_SETTING.getKey(), "test_node")
                    .build(),
                new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS),
                threadPool
            )
        ) {

            final DiscoveryNode localNode = new DiscoveryNode(
                "node1",
                buildNewFakeTransportAddress(),
                emptyMap(),
                emptySet(),
                Version.CURRENT
            );
            final ClusterState initialClusterState = ClusterState.builder(new ClusterName(MasterServiceTests.class.getSimpleName()))
                .nodes(DiscoveryNodes.builder().add(localNode).localNodeId(localNode.getId()).masterNodeId(localNode.getId()))
                .blocks(ClusterBlocks.EMPTY_CLUSTER_BLOCK)
                .build();
            final AtomicReference<ClusterState> clusterStateRef = new AtomicReference<>(initialClusterState);
            masterService.setClusterStatePublisher((clusterStatePublicationEvent, publishListener, ackListener) -> {
                ClusterServiceUtils.setAllElapsedMillis(clusterStatePublicationEvent);
                if (clusterStatePublicationEvent.getSummary().contains("test5")) {
                    relativeTimeInMillis += MasterService.MASTER_SERVICE_SLOW_TASK_LOGGING_THRESHOLD_SETTING.get(Settings.EMPTY).millis()
                        + randomLongBetween(1, 1000000);
                }
                if (clusterStatePublicationEvent.getSummary().contains("test6")) {
                    relativeTimeInMillis += MasterService.MASTER_SERVICE_SLOW_TASK_LOGGING_THRESHOLD_SETTING.get(Settings.EMPTY).millis()
                        + randomLongBetween(1, 1000000);
                    throw new ElasticsearchException("simulated error during slow publication which should trigger logging");
                }
                clusterStateRef.set(clusterStatePublicationEvent.getNewState());
                publishListener.onResponse(null);
            });
            masterService.setClusterStateSupplier(clusterStateRef::get);
            masterService.start();

            final CountDownLatch latch = new CountDownLatch(6);
            final CountDownLatch processedFirstTask = new CountDownLatch(1);
            masterService.submitStateUpdateTask("test1", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    relativeTimeInMillis += randomLongBetween(
                        0L,
                        MasterService.MASTER_SERVICE_SLOW_TASK_LOGGING_THRESHOLD_SETTING.get(Settings.EMPTY).millis()
                    );
                    return currentState;
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                    processedFirstTask.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    fail();
                }
            }, ClusterStateTaskExecutor.unbatched());

            processedFirstTask.await();
            masterService.submitStateUpdateTask("test2", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    relativeTimeInMillis += MasterService.MASTER_SERVICE_SLOW_TASK_LOGGING_THRESHOLD_SETTING.get(Settings.EMPTY).millis()
                        + randomLongBetween(1, 1000000);
                    throw new IllegalArgumentException("Testing handling of exceptions in the cluster state task");
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    fail();
                }

                @Override
                public void onFailure(Exception e) {
                    latch.countDown();
                }
            }, ClusterStateTaskExecutor.unbatched());
            masterService.submitStateUpdateTask("test3", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    relativeTimeInMillis += MasterService.MASTER_SERVICE_SLOW_TASK_LOGGING_THRESHOLD_SETTING.get(Settings.EMPTY).millis()
                        + randomLongBetween(1, 1000000);
                    return ClusterState.builder(currentState).incrementVersion().build();
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    fail();
                }
            }, ClusterStateTaskExecutor.unbatched());
            masterService.submitStateUpdateTask("test4", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    relativeTimeInMillis += MasterService.MASTER_SERVICE_SLOW_TASK_LOGGING_THRESHOLD_SETTING.get(Settings.EMPTY).millis()
                        + randomLongBetween(1, 1000000);
                    return currentState;
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    fail();
                }
            }, ClusterStateTaskExecutor.unbatched());
            masterService.submitStateUpdateTask("test5", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return ClusterState.builder(currentState).incrementVersion().build();
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    fail();
                }
            }, ClusterStateTaskExecutor.unbatched());
            masterService.submitStateUpdateTask("test6", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return ClusterState.builder(currentState).incrementVersion().build();
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    fail();
                }

                @Override
                public void onFailure(Exception e) {
                    fail(); // maybe we should notify here?
                }
            }, ClusterStateTaskExecutor.unbatched());
            // Additional update task to make sure all previous logging made it to the loggerName
            // We don't check logging for this on since there is no guarantee that it will occur before our check
            masterService.submitStateUpdateTask("test7", new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return currentState;
                }

                @Override
                public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    fail();
                }
            }, ClusterStateTaskExecutor.unbatched());
            latch.await();
        } finally {
            Loggers.removeAppender(clusterLogger, mockAppender);
            mockAppender.stop();
        }
        mockAppender.assertAllExpectationsMatched();
    }

    public void testAcking() throws InterruptedException {
        final DiscoveryNode node1 = new DiscoveryNode("node1", buildNewFakeTransportAddress(), emptyMap(), emptySet(), Version.CURRENT);
        final DiscoveryNode node2 = new DiscoveryNode("node2", buildNewFakeTransportAddress(), emptyMap(), emptySet(), Version.CURRENT);
        final DiscoveryNode node3 = new DiscoveryNode("node3", buildNewFakeTransportAddress(), emptyMap(), emptySet(), Version.CURRENT);
        try (
            MasterService masterService = new MasterService(
                Settings.builder()
                    .put(ClusterName.CLUSTER_NAME_SETTING.getKey(), MasterServiceTests.class.getSimpleName())
                    .put(Node.NODE_NAME_SETTING.getKey(), "test_node")
                    .build(),
                new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS),
                threadPool
            )
        ) {

            final ClusterState initialClusterState = ClusterState.builder(new ClusterName(MasterServiceTests.class.getSimpleName()))
                .nodes(DiscoveryNodes.builder().add(node1).add(node2).add(node3).localNodeId(node1.getId()).masterNodeId(node1.getId()))
                .blocks(ClusterBlocks.EMPTY_CLUSTER_BLOCK)
                .build();
            final AtomicReference<ClusterStatePublisher> publisherRef = new AtomicReference<>();
            masterService.setClusterStatePublisher((e, pl, al) -> {
                ClusterServiceUtils.setAllElapsedMillis(e);
                publisherRef.get().publish(e, pl, al);
            });
            masterService.setClusterStateSupplier(() -> initialClusterState);
            masterService.start();

            // check that we don't time out before even committing the cluster state
            {
                final CountDownLatch latch = new CountDownLatch(1);

                publisherRef.set(
                    (clusterChangedEvent, publishListener, ackListener) -> publishListener.onFailure(
                        new FailedToCommitClusterStateException("mock exception")
                    )
                );

                masterService.submitStateUpdateTask("test2", new AckedClusterStateUpdateTask(ackedRequest(TimeValue.ZERO, null), null) {
                    @Override
                    public ClusterState execute(ClusterState currentState) {
                        return ClusterState.builder(currentState).build();
                    }

                    @Override
                    public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                        fail();
                    }

                    @Override
                    protected AcknowledgedResponse newResponse(boolean acknowledged) {
                        fail();
                        return null;
                    }

                    @Override
                    public void onFailure(Exception e) {
                        latch.countDown();
                    }

                    @Override
                    public void onAckTimeout() {
                        fail();
                    }
                }, ClusterStateTaskExecutor.unbatched());

                latch.await();
            }

            // check that we timeout if commit took too long
            {
                final CountDownLatch latch = new CountDownLatch(2);

                final TimeValue ackTimeout = TimeValue.timeValueMillis(randomInt(100));

                publisherRef.set((clusterChangedEvent, publishListener, ackListener) -> {
                    publishListener.onResponse(null);
                    ackListener.onCommit(TimeValue.timeValueMillis(ackTimeout.millis() + randomInt(100)));
                    ackListener.onNodeAck(node1, null);
                    ackListener.onNodeAck(node2, null);
                    ackListener.onNodeAck(node3, null);
                });

                masterService.submitStateUpdateTask("test2", new AckedClusterStateUpdateTask(ackedRequest(ackTimeout, null), null) {
                    @Override
                    public ClusterState execute(ClusterState currentState) {
                        return ClusterState.builder(currentState).build();
                    }

                    @Override
                    public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
                        latch.countDown();
                    }

                    @Override
                    protected AcknowledgedResponse newResponse(boolean acknowledged) {
                        fail();
                        return null;
                    }

                    @Override
                    public void onFailure(Exception e) {
                        fail();
                    }

                    @Override
                    public void onAckTimeout() {
                        latch.countDown();
                    }
                }, ClusterStateTaskExecutor.unbatched());

                latch.await();
            }
        }
    }

    @TestLogging(value = "org.elasticsearch.cluster.service.MasterService:WARN", reason = "testing WARN logging")
    public void testStarvationLogging() throws Exception {
        final long warnThresholdMillis = MasterService.MASTER_SERVICE_STARVATION_LOGGING_THRESHOLD_SETTING.get(Settings.EMPTY).millis();
        relativeTimeInMillis = randomLongBetween(0, Long.MAX_VALUE - warnThresholdMillis * 3);
        final long startTimeMillis = relativeTimeInMillis;
        final long taskDurationMillis = TimeValue.timeValueSeconds(1).millis();

        MockLogAppender mockAppender = new MockLogAppender();
        mockAppender.start();

        Logger clusterLogger = LogManager.getLogger(MasterService.class);
        Loggers.addAppender(clusterLogger, mockAppender);
        try (MasterService masterService = createMasterService(true)) {
            final AtomicBoolean keepRunning = new AtomicBoolean(true);

            final Runnable await = new Runnable() {
                private final CyclicBarrier cyclicBarrier = new CyclicBarrier(2);

                @Override
                public void run() {
                    try {
                        cyclicBarrier.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                        throw new AssertionError("unexpected", e);
                    }
                }
            };
            final Runnable awaitNextTask = () -> {
                await.run();
                await.run();
            };

            final ClusterStateUpdateTask starvationCausingTask = new ClusterStateUpdateTask(Priority.HIGH) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    await.run();
                    relativeTimeInMillis += taskDurationMillis;
                    if (keepRunning.get()) {
                        masterService.submitStateUpdateTask("starvation-causing task", this, ClusterStateTaskExecutor.unbatched());
                    }
                    await.run();
                    return currentState;
                }

                @Override
                public void onFailure(Exception e) {
                    fail();
                }
            };
            masterService.submitStateUpdateTask("starvation-causing task", starvationCausingTask, ClusterStateTaskExecutor.unbatched());

            final CountDownLatch starvedTaskExecuted = new CountDownLatch(1);
            masterService.submitStateUpdateTask("starved task", new ClusterStateUpdateTask(Priority.NORMAL) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    assertFalse(keepRunning.get());
                    starvedTaskExecuted.countDown();
                    return currentState;
                }

                @Override
                public void onFailure(Exception e) {
                    fail();
                }
            }, ClusterStateTaskExecutor.unbatched());

            // check that a warning is logged after 5m
            final MockLogAppender.EventuallySeenEventExpectation expectation1 = new MockLogAppender.EventuallySeenEventExpectation(
                "starvation warning",
                MasterService.class.getCanonicalName(),
                Level.WARN,
                "pending task queue has been nonempty for [5m/300000ms] which is longer than the warn threshold of [300000ms];"
                    + " there are currently [2] pending tasks, the oldest of which has age [*"
            );
            mockAppender.addExpectation(expectation1);

            while (relativeTimeInMillis - startTimeMillis < warnThresholdMillis) {
                awaitNextTask.run();
                mockAppender.assertAllExpectationsMatched();
            }

            expectation1.setExpectSeen();
            awaitNextTask.run();
            // the master service thread is somewhere between completing the previous task and starting the next one, which is when the
            // logging happens, so we must wait for another task to run too to ensure that the message was logged
            awaitNextTask.run();
            mockAppender.assertAllExpectationsMatched();

            // check that another warning is logged after 10m
            final MockLogAppender.EventuallySeenEventExpectation expectation2 = new MockLogAppender.EventuallySeenEventExpectation(
                "starvation warning",
                MasterService.class.getCanonicalName(),
                Level.WARN,
                "pending task queue has been nonempty for [10m/600000ms] which is longer than the warn threshold of [300000ms];"
                    + " there are currently [2] pending tasks, the oldest of which has age [*"
            );
            mockAppender.addExpectation(expectation2);

            while (relativeTimeInMillis - startTimeMillis < warnThresholdMillis * 2) {
                awaitNextTask.run();
                mockAppender.assertAllExpectationsMatched();
            }

            expectation2.setExpectSeen();
            awaitNextTask.run();
            // the master service thread is somewhere between completing the previous task and starting the next one, which is when the
            // logging happens, so we must wait for another task to run too to ensure that the message was logged
            awaitNextTask.run();
            mockAppender.assertAllExpectationsMatched();

            // now stop the starvation and clean up
            keepRunning.set(false);
            awaitNextTask.run();
            assertTrue(starvedTaskExecuted.await(10, TimeUnit.SECONDS));

        } finally {
            Loggers.removeAppender(clusterLogger, mockAppender);
            mockAppender.stop();
        }
    }

    /**
     * Returns the cluster state that the master service uses (and that is provided by the discovery layer)
     */
    public static ClusterState discoveryState(MasterService masterService) {
        return masterService.state();
    }

    /**
     * Returns a plain {@link AckedRequest} that does not implement any functionality outside of the timeout getters.
     */
    public static AckedRequest ackedRequest(TimeValue ackTimeout, TimeValue masterNodeTimeout) {
        return new AckedRequest() {
            @Override
            public TimeValue ackTimeout() {
                return ackTimeout;
            }

            @Override
            public TimeValue masterNodeTimeout() {
                return masterNodeTimeout;
            }
        };
    }

}
