package org.ngengine.platform;

/**
 * A boolean flag that kills the application when it detects memory corruption (eg. bit rot).
 * 
 * Use for flags that are critical to the integrity of the application.
 */
public final class SafeFlag {

    private static final long ENABLED  = 0x6D3A91C54E27B8F0L;
    private static final long DISABLED = 0x19C7E42AB508D36FL;

    private volatile long encoded;

    public SafeFlag(boolean enabled) {
        set(enabled);
    }

    public void set(boolean enabled) {
        encoded = enabled ? ENABLED : DISABLED;
    }

    public boolean get() {
        long snapshot = encoded;

        if (snapshot == ENABLED) {
            return true;
        }

        if (snapshot == DISABLED) {
            return false;
        }

        NGEUtils.getPlatform().panic("Memory corruption detected in boolean flag");
        throw new SecurityException("Memory corruption detected in boolean flag");
    }
}