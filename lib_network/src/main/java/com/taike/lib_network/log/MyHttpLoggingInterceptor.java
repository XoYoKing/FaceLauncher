package com.taike.lib_network.log;


import android.os.Build;
import android.text.TextUtils;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.http.HttpHeaders;
import okio.Buffer;
import okio.BufferedSource;
import okio.GzipSource;

/**
 * An OkHttp interceptor which logs request and response information. Can be applied as an
 * {@linkplain OkHttpClient#interceptors() application interceptor} or as a {@linkplain
 * OkHttpClient#networkInterceptors() network interceptor}. <p> The format of the logs created by
 * this class should not be considered stable and may change slightly between releases. If you need
 * a stable logging format, use your own interceptor.
 */
public final class MyHttpLoggingInterceptor implements Interceptor {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public enum Level {
        /**
         * No logs.
         */
        NONE,
        /**
         * Logs request and response lines.
         *
         * <p>Example:
         * <pre>{@code
         * --> POST /greeting http/1.1 (3-byte body)
         *
         * <-- 200 OK (22ms, 6-byte body)
         * }</pre>
         */
        BASIC,
        /**
         * Logs request and response lines and their respective headers.
         *
         * <p>Example:
         * <pre>{@code
         * --> POST /greeting http/1.1
         * Host: example.com
         * Content-Type: plain/text
         * Content-Length: 3
         * --> END POST
         *
         * <-- 200 OK (22ms)
         * Content-Type: plain/text
         * Content-Length: 6
         * <-- END HTTP
         * }</pre>
         */
        HEADERS,
        /**
         * Logs request and response lines and their respective headers and bodies (if present).
         *
         * <p>Example:
         * <pre>{@code
         * --> POST /greeting http/1.1
         * Host: example.com
         * Content-Type: plain/text
         * Content-Length: 3
         *
         * Hi?
         * --> END POST
         *
         * <-- 200 OK (22ms)
         * Content-Type: plain/text
         * Content-Length: 6
         *
         * Hello!
         * <-- END HTTP
         * }</pre>
         */
        BODY
    }

    private final Logger logger;
    private LogFilter filter;

    private volatile Set<String> careHeaders = new HashSet<>();
    private volatile Level level = Level.NONE;

    public MyHttpLoggingInterceptor() {
        this(Logger.DEFAULT);
    }

    public MyHttpLoggingInterceptor(Logger logger) {
        this(logger, null);
    }

    /**
     * @param logger 实现具体的打印逻辑
     * @param filter 对请求参数或响应数据中的敏感信息进行过滤
     */
    public MyHttpLoggingInterceptor(Logger logger, LogFilter filter) {
        this.logger = logger;
        this.filter = filter;
    }

    public LogFilter getFilter() {
        return filter;
    }

    public void setFilter(LogFilter filter) {
        this.filter = filter;
    }

    /**
     * Hear里有很多信息，此处可添加你关心的信息，它们将在日志中输出，否则，请求头信息不会输出到日志中
     *
     * @param names 请求头信息的Key
     */
    public void setCareHeaders(String... names) {
        careHeaders.addAll(Arrays.asList(names));
    }


    /**
     * Change the level at which this interceptor logs.
     */
    public MyHttpLoggingInterceptor setLevel(Level level) {
        if (level == null) throw new NullPointerException("level == null. Use Level.NONE instead.");
        this.level = level;
        return this;
    }

