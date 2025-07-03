package io.teak.sdk.core;

import java.util.Map;

import io.teak.sdk.Helpers;
import io.teak.sdk.Teak;
import io.teak.sdk.Unobfuscable;
import io.teak.sdk.json.JSONObject;

/**
 * Encapsulation of the state of a channel.
 */
public class ChannelStatus implements Unobfuscable {
    public final Teak.Channel.State state;
    public final boolean deliveryFault;
    public final Map<String, Object> categories;

    public static final ChannelStatus Unknown = new ChannelStatus("unknown", null, false);

    protected ChannelStatus(final String state, final Map<String, Object> categories, final boolean deliveryFault) {
        this.state = Teak.Channel.State.fromString(state);
        this.categories = categories;
        this.deliveryFault = deliveryFault;
    }

    public static ChannelStatus fromJSON(final JSONObject jsonObject) {
        final JSONObject categories = jsonObject.optJSONObject("categories");
        return new ChannelStatus(jsonObject.optString("state", "unknown"),
            categories == null ? null : categories.toMap(),
            jsonObject.optBoolean("delivery_fault", false));
    }

    public JSONObject toJSON() {
        final JSONObject json = new JSONObject();
        json.put("state", this.state.name);
        json.put("categories", this.categories);
        json.put("delivery_fault", Helpers.stringForBool(this.deliveryFault));
        return json;
    }
}
