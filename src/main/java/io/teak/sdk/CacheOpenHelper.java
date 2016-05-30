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

import android.content.Context;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import android.util.Log;

class CacheOpenHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "teak.db";
    private static final int DATABASE_VERSION = 1;

    public CacheOpenHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(CachedRequest.REQUEST_CACHE_CREATE_SQL);
        database.execSQL(TeakNotification.INBOX_CACHE_CREATE_SQL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (Teak.isDebug) {
            Log.d(Teak.LOG_TAG, "Upgrading database " + database + " from version " + oldVersion + " to " + newVersion);
        }

        database.execSQL("DROP TABLE IF EXISTS cache");
        database.execSQL("DROP TABLE IF EXISTS inbox");
        onCreate(database);
    }
}