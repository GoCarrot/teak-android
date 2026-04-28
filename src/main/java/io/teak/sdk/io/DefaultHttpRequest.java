package io.teak.sdk.io;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

public class DefaultHttpRequest implements IHttpRequest {
    @Override
    @SuppressWarnings("TryWithIdenticalCatches")
    public Response synchronousRequest(final URL url, final String method, final String requestBody, final String sig) throws IOException {
        Response ret = Response.ERROR_RESPONSE;

        HttpURLConnection connection = null;
        BufferedReader rd = null;

        try {
            if ("https".equalsIgnoreCase(url.getProtocol())) {
                connection = (HttpsURLConnection) url.openConnection();
            } else if ("http".equalsIgnoreCase(url.getProtocol())) {
                connection = (HttpURLConnection) url.openConnection();
            }

            final boolean isBodyMethod = !"GET".equalsIgnoreCase(method);

            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept-Charset", "UTF-8");
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "TeakV2-HMAC-SHA256 Signature=" + sig);

            if (isBodyMethod) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Content-Length",
                    "" + requestBody.getBytes().length);

                DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
                wr.writeBytes(requestBody);
                wr.flush();
                wr.close();
            }

            // Get Response
            InputStream is;
            if (connection.getResponseCode() < 400) {
                is = connection.getInputStream();
            } else {
                is = connection.getErrorStream();
            }
            rd = new BufferedReader(new InputStreamReader(is));
            String line;
            StringBuilder response = new StringBuilder();
            while ((line = rd.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }
            rd.close();
            ret = new Response(connection.getResponseCode(), response.toString(), connection.getHeaderFields());
        } catch (UnknownHostException uh_e) {
            // Ignored, Sentry issue 'TEAK-SDK-F', 'TEAK-SDK-M', 'TEAK-SDK-X'
        } catch (SocketTimeoutException st_e) {
            // Ignored, Sentry issue 'TEAK-SDK-11'
        } catch (ConnectException c_e) {
            // Ignored, Sentry issue 'TEAK-SDK-Q', 'TEAK-SDK-K', 'TEAK-SDK-W', 'TEAK-SDK-V',
            //      'TEAK-SDK-J', 'TEAK-SDK-P'
        } catch (HttpRetryException http_e) {
            // Ignored, Sentry issue 'TEAK-SDK-N'
        } catch (SSLException ssl_e) {
            // Ignored, Sentry issue 'TEAK-SDK-T'
        } catch (SocketException sock_e) {
            // Ignored, Sentry issue 'TEAK-SDK-S'
        } catch (EOFException eof_e) {
            // Ignored, Sentry issue 'TEAK-ANDROID-SDK-E4'
        } catch (IOException io_e) {
            // Ignored, Sentry issue 'TEAK-ANDROID-SDK-E2'
        } finally {
            if (rd != null) {
                try {
                    rd.close();
                } catch (Exception ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }

        return ret;
    }
}
