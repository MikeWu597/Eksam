package com.eksam.app;

import androidx.annotation.Nullable;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import io.socket.client.IO;
import io.socket.client.Socket;

@CapacitorPlugin(name = "NativeWs")
public class NativeWsPlugin extends Plugin {
  private Socket socket;
  private String currentUrl;

  @Override
  public void load() {
    // no-op
  }

  @PluginMethod
  public void connect(PluginCall call) {
    String url = call.getString("url");
    if (url == null || url.trim().isEmpty()) {
      call.reject("MISSING_URL");
      return;
    }

    currentUrl = url.trim();

    // Socket.IO Java client expects http/https base URL and uses a configurable "path" for the Engine.IO endpoint.
    // We accept a ws/wss-like URL (e.g. wss://host/ws?role=candidate) and convert it.
    String httpLikeUrl = currentUrl;
    if (httpLikeUrl.startsWith("ws://")) httpLikeUrl = "http://" + httpLikeUrl.substring("ws://".length());
    if (httpLikeUrl.startsWith("wss://")) httpLikeUrl = "https://" + httpLikeUrl.substring("wss://".length());

    URI uri;
    try {
      uri = new URI(httpLikeUrl);
    } catch (URISyntaxException e) {
      call.reject("INVALID_URL", e);
      return;
    }

    String scheme = uri.getScheme();
    String host = uri.getHost();
    int port = uri.getPort();
    if (scheme == null || host == null) {
      JSObject info = new JSObject();
      info.put("raw", currentUrl);
      info.put("httpLike", httpLikeUrl);
      info.put("scheme", scheme);
      info.put("host", host);
      info.put("port", port);
      call.reject("INVALID_URL", info);
      return;
    }

    String baseUrl = scheme + "://" + host + (port != -1 ? (":" + port) : "");
    String path = uri.getPath();
    if (path == null || path.trim().isEmpty()) path = "/ws";
    String query = uri.getQuery();

    closeSocket();

    IO.Options opts = new IO.Options();
    opts.path = path;
    opts.query = query;
    opts.reconnection = true;
    opts.forceNew = true;
    opts.timeout = TimeUnit.SECONDS.toMillis(20);

    try {
      socket = IO.socket(baseUrl, opts);
    } catch (Exception e) {
      JSObject info = new JSObject();
      info.put("raw", currentUrl);
      info.put("baseUrl", baseUrl);
      info.put("path", path);
      info.put("query", query);
      info.put("message", e.getMessage());
      info.put("name", e.getClass().getName());
      call.reject("CONNECT_INIT_FAILED", info);
      return;
    }

    socket.on(Socket.EVENT_CONNECT, (args) -> {
      JSObject data = new JSObject();
      data.put("url", currentUrl);
      notifyListeners("open", data);
    });

    socket.on("message", (args) -> {
      Object first = (args != null && args.length > 0) ? args[0] : null;
      JSObject data = new JSObject();
      data.put("text", first != null ? String.valueOf(first) : "");
      notifyListeners("message", data);
    });

    socket.on(Socket.EVENT_DISCONNECT, (args) -> {
      JSObject data = new JSObject();
      data.put("reason", (args != null && args.length > 0) ? String.valueOf(args[0]) : "DISCONNECT");
      notifyListeners("close", data);
    });

    socket.on(Socket.EVENT_CONNECT_ERROR, (args) -> notifyError(args));
    socket.on("error", (args) -> notifyError(args));

    socket.connect();

    JSObject ret = new JSObject();
    ret.put("ok", true);
    ret.put("url", currentUrl);
    call.resolve(ret);
  }

  @PluginMethod
  public void disconnect(PluginCall call) {
    closeSocket();
    JSObject ret = new JSObject();
    ret.put("ok", true);
    call.resolve(ret);
  }

  @PluginMethod
  public void send(PluginCall call) {
    String text = call.getString("text");
    if (text == null) {
      call.reject("MISSING_TEXT");
      return;
    }
    if (socket == null || socket.connected() != true) {
      call.reject("NOT_CONNECTED");
      return;
    }
    socket.send(text);
    JSObject ret = new JSObject();
    ret.put("ok", true);
    call.resolve(ret);
  }

  @PluginMethod
  public void status(PluginCall call) {
    JSObject ret = new JSObject();
    ret.put("connected", socket != null && socket.connected() == true);
    ret.put("url", currentUrl);
    call.resolve(ret);
  }

  private void closeSocket() {
    if (socket != null) {
      try {
        socket.off();
      } catch (Exception ignored) {
      }
      try {
        socket.disconnect();
      } catch (Exception ignored) {
      }
      try {
        socket.close();
      } catch (Exception ignored) {
      }
      socket = null;
    }
  }

  private void notifyError(@Nullable Object[] args) {
    Throwable t = null;
    if (args != null && args.length > 0 && args[0] instanceof Throwable) {
      t = (Throwable) args[0];
    }

    JSObject data = new JSObject();
    if (t != null) {
      data.put("message", t.getMessage());
      data.put("name", t.getClass().getName());
      try {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        data.put("stack", sw.toString());
      } catch (Exception ignored) {
      }
    } else {
      String msg = "CONNECT_ERROR";
      if (args != null && args.length > 0 && args[0] != null) {
        msg = String.valueOf(args[0]);
      }
      data.put("message", msg);
    }
    notifyListeners("error", data);
  }
}
