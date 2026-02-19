package io.teak.app.test;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;

import io.teak.sdk.Log;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.configuration.DebugConfiguration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DebugConfigurationTest extends TeakUnitTest {

    private boolean getLogLocally() throws NoSuchFieldException, IllegalAccessException {
        Field f = Log.class.getDeclaredField("logLocally");
        f.setAccessible(true);
        return (boolean) f.get(Teak.log);
    }

    private boolean getLogRemotely() throws NoSuchFieldException, IllegalAccessException {
        Field f = Log.class.getDeclaredField("logRemotely");
        f.setAccessible(true);
        return (boolean) f.get(Teak.log);
    }

    private void reinitializeWithCurrentMocks() throws NoSuchFieldException, IllegalAccessException {
        TestHelpers.resetTeakConfiguration();
        if (!TeakConfiguration.initialize(context, objectFactory)) {
            throw new IllegalArgumentException("TeakConfiguration re-initialization failed.");
        }
    }

    @After
    public void resetForceDebug() {
        Teak.forceDebug = false;
    }

    @Test
    public void forceDebugOutputResourceTrue_enablesLocalLogging() throws Exception {
        when(androidResources.getBooleanResource(DebugConfiguration.TEAK_FORCE_DEBUG_OUTPUT)).thenReturn(true);
        reinitializeWithCurrentMocks();

        assertTrue("logLocally should be true when io_teak_force_debug_output is true", getLogLocally());
    }

    @Test
    public void forceDebugOutputResourceFalse_loggingRemainsDisabled() throws Exception {
        when(androidResources.getBooleanResource(DebugConfiguration.TEAK_FORCE_DEBUG_OUTPUT)).thenReturn(false);
        reinitializeWithCurrentMocks();

        assertFalse("logLocally should be false when io_teak_force_debug_output is false", getLogLocally());
    }

    @Test
    public void forceDebugOutputResourceAbsent_loggingRemainsDisabled() throws Exception {
        // getBooleanResource returns null by default for unmocked calls (Mockito default)
        // This simulates the resource not being present in the app

        assertFalse("logLocally should be false when io_teak_force_debug_output resource is absent", getLogLocally());
    }

    // --- Server vs local logging interaction ---

    @Test
    public void serverFalse_doesNotOverrideForceDebugOutputResource() throws Exception {
        when(androidResources.getBooleanResource(DebugConfiguration.TEAK_FORCE_DEBUG_OUTPUT)).thenReturn(true);
        reinitializeWithCurrentMocks();

        assertTrue("logLocally should be true before server response", getLogLocally());

        TeakConfiguration.get().debugConfiguration.setLogPreferences(false, false);

        assertTrue("logLocally should remain true after server sends verbose_logging=false", getLogLocally());
    }

    @Test
    public void serverTrue_enablesLogging() throws Exception {
        assertFalse("logLocally should be false before server response", getLogLocally());

        TeakConfiguration.get().debugConfiguration.setLogPreferences(true, false);

        assertTrue("logLocally should be true after server sends verbose_logging=true", getLogLocally());
    }

    @Test
    public void serverFalse_disablesRemoteLogging() throws Exception {
        TeakConfiguration.get().debugConfiguration.setLogPreferences(false, true);

        assertTrue("logRemotely should be true after server enables it", getLogRemotely());

        TeakConfiguration.get().debugConfiguration.setLogPreferences(false, false);

        assertFalse("logRemotely should be false after server disables it", getLogRemotely());
    }

    @Test
    public void serverFalse_disablesPreviouslyEnabledLocalLogging() throws Exception {
        // Without the force resource, server-enabled local logging can be turned off
        TeakConfiguration.get().debugConfiguration.setLogPreferences(true, false);

        assertTrue("logLocally should be true after server enables it", getLogLocally());

        TeakConfiguration.get().debugConfiguration.setLogPreferences(false, false);

        assertFalse("logLocally should be false after server disables it", getLogLocally());
    }
}
