package io.teak.sdk.io;

import android.support.annotation.Nullable;

import java.io.IOException;
import java.net.URL;

public interface IHttpsRequest {
    class Response {
        public final int statusCode;
        public final String body;

        Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public static final Response ERROR_RESPONSE = new Response(599, "{}");
    }

    @Nullable
    Response synchronousRequest(URL url, String requestBody) throws IOException;
}
