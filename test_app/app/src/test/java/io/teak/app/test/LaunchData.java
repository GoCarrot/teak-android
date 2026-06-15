package io.teak.app.test;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import io.teak.sdk.Helpers;
import io.teak.sdk.Teak;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class LaunchData {
    static final String teakScheduleName = "some_schedule_name";
    static final String teakScheduleId = "some_schedule_id";
    static final String teakCreativeName = "some_creative_name";
    static final String teakCreativeId = "some_creative_id";

    @Test
    public void RewardlinkLaunchData() {
        final Teak.RewardlinkLaunchData data = new Teak.RewardlinkLaunchData(getGenericUri(), null);

        // Make sure that the correct items go into the correct variables
        assertTrue(Helpers.stringsAreEqual(data.scheduleName, teakScheduleName));
        assertTrue(Helpers.stringsAreEqual(data.scheduleId, teakScheduleId));
        assertTrue(Helpers.stringsAreEqual(data.creativeName, teakCreativeName));
        assertTrue(Helpers.stringsAreEqual(data.creativeId, teakCreativeId));
        assertTrue(Helpers.stringsAreEqual(data.channelName, "generic_link"));
    }

    // Locks the RewardlinkLaunchData constructor contract: a non-null shortLink is retained as
    // launchLink (and null stays null). That contract is the mechanism the LaunchDataSource fix
    // relies on to keep the launch link when AndroidPath is absent. It does NOT exercise
    // LaunchDataSource itself — that path needs a live HttpsURLConnection + real Uri and isn't
    // cheaply unit-testable, so re-adding the resolver's null branch would not fail this test.
    @Test
    public void RewardlinkLaunchDataRetainsShortLinkAsLaunchLink() {
        final Uri shortLink = mock(Uri.class);

        final Teak.RewardlinkLaunchData withShortLink = new Teak.RewardlinkLaunchData(getGenericUri(), shortLink);
        assertSame(shortLink, withShortLink.launchLink);

        final Teak.RewardlinkLaunchData withoutShortLink = new Teak.RewardlinkLaunchData(getGenericUri(), null);
        assertNull(withoutShortLink.launchLink);
    }

    private Uri getGenericUri() {
        final Uri uri = mock(Uri.class);
        when(uri.getQueryParameter("teak_schedule_name")).thenReturn(teakScheduleName);
        when(uri.getQueryParameter("teak_schedule_id")).thenReturn(teakScheduleId);
        when(uri.getQueryParameter("teak_channel_name")).thenReturn("generic_link");

        // When constructing from a Uri, the rewardlink_name/id should be the creative name
        when(uri.getQueryParameter("teak_rewardlink_name")).thenReturn(teakCreativeName);
        when(uri.getQueryParameter("teak_rewardlink_id")).thenReturn(teakCreativeId);
        return uri;
    }

    private Uri getEmailUri() {
        final Uri uri = mock(Uri.class);
        when(uri.getQueryParameter("teak_schedule_name")).thenReturn(teakScheduleName);
        when(uri.getQueryParameter("teak_schedule_id")).thenReturn(teakScheduleId);
        when(uri.getQueryParameter("teak_channel_name")).thenReturn("email");

        // Email will have a teak_notif_id
        when(uri.getQueryParameter("teak_notif_id")).thenReturn("123456");

        // When constructing from a Uri, the rewardlink_name/id should be the creative name
        when(uri.getQueryParameter("teak_rewardlink_name")).thenReturn(teakCreativeName);
        when(uri.getQueryParameter("teak_rewardlink_id")).thenReturn(teakCreativeId);
        return uri;
    }
}
