package com.bitchat.android.net;

import okhttp3.OkHttpClient;

/**
 * Fournit une instance singleton du client OkHttp.
 * Placeholder.
 */
public class OkHttpProvider {
    private static final OkHttpClient httpClient = new OkHttpClient();

    public static OkHttpClient getHttpClient() {
        return httpClient;
    }
}
