package org.ngengine.platform.ios.interop;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "com.sun.management.internal.OperatingSystemImpl")
final class Target_com_sun_management_internal_OperatingSystemImpl {
    @Substitute
    private static void initialize0() {
        // iOS Graal runtime does not provide this management JNI entrypoint.
    }
}
