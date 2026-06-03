package com.fntv.app.api;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * 核心拦截器：注入签名
 */
public class AuthInterceptor implements Interceptor {
    private String token;

    public void setToken(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();

        String urlPath = original.url().encodedPath();
        String jsonBody = null;
        if (original.body() != null) {
            Buffer buffer = new Buffer();
            original.body().writeTo(buffer);
            jsonBody = buffer.readString(Charset.forName("UTF-8"));
        }

        String authx = FnAuthUtils.genAuthx(urlPath, jsonBody);

        Request.Builder requestBuilder = original.newBuilder()
                .header("Content-Type", "application/json")
                .header("Authx", authx)
                .header("Cookie", "mode=relay");

        if (token != null) {
            requestBuilder.header("Authorization", token);
        }

        return chain.proceed(requestBuilder.build());
    }
}
