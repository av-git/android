package com.mapzen.android.graphics;

import com.mapzen.tangram.HttpHandler;

import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionSpec;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.TlsVersion;

/**
 * Temporary {@link MapzenMapHttpHandler.InternalHttpHandler} super class until Tangram allows
 * customizing headers.
 * https://github.com/tangrams/tangram-es/issues/1542
 */
class TmpHttpHandler extends HttpHandler {

  OkHttpClient okClient;

  /**
   * Enables TLS v1.2 when creating SSLSockets.
   * <p/>
   * For some reason, android supports TLS v1.2 from API 16, but enables it by
   * default only from API 20.
   *
   * @link https://developer.android.com/reference/javax/net/ssl/SSLSocket.html
   * @see SSLSocketFactory
   */
  private class Tls12SocketFactory extends SSLSocketFactory {
    private final String[] TLS_V12_ONLY = {"TLSv1.2"};

    final SSLSocketFactory delegate;

    public Tls12SocketFactory(SSLSocketFactory base) {
      this.delegate = base;
    }

    @Override
    public String[] getDefaultCipherSuites() {
      return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
      return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws
        IOException {
      return patch(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
      return patch(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException {
      return patch(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
      return patch(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
      return patch(delegate.createSocket(address, port, localAddress, localPort));
    }

    private Socket patch(Socket s) {
      if (s instanceof SSLSocket) {
        ((SSLSocket) s).setEnabledProtocols(TLS_V12_ONLY);
      }
      return s;
    }
  }

  /**
   * Construct an {@code HttpHandler} with default options.
   */
  public TmpHttpHandler() {
    this(null, 0);
  }

  /**
   * Construct an {@code HttpHandler} with cache.
   * Cache map data in a directory with a specified size limit
   * @param directory Directory in which map data will be cached
   * @param maxSize Maximum size of data to cache, in bytes
   */
  public TmpHttpHandler(File directory, long maxSize) {
    OkHttpClient.Builder builder = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS);

    if (directory != null && maxSize > 0) {
      builder.cache(new Cache(directory, maxSize));
    }

    if (Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 22) {
      try {
        SSLContext sc = SSLContext.getInstance("TLSv1.2");
        sc.init(null, null, null);
        builder.sslSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));

        ConnectionSpec cs = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .build();

        List<ConnectionSpec> specs = new ArrayList<>();
        specs.add(cs);
        specs.add(ConnectionSpec.COMPATIBLE_TLS);
        specs.add(ConnectionSpec.CLEARTEXT);

        builder.connectionSpecs(specs);
      } catch (Exception exc) {
        android.util.Log.e("Tangram", "Error while setting TLS 1.2", exc);
      }
    }

    okClient = builder.build();
  }

  /**
   * Begin an HTTP request
   * @param url URL for the requested resource
   * @param cb Callback for handling request result
   * @return true if request was successfully started
   */
  public boolean onRequest(String url, Map<String, String> headers, Callback cb) {
    Request request = new Request.Builder()
        .url(url)
        .headers(Headers.of(headers))
        .build();
    okClient.newCall(request).enqueue(cb);
    return true;
  }

  /**
   * Cancel an HTTP request
   * @param url URL of the request to be cancelled
   */
  public void onCancel(String url) {

    // check and cancel running call
    for (Call runningCall : okClient.dispatcher().runningCalls()) {
      if (runningCall.request().url().toString().equals(url)) {
        runningCall.cancel();
      }
    }

    // check and cancel queued call
    for (Call queuedCall : okClient.dispatcher().queuedCalls()) {
      if (queuedCall.request().url().toString().equals(url)) {
        queuedCall.cancel();
      }
    }
  }

}
