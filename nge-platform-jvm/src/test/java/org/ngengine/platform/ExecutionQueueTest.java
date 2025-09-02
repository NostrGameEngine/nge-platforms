/**
 * BSD 3-Clause License
 * 
 * Copyright (c) 2025, Riccardo Balbo
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngengine.platform;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ExecutionQueueTest {

    private ExecutionQueue queue;

    @Before
    public void setUp() {
        queue = new ExecutionQueue();
    }

    @After
    public void tearDown() throws IOException {
        if (queue != null) {
            queue.close();
        }
    }

    @Test(timeout = 5000)
    public void testSingleTask() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

        AsyncTask<String> task = queue.enqueue((resolve, reject) -> {
            resolve.accept("test-result");
        });

        task.then(value -> {
            result.set(value);
            latch.countDown();
            return null;
        });

        assertTrue("Task should complete within timeout", latch.await(2, TimeUnit.SECONDS));
        assertEquals("test-result", result.get());
    }

    @Test(timeout = 10000)
    public void testMultipleTasksExecuteInOrder() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger executionOrder = new AtomicInteger(0);
        AtomicReference<String> results = new AtomicReference<>("");

        // Task 1 - add delay to ensure ordering
        AsyncTask<String> task1 = queue.enqueue((resolve, reject) -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int order = executionOrder.incrementAndGet();
            resolve.accept("task1-" + order);
        });

        // Task 2
        AsyncTask<String> task2 = queue.enqueue((resolve, reject) -> {
            int order = executionOrder.incrementAndGet();
            resolve.accept("task2-" + order);
        });

        // Task 3
        AsyncTask<String> task3 = queue.enqueue((resolve, reject) -> {
            int order = executionOrder.incrementAndGet();
            resolve.accept("task3-" + order);
        });

        // Collect results
        task1.then(value -> {
            results.updateAndGet(current -> current + value + ";");
            latch.countDown();
            return null;
        });

        task2.then(value -> {
            results.updateAndGet(current -> current + value + ";");
            latch.countDown();
            return null;
        });

        task3.then(value -> {
            results.updateAndGet(current -> current + value + ";");
            latch.countDown();
            return null;
        });

        assertTrue("All tasks should complete within timeout", latch.await(5, TimeUnit.SECONDS));

        // Verify tasks executed in order
        String finalResults = results.get();
        assertTrue("Task 1 should execute first", finalResults.contains("task1-1"));
        assertTrue("Task 2 should execute second", finalResults.contains("task2-2"));
        assertTrue("Task 3 should execute third", finalResults.contains("task3-3"));
    }

    @Test(timeout = 5000)
    public void testTaskWithException() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        AsyncTask<String> task = queue.enqueue((resolve, reject) -> {
            reject.accept(new RuntimeException("Test exception"));
        });

        task.catchException(ex -> {
            error.set(ex);
            latch.countDown();
        });

        assertTrue("Exception should be caught within timeout", latch.await(2, TimeUnit.SECONDS));
        assertNotNull(error.get());
        assertEquals("Test exception", error.get().getMessage());
    }

    @Test(timeout = 10000)
    public void testExceptionDoesNotBreakQueue() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<String> successResult = new AtomicReference<>();
        AtomicReference<Throwable> errorResult = new AtomicReference<>();

        // First task fails
        AsyncTask<String> failingTask = queue.enqueue((resolve, reject) -> {
            reject.accept(new RuntimeException("Test failure"));
        });

        // Second task should still execute
        AsyncTask<String> successTask = queue.enqueue((resolve, reject) -> {
            resolve.accept("success-after-failure");
        });

        failingTask.catchException(ex -> {
            errorResult.set(ex);
            latch.countDown();
        });

        successTask.then(value -> {
            successResult.set(value);
            latch.countDown();
            return null;
        });

        assertTrue("Both tasks should complete within timeout", latch.await(5, TimeUnit.SECONDS));
        assertNotNull("First task should have failed", errorResult.get());
        assertEquals("Second task should succeed", "success-after-failure", successResult.get());
    }

    @Test(timeout = 15000)
    public void testConcurrentEnqueue() throws Exception {
        final int numThreads = 3;
        final int tasksPerThread = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(numThreads * tasksPerThread);
        AtomicInteger completedTasks = new AtomicInteger(0);

        // Create multiple threads that enqueue tasks
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await();

                    for (int j = 0; j < tasksPerThread; j++) {
                        final int taskId = j;
                        AsyncTask<String> task = queue.enqueue((resolve, reject) -> {
                            resolve.accept("thread-" + threadId + "-task-" + taskId);
                        });

                        task.then(result -> {
                            completedTasks.incrementAndGet();
                            completeLatch.countDown();
                            return null;
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })
                .start();
        }

        // Start all threads
        startLatch.countDown();

        // Wait for all tasks to complete
        assertTrue("All concurrent tasks should complete", completeLatch.await(10, TimeUnit.SECONDS));
        assertEquals(numThreads * tasksPerThread, completedTasks.get());
    }

    @Test
    public void testClose() throws IOException {
        // Should not throw exception
        queue.close();

        // Should be safe to call multiple times
        queue.close();
    }

    @Test(timeout = 5000)
    public void testQueueIsEmpty() throws Exception {
        // Test that queue starts empty (no hanging tasks)
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

        AsyncTask<String> task = queue.enqueue((resolve, reject) -> {
            resolve.accept("immediate-result");
        });

        task.then(value -> {
            result.set(value);
            latch.countDown();
            return null;
        });

        assertTrue("Task should complete immediately", latch.await(1, TimeUnit.SECONDS));
        assertEquals("immediate-result", result.get());
    }

    @Test(timeout = 10000)
    public void testComplexTaskChaining() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

        AsyncTask<String> task1 = queue.enqueue((resolve, reject) -> {
            resolve.accept("step1");
        });

        AsyncTask<String> task2 = queue.enqueue((resolve, reject) -> {
            resolve.accept("step2");
        });

        AsyncTask<String> task3 = queue.enqueue((resolve, reject) -> {
            resolve.accept("step3");
        });

        // Chain the results
        task1.then(result1 -> {
            return task2.then(result2 -> {
                return task3.then(result3 -> {
                    result.set(result1 + "-" + result2 + "-" + result3);
                    latch.countDown();
                    return null;
                });
            });
        });

        assertTrue("Chained tasks should complete", latch.await(5, TimeUnit.SECONDS));
        assertEquals("step1-step2-step3", result.get());
    }

    @Test(timeout = 5000)
    public void testTaskComposition() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

        AsyncTask<String> composedTask = queue
            .enqueue((resolve, reject) -> {
                resolve.accept("initial");
            })
            .compose(initialResult -> {
                return queue.enqueue((resolve, reject) -> {
                    resolve.accept(initialResult + "-composed");
                });
            });

        composedTask.then(finalResult -> {
            result.set(finalResult);
            latch.countDown();
            return null;
        });

        assertTrue("Composed task should complete", latch.await(3, TimeUnit.SECONDS));
        assertEquals("initial-composed", result.get());
    }

    @Test(timeout = 10000)
    public void testRapidEnqueueingStressTest() throws Exception {
        final int numTasks = 10;
        CountDownLatch latch = new CountDownLatch(numTasks);
        AtomicInteger completedTasks = new AtomicInteger(0);
        AtomicInteger executedTasks = new AtomicInteger(0);

        // Rapidly enqueue many tasks
        for (int i = 0; i < numTasks; i++) {
            final int taskId = i;
            AsyncTask<String> task = queue.enqueue((resolve, reject) -> {
                int executed = executedTasks.incrementAndGet();
                System.out.println("Task " + taskId + " executing (total executed: " + executed + ")");

                // Add small delay to simulate work
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                resolve.accept("result-" + taskId);
            });

            task.then(result -> {
                int completed = completedTasks.incrementAndGet();
                System.out.println("Task completed: " + result + " (total completed: " + completed + ")");
                latch.countDown();
                return null;
            });
        }

        boolean allCompleted = latch.await(8, TimeUnit.SECONDS);

        System.out.println("Tasks executed: " + executedTasks.get());
        System.out.println("Tasks completed: " + completedTasks.get());
        System.out.println("All completed: " + allCompleted);

        assertTrue("All tasks should complete", allCompleted);
        assertEquals("All tasks should have executed", numTasks, executedTasks.get());
        assertEquals("All tasks should have completed", numTasks, completedTasks.get());
    }

    @Test(timeout = 5000)
    public void testVeryRapidEnqueueing() throws Exception {
        final int numTasks = 100;
        CountDownLatch latch = new CountDownLatch(numTasks);
        AtomicInteger completedTasks = new AtomicInteger(0);

        // Enqueue tasks as fast as possible
        for (int i = 0; i < numTasks; i++) {
            final int taskId = i;
            AsyncTask<String> task = queue.enqueue((resolve, reject) -> {
                resolve.accept("task-" + taskId);
            });

            task.then(result -> {
                completedTasks.incrementAndGet();
                latch.countDown();
                return null;
            });
        }

        assertTrue("All rapid tasks should complete", latch.await(4, TimeUnit.SECONDS));
        assertEquals(numTasks, completedTasks.get());
    }

    @Test(timeout = 5000)
    public void testMixedSuccessAndFailure() throws Exception {
        final int numTasks = 20;
        CountDownLatch latch = new CountDownLatch(numTasks);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < numTasks; i++) {
            final int taskId = i;
            AsyncTask<String> task = queue.enqueue((resolve, reject) -> {
                if (taskId % 2 == 0) {
                    resolve.accept("success-" + taskId);
                } else {
                    reject.accept(new RuntimeException("failure-" + taskId));
                }
            });

            task
                .then(result -> {
                    successes.incrementAndGet();
                    latch.countDown();
                    return null;
                })
                .catchException(error -> {
                    failures.incrementAndGet();
                    latch.countDown();
                });
        }

        assertTrue("All mixed tasks should complete", latch.await(4, TimeUnit.SECONDS));
        assertEquals(numTasks / 2, successes.get());
        assertEquals(numTasks / 2, failures.get());
    }

    @Test(timeout = 10000)
    public void testLongRunningTasks() throws Exception {
        final int numTasks = 5;
        CountDownLatch latch = new CountDownLatch(numTasks);
        AtomicInteger completedTasks = new AtomicInteger(0);

        for (int i = 0; i < numTasks; i++) {
            final int taskId = i;
            AsyncTask<String> task = queue.enqueue((resolve, reject) -> {
                try {
                    Thread.sleep(500); // Long running task
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                resolve.accept("long-task-" + taskId);
            });

            task.then(result -> {
                completedTasks.incrementAndGet();
                latch.countDown();
                return null;
            });
        }

        assertTrue("All long tasks should complete", latch.await(8, TimeUnit.SECONDS));
        assertEquals(numTasks, completedTasks.get());
    }

    @Test(timeout = 5000)
    public void testNestedEnqueueing() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        AtomicReference<String> results = new AtomicReference<>("");

        AsyncTask<String> task1 = queue.enqueue((resolve, reject) -> {
            resolve.accept("outer");
        });

        task1.then(result1 -> {
            results.updateAndGet(current -> current + result1 + "-");
            latch.countDown();

            // Enqueue another task from within a callback
            AsyncTask<String> task2 = queue.enqueue((resolve, reject) -> {
                resolve.accept("nested");
            });

            task2.then(result2 -> {
                results.updateAndGet(current -> current + result2 + "-");
                latch.countDown();

                // Even deeper nesting
                AsyncTask<String> task3 = queue.enqueue((resolve, reject) -> {
                    resolve.accept("deep");
                });

                task3.then(result3 -> {
                    results.updateAndGet(current -> current + result3);
                    latch.countDown();
                    return null;
                });

                return null;
            });

            return null;
        });

        assertTrue("Nested tasks should complete", latch.await(4, TimeUnit.SECONDS));
        assertEquals("outer-nested-deep", results.get());
    }
}
