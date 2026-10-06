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
package org.ngengine.platform.jvm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.Test;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.MemoryLimits;
import org.ngengine.platform.transport.WebsocketTransportListener;

/** Controlled handshakes and sockets: no HTTP or WebSocket connection is made. */
public class JVMWebsocketTransportLifecycleTest {

    // A numeric public address avoids DNS. The controlled builder performs no
    // network I/O; keep the real URI guard active.
    private static final String URL = "ws://8.8.8.8:80/lifecycle";

    @Test(timeout = 10000)
    public void closeBeforeOpenCancelsAttemptAndAbortsLateOpen() throws Exception {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connect = fixture.transport.connect(URL);
        Attempt first = fixture.client.attempts.get(0);

        fixture.transport.close("cancel pending").await();
        fixture.transport.close("duplicate").await();
        assertFailed(connect);
        assertTrue(first.future.isCancelled());
        assertEquals(1, first.future.cancelCalls);
        assertEquals(1, fixture.events.clientCloses);

        RecordingSocket late = new RecordingSocket();
        first.listener.onOpen(late);
        assertTrue(late.aborted.isDone());
        assertFalse(fixture.transport.isConnected());
        assertEquals(0, fixture.events.opens);
        assertEquals(0L, late.demand);
    }

    @Test(timeout = 10000)
    public void uncancellableLateCompletionIsAbortedWithoutRevival() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.ignoreCancellation = true;
        AsyncTask<Void> connect = fixture.transport.connect(URL);
        Attempt first = fixture.client.attempts.get(0);
        fixture.transport.close("cancel pending").await();

