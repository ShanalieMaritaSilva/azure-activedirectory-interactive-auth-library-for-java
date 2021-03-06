/**
 * Copyright 2014 Microsoft Open Technologies Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.microsoftopentechnologies.auth;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoftopentechnologies.auth.browser.BrowserLauncher;
import com.microsoftopentechnologies.auth.browser.BrowserLauncherDefault;
import com.microsoftopentechnologies.auth.utils.EncodingHelper;
import com.microsoftopentechnologies.auth.utils.JsonUtils;
import com.microsoftopentechnologies.auth.webserver.AADWebServer;
import com.microsoftopentechnologies.auth.webserver.ClosedCallback;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public class AuthenticationContext {
    private String authority;

    private final String AUTHORIZE_ENDPOINT_TEMPLATE = "https://{host}/{tenant}/oauth2/authorize";
    private final String TOKEN_ENDPOINT_TEMPLATE = "https://{host}/{tenant}/oauth2/token";

    private AADWebServer webServer = null;

    private ReentrantLock authCodeLock = new ReentrantLock();
    private boolean gotAuthCode = false;

    private BrowserLauncher browserLauncher = new BrowserLauncherDefault();

    public AuthenticationContext(final String authority) throws IOException {
        this.authority = authority;
    }

    public void dispose() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
    }

    public void setBrowserLauncher(BrowserLauncher browserLauncher) {
        this.browserLauncher = browserLauncher;
    }

    public ListenableFuture<AuthenticationResult> acquireTokenInteractiveAsync(
            final String tenantName,
            final String resource,
            final String clientId,
            final String redirectUri,
            final String promptValue) throws IOException {
        return acquireTokenInteractiveAsync(
                tenantName,
                resource,
                clientId,
                redirectUri,
                "Sign in to your Microsoft account",
                promptValue);
    }

    public ListenableFuture<AuthenticationResult> acquireTokenInteractiveAsync(
            final String tenantName,
            final String resource,
            final String clientId,
            final String redirectUri,
            final String windowTitle,
            final String promptValue) throws IOException {

        final SettableFuture<AuthenticationResult> future = SettableFuture.create();

        // get the auth code
        ListenableFuture<String> authCodeFuture = acquireAuthCodeInteractiveAsync(
                tenantName,
                resource,
                clientId,
                redirectUri,
                windowTitle,
                promptValue);
        Futures.addCallback(authCodeFuture, new FutureCallback<String>() {
            @Override
            public void onSuccess(String code) {
                OutputStream output = null;
                BufferedReader reader = null;

                try {
                    // if code is null then the user cancelled the auth
                    if (code == null) {
                        future.set(null);
                        return;
                    }

                    URL adAuthEndpointUrl = new URL(TOKEN_ENDPOINT_TEMPLATE.
                            replace("{host}", authority).replace("{tenant}", tenantName));

                    // build the a/d auth params
                    Map<String, String> params = new HashMap<String, String>();
                    params.put(OAuthParameter.clientId, clientId);
                    params.put(OAuthParameter.code, code);
                    params.put(OAuthParameter.grantType, OAuthGrantType.AuthorizationCode);
                    params.put(OAuthParameter.redirectUri, redirectUri);
                    params.put(OAuthParameter.resource, resource);
                    byte[] requestData = EncodingHelper.toQueryString(params).getBytes(Charsets.UTF_8);

                    // make a POST request to the endpoint with this data
                    HttpURLConnection connection = (HttpURLConnection) adAuthEndpointUrl.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setDoInput(true);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded; charset=" + Charsets.UTF_8.name());
                    connection.setRequestProperty("Content-Length", Integer.toString(requestData.length));
                    output = connection.getOutputStream();
                    output.write(requestData);
                    output.close();
                    output = null;

                    // read the response
                    int statusCode = connection.getResponseCode();
                    if (statusCode != HttpURLConnection.HTTP_OK) {
                        // TODO: Is IOException the right exception type to raise?
                        String err = CharStreams.toString(new InputStreamReader(connection.getErrorStream()));
                        future.setException(new IOException("AD Auth token endpoint returned HTTP status code " +
                                Integer.toString(statusCode) + ". Error info: " + err));
                        return;
                    }

                    reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    reader = null;

                    // parse the JSON
                    String response = sb.toString();
                    JsonParser parser = new JsonParser();
                    JsonObject root = (JsonObject) parser.parse(response);

                    // construct the authentication result object
                    AuthenticationResult result = new AuthenticationResult(
                            JsonUtils.getJsonStringProp(root, OAuthReservedClaim.TokenType),
                            JsonUtils.getJsonStringProp(root, OAuthReservedClaim.AccessToken),
                            JsonUtils.getJsonStringProp(root, OAuthReservedClaim.RefreshToken),
                            JsonUtils.getJsonLongProp(root, OAuthReservedClaim.ExpiresOn),
                            JsonUtils.getJsonStringProp(root, OAuthReservedClaim.Resource),
                            UserInfo.parse(JsonUtils.getJsonStringProp(root, OAuthReservedClaim.IdToken)));
                    future.set(result);
                } catch (Exception e) {
                    future.setException(e);
                } finally {
                    try {
                        if (output != null) {
                            output.close();
                        }
                        if (reader != null) {
                            reader.close();
                        }
                    } catch (IOException ignored) {
                    }
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
                future.setException(throwable);
            }
        });

        return future;
    }

    public AuthenticationResult acquireTokenByRefreshToken(
            AuthenticationResult authenticationResult,
            String tenantName,
            String resource,
            String clientId) throws IOException {

        URL adAuthEndpointUrl = new URL(TOKEN_ENDPOINT_TEMPLATE.
                replace("{host}", authority).replace("{tenant}", tenantName));

        // build the a/d auth params
        Map<String, String> params = new HashMap<String, String>();
        params.put(OAuthParameter.clientId, clientId);
        params.put(OAuthParameter.grantType, OAuthGrantType.RefreshToken);
        params.put(OAuthParameter.refreshToken, authenticationResult.getRefreshToken());
        if (resource != null) {
            params.put(OAuthParameter.resource, resource);
        }
        byte[] requestData = EncodingHelper.toQueryString(params).getBytes(Charsets.UTF_8);

        // make a POST request to the endpoint with this data
        HttpURLConnection connection = (HttpURLConnection) adAuthEndpointUrl.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Content-Type",
                "application/x-www-form-urlencoded; charset=" + Charsets.UTF_8.name());
        connection.setRequestProperty("Content-Length", Integer.toString(requestData.length));
        OutputStream output = connection.getOutputStream();
        output.write(requestData);
        output.close();

        // read the response
        int statusCode = connection.getResponseCode();
        if (statusCode != HttpURLConnection.HTTP_OK) {
            // TODO: Is IOException the right exception type to raise?
            InputStream errorStream = null;
            InputStreamReader errorReader = null;
            String err = "";
            try {
                errorStream = connection.getErrorStream();
                errorReader = new InputStreamReader(errorStream);
                err = CharStreams.toString(errorReader);
            }
            finally {
                if(errorStream != null) {
                    errorStream.close();
                }
                if(errorReader != null) {
                    errorReader.close();
                }
            }

            throw new IOException("AD Auth token endpoint returned HTTP status code " +
                    Integer.toString(statusCode) + ". Error info: " + err);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();

        // parse the JSON
        String response = sb.toString();
        JsonParser parser = new JsonParser();
        JsonObject root = (JsonObject) parser.parse(response);

        // update the authentication result object
        return new AuthenticationResult(
                JsonUtils.getJsonStringProp(root, OAuthReservedClaim.TokenType),
                JsonUtils.getJsonStringProp(root, OAuthReservedClaim.AccessToken),
                JsonUtils.getJsonStringProp(root, OAuthReservedClaim.RefreshToken),
                JsonUtils.getJsonLongProp(root, OAuthReservedClaim.ExpiresOn),
                JsonUtils.getJsonStringProp(root, OAuthReservedClaim.Resource),
                authenticationResult.getUserInfo()
        );
    }

    private ListenableFuture<String> acquireAuthCodeInteractiveAsync(
            final String tenantName,
            final String resource,
            final String clientId,
            final String redirectUri,
            final String windowTitle,
            final String promptValue) throws IOException {

        final SettableFuture<String> future = SettableFuture.create();

        try {
            String correlationId = UUID.randomUUID().toString();

            boolean noShell = PromptValue.attemptNone.equals(promptValue);

            // build the a/d auth URI params
            Map<String, String> params = new HashMap<String, String>();
            params.put(OAuthParameter.resource, resource);
            params.put(OAuthParameter.clientId, clientId);
            params.put(OAuthParameter.responseType, OAuthResponseType.code);
            params.put(OAuthParameter.redirectUri, redirectUri);
            params.put(OAuthParameter.correlationId, correlationId);
            params.put(OAuthParameter.prompt, promptValue);
            params.put("site_id", "500879");
            params.put("display", "popup");
            String query = EncodingHelper.toQueryString(params);

            // build the actual URI
            String adUri = AUTHORIZE_ENDPOINT_TEMPLATE.replace("{host}", authority).replace("{tenant}", tenantName);
            adUri = adUri + "?" + query;

            // initialize and start up web server
            if (webServer == null) {
                webServer = new AADWebServer();
                webServer.start();
            }

            webServer.setAuthCodeCallback(new AuthCodeCallback() {
                @Override
                public void onAuthCodeReceived(String code, Map<String, String> params) {
                    authCodeLock.lock();
                    try {
                        gotAuthCode = true;

                        if (Strings.isNullOrEmpty(code)) {
                            String msg = "An error occurred during authentication. 'code' is null/empty.";
                            if (params.containsKey("error")) {
                                msg += "\nError code: " + params.get("error");
                            }
                            if (params.containsKey("error_description")) {
                                msg += "\nDescription: " + params.get("error_description");
                            }

                            future.setException(new IllegalArgumentException(msg));
                        } else {
                            future.set(code);
                        }
                    } finally {
                        authCodeLock.unlock();
                    }
                }
            });

            webServer.setClosedCallback(new ClosedCallback() {
                @Override
                public void onClosed() {
                    authCodeLock.lock();
                    try {
                        if (!gotAuthCode) {
                            future.set(null);
                        }
                    } finally {
                        authCodeLock.unlock();
                    }
                }
            });

            // start the browser
            try {
                ListenableFuture<Void> launchFuture = browserLauncher.browseAsync(adUri, redirectUri,
                        webServer.getBaseURL().toString(),
                        windowTitle,
                        noShell);

                Futures.addCallback(launchFuture, new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        // do nothing in case browser launch is successful
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        future.setException(t);
                    }
                });

            } catch (MalformedURLException ignored) {
            }
        } catch (UnsupportedEncodingException e) {
            future.setException(e);
        } catch (MalformedURLException e) {
            future.setException(e);
        }

        return future;
    }
}