    public Level getLevel() {
        return level;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        long startNs = System.currentTimeMillis();
        Level level = this.level;
        Request request = chain.request();
        if (level == Level.NONE) {
            return chain.proceed(request);
        }
        boolean logBody = level == Level.BODY;
        RequestBody requestBody = request.body();
        boolean logHeaders = logBody || level == Level.HEADERS;
        if (logHeaders) {
            Headers headers = request.headers();
            StringBuilder headBuilder = new StringBuilder();
            for (int i = 0, count = headers.size(); i < count; i++) {
                String name = headers.name(i);
                if (!"Content-Type".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)) {
                    String part = getLogHeader(headers, i);
                    if (part != null) {
                        headBuilder.append(part);
                        if (i != headers.size() - 1) {
                            headBuilder.append(",");
                        }
                    }
                }
            }
            if (headBuilder.length() > 0) {
                headBuilder.insert(0, "url：" + request.url() + " header:{");
                headBuilder.insert(headBuilder.length(), "}");
                logHeader(headBuilder.toString());
            }
            String requestBodyString = "";
            if (requestBody != null) {
                Buffer buffer = new Buffer();
                requestBody.writeTo(buffer);
                Charset charset = UTF8;
                MediaType contentType = requestBody.contentType();
                if (contentType != null) {
                    charset = contentType.charset(UTF8);
                }
                if (isPlaintext(buffer) && charset != null) {
                    requestBodyString = buffer.readString(charset);
                }
            }

            StringBuilder requestMessage = new StringBuilder("-------->>>>>>Request:")
                    .append(request.method())
                    .append(" url：")
                    .append(request.url());

            if (!TextUtils.isEmpty(requestBodyString)) {
                if (filter != null) {
                    requestBodyString = filter.filter(requestBodyString);//过滤
                }
                requestMessage.append(" body:").append(requestBodyString);
            }
            logger.log(requestMessage.toString());
        }

        Response response;
        try {
            response = chain.proceed(request);
        } catch (Exception e) {
            logger.log("<-- HTTP FAILED: " + e);
            throw e;
        }

        long costTime = System.currentTimeMillis() - startNs;

        String responseBodyString = "";
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            return response;
        }
        long contentLength = responseBody.contentLength();
        if (logHeaders) {
            Headers headers = response.headers();
            for (int i = 0, count = headers.size(); i < count; i++) {
                getLogHeader(headers, i);
            }
            if (logBody && HttpHeaders.hasBody(response)) {
                if (!bodyHasUnknownEncoding(response.headers())) {
                    BufferedSource source = responseBody.source();
                    source.request(Long.MAX_VALUE); // Buffer the entire body.
                    Buffer buffer = source.buffer();

                    if ("gzip".equalsIgnoreCase(headers.get("Content-Encoding")) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        try (GzipSource gzippedResponseBody = new GzipSource(buffer.clone())) {
                            buffer = new Buffer();
                            buffer.writeAll(gzippedResponseBody);
                        }
                    }
                    Charset charset = UTF8;
                    MediaType contentType = responseBody.contentType();
                    if (contentType != null) {
                        charset = contentType.charset(UTF8);
                    }
                    if (contentLength != 0 && charset != null) {
                        responseBodyString = buffer.clone().readString(charset);
                        if (filter != null) {
                            responseBodyString = filter.filter(responseBodyString);//过滤
                        }
                    }
                }
            }
        }
        logResponse(response, costTime, responseBodyString);
        return response;
    }

    void logHeader(String headerInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Objects.requireNonNull(logger, "logger is cannot be null");
        }
        logger.log(headerInfo);
    }


    private void logResponse(Response response, long costTime, String responseBodyString) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Objects.requireNonNull(logger, "logger is cannot be null");
        }
        logger.log("<<<<<<--------Response:" +
                " costTime:" + costTime +" ms"+
                " code: " + response.code() +
                " url: " + response.request().url() +
                " body: " + (TextUtils.isEmpty(responseBodyString) ? "" : responseBodyString));
    }


    private String getLogHeader(Headers headers, int i) {
        if (!careHeaders.contains(headers.name(i))) {
            return null;
        }
        return "{" + headers.name(i) + ":" + headers.value(i) + "}";
    }

    /**
     * Returns true if the body in question probably contains human readable text. Uses a small sample
     * of code points to detect unicode control characters commonly used in binary file signatures.
     */
    static boolean isPlaintext(Buffer buffer) {
        try {
            Buffer prefix = new Buffer();
            long byteCount = buffer.size() < 64 ? buffer.size() : 64;
            buffer.copyTo(prefix, 0, byteCount);
            for (int i = 0; i < 16; i++) {
                if (prefix.exhausted()) {
                    break;
                }
                int codePoint = prefix.readUtf8CodePoint();
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                    return false;
                }
            }
            return true;
        } catch (EOFException e) {
            return false; // Truncated UTF-8 sequence.
        }
    }

    private static boolean bodyHasUnknownEncoding(Headers headers) {
        String contentEncoding = headers.get("Content-Encoding");
        return contentEncoding != null
                && !contentEncoding.equalsIgnoreCase("identity")
                && !contentEncoding.equalsIgnoreCase("gzip");
    }
}

