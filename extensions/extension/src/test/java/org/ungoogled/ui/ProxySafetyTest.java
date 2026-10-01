package org.ungoogled.ui;

import android.content.Context;
import android.widget.Switch;
import org.chromium.net.CronetEngine;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ProxySafetyTest {
    private Context context;

    @Before public void reset() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ungoogled_ui", Context.MODE_PRIVATE).edit().clear().commit();
        setEffective(""); CronetProxy.supported = true;
    }

    private static void setEffective(String value) throws Exception {
        Field f = Shapes.class.getDeclaredField("PROXY_EFFECTIVE"); f.setAccessible(true); f.set(null, value);
    }

    @Test public void cannotEnableProxyWithEmptyHost() {
        assertThrows(IllegalArgumentException.class, () -> Shapes.setProxyParts(context, true, "", 8118));
        assertFalse(Shapes.proxyOn(context));
    }

    @Test public void invalidPortCannotReplaceWorkingConfiguration() {
        Shapes.setProxyParts(context, true, "localhost", 8118);
        assertThrows(IllegalArgumentException.class, () -> Shapes.setProxyParts(context, true, "localhost", 0));
        assertEquals("localhost:8118", Shapes.proxy(context));
        assertThrows(IllegalArgumentException.class, () -> Shapes.setProxyParts(context, true, "localhost", 65536));
        assertEquals(8118, Shapes.proxyPort(context));
    }

    @Test public void disabledProxyDoesNotTouchCronetBuilder() {
        CronetProxy.onBuild(null);
    }

    @Test public void malformedEffectivePortCannotSilentlySkipProxy() throws Exception {
        setEffective("localhost:not-a-port");
        assertThrows(IllegalStateException.class, () -> CronetProxy.onBuild(new CronetEngine.Builder()));
    }

    @Test public void unsupportedCronetProxyApiCannotContinueEngineBuild() throws Exception {
        setEffective("localhost:8118");
        // The project's existing Cronet stubs throw UnsupportedOperationException.
        // This models the unavailable-API path, not successful real Cronet networking.
        CronetEngine.Builder builder = new CronetEngine.Builder() {
            @Override public CronetEngine.Builder enableQuic(boolean value) { return this; }
        };
        assertThrows(IllegalStateException.class, () -> CronetProxy.onBuild(builder));
        assertFalse(CronetProxy.supported);
    }

    @Test public void invalidEnableToggleReturnsToDisabledWithoutCrashing() throws Exception {
        ProxyActivity activity = Robolectric.buildActivity(ProxyActivity.class).setup().get();
        Field f = ProxyActivity.class.getDeclaredField("enable"); f.setAccessible(true);
        Switch toggle = (Switch) f.get(activity);
        toggle.setChecked(true);
        assertFalse(toggle.isChecked());
        assertFalse(Shapes.proxyOn(context));
    }

    @Test public void validConfigurationCanBeDisabledAndReenabled() {
        Shapes.setProxyParts(context, true, " localhost ", 8118);
        assertTrue(Shapes.proxyOn(context)); assertEquals("localhost:8118", Shapes.proxy(context));
        Shapes.setProxyParts(context, false, "localhost", 8118);
        assertFalse(Shapes.proxyOn(context)); assertEquals("", Shapes.proxy(context));
        Shapes.setProxyParts(context, true, Shapes.proxyHost(context), Shapes.proxyPort(context));
        assertEquals("localhost:8118", Shapes.proxy(context));
    }
}
