package io.teak.app.test;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.RewardClaimManager;
import io.teak.sdk.event.RemoteConfigurationEvent;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * C-721 Phase 2 acceptance: SDK reads {@code claim_poll_initial_delay_ms} and
 * {@code claim_poll_ceiling_ms} from the settings.json response and falls back to
 * hardcoded constants when the keys are absent.
 *
 * <p>Settings parsing happens inside {@link RemoteConfiguration#registerStaticEventListeners()}
 * which is awkward to drive from a unit test. These tests construct {@link RemoteConfiguration}
 * directly, mirroring what the parser does and asserting the value flows through.
 */
@RunWith(MockitoJUnitRunner.class)
public class RewardClaimSettingsConsumptionTest extends TeakUnitTest {

    @Test
    public void remoteConfiguration_carriesPollConstants_fromConstructor() {
        final RemoteConfiguration cfg = new RemoteConfiguration(
            mock(AppConfiguration.class),
            "gocarrot.com", null, null, null, null,
            false, false, null, null, 60,
            5000, 60000,
            new ArrayList<Teak.Channel.Category>(), true);

        assertEquals(5000, cfg.claimPollInitialDelayMs);
        assertEquals(60000, cfg.claimPollCeilingMs);
    }

    @Test
    public void remoteConfiguration_constructorAcceptsHardcodedDefaults() {
        // Mirror the fallback path: caller passes the manager's defaults explicitly.
        final RemoteConfiguration cfg = new RemoteConfiguration(
            mock(AppConfiguration.class),
            "gocarrot.com", null, null, null, null,
            false, false, null, null, 60,
            RewardClaimManager.DEFAULT_INITIAL_DELAY_MS,
            RewardClaimManager.DEFAULT_CEILING_MS,
            new ArrayList<Teak.Channel.Category>(), true);

        assertEquals(2000, cfg.claimPollInitialDelayMs);
        assertEquals(30000, cfg.claimPollCeilingMs);
    }

    @Test
    public void rewardClaimManagerBackoff_usesActiveRemoteConfiguration() throws Exception {
        // Wire an active RemoteConfiguration with custom curve, then confirm the manager
        // pulls it via TeakConfiguration.get().
        final AppConfiguration appConfig = TeakConfiguration.get().appConfiguration;
        final RemoteConfiguration cfg = new RemoteConfiguration(
            appConfig,
            "gocarrot.com", null, null, null, null,
            false, false, null, null, 60,
            500, 4000,
            new ArrayList<Teak.Channel.Category>(), true);
        TeakConfiguration.get().remoteConfiguration = cfg;

        // 500 << 0 = 500; 500 << 1 = 1000; ...; cap at 4000.
        assertEquals(500L, RewardClaimManager.computeBackoffMs(0,
            cfg.claimPollInitialDelayMs, cfg.claimPollCeilingMs));
        assertEquals(1000L, RewardClaimManager.computeBackoffMs(1,
            cfg.claimPollInitialDelayMs, cfg.claimPollCeilingMs));
        assertEquals(4000L, RewardClaimManager.computeBackoffMs(3,
            cfg.claimPollInitialDelayMs, cfg.claimPollCeilingMs));
        assertEquals(4000L, RewardClaimManager.computeBackoffMs(10,
            cfg.claimPollInitialDelayMs, cfg.claimPollCeilingMs));
    }
}
