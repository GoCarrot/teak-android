/* Teak -- Copyright (C) 2016 GoCarrot Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.teak.sdk;

import android.os.Bundle;
import android.support.annotation.NonNull;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.security.InvalidParameterException;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

public class Helpers {
    public static boolean getBooleanFromBundle(Bundle b, String key) {
        String boolAsStringMaybe = b.getString(key);
        if (boolAsStringMaybe != null) {
            return Boolean.parseBoolean(boolAsStringMaybe);
        }
        return b.getBoolean(key);
    }

    public static HashMap<String, Object> jsonToMap(JSONObject json) throws JSONException {
        HashMap<String, Object> retMap = new HashMap<>();

        if (json != JSONObject.NULL) {
            retMap = toMap(json);
        }
        return retMap;
    }

    private static HashMap<String, Object> toMap(JSONObject object) throws JSONException {
        HashMap<String, Object> map = new HashMap<>();

        Iterator keysItr = object.keys();
        while (keysItr.hasNext()) {
            String key = keysItr.next().toString();
            Object value = object.get(key);

            if (value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            map.put(key, value);
        }
        return map;
    }

    private static List<Object> toList(JSONArray array) throws JSONException {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            list.add(value);
        }
        return list;
    }


    public static class mm {
        public static Map<String, Object> h(@NonNull Object... args) {
            Map<String, Object> ret = new HashMap<>();
            if (args.length % 2 != 0)
                throw new InvalidParameterException("Args must be in key value pairs.");
            for (int i = 0; i < args.length; i += 2) {
                ret.put(args[i].toString(), args[i + 1]);
            }
            return ret;
        }
    }
}
