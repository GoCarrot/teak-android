package io.teak.sdk;

import org.junit.Test;

import io.teak.sdk.TeakNotification.Reward;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Reward-claim responses must never crash or hang. Every parse outcome yields a non-null Reward
 * whose json always carries teakRewardId + status — the wrappers index json["status"] directly and
 * would KeyNotFoundException on a bare {}, and direct callers read teakRewardId off the json.
 *
 * Lives in io.teak.sdk so it can reach the package-private parse helper without standing up a
 * full session / WireMock path.
 */
public class RewardClaimResponseTest {
    private static final String REWARD_ID = "test-reward-id";

    // Parses a body and asserts the invariants that hold for *every* outcome.
    private static Reward parse(String body) {
        final Reward reward = Reward.rewardFromClaimResponse(REWARD_ID, body);
        assertNotNull(reward);
        assertNotNull(reward.json);
        assertTrue("json must carry teakRewardId for the wrappers", reward.json.has("teakRewardId"));
        assertTrue("json must carry status for the wrappers", reward.json.has("status"));
        return reward;
    }

    @Test
    public void unknownSentinelIsWrapperSafe() throws Exception {
        final Reward reward = Reward.unknownReward(REWARD_ID);
        assertEquals(Reward.UNKNOWN, reward.status);
        assertEquals(REWARD_ID, reward.json.getString("teakRewardId"));
        // Reuses the iOS wire value so teak-unity maps it to RewardStatus.InternalError.
        assertEquals("internal_error", reward.json.getString("status"));
    }

    @Test
    public void nullBodyYieldsUnknown() {
        assertEquals(Reward.UNKNOWN, parse(null).status);
    }

    @Test
    public void unparseableBodyYieldsUnknown() {
        assertEquals(Reward.UNKNOWN, parse("this is not json").status);
    }

    @Test
    public void missingResponseObjectYieldsUnknown() {
        assertEquals(Reward.UNKNOWN, parse("{\"foo\":\"bar\"}").status);
    }

    @Test
    public void missingStatusYieldsUnknown() {
        assertEquals(Reward.UNKNOWN, parse("{\"response\":{}}").status);
    }

    @Test
    public void unrecognizedStatusYieldsUnknown() {
        assertEquals(Reward.UNKNOWN, parse("{\"response\":{\"status\":\"bananas\"}}").status);
    }

    @Test
    public void grantRewardParsesWithReward() {
        final Reward reward = parse("{\"response\":{\"status\":\"grant_reward\",\"reward\":{\"coins\":10}}}");
        assertEquals(Reward.GRANT_REWARD, reward.status);
        assertTrue(reward.json.has("reward"));
    }

    @Test
    public void selfClickParses() {
        assertEquals(Reward.SELF_CLICK, parse("{\"response\":{\"status\":\"self_click\"}}").status);
    }

    @Test
    public void alreadyClickedParses() {
        assertEquals(Reward.ALREADY_CLICKED, parse("{\"response\":{\"status\":\"already_clicked\"}}").status);
    }
}
