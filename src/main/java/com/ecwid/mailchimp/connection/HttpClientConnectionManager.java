/*
 * Copyright 2012 Ecwid, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ecwid.mailchimp.connection;

import com.ecwid.mailchimp.MailChimpException;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;

import javax.net.ssl.SSLException;
import java.io.*;
import java.net.ConnectException;
import java.net.UnknownHostException;

/**
 * Implementation of {@link MailChimpConnectionManager}
 * which uses Apache HttpClient library to access MailChimp API service point.
 *
 * @author Vasily Karyaev <v.karyaev@gmail.com>
 * @author Ergin Demirel
 */
public class HttpClientConnectionManager implements MailChimpConnectionManager {
    private final HttpClient http = new DefaultHttpClient();
    private final int BUFFER_SIZE = 4096;
    private final int DELAY_IN_MSECS = 30000;
    private final int MAX_RETRY = 4;

    @Override
    public String post(String url, String payload) throws IOException {
        HttpPost post = new HttpPost(url);
        post.setEntity(new StringEntity(payload));
        return http.execute(post, new BasicResponseHandler());
    }

    @Override
    public String getAsFile(String url, String path, String fileName) throws IOException, MailChimpException {
        if(!new File(path).exists())
        {
            throw new IOException("Folder does not exist");
        }

        HttpGet get = new HttpGet(url);

        //set retry handler
        ((DefaultHttpClient) http).setHttpRequestRetryHandler(new HttpRequestRetryHandler() {
            @Override
            public boolean retryRequest(
                    IOException exception,
                    int executionCount,
                    HttpContext context) {
                if (executionCount >= MAX_RETRY) {
                    return false;
                }
                if (exception instanceof InterruptedIOException) {
                    // Timeout
                    return false;
                }
                if (exception instanceof UnknownHostException) {
                    // Unknown host
                    return false;
                }
                if (exception instanceof ConnectException) {
                    // Connection refused
                    return false;
                }
                if (exception instanceof SSLException) {
                    // SSL handshake exception
                    return false;
                }
                HttpRequest request = (HttpRequest) context.getAttribute(ExecutionContext.HTTP_REQUEST);
                boolean idempotent = !(request instanceof HttpEntityEnclosingRequest);
                if (idempotent) {
                    try {
                        Thread.sleep(DELAY_IN_MSECS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    return true;
                }
                return false;
            }
        });

        HttpResponse response = http.execute(get);

        if (response.getStatusLine().getStatusCode() < HttpStatus.SC_MOVED_PERMANENTLY) {
            String fullPath = new File(path, fileName).toString();
            HttpEntity entity = response.getEntity();
            byte[] buffer = new byte[BUFFER_SIZE];

            if (entity != null) {
                InputStream inputStream = entity.getContent();
                FileOutputStream fileOutputStream = new FileOutputStream(new File(fullPath));
                try {
                    int bytesRead = 0;
                    BufferedInputStream bis = new BufferedInputStream(inputStream);
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        fileOutputStream.write(buffer, 0, bytesRead);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        inputStream.close();
                        fileOutputStream.close();
                    } catch (Exception ignore) {
                    }
                }

                return fullPath;
            } else {
                //nothing found to write
                return null;
            }
        } else {
            throw new MailChimpException(response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
        }
    }

    @Override
    public void close() {
        http.getConnectionManager().shutdown();
    }
}
