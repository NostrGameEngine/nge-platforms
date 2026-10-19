package org.ngengine.platform.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AndroidJsonSerializationTest {

    @Test
    public void jsonUsesInternalJavaScriptCompatibilityModule() {
        AndroidThreadedPlatform platform = new AndroidThreadedPlatform(null);
        Map<?, ?> parsed = platform.fromJSON("{\"z\":1,\"a\":{\"last\":null,\"first\":true},\"2\":2}", Map.class);
        assertEquals(LinkedHashMap.class, parsed.getClass());
        assertEquals(LinkedHashMap.class, parsed.get("a").getClass());
        assertEquals("{\"2\":2,\"z\":1,\"a\":{\"last\":null,\"first\":true}}", platform.toJSON(parsed));
        assertEquals("[0,1e+23,5e-324,null]", platform.toJSON(List.of(-0.0, 1e23, Double.MIN_VALUE, Double.NaN)));
        assertTrue(platform.supportsMinimalJSONEscaping());
    }
}
