package org.ngengine.platform.teavm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.teavm.junit.JsModuleTest;
import org.teavm.junit.ServeJS;
import org.teavm.junit.SkipJVM;
import org.teavm.junit.TeaVMTestRunner;

@RunWith(TeaVMTestRunner.class)
@JsModuleTest
@SkipJVM
public class TeaVMAsyncExecutorTest {
    @Test
    @ServeJS(from = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js", as = "org/ngengine/platform/teavm/TeaVMBinds.bundle.js")
    public void backgroundWorkersDoNotInheritInputPriorityIncludingWhenReused() throws Exception {
        TeaVMPlatform platform = new TeaVMPlatform();
        TeaVMAsyncExecutor executor = new TeaVMAsyncExecutor(platform);
        try {
            int[] priority = new int[3];
            Throwable[] failure = {null};
            Thread input = new Thread(() -> {
                try {
                    for (int i = 0; i < priority.length; i++) {
                        priority[i] = executor.run(() -> Thread.currentThread().getPriority()).await();
                    }
                } catch (Throwable error) {
                    failure[0] = error;
                }
            }, "test input");
            input.setPriority(Thread.MAX_PRIORITY);
            input.start();
            input.join();
            if (failure[0] != null) throw new AssertionError(failure[0]);
            for (int actual : priority) assertEquals(Thread.NORM_PRIORITY, actual);
        } finally {
            executor.close();
        }
    }
}
