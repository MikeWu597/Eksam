import { Capacitor, registerPlugin } from '@capacitor/core';

const NativeWs = registerPlugin('NativeWs');

export function isNativeWsAvailable() {
  return Capacitor.isNativePlatform();
}

/**
 * Creates a unified WS-like client.
 * - Native: uses the NativeWs Capacitor plugin.
 * - Web: uses browser WebSocket.
 */
export function createWsClient(url) {
  if (isNativeWsAvailable()) {
    const handlers = {
      open: [],
      close: [],
      error: [],
      message: [],
    };

    const removeOpen = NativeWs.addListener('open', (ev) => {
      for (const fn of handlers.open) fn(ev);
    });
    const removeClose = NativeWs.addListener('close', (ev) => {
      for (const fn of handlers.close) fn(ev);
    });
    const removeError = NativeWs.addListener('error', (ev) => {
      for (const fn of handlers.error) fn(ev);
    });
    const removeMessage = NativeWs.addListener('message', (ev) => {
      for (const fn of handlers.message) fn(ev);
    });

    let connected = false;

    const api = {
      async connect() {
        await NativeWs.connect({ url });
      },
      async close() {
        await NativeWs.disconnect();
        connected = false;
      },
      async send(text) {
        await NativeWs.send({ text });
      },
      on(event, fn) {
        handlers[event]?.push(fn);
      },
      async dispose() {
        try { await api.close(); } catch {}
        try { (await removeOpen).remove(); } catch {}
        try { (await removeClose).remove(); } catch {}
        try { (await removeError).remove(); } catch {}
        try { (await removeMessage).remove(); } catch {}
      },
      get connected() {
        return connected;
      },
      set connected(v) {
        connected = !!v;
      },
    };

    return { kind: 'native', api };
  }

  const handlers = {
    open: [],
    close: [],
    error: [],
    message: [],
  };

  let socket = null;

  const api = {
    async connect() {
      if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;
      socket = new WebSocket(url);
      socket.onopen = (ev) => {
        for (const fn of handlers.open) fn(ev);
      };
      socket.onclose = (ev) => {
        for (const fn of handlers.close) fn(ev);
      };
      socket.onerror = () => {
        for (const fn of handlers.error) fn({ message: 'WEBSOCKET_ERROR' });
      };
      socket.onmessage = (ev) => {
        for (const fn of handlers.message) fn({ text: ev.data });
      };
    },
    async close() {
      try { socket?.close?.(); } catch {}
      socket = null;
    },
    async send(text) {
      socket?.send?.(text);
    },
    on(event, fn) {
      handlers[event]?.push(fn);
    },
    async dispose() {
      try { await api.close(); } catch {}
    },
  };

  return { kind: 'web', api };
}
