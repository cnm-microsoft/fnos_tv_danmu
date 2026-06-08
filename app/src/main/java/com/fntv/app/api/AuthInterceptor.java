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
                .header("Authx", authx);

        if (token != null) {
            requestBuilder.header("Authorization", token);
            requestBuilder.header("Cookie", "mode=relay; Trim-MC-token=" + token);
        } else {
            requestBuilder.header("Cookie", "mode=relay");
        }
        // GET 请求不需要 Content-Type
        if (!original.method().equalsIgnoreCase("GET") || original.body() != null) {
            requestBuilder.header("Content-Type", "application/json");
        }

        return chain.proceed(requestBuilder.build());
    }
}
