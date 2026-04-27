package io.teak.app.test;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.TeakNotification;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.json.JSONObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ClaimModeTest extends TeakUnitTest {

    private void reinitializeWithCurrentMocks() throws NoSuchFieldException, IllegalAccessException {
        TestHelpers.resetTeakConfiguration();
        if (!TeakConfiguration.initialize(context, objectFactory)) {
            throw new IllegalArgumentException("TeakConfiguration re-initialization failed.");
        }
    }

    // The Reward(JSONObject) constructor is package-private. Tests live in io.teak.app.test,
    // so reach through reflection. This mirrors how DebugConfigurationTest pokes at Log internals.
    private int rewardStatusForServerString(String serverStatus) throws Exception {
        final JSONObject body = new JSONObject();
        body.put("status", serverStatus);

        final Constructor<TeakNotification.Reward> ctor =
            TeakNotification.Reward.class.getDeclaredConstructor(JSONObject.class);
        ctor.setAccessible(true);
        final TeakNotification.Reward reward = ctor.newInstance(body);

        final Field statusField = TeakNotification.Reward.class.getDeclaredField("status");
        return (int) statusField.get(reward);
    }

    // --- AppConfiguration.claimMode ---

    @Test
    public void claimMode_defaultsToLegacy_whenResourceAbsent() {
        // androidResources is unmocked for io_teak_claim_mode in TeakUnitTest -> returns null.
        assertEquals(AppConfiguration.DefaultClaimMode,
            TeakConfiguration.get().appConfiguration.claimMode);
        assertEquals("legacy", TeakConfiguration.get().appConfiguration.claimMode);
    }

    @Test
    public void claimMode_defaultsToLegacy_whenResourceEmpty() throws Exception {
        when(androidResources.getStringResource(AppConfiguration.TEAK_CLAIM_MODE_RESOURCE)).thenReturn("   ");
        reinitializeWithCurrentMocks();

        assertEquals(AppConfiguration.DefaultClaimMode,
            TeakConfiguration.get().appConfiguration.claimMode);
    }

    @Test
    public void claimMode_readsConfiguredValue() throws Exception {
        when(androidResources.getStringResource(AppConfiguration.TEAK_CLAIM_MODE_RESOURCE)).thenReturn("client_jwt");
        reinitializeWithCurrentMocks();

        assertEquals("client_jwt", TeakConfiguration.get().appConfiguration.claimMode);
    }

    @Test
    public void claimMode_appearsInToMap() throws Exception {
        when(androidResources.getStringResource(AppConfiguration.TEAK_CLAIM_MODE_RESOURCE)).thenReturn("client_jwt");
        reinitializeWithCurrentMocks();

        assertEquals("client_jwt",
            TeakConfiguration.get().appConfiguration.toMap().get("claimMode"));
    }

    // --- Reward.Status string-to-enum mapping for new values ---

    @Test
    public void rewardStatus_mapsPlayerIneligible() throws Exception {
        assertEquals(TeakNotification.Reward.PLAYER_INELIGIBLE, rewardStatusForServerString("player_ineligible"));
    }

    @Test
    public void rewardStatus_mapsNoRewardAvailable() throws Exception {
        assertEquals(TeakNotification.Reward.NO_REWARD_AVAILABLE, rewardStatusForServerString("no_reward_available"));
    }

    @Test
    public void rewardStatus_mapsClaimModeUnsupported() throws Exception {
        assertEquals(TeakNotification.Reward.CLAIM_MODE_UNSUPPORTED, rewardStatusForServerString("claim_mode_unsupported"));
    }

    @Test
    public void rewardStatus_unknownStringFallsBackToUnknown() throws Exception {
        assertEquals(TeakNotification.Reward.UNKNOWN, rewardStatusForServerString("something_brand_new"));
    }

    @Test
    public void rewardStatus_existingMappingsStillWork() throws Exception {
        // Sanity: the new branches do not break legacy strings.
        assertEquals(TeakNotification.Reward.GRANT_REWARD, rewardStatusForServerString("grant_reward"));
        assertEquals(TeakNotification.Reward.SELF_CLICK, rewardStatusForServerString("self_click"));
    }

    // --- Enum constants are distinct ---

    @Test
    public void rewardStatus_newConstantsHaveExpectedValues() {
        assertEquals(-7, TeakNotification.Reward.PLAYER_INELIGIBLE);
        assertEquals(-8, TeakNotification.Reward.NO_REWARD_AVAILABLE);
        assertEquals(-9, TeakNotification.Reward.CLAIM_MODE_UNSUPPORTED);

        // And they're distinct from the legacy values
        assertNotEquals(TeakNotification.Reward.PLAYER_INELIGIBLE, TeakNotification.Reward.INVALID_POST);
        assertNotEquals(TeakNotification.Reward.NO_REWARD_AVAILABLE, TeakNotification.Reward.INVALID_POST);
        assertNotEquals(TeakNotification.Reward.CLAIM_MODE_UNSUPPORTED, TeakNotification.Reward.INVALID_POST);
    }
}
