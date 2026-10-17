package org.ngengine.platform;

import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.*;
import org.junit.Test;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

public class AsyncCancellationLoggingTest {
    @Test public void cancellationStillRejectsWithoutAnErrorWarning() throws Exception {
        var platform=new JVMAsyncPlatform();var warnings=new CopyOnWriteArrayList<LogRecord>();
        var logger=Logger.getLogger(NGEPlatform.class.getName());
        var handler=new Handler(){public void publish(LogRecord record){if(record.getLevel().intValue()>=Level.WARNING.intValue())warnings.add(record);}public void flush(){}public void close(){}};
        logger.addHandler(handler);
        try {
            var cancelled=platform.<String>wrapPromise((resolve,reject)->reject.accept(new CancellationException("Disposable read cancelled")));
            assertThrows(Exception.class,()->platform.awaitAll(List.of(cancelled)).await());
            assertThrows(Exception.class,()->platform.awaitAny(List.of(cancelled)).await());
            assertThrows(Exception.class,()->platform.awaitAny(List.of(cancelled),value->true).await());
            assertTrue(warnings.toString(),warnings.isEmpty());
            var failed=platform.<String>wrapPromise((resolve,reject)->reject.accept(new IllegalStateException("Real test failure")));
            assertThrows(Exception.class,()->platform.awaitAll(List.of(failed)).await());
            assertFalse("Actual failures must remain visible",warnings.isEmpty());
        }finally{logger.removeHandler(handler);}
    }
}
