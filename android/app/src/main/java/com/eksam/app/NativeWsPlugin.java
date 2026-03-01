package com.eksam.app;

import androidx.annotation.Nullable;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

@CapacitorPlugin(name = "NativeWs")
public class NativeWsPlugin extends Plugin {
  private OkHttpClient client;
  private WebSocket webSocket;
  private volatile boolean isOpen = false;
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
    disconnectInternal();

    if (client == null) {
      client = new OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build();
    }

    Request request;
    try {
      request = new Request.Builder().url(currentUrl).build();
    } catch (IllegalArgumentException e) {
      JSObject info = new JSObject();
      info.put("raw", currentUrl);
      info.put("message", e.getMessage());
      info.put("name", e.getClass().getName());
      call.reject("INVALID_URL", info);
      return;
    }

    webSocket = client.newWebSocket(request, new WebSocketListener() {
      @Override
      public void onOpen(WebSocket ws, Response response) {
        isOpen = true;
        JSObject data = new JSObject();
        data.put("url", currentUrl);
        if (response != null) {
          data.put("httpCode", response.code());
          data.put("httpMessage", response.message());
        }
        notifyListeners("open", data);
      }

      @Override
      public void onMessage(WebSocket ws, String text) {
        JSObject data = new JSObject();
        data.put("text", text != null ? text : "");
        notifyListeners("message", data);
      }

      @Override
      public void onMessage(WebSocket ws, ByteString bytes) {
        JSObject data = new JSObject();
        data.put("text", bytes != null ? bytes.utf8() : "");
        notifyListeners("message", data);
      }

      @Override
      public void onClosing(WebSocket ws, int code, String reason) {
        isOpen = false;
        JSObject data = new JSObject();
        data.put("code", code);
        data.put("reason", reason != null ? reason : "");
        notifyListeners("close", data);
        ws.close(code, reason);
      }

      @Override
      public void onClosed(WebSocket ws, int code, String reason) {
        isOpen = false;
        JSObject data = new JSObject();
        data.put("code", code);
        data.put("reason", reason != null ? reason : "");
        notifyListeners("close", data);
      }

      @Override
      public void onFailure(WebSocket ws, Throwable t, Response response) {
        isOpen = false;
        notifyError(t, response);
      }
    });

    JSObject ret = new JSObject();
    ret.put("ok", true);
    ret.put("url", currentUrl);
    call.resolve(ret);
  }

  @PluginMethod
  public void disconnect(PluginCall call) {
    disconnectInternal();
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
    if (webSocket == null || !isOpen) {
      call.reject("NOT_CONNECTED");
      return;
    }

    boolean ok;
    try {
      ok = webSocket.send(text);
    } catch (Exception e) {
      JSObject info = new JSObject();
      info.put("message", e.getMessage());
      info.put("name", e.getClass().getName());
      call.reject("SEND_FAILED", info);
      return;
    }

    if (!ok) {
      call.reject("SEND_FAILED");
      return;
    }

    JSObject ret = new JSObject();
    ret.put("ok", true);
    call.resolve(ret);
  }

  @PluginMethod
  public void status(PluginCall call) {
    JSObject ret = new JSObject();
    ret.put("connected", webSocket != null && isOpen);
    ret.put("url", currentUrl);
    call.resolve(ret);
  }

  private void disconnectInternal() {
    WebSocket ws = webSocket;
    webSocket = null;
    isOpen = false;

    if (ws != null) {
      try {
        ws.close(1000, "CLIENT_CLOSE");
      } catch (Exception ignored) {
      }
      try {
        ws.cancel();
      } catch (Exception ignored) {
      }
    }
  }

  private void notifyError(@Nullable Throwable t, @Nullable Response response) {
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
    }
    if (response != null) {
      data.put("httpCode", response.code());
      data.put("httpMessage", response.message());
      try {
        data.put("httpBody", response.body() != null ? response.body().string() : null);
      } catch (IOException ignored) {
      }
    }
    notifyListeners("error", data);
  }
}
