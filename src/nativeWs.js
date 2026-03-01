import { Capacitor, registerPlugin } from '@capacitor/core';
import ioImport from 'socket.io-client';

const NativeWs = registerPlugin('NativeWs');

export function isNativeWsAvailable() {
  return Capacitor.isNativePlatform();
}

function parseWsLikeUrl(url) {
  const u = new URL(url);
  const protocol = u.protocol === 'wss:' ? 'https:' : 'http:';
  const baseUrl = `${protocol}//${u.host}`;
  const path = u.pathname || '/ws';
  const query = {};
  for (const [k, v] of u.searchParams.entries()) query[k] = v;
  return { baseUrl, path, query };
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

  const { baseUrl, path, query } = parseWsLikeUrl(url);

  const handlers = {
    open: [],
    close: [],
    error: [],
    message: [],
  };

  const io = ioImport?.default || ioImport;
  const socket = io(baseUrl, {
    path,
    query,
    autoConnect: false,
    reconnection: true,
  });

  socket.on('connect', () => {
    for (const fn of handlers.open) fn({ url });
  });

  socket.on('disconnect', (reason) => {
    for (const fn of handlers.close) fn({ reason });
  });

  socket.on('connect_error', (err) => {
    const extra = [];
    if (err?.type) extra.push(String(err.type));
    if (err?.description) extra.push(String(err.description));
    const msg = [err?.message || 'CONNECT_ERROR', ...extra].filter(Boolean).join(' | ');
    for (const fn of handlers.error) fn({ message: msg });
  });

  socket.on('error', (err) => {
    for (const fn of handlers.error) fn({ message: err?.message || 'ERROR' });
  });

  socket.on('message', (data) => {
    const text = (typeof data === 'string') ? data : JSON.stringify(data);
    for (const fn of handlers.message) fn({ text });
  });

  const api = {
    async connect() {
      socket.connect();
    },
    async close() {
      try { socket.disconnect(); } catch {}
    },
    async send(text) {
      socket.send(text);
    },
    on(event, fn) {
      handlers[event]?.push(fn);
    },
    async dispose() {
      try { await api.close(); } catch {}
      try { socket.removeAllListeners(); } catch {}
      try { socket.close?.(); } catch {}
    },
  };

  return { kind: 'web', api };
}