        RecordingSocket completion = new RecordingSocket();
        first.future.complete(completion); // also covers completion without onOpen
        first.listener.onOpen(completion);
        assertFailed(connect);
        assertEquals(1, first.future.cancelCalls);
        assertTrue(completion.aborted.isDone());
        assertFalse(fixture.transport.isConnected());
        assertEquals(0, fixture.events.opens);
    }

    @Test(timeout = 10000)
    public void closeDuringBuildCancelsFutureReturnedAfterClose() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.duringBuild = attempt -> fixture.transport.close("during build");
        AsyncTask<Void> connect = fixture.transport.connect(URL);
        Attempt first = fixture.client.attempts.get(0);
        assertFailed(connect);
        assertTrue(first.future.isCancelled());
        assertEquals(1, first.future.cancelCalls);
        RecordingSocket late = new RecordingSocket();
        first.listener.onOpen(late);
        assertTrue(late.aborted.isDone());
        assertFalse(fixture.transport.isConnected());
    }

    @Test(timeout = 10000)
    public void overlappingConnectRetiresPendingAttemptAndKeepsReplacement() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.ignoreCancellation = true;
        AsyncTask<Void> oldTask = fixture.transport.connect(URL);
        Attempt old = fixture.client.attempts.get(0);
        AsyncTask<Void> replacementTask = fixture.transport.connect(URL);
        Attempt replacement = fixture.client.attempts.get(1);
        assertFailed(oldTask);
        assertEquals(1, old.future.cancelCalls);

        RecordingSocket late = new RecordingSocket();
        old.listener.onOpen(late);
        old.future.complete(late);
        old.listener.onError(late, new IOException("stale pending error"));
        RecordingSocket current = new RecordingSocket();
        replacement.open(current);
        assertSucceeded(replacementTask);
        assertTrue(late.aborted.isDone());
        assertTrue(fixture.transport.isConnected());
        assertFalse(current.aborted.isDone());
        assertEquals(1, fixture.events.opens);
        assertEquals(0, fixture.events.errors);
    }

    @Test(timeout = 10000)
    public void oldCloseErrorAndMessagesCannotClearOrContaminateReplacement() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket oldSocket = fixture.connect();
        Attempt old = fixture.client.attempts.get(0);
        old.listener.onText(oldSocket, "old text fragment", false);
        old.listener.onBinary(oldSocket, ByteBuffer.wrap(new byte[] { 9 }), false);
        RecordingSocket current = fixture.connect();
        Attempt replacement = fixture.client.attempts.get(1);
        assertTrue(oldSocket.aborted.isDone());

        old.listener.onClose(oldSocket, 1006, "stale close");
        old.listener.onError(oldSocket, new IOException("stale error"));
        old.listener.onText(oldSocket, "stale text", true);
        old.listener.onBinary(oldSocket, ByteBuffer.wrap(new byte[] { 8 }), true);
        fixture.transport.onClose(oldSocket, 1006, "legacy stale close");
        fixture.transport.onError(oldSocket, new IOException("legacy stale error"));
        replacement.listener.onText(current, "new", true);
        replacement.listener.onBinary(current, ByteBuffer.wrap(new byte[] { 1, 2 }), true);
        fixture.transport.send("still current").await();

        assertTrue(fixture.transport.isConnected());
        assertFalse(current.aborted.isDone());
        assertEquals(Arrays.asList("new"), fixture.events.texts);
        assertEquals(1, fixture.events.binaries.size());
        assertArrayEquals(new byte[] { 1, 2 }, fixture.events.binaries.get(0));
        assertEquals(Arrays.asList("T:still current:true"), current.sent);
        assertEquals(0, fixture.events.serverCloses);
        assertEquals(0, fixture.events.errors);
    }

    @Test(timeout = 10000)
    public void legacyStaleErrorCannotRetirePendingReplacement() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket old = fixture.connect();
        AsyncTask<Void> replacement = fixture.transport.connect(URL);
        fixture.transport.onError(old, new IOException("old error while new is pending"));
        fixture.transport.onOpen(old);
        RecordingSocket current = new RecordingSocket();
        fixture.client.attempts.get(1).open(current);
        assertSucceeded(replacement);
        assertTrue(fixture.transport.isConnected());
        assertFalse(current.aborted.isDone());
        assertEquals(0, fixture.events.errors);
    }

    @Test(timeout = 10000)
    public void currentErrorAndServerCloseAbortAndPermitReconnect() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket first = fixture.connect();
        Attempt firstAttempt = fixture.client.attempts.get(0);
        firstAttempt.listener.onError(first, new IOException("lost"));
        assertFalse(fixture.transport.isConnected());
        assertTrue(first.aborted.isDone());
        assertEquals(1, fixture.events.errors);
        assertEquals(1, fixture.events.serverCloses);
        firstAttempt.listener.onClose(first, 1006, "duplicate");
        assertEquals(1, fixture.events.serverCloses);

        RecordingSocket second = fixture.connect();
        fixture.client.attempts.get(1).listener.onClose(second, 1000, "server done");
        assertTrue(second.aborted.isDone());
        assertFalse(fixture.transport.isConnected());
        assertEquals(2, fixture.events.serverCloses);
        RecordingSocket third = fixture.connect();
        fixture.transport.close("done").await();
        assertTrue(third.aborted.isDone());
        fixture.connect();
        assertTrue(fixture.transport.isConnected());
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void closeFromOpenListenerRetiresBeforeConnectCompletion() throws Exception {
        Fixture fixture = new Fixture();
        fixture.transport.addListener(new Events() {
            @Override
            public void onConnectionOpen() {
                fixture.transport.close("close inside open callback");
            }
        });
        AsyncTask<Void> connect = fixture.transport.connect(URL);
        RecordingSocket socket = new RecordingSocket();
        fixture.client.attempts.get(0).open(socket);
        assertFailed(connect);
        assertFalse(fixture.transport.isConnected());
        assertTrue(socket.aborted.isDone());
        assertEquals(1, fixture.events.opens);
        assertEquals(1, fixture.events.clientCloses);
        assertEquals(0L, socket.demand);
    }

    @Test(timeout = 10000)
    public void reconnectFromErrorListenerSurvivesOldCallbacks() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket old = fixture.connect();
        Attempt oldAttempt = fixture.client.attempts.get(0);
        List<AsyncTask<Void>> reconnects = new ArrayList<>();
        fixture.transport.addListener(new Events() {
            @Override
            public void onConnectionError(Throwable error) {
                reconnects.add(fixture.transport.connect(URL));
            }
        });
        oldAttempt.listener.onError(old, new IOException("current error"));
        RecordingSocket current = new RecordingSocket();
        fixture.client.attempts.get(1).open(current);
        assertSucceeded(reconnects.get(0));
        oldAttempt.listener.onClose(old, 1006, "late close after error reconnect");
        oldAttempt.listener.onError(old, new IOException("late error after reconnect"));
        assertEquals(1, reconnects.size());
        assertEquals(1, fixture.events.errors);
        assertTrue(old.aborted.isDone());
        assertTrue(fixture.transport.isConnected());
        assertFalse(current.aborted.isDone());
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void errorBeforeOpenRejectsConnectAndCancelsPendingBuild() {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connect = fixture.transport.connect(URL);
        Attempt attempt = fixture.client.attempts.get(0);
        attempt.listener.onError(null, new IOException("handshake error"));
        assertFailed(connect);
        assertTrue(attempt.future.isCancelled());
        assertEquals(1, fixture.events.errors);
        assertEquals(0, fixture.events.serverCloses);
        assertFalse(fixture.transport.isConnected());
    }

    @Test(timeout = 10000)
    public void asynchronousBuildFailureSettlesTask() {
        Fixture fixture = new Fixture();
        AsyncTask<Void> failed = fixture.transport.connect(URL);
        fixture.client.attempts.get(0).future.completeExceptionally(new CompletionException(new IOException("build")));
        assertFailed(failed);
        assertFalse(fixture.transport.isConnected());
    }

    @Test(timeout = 10000)
    public void completionBeforeOpenIsRetainedAndClosePreventsRevival() throws Exception {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connect = fixture.transport.connect(URL);
        Attempt first = fixture.client.attempts.get(0);
        RecordingSocket socket = new RecordingSocket();
        first.future.complete(socket);
        assertFalse("A successful connect must expose an opened, send-ready socket", connect.isDone());
        assertFalse(fixture.transport.isConnected());
        fixture.transport.close("completed before open").await();
        assertFailed(connect);
        assertTrue(socket.aborted.isDone());
        first.listener.onOpen(socket);
        assertFalse(fixture.transport.isConnected());
        assertEquals(0, fixture.events.opens);

        AsyncTask<Void> replacement = fixture.transport.connect(URL);
        Attempt second = fixture.client.attempts.get(1);
        RecordingSocket current = new RecordingSocket();
        second.future.complete(current);
        assertFalse(replacement.isDone());
        second.listener.onOpen(current);
        assertSucceeded(replacement);
        second.listener.onOpen(current); // duplicate open must not notify twice
        assertTrue(fixture.transport.isConnected());
        assertEquals(1, fixture.events.opens);
        assertFalse(current.aborted.isDone());
        fixture.transport.send("ready").await();
        assertEquals(List.of("T:ready:true"), current.sent);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void synchronousConnectAndSendFailuresSettleAndQueueRecovers() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.buildFailure = new IllegalStateException("synchronous build failure");
        assertFailed(fixture.transport.connect(URL));
        fixture.client.buildFailure = null;
        assertFailed(fixture.transport.connect("not a URI"));
        assertEquals(0, fixture.client.attempts.size());
        RecordingSocket socket = fixture.connect();

        socket.sendFailure = new IllegalStateException("synchronous text failure");
        assertFailed(fixture.transport.send("fails"));
        assertFailed(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 1 })));
        socket.sendFailure = null;
        socket.nextSend = CompletableFuture.failedFuture(new IOException("asynchronous send failure"));
        assertFailed(fixture.transport.send("also fails"));
        fixture.transport.send("recovers").await();
        fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 })).await();
        assertEquals(Arrays.asList("T:also fails:true", "T:recovers:true", "B:[2]:true"), socket.sent);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void rejectedSendExecutorSettlesPublicSendTask() throws Exception {
        Fixture fixture = new Fixture(command -> { throw new RejectedExecutionException("rejected"); });
        fixture.connect();
        assertFailed(fixture.transport.send("text"));
        assertFailed(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 1 })));
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void closeBypassesStalledQueueSettlesSendsAndIsIdempotent() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket socket = fixture.connect();
        socket.nextSend = new CompletableFuture<>();
        AsyncTask<Void> sending = fixture.transport.send("stalled");
        AsyncTask<Void> queued = fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 1 }));
        assertFalse(sending.isDone());
        assertFalse(queued.isDone());
        socket.closeFailure = new IllegalArgumentException("invalid close reason");

        fixture.transport.close("close without queue").await();
        fixture.transport.close("duplicate").await();
        assertFailed(sending);
        assertFailed(queued);
        assertTrue(socket.aborted.isDone());
        assertEquals(1, socket.closeCalls);
        assertEquals(1, fixture.events.clientCloses);
        assertEquals(Arrays.asList("T:stalled:true"), socket.sent);
        RecordingSocket current = fixture.connect();
        fixture.transport.send("fresh queue").await();
        assertEquals(Arrays.asList("T:fresh queue:true"), current.sent);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void gracefulCloseIsBoundedWhenCloseFutureNeverCompletes() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket socket = fixture.connect();
        socket.closeFuture = new CompletableFuture<>();
        fixture.transport.close("bounded close").await();
        assertFalse(fixture.transport.isConnected());
        // Observe physical cleanup with a bounded wait, not an unbounded await.
        socket.aborted.get(5, TimeUnit.SECONDS);
        assertEquals(1, socket.closeCalls);
        assertTrue(socket.isInputClosed());
        assertTrue(socket.isOutputClosed());
    }

    @Test(timeout = 10000)
    public void serverCloseBeforeBuildCompletionOwnsSocketUntilCloseSendFinishes() throws Exception {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connecting = fixture.transport.connect(URL);
        Attempt attempt = fixture.client.attempts.get(0);
        RecordingSocket socket = new RecordingSocket();
        socket.closeFuture = new CompletableFuture<>();
        attempt.listener.onOpen(socket);
        attempt.listener.onClose(socket, 1000, "early close");
        assertFailed(connecting);
        assertEquals(0, attempt.future.cancelCalls);
        assertEquals(1, socket.closeCalls);
        assertFalse(socket.aborted.isDone());

        // Neither build completion nor other late callbacks own physical cleanup.
        attempt.future.complete(socket);
        attempt.listener.onOpen(socket);
        attempt.listener.onClose(socket, 1000, "duplicate");
        fixture.transport.onError(socket, new IOException("late callback"));
        assertFalse(socket.aborted.isDone());
        assertFalse(fixture.transport.isConnected());
        socket.closeFuture.complete(socket);
        assertTrue(socket.aborted.isDone());
        assertEquals(1, fixture.events.serverCloses);
    }

    @Test(timeout = 10000)
    public void serverCloseDuringBuildPreservesFutureReturnedAfterRetirement() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket socket = new RecordingSocket();
        socket.closeFuture = new CompletableFuture<>();
        fixture.client.duringBuild = attempt -> {
            attempt.listener.onOpen(socket);
            attempt.listener.onClose(socket, 1000, "close before build returns");
        };
        AsyncTask<Void> connecting = fixture.transport.connect(URL);
        Attempt attempt = fixture.client.attempts.get(0);
        assertFailed(connecting);
        assertEquals(0, attempt.future.cancelCalls);
        assertFalse(socket.aborted.isDone());
        socket.closeFuture.complete(socket);
        assertTrue(socket.aborted.isDone());
        assertTrue(attempt.future.isCancelled());
        assertEquals(1, attempt.future.cancelCalls);
    }

    @Test(timeout = 10000)
    public void unrelatedLateBuildSocketIsAbortedWhileOwnedCloseContinues() throws Exception {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connecting = fixture.transport.connect(URL);
        Attempt attempt = fixture.client.attempts.get(0);
        RecordingSocket owned = new RecordingSocket();
        owned.closeFuture = new CompletableFuture<>();
        attempt.listener.onOpen(owned);
        attempt.listener.onClose(owned, 1000, "early close");
        assertFailed(connecting);
        RecordingSocket unrelated = new RecordingSocket();
        attempt.future.complete(unrelated);
        assertTrue(unrelated.aborted.isDone());
        assertFalse(owned.aborted.isDone());
        owned.closeFuture.complete(owned);
        assertTrue(owned.aborted.isDone());
    }

    @Test(timeout = 10000)
    public void earlyServerCloseTimeoutAbortsSocketAndCancelsPendingBuild() throws Exception {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connecting = fixture.transport.connect(URL);
        Attempt attempt = fixture.client.attempts.get(0);
        RecordingSocket socket = new RecordingSocket();
        socket.closeFuture = new CompletableFuture<>();
        attempt.listener.onOpen(socket);
        attempt.listener.onClose(socket, 1000, "stalled close send");
        assertFailed(connecting);
        assertFalse(attempt.future.isCancelled());
        // Future cancellation follows physical abort; wait for both observable effects.
        CompletableFuture<Void> cancelled = attempt.future.handle((value, error) -> null);
        cancelled.get(5, TimeUnit.SECONDS);
        assertTrue(socket.aborted.isDone());
        assertTrue(attempt.future.isCancelled());
        assertEquals(1, attempt.future.cancelCalls);
    }

    @Test(timeout = 10000)
    public void gracefulCleanupReleasesOwnershipAndCancelsBuildWhenAbortThrows() throws Exception {
        Fixture fixture = new Fixture();
        fixture.transport.connect(URL);
        Attempt attempt = fixture.client.attempts.get(0);
        RecordingSocket socket = new RecordingSocket();
        socket.closeFuture = new CompletableFuture<>();
        socket.abortFailure = new IllegalStateException("abort failure");
        attempt.listener.onOpen(socket);
        attempt.listener.onClose(socket, 1000, "early close");
        socket.closeFuture.complete(socket);
        assertTrue(attempt.future.isCancelled());
        assertEquals(1, attempt.future.cancelCalls);
        // Ownership was released even though physical abort threw.
        socket.abortFailure = null;
        attempt.listener.onOpen(socket);
        assertTrue(socket.aborted.isDone());
    }

    @Test(timeout = 10000)
    public void replacementDoesNotPreemptOwnedGracefulClose() throws Exception {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connecting = fixture.transport.connect(URL);
        Attempt oldAttempt = fixture.client.attempts.get(0);
        RecordingSocket old = new RecordingSocket();
        old.closeFuture = new CompletableFuture<>();
        oldAttempt.listener.onOpen(old);
        oldAttempt.listener.onClose(old, 1000, "early close");
        assertFailed(connecting);
        RecordingSocket current = fixture.connect();
        oldAttempt.future.complete(old);
        oldAttempt.listener.onText(old, "stale", true);
        fixture.transport.onOpen(old);
        assertFalse(old.aborted.isDone());
        assertFalse(current.aborted.isDone());
        old.closeFuture.complete(old);
        assertTrue(old.aborted.isDone());
        fixture.transport.send("replacement survives").await();
        assertEquals(List.of("T:replacement survives:true"), current.sent);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void replacementAndErrorStillAbortAcquiredPendingSocketsImmediately() throws Exception {
        Fixture fixture = new Fixture();
        AsyncTask<Void> first = fixture.transport.connect(URL);
        Attempt firstAttempt = fixture.client.attempts.get(0);
        RecordingSocket firstSocket = new RecordingSocket();
        firstAttempt.listener.onOpen(firstSocket);
        AsyncTask<Void> second = fixture.transport.connect(URL);
        assertFailed(first);
        assertTrue(firstAttempt.future.isCancelled());
        assertTrue(firstSocket.aborted.isDone());
        assertEquals(0, firstSocket.closeCalls);

        Attempt secondAttempt = fixture.client.attempts.get(1);
        RecordingSocket secondSocket = new RecordingSocket();
        secondAttempt.listener.onOpen(secondSocket);
        secondAttempt.listener.onError(secondSocket, new IOException("failed before build completes"));
        assertFailed(second);
        assertTrue(secondAttempt.future.isCancelled());
        assertTrue(secondSocket.aborted.isDone());
        assertEquals(0, secondSocket.closeCalls);
    }

    @Test(timeout = 10000)
    public void reentrantReconnectDuringRetirementIsNotOverwritten() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket old = fixture.connect();
        old.nextSend = new CompletableFuture<>();
        AsyncTask<Void> stalled = fixture.transport.send("stalled");
        List<AsyncTask<Void>> reentrant = new ArrayList<>();
        stalled.catchException(error -> {
            assertTrue("physical cleanup starts before user continuations", old.aborted.isDone());
            reentrant.add(fixture.transport.connect(URL));
        });
        AsyncTask<Void> superseded = fixture.transport.connect(URL);
        assertFailed(superseded);
        assertEquals(1, reentrant.size());
        assertEquals(2, fixture.client.attempts.size());
        RecordingSocket current = new RecordingSocket();
        fixture.client.attempts.get(1).open(current);
        assertSucceeded(reentrant.get(0));
        assertTrue(old.aborted.isDone());
        assertTrue(fixture.transport.isConnected());
        assertFalse(current.aborted.isDone());
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void mixedTextBinaryFragmentsPreserveOrderBoundsAndCallerBuffers() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket socket = fixture.connect();
        fixture.transport.setMaxMessageSize(2);
        CompletableFuture<WebSocket> firstSend = new CompletableFuture<>();
        socket.nextSend = firstSend;
        AsyncTask<Void> text = fixture.transport.send("abc");
        ByteBuffer binary = ByteBuffer.wrap(new byte[] { 0, 1, 2, 3, 4 });
        binary.position(1);
        AsyncTask<Void> bytes = fixture.transport.sendBinary(binary);
        AsyncTask<Void> tail = fixture.transport.send("z");
        assertEquals(Arrays.asList("T:ab:false"), socket.sent);
        firstSend.complete(socket);
        assertSucceeded(text);
        assertSucceeded(bytes);
        assertSucceeded(tail);
        assertEquals(1, binary.position());
        assertEquals(Arrays.asList("T:ab:false", "T:c:true", "B:[1, 2]:false", "B:[3, 4]:true", "T:z:true"), socket.sent);

        Attempt attempt = fixture.client.attempts.get(0);
        attempt.listener.onText(socket, "a", false);
        attempt.listener.onText(socket, "b", true);
        attempt.listener.onBinary(socket, ByteBuffer.wrap(new byte[] { 1 }), false);
        attempt.listener.onBinary(socket, ByteBuffer.wrap(new byte[] { 2 }), true);
        assertEquals(Arrays.asList("ab"), fixture.events.texts);
        assertArrayEquals(new byte[] { 1, 2 }, fixture.events.binaries.get(0));
        assertTrue(fixture.events.binaryReadOnly);
        expectIllegalArgument(() -> attempt.listener.onText(socket, "abc", true));
        expectIllegalArgument(() -> attempt.listener.onBinary(socket, ByteBuffer.wrap(new byte[] { 1, 2, 3 }), true));
        fixture.transport.setMaxMessageSize(-1);
        fixture.platform.transportLimit = 2;
        expectIllegalArgument(() -> attempt.listener.onText(socket, "abc", true));
        fixture.platform.transportLimit = 0;
        assertFailed(fixture.transport.send("invalid memory limit"));
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void failedRetriesAcrossTransportsReuseOnePlatformClient() throws Exception {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 128; i++) {
            JVMWebsocketTransport transport = new JVMWebsocketTransport(fixture.platform, Runnable::run);
            assertSame(fixture.client, clientOf(transport));
            AsyncTask<Void> retry = transport.connect(URL);
            fixture.client.attempts.get(i).future.completeExceptionally(new IOException("retry failure"));
            assertFailed(retry);
            transport.close("failed retry cleanup").await();
        }
        assertEquals(128, fixture.client.attempts.size());
    }

    @Test(timeout = 10000)
    public void clientReuseIsPerPlatformAndKeepsSecurityConfiguration() throws Exception {
        JVMAsyncPlatform first = new JVMAsyncPlatform();
        JVMAsyncPlatform second = new JVMAsyncPlatform();
        HttpClient client = first.getWebsocketHttpClient();
        HttpClient other = second.getWebsocketHttpClient();
        try {
            for (int i = 0; i < 128; i++) {
                JVMWebsocketTransport transport = new JVMWebsocketTransport(first, Runnable::run);
                assertSame(client, clientOf(transport));
                transport.close("unused").await();
            }
            assertSame(client, first.getWebsocketHttpClient());
            assertNotSame(client, other);
            assertEquals(Optional.of(Duration.ofSeconds(2)), client.connectTimeout());
            assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
            assertSame(first.executor, client.executor().orElseThrow());
        } finally {
            client.shutdownNow();
            other.shutdownNow();
            first.executor.shutdownNow();
            second.executor.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void binaryListenerDoesNotInvertChannelAndLifecycleLocks() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket socket = fixture.connect();
        CrossedLock crossed = new CrossedLock(fixture.transport);
        fixture.transport.addListener(new Events() {
            @Override
            public void onConnectionBinaryMessage(ByteBuffer message) { crossed.callback(); }
        });
        runCrossedLock(crossed,
            () -> fixture.client.attempts.get(0).listener.onBinary(socket, ByteBuffer.wrap(new byte[] { 1 }), true),
            () -> assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 }))));
        assertArrayEquals(new byte[] { 1 }, fixture.events.binaries.get(0));
        assertEquals(List.of("B:[2]:true"), socket.sent);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void textListenerDoesNotInvertChannelAndLifecycleLocks() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket socket = fixture.connect();
        CrossedLock crossed = new CrossedLock(fixture.transport);
        fixture.transport.addListener(new Events() {
            @Override
            public void onConnectionMessage(String message) { crossed.callback(); }
        });
        runCrossedLock(crossed,
            () -> fixture.client.attempts.get(0).listener.onText(socket, "received", true),
            () -> assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 }))));
        assertEquals(List.of("received"), fixture.events.texts);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void blockedOpenListenerAllowsSendButDefersConnectSuccess() throws Exception {
        Fixture fixture = new Fixture();
        CrossedLock crossed = new CrossedLock(fixture.transport);
        fixture.transport.addListener(new Events() {
            @Override
            public void onConnectionOpen() { crossed.callback(); }
        });
        AsyncTask<Void> connecting = fixture.transport.connect(URL);
        Attempt attempt = fixture.client.attempts.get(0);
        RecordingSocket socket = new RecordingSocket();
        attempt.future.complete(socket);
        runCrossedLock(crossed, () -> attempt.listener.onOpen(socket), () -> {
            assertFalse("open callbacks and initial demand precede connect success", connecting.isDone());
            assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 })));
        });
        assertSucceeded(connecting);
        assertEquals(1L, socket.demand);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void blockedBinaryListenerCannotDeliverTailOrDemandToReplacement() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket old = fixture.connect();
        Attempt oldAttempt = fixture.client.attempts.get(0);
        CrossedLock crossed = new CrossedLock(fixture.transport);
        AtomicInteger tail = new AtomicInteger();
        fixture.transport.addListener(new Events() {
            @Override
            public void onConnectionBinaryMessage(ByteBuffer message) { crossed.callback(); }
        });
        fixture.transport.addListener(new Events() {
            @Override
            public void onConnectionBinaryMessage(ByteBuffer message) { tail.incrementAndGet(); }
        });
        AtomicReference<RecordingSocket> replacement = new AtomicReference<>();
        runCrossedLock(crossed,
            () -> oldAttempt.listener.onBinary(old, ByteBuffer.wrap(new byte[] { 1 }), true),
            () -> {
                oldAttempt.listener.onText(old, "queued old message", true);
                oldAttempt.listener.onBinary(old, ByteBuffer.wrap(new byte[] { 9 }), false);
                replacement.set(fixture.connect());
                oldAttempt.listener.onClose(old, 1006, "stale close");
                oldAttempt.listener.onError(old, new IOException("stale error"));
                oldAttempt.listener.onText(old, "stale text", true);
                oldAttempt.listener.onBinary(old, ByteBuffer.wrap(new byte[] { 8 }), true);
                assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 })));
            });
        assertEquals(0, tail.get());
        assertEquals(1L, old.demand);
        assertTrue(old.aborted.isDone());
        assertFalse(replacement.get().aborted.isDone());
        assertEquals(List.of(), fixture.events.texts);
        assertEquals(0, fixture.events.errors);
        assertEquals(0, fixture.events.serverCloses);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void blockedClientCloseListenerAllowsReplacementAndSend() throws Exception {
        assertTerminalListenerAllowsReplacement("client close");
    }

    @Test(timeout = 10000)
    public void blockedServerCloseListenerAllowsReplacementAndSend() throws Exception {
        assertTerminalListenerAllowsReplacement("server close");
    }

    @Test(timeout = 10000)
    public void blockedErrorListenerSuppressesOldCloseAfterReplacement() throws Exception {
        assertTerminalListenerAllowsReplacement("error");
    }

    private static void assertTerminalListenerAllowsReplacement(String kind) throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket old = fixture.connect();
        Attempt attempt = fixture.client.attempts.get(0);
        CrossedLock crossed = new CrossedLock(fixture.transport);
        Events blocking = new Events() {
            @Override
            public void onConnectionClosedByClient(String reason) {
                if (kind.equals("client close")) { crossed.callback(); }
            }

            @Override
            public void onConnectionClosedByServer(String reason) {
                if (kind.equals("server close")) { crossed.callback(); }
            }

            @Override
            public void onConnectionError(Throwable error) {
                if (kind.equals("error")) { crossed.callback(); }
            }
        };
        fixture.transport.addListener(blocking);
        AtomicReference<RecordingSocket> replacement = new AtomicReference<>();
        runCrossedLock(crossed, () -> {
            if (kind.equals("client close")) {
                fixture.transport.close("client");
            } else if (kind.equals("server close")) {
                attempt.listener.onClose(old, 1000, "server");
            } else {
                attempt.listener.onError(old, new IOException("lost"));
            }
        }, () -> {
            assertTrue("physical cleanup precedes notification", old.aborted.isDone());
            replacement.set(fixture.connect());
            assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 })));
        });
        assertTrue(fixture.transport.isConnected());
        assertFalse(replacement.get().aborted.isDone());
        if (kind.equals("error")) {
            assertEquals(1, fixture.events.errors);
            assertEquals(0, fixture.events.serverCloses);
        }
        fixture.transport.removeListener(blocking);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void connectCompletionContinuationDoesNotHoldLifecycleMonitor() throws Exception {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connecting = fixture.transport.connect(URL);
        Attempt attempt = fixture.client.attempts.get(0);
        RecordingSocket socket = new RecordingSocket();
        attempt.listener.onOpen(socket);
        CrossedLock crossed = new CrossedLock(fixture.transport);
        AsyncTask<Void> continuation = connecting.then(value -> {
            crossed.callback();
            return null;
        });
        runCrossedLock(crossed, () -> attempt.future.complete(socket),
            () -> assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 }))));
        assertSucceeded(continuation);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void retirementSendContinuationAllowsReplacementAndSend() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket old = fixture.connect();
        old.nextSend = new CompletableFuture<>();
        AsyncTask<Void> sending = fixture.transport.send("stalled");
        CrossedLock crossed = new CrossedLock(fixture.transport);
        sending.catchException(error -> crossed.callback());
        runCrossedLock(crossed, () -> fixture.transport.close("retire"), () -> {
            assertTrue(old.aborted.isDone());
            fixture.connect();
            assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 })));
        });
        assertFailed(sending);
        assertTrue(fixture.transport.isConnected());
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void retirementConnectContinuationAllowsReplacementAndSend() throws Exception {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connecting = fixture.transport.connect(URL);
        CrossedLock crossed = new CrossedLock(fixture.transport);
        connecting.catchException(error -> crossed.callback());
        runCrossedLock(crossed, () -> fixture.transport.close("retire pending"), () -> {
            fixture.connect();
            assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 })));
        });
        assertFailed(connecting);
        assertTrue(fixture.transport.isConnected());
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void inlineEnqueueTaskDoesNotHoldLifecycleMonitor() throws Exception {
        Fixture fixture = new Fixture();
        fixture.connect();
        CrossedLock crossed = new CrossedLock(fixture.transport);
        AtomicReference<AsyncTask<Void>> queued = new AtomicReference<>();
        runCrossedLock(crossed, () -> fixture.transport.<Void>enqueue((resolve, reject) -> {
            crossed.callback();
            resolve.accept(null);
        }), () -> {
            queued.set(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 })));
            assertFalse("the next send waits for the active task", queued.get().isDone());
        });
        assertSucceeded(queued.get());
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void sendCompletionContinuationDoesNotHoldLifecycleMonitor() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket socket = fixture.connect();
        CompletableFuture<WebSocket> pending = new CompletableFuture<>();
        socket.nextSend = pending;
        AsyncTask<Void> sending = fixture.transport.send("pending");
        CrossedLock crossed = new CrossedLock(fixture.transport);
        AsyncTask<Void> continuation = sending.then(value -> {
            crossed.callback();
            return null;
        });
        AtomicReference<AsyncTask<Void>> queued = new AtomicReference<>();
        runCrossedLock(crossed, () -> pending.complete(socket),
            () -> queued.set(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 }))));
        assertSucceeded(continuation);
        assertSucceeded(queued.get());
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void initialRequestCallbackAllowsReplacementWithoutConnectRevival() throws Exception {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connecting = fixture.transport.connect(URL);
        Attempt attempt = fixture.client.attempts.get(0);
        RecordingSocket old = new RecordingSocket();
        attempt.future.complete(old);
        CrossedLock crossed = new CrossedLock(fixture.transport);
        old.requestCallback = crossed::callback;
        AtomicReference<RecordingSocket> replacement = new AtomicReference<>();
        runCrossedLock(crossed, () -> attempt.listener.onOpen(old), () -> {
            replacement.set(fixture.connect());
            assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 })));
        });
        assertFailed(connecting);
        assertTrue(old.aborted.isDone());
        assertFalse(replacement.get().aborted.isDone());
        assertTrue(fixture.transport.isConnected());
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void blockedReceiveCallbackPreservesAdmittedTextBinaryOrder() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket socket = fixture.connect();
        Attempt attempt = fixture.client.attempts.get(0);
        CrossedLock crossed = new CrossedLock(fixture.transport);
        List<String> order = new ArrayList<>();
        fixture.transport.addListener(new Events() {
            @Override
            public void onConnectionMessage(String message) {
                order.add("T:" + message);
                if (message.equals("first")) { crossed.callback(); }
            }

            @Override
            public void onConnectionBinaryMessage(ByteBuffer message) {
                order.add("B:" + message.get());
            }
        });
        runCrossedLock(crossed, () -> attempt.listener.onText(socket, "first", true), () -> {
            attempt.listener.onBinary(socket, ByteBuffer.wrap(new byte[] { 1 }), false);
            attempt.listener.onBinary(socket, ByteBuffer.wrap(new byte[] { 2 }), true);
            attempt.listener.onText(socket, "ta", false);
            attempt.listener.onText(socket, "il", true);
            assertEquals(List.of("T:first"), order);
            assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 3 })));
        });
        assertEquals(List.of("T:first", "B:1", "T:tail"), order);
        assertArrayEquals(new byte[] { 1, 2 }, fixture.events.binaries.get(0));
        assertEquals(6L, socket.demand);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void abortCompletionContinuationDoesNotHoldLifecycleMonitor() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket old = fixture.connect();
        CrossedLock crossed = new CrossedLock(fixture.transport);
        CompletableFuture<Void> continuation = old.aborted.thenRun(crossed::callback);
        runCrossedLock(crossed, () -> fixture.transport.close("retire socket"), () -> {
            fixture.connect();
            assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 })));
        });
        assertTrue(continuation.isDone());
        assertFalse(continuation.isCompletedExceptionally());
        assertTrue(fixture.transport.isConnected());
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void pongRequestCallbackDoesNotHoldLifecycleMonitor() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket socket = fixture.connect();
        CrossedLock crossed = new CrossedLock(fixture.transport);
        socket.requestCallback = crossed::callback;
        runCrossedLock(crossed,
            () -> fixture.client.attempts.get(0).listener.onPong(socket, ByteBuffer.allocate(0)),
            () -> assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 }))));
        assertEquals(2L, socket.demand);
        socket.requestCallback = null;
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void pingRequestCallbackDoesNotHoldLifecycleMonitor() throws Exception {
        Fixture fixture = new Fixture();
        RecordingSocket socket = fixture.connect();
        CrossedLock crossed = new CrossedLock(fixture.transport);
        socket.requestCallback = crossed::callback;
        runCrossedLock(crossed,
            () -> fixture.client.attempts.get(0).listener.onPing(socket, ByteBuffer.allocate(0)),
            () -> assertSucceeded(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 }))));
        assertEquals(2L, socket.demand);
        socket.requestCallback = null;
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void queuedExecutorRejectionContinuationDoesNotHoldLifecycleMonitor() throws Exception {
        AtomicInteger scheduled = new AtomicInteger();
        Fixture fixture = new Fixture(command -> {
            if (scheduled.incrementAndGet() == 1) {
                command.run();
            } else {
                throw new RejectedExecutionException("controlled rejection");
            }
        });
        RecordingSocket socket = fixture.connect();
        CompletableFuture<WebSocket> pending = new CompletableFuture<>();
        socket.nextSend = pending;
        AsyncTask<Void> sending = fixture.transport.send("pending");
        AsyncTask<Void> queued = fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 1 }));
        CrossedLock crossed = new CrossedLock(fixture.transport);
        queued.catchException(error -> crossed.callback());
        runCrossedLock(crossed, () -> pending.complete(socket),
            () -> assertFailed(fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 }))));
        assertSucceeded(sending);
        assertFailed(queued);
        fixture.transport.close("cleanup").await();
    }

    @Test(timeout = 10000)
    public void retiredScheduledSendCannotRunOrRemoveReplacementQueue() throws Exception {
        List<Runnable> scheduled = new ArrayList<>();
        Fixture fixture = new Fixture(scheduled::add);
        RecordingSocket old = fixture.connect();
        AsyncTask<Void> retiredSend = fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 1 }));
        RecordingSocket current = fixture.connect();
        AsyncTask<Void> currentSend = fixture.transport.sendBinary(ByteBuffer.wrap(new byte[] { 2 }));
        assertFailed(retiredSend);
        assertFalse(currentSend.isDone());
        assertEquals(2, scheduled.size());
        scheduled.get(0).run();
        assertFalse(currentSend.isDone());
        assertEquals(List.of(), old.sent);
        scheduled.get(1).run();
        assertSucceeded(currentSend);
        assertEquals(List.of("B:[2]:true"), current.sent);
        assertFalse(current.aborted.isDone());
        fixture.transport.close("cleanup").await();
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private static final class CrossedLock {
        // Interruptible channel ownership models the TURN lock edge and lets
        // failure cleanup release a regressed transport's intrinsic monitor.
        private final ReentrantLock channel = new ReentrantLock();
        private final CountDownLatch channelHeld = new CountDownLatch(1);
        private final CountDownLatch callbackEntered = new CountDownLatch(1);
        private final CountDownLatch callbackFinished = new CountDownLatch(1);
        private final CountDownLatch controlFinished = new CountDownLatch(1);
        private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        private final AtomicReference<Throwable> controlFailure = new AtomicReference<>();
        private final Object lifecycleMonitor;

        private CrossedLock(JVMWebsocketTransport transport) throws Exception {
            Field field = JVMWebsocketTransport.class.getDeclaredField("lifecycleMonitor");
            field.setAccessible(true);
            lifecycleMonitor = field.get(transport);
        }

        private void callback() {
            callbackEntered.countDown();
            try {
                assertFalse("external callback holds lifecycle monitor", Thread.holdsLock(lifecycleMonitor));
                channel.lockInterruptibly();
                try {
                    assertTrue("callback acquires channel after control send", controlFinished.getCount() == 0L);
                } finally {
                    channel.unlock();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                AssertionError failure = new AssertionError("callback interrupted during bounded failure cleanup", error);
                callbackFailure.compareAndSet(null, failure);
                throw failure;
            } catch (Throwable error) {
                callbackFailure.compareAndSet(null, error);
                throw error;
            }
        }
    }

    private static void runCrossedLock(CrossedLock crossed, CheckedAction emit, CheckedAction control) throws Exception {
        Thread channelOwner = new Thread(() -> {
            crossed.channel.lock();
            try {
                crossed.channelHeld.countDown();
                assertTrue("external callback starts", crossed.callbackEntered.await(2, TimeUnit.SECONDS));
                control.run();
            } catch (Throwable error) {
                crossed.controlFailure.set(error);
            } finally {
                crossed.controlFinished.countDown();
                crossed.channel.unlock();
            }
        }, "lifecycle-channel-owner");
        Thread callbackOwner = new Thread(() -> {
            try {
                assertTrue("channel is held before callback", crossed.channelHeld.await(2, TimeUnit.SECONDS));
                emit.run();
            } catch (Throwable error) {
                crossed.callbackFailure.set(error);
            } finally {
                crossed.callbackFinished.countDown();
            }
        }, "lifecycle-callback-owner");
        channelOwner.setDaemon(true);
        callbackOwner.setDaemon(true);
        try {
            channelOwner.start();
            callbackOwner.start();
            assertTrue("channel-held control path must finish while callback waits", crossed.controlFinished.await(2, TimeUnit.SECONDS));
            assertTrue("callback must finish after channel release", crossed.callbackFinished.await(2, TimeUnit.SECONDS));
            assertEquals("control failure", null, crossed.controlFailure.get());
            assertEquals("callback failure", null, crossed.callbackFailure.get());
        } finally {
            // No unbounded join, await or leaked lock on assertion failure.
            callbackOwner.interrupt();
            channelOwner.interrupt();
            callbackOwner.join(2000L);
            channelOwner.join(2000L);
            assertFalse("callback worker must stop", callbackOwner.isAlive());
            assertFalse("control worker must stop", channelOwner.isAlive());
        }
    }

    // Share a controlled, no-I/O public connect/open path with the stress
    // fixtures instead of inventing connected state with reflection.
    static JVMWebsocketTransport connectedTransport(WebSocket socket) {
        Fixture fixture = new Fixture();
        AsyncTask<Void> connecting = fixture.transport.connect(URL);
        Attempt attempt = fixture.client.attempts.get(0);
        attempt.listener.onOpen(socket);
        attempt.future.complete(socket);
        assertSucceeded(connecting);
        return fixture.transport;
    }

    private static HttpClient clientOf(JVMWebsocketTransport transport) throws Exception {
        Field field = JVMWebsocketTransport.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        return (HttpClient) field.get(transport);
    }

    private static void assertFailed(AsyncTask<?> task) {
        assertTrue("task must settle", task.isDone());
        assertTrue("task must fail", task.isFailed());
    }

    private static void assertSucceeded(AsyncTask<?> task) {
        assertTrue("task must settle", task.isDone());
        assertTrue("task must succeed", task.isSuccess());
    }

    private static void expectIllegalArgument(Runnable action) {
        boolean thrown = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        assertTrue("message bounds must be enforced", thrown);
    }

    private static final class Fixture {
        private final ControlledClient client = new ControlledClient();
        private final TestPlatform platform = new TestPlatform(client);
        private final Events events = new Events();
        private final JVMWebsocketTransport transport;

        private Fixture() {
            this(Runnable::run);
        }

        private Fixture(Executor executor) {
            transport = new JVMWebsocketTransport(platform, executor);
            transport.addListener(events);
        }

        private RecordingSocket connect() {
            AsyncTask<Void> task = transport.connect(URL);
            RecordingSocket socket = new RecordingSocket();
            client.attempts.get(client.attempts.size() - 1).open(socket);
            assertSucceeded(task);
            return socket;
        }
    }

    private static final class TestPlatform extends JVMAsyncPlatform {
        private final HttpClient client;
        private long transportLimit = 64L * 1024L * 1024L;

        private TestPlatform(HttpClient client) {
            this.client = client;
        }

        @Override
        HttpClient getWebsocketHttpClient() {
            return client;
        }

        @Override
        public MemoryLimits getMemoryLimits() {
            return new MemoryLimits() {
                @Override
                public long getTransportLimit() {
                    return transportLimit;
                }
            };
        }
    }

    private static class Events implements WebsocketTransportListener {
        private int opens;
        private int clientCloses;
        private int serverCloses;
        private int errors;
        private final List<String> texts = new ArrayList<>();
        private final List<byte[]> binaries = new ArrayList<>();
        private boolean binaryReadOnly = true;

        @Override
        public void onConnectionOpen() { opens++; }

        @Override
        public void onConnectionClosedByClient(String reason) { clientCloses++; }

        @Override
        public void onConnectionClosedByServer(String reason) { serverCloses++; }

        @Override
        public void onConnectionError(Throwable error) { errors++; }

        @Override
        public void onConnectionMessage(String message) { texts.add(message); }

        @Override
        public void onConnectionBinaryMessage(ByteBuffer message) {
            binaryReadOnly &= message.isReadOnly();
            byte[] copy = new byte[message.remaining()];
            message.get(copy);
            binaries.add(copy);
        }
    }

    private static final class ConnectFuture extends CompletableFuture<WebSocket> {
        private final boolean ignoreCancellation;
        private int cancelCalls;

        private ConnectFuture(boolean ignoreCancellation) {
            this.ignoreCancellation = ignoreCancellation;
        }

        @Override
        public boolean cancel(boolean interrupt) {
            cancelCalls++;
            return !ignoreCancellation && super.cancel(interrupt);
        }
    }

    private static final class Attempt {
        private final WebSocket.Listener listener;
        private final ConnectFuture future;

        private Attempt(WebSocket.Listener listener, boolean ignoreCancellation) {
            this.listener = listener;
            future = new ConnectFuture(ignoreCancellation);
        }

        private void open(RecordingSocket socket) {
            listener.onOpen(socket);
            future.complete(socket);
        }
    }

    private static final class ControlledClient extends HttpClient {
        private final List<Attempt> attempts = new ArrayList<>();
        private boolean ignoreCancellation;
        private RuntimeException buildFailure;
        private Consumer<Attempt> duringBuild;

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            return new WebSocket.Builder() {
                private Duration timeout;

                @Override
                public WebSocket.Builder header(String name, String value) { return this; }

                @Override
                public WebSocket.Builder connectTimeout(Duration value) {
                    timeout = value;
                    return this;
                }

                @Override
                public WebSocket.Builder subprotocols(String first, String... rest) { return this; }

                @Override
                public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
                    assertEquals(Duration.ofSeconds(2), timeout);
                    assertEquals(URI.create(URL), uri);
                    if (buildFailure != null) {
                        throw buildFailure;
                    }
                    Attempt attempt = new Attempt(listener, ignoreCancellation);
                    attempts.add(attempt);
                    if (duringBuild != null) {
                        duringBuild.accept(attempt);
                    }
                    return attempt.future;
                }
            };
        }

        @Override
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }

        @Override
        public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(2)); }

        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }

        @Override
        public Optional<ProxySelector> proxy() { return Optional.empty(); }

        @Override
        public SSLContext sslContext() { throw new UnsupportedOperationException(); }

        @Override
        public SSLParameters sslParameters() { return new SSLParameters(); }

        @Override
        public Optional<Authenticator> authenticator() { return Optional.empty(); }

        @Override
        public Version version() { return Version.HTTP_1_1; }

        @Override
        public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException("HTTP is outside this fixture");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException("HTTP is outside this fixture");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler
        ) {
            throw new UnsupportedOperationException("HTTP is outside this fixture");
        }
    }

    private static final class RecordingSocket implements WebSocket {
        private final List<String> sent = new ArrayList<>();
        private final CompletableFuture<Void> aborted = new CompletableFuture<>();
        private CompletableFuture<WebSocket> nextSend;
        private CompletableFuture<WebSocket> closeFuture;
        private RuntimeException sendFailure;
        private RuntimeException closeFailure;
        private RuntimeException abortFailure;
        private int closeCalls;
        private long demand;
        private boolean outputClosed;
        private Runnable requestCallback;
        private Runnable pongCallback;

        private CompletableFuture<WebSocket> sendResult() {
            CompletableFuture<WebSocket> result = nextSend;
            nextSend = null;
            return result != null ? result : CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            if (sendFailure != null) { throw sendFailure; }
            sent.add("T:" + data + ":" + last);
            return sendResult();
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            if (sendFailure != null) { throw sendFailure; }
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            sent.add("B:" + Arrays.toString(copy) + ":" + last);
            return sendResult();
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer data) { return CompletableFuture.completedFuture(this); }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer data) {
            if (pongCallback != null) { pongCallback.run(); }
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int status, String reason) {
            closeCalls++;
            if (closeFailure != null) { throw closeFailure; }
            outputClosed = true;
            return closeFuture != null ? closeFuture : CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long count) {
            demand += count;
            if (requestCallback != null) { requestCallback.run(); }
        }

        @Override
        public String getSubprotocol() { return ""; }

        @Override
        public boolean isInputClosed() { return aborted.isDone(); }

        @Override
        public boolean isOutputClosed() { return outputClosed || aborted.isDone(); }

        @Override
        public void abort() {
            if (abortFailure != null) { throw abortFailure; }
            aborted.complete(null);
        }
    }
}
