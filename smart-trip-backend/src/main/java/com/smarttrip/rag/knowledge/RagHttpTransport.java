package com.smarttrip.rag.knowledge;

import java.io.*;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.http.*;
import org.springframework.http.client.*;

/** A single-attempt transport shared by the SDK and Elasticsearch; no redirects or reconnect retries. */
final class RagHttpTransport {
  private RagHttpTransport() {}
  static String baseUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
          || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
        throw new IllegalArgumentException();
      return value.replaceAll("/+$", "");
    } catch (RuntimeException e) { throw new RagException("RAG_CONFIG_INVALID", "RAG服务地址配置无效"); }
  }
  static OkHttpClient client(int timeoutMillis) {
    if (timeoutMillis < 100 || timeoutMillis > 300000)
      throw new RagException("RAG_CONFIG_INVALID", "RAG超时必须介于100和300000毫秒");
    return new OkHttpClient.Builder().connectTimeout(Math.min(timeoutMillis, 2000), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS).callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
  }
  static ClientHttpRequestFactory factory(OkHttpClient client) {
    return (uri, method) -> new AbstractBufferingClientHttpRequest() {
      public URI getURI() { return uri; }
      public HttpMethod getMethod() { return method; }
      protected ClientHttpResponse executeInternal(HttpHeaders headers, byte[] bytes) throws IOException {
        Request.Builder request = new Request.Builder().url(uri.toString());
        headers.forEach((key, values) -> values.forEach(value -> request.addHeader(key, value)));
        boolean bodyAllowed = !method.equals(HttpMethod.GET) && !method.equals(HttpMethod.HEAD);
        request.method(method.name(), bodyAllowed ? RequestBody.create(bytes, (okhttp3.MediaType) null) : null);
        Response response = client.newCall(request.build()).execute();
        return new ClientHttpResponse() {
          public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(response.code()); }
          public String getStatusText() { return response.message(); }
          public HttpHeaders getHeaders() {
            HttpHeaders converted = new HttpHeaders();
            response.headers().toMultimap().forEach(converted::put);
            return converted;
          }
          public InputStream getBody() { return response.body() == null ? InputStream.nullInputStream() : response.body().byteStream(); }
          public void close() { response.close(); }
        };
      }
    };
  }
  static boolean timeout(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof java.net.SocketTimeoutException || cause instanceof InterruptedIOException
          || cause instanceof java.util.concurrent.TimeoutException) return true;
    return false;
  }
}