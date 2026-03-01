<template>
  <div style="padding: 16px; max-width: 720px; margin: 0 auto;">
    <h2 :class="['clock', { focus: clockFocus }]" @click="handleClockTap">{{ clockLabel }}</h2>

    <transition name="fade-slide" mode="out-in">
      <div v-if="clockFocus" class="row" style="justify-content: center; margin-bottom: 12px;">
        <el-button type="primary" @click="clockFocus = false">展开</el-button>
      </div>
    </transition>

    <transition name="fade-slide" mode="out-in">
      <el-card v-if="!clockFocus">
      <template #header>
        <div style="display:flex; justify-content: space-between; align-items: center; gap: 12px;">
          <div>
            <div><b>连接状态：</b>{{ connectionLabel }}</div>
            <div><b>考试状态：</b>{{ phaseLabel }}</div>
          </div>
          <div class="row" style="justify-content: flex-end;">
            <el-button :disabled="connecting" @click="reconnect">重连</el-button>
            <el-button :disabled="connecting" @click="enterClockFocus">时钟</el-button>
          </div>
        </div>
      </template>

      <div v-if="lastNotification" style="margin-bottom: 12px;">
        <el-alert :title="lastNotification" type="info" show-icon />
      </div>

      <el-alert
        v-if="requiredPhotoKind"
        :title="photoHint"
        type="warning"
        show-icon
        style="margin-bottom: 12px;"
      />

      <div v-if="requiredPhotoKind" class="row">
        <el-button type="primary" @click="openPhotoDialog">拍照</el-button>
        <el-button @click="clearDraft">清空</el-button>
      </div>

      <div style="margin-top: 12px;" v-if="lastUploadOk">
        <el-alert title="已上传成功" type="success" show-icon />
      </div>

      <div style="margin-top: 12px;">
        <div class="msgRow">
          <el-input class="msgInput" v-model="candidateMsg" placeholder="给监考端发送消息" :disabled="!connected" />
          <el-button type="primary" :disabled="!connected || !candidateMsg.trim()" @click="sendCandidateMessage">发送</el-button>
        </div>
      </div>

      <div v-if="debugMode" style="margin-top: 12px;">
        <div class="row" style="justify-content: space-between; align-items: center;">
          <b>调试日志</b>
          <el-button size="small" @click="clearLogs">清空</el-button>
        </div>
        <div class="logBox"><pre class="logPre">{{ logsText }}</pre></div>
      </div>
      </el-card>
    </transition>

    <el-dialog v-model="photoDialogVisible" title="拍照预览" width="95%" :close-on-click-modal="false">
      <div class="row" style="align-items:center; margin-bottom: 12px;">
        <el-button type="primary" :disabled="uploading || addDisabled" @click="triggerPick">
          {{ requiredPhotoKind === 'collect_paper' ? '添加一张' : '选择照片' }}
        </el-button>
        <div class="muted">
          {{ requiredPhotoKind === 'paper_check' ? '试卷检查：仅 1 张' : '收卷：可多张' }}
        </div>
      </div>

      <div v-if="draftPhotos.length === 0" class="muted">尚未拍照/选择</div>

      <div v-else class="grid">
        <div v-for="p in draftPhotos" :key="p.id" class="thumb">
          <img :src="p.previewUrl" alt="preview" />
          <div class="row" style="justify-content: space-between; margin-top: 6px;">
            <div class="muted" style="max-width: 72%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
              {{ p.file.name }}
            </div>
            <el-button size="small" :disabled="uploading" @click="removeDraft(p.id)">移除</el-button>
          </div>
        </div>
      </div>

      <template #footer>
        <el-button :disabled="uploading" @click="photoDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="draftPhotos.length === 0 || uploading" :loading="uploading" @click="uploadAll">
          确认上传
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { createWsClient, isNativeWsAvailable } from './nativeWs';
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera';

const serverUrl = (import.meta.env.VITE_SERVER_URL || 'http://localhost:3000').replace(/\/$/, '');

const ws = ref(null);
const connecting = ref(false);
const connected = ref(false);

const debugMode = ref(false);
const logs = ref([]); // { ts, text }
let clockTapCount = 0;
let lastClockTapAt = 0;

function logLine(text) {
  const t = String(text || '').trim();
  if (!t) return;
  logs.value.push({ ts: Date.now(), text: t });
  if (logs.value.length > 200) logs.value.splice(0, logs.value.length - 200);
}

function clearLogs() {
  logs.value = [];
}

const logsText = computed(() => {
  return logs.value
    .map((x) => `${new Date(x.ts).toLocaleTimeString()}  ${x.text}`)
    .join('\n');
});

function handleClockTap() {
  const now = Date.now();
  if (now - lastClockTapAt > 1500) {
    clockTapCount = 0;
  }
  lastClockTapAt = now;
  clockTapCount += 1;
  if (clockTapCount >= 10) {
    clockTapCount = 0;
    debugMode.value = true;
    ElMessage.success('调试模式已开启');
    logLine('DEBUG_MODE_ENABLED');
  }
}

let wsClient = null; // { kind, api }

let autoReconnectTimer = null;

const latencyMs = ref(null);
let pingTimer = null;

let listeningAudio = null;
const listeningAudioUrl = ref('');

const phase = ref('idle');
const lastNotification = ref('');
const requiredPhotoKind = ref(null); // 'paper_check' | 'collect_paper' | null

const clockMode = ref('up'); // 'up' | 'down'
const clockValueSec = ref(0); // up: elapsed, down: remaining
let tickTimer = null;
let syncTimer = null;

const photoDialogVisible = ref(false);
const uploading = ref(false);
const lastUploadOk = ref(false);

const draftPhotos = ref([]); // { id, file, previewUrl }
const addDisabled = computed(() => requiredPhotoKind.value === 'paper_check' && draftPhotos.value.length >= 1);

const clockFocus = ref(false);

const candidateMsg = ref('');

const connectionLabel = computed(() => {
  if (connecting.value) return '连接中';
  if (!connected.value) return '未连接';
  if (latencyMs.value == null) return '延迟 -';
  return `延迟 ${latencyMs.value}ms`;
});

const phaseLabel = computed(() => {
  const map = {
    idle: '未开始',
    opened: '已开启',
    paper_check: '试卷检查',
    in_progress: '考试中',
    ended: '已下考',
    collecting: '收卷中',
    closed: '已关闭',
  };
  return map[phase.value] || phase.value;
});

const clockLabel = computed(() => formatClock(clockValueSec.value));

const photoHint = computed(() => {
  if (requiredPhotoKind.value === 'paper_check') return '请按要求拍摄“试卷检查”照片并上传';
  if (requiredPhotoKind.value === 'collect_paper') return '请按要求拍摄“收卷”照片并上传';
  return '';
});

function wsUrl() {
  const u = new URL(serverUrl);
  u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
  u.pathname = '/ws';
  u.search = '?role=candidate';
  return u.toString();
}

function sendAck(commandType) {
  try {
    wsClient?.api?.send?.(JSON.stringify({ type: 'ack', ts: Date.now(), commandType }));
  } catch {}
}

function sendPing() {
  if (!connected.value) return;
  try {
    const payload = JSON.stringify({ type: 'ping', ts: Date.now(), payload: { clientTs: Date.now() } });
    wsClient?.api?.send?.(payload);
  } catch {}
}

function sendCandidateMessage() {
  const text = candidateMsg.value.trim();
  if (!text) return;
  if (!connected.value) return;
  try {
    const payload = JSON.stringify({ type: 'candidate_message', ts: Date.now(), payload: { text } });
    wsClient?.api?.send?.(payload);
    candidateMsg.value = '';
    ElMessage.success('已发送');
  } catch {
    ElMessage.error('发送失败');
  }
}

function sendNotifyReceipt(text) {
  const t = String(text || '').trim();
  if (!t) return;
  if (!connected.value) return;
  try {
    const payload = JSON.stringify({ type: 'notify_receipt', ts: Date.now(), payload: { text: t } });
    wsClient?.api?.send?.(payload);
  } catch {}
}

function formatClock(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const mm = String(Math.floor(sec / 60)).padStart(2, '0');
  const ss = String(sec % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

function getStartAt() {
  const raw = localStorage.getItem('eksam_examStartAt');
  const v = Number(raw);
  return Number.isFinite(v) && v > 0 ? v : null;
}

function setStartAt(ms) {
  localStorage.setItem('eksam_examStartAt', String(ms));
}

function clearStartAt() {
  localStorage.removeItem('eksam_examStartAt');
}

function getClockMode() {
  const v = localStorage.getItem('eksam_clockMode');
  return v === 'down' ? 'down' : 'up';
}

function setClockMode(mode) {
  localStorage.setItem('eksam_clockMode', mode === 'down' ? 'down' : 'up');
}

function clearClockMode() {
  localStorage.removeItem('eksam_clockMode');
}

function getEndAt() {
  const raw = localStorage.getItem('eksam_examEndAt');
  const v = Number(raw);
  return Number.isFinite(v) && v > 0 ? v : null;
}

function setEndAt(ms) {
  localStorage.setItem('eksam_examEndAt', String(ms));
}

function clearEndAt() {
  localStorage.removeItem('eksam_examEndAt');
}

function computeElapsedFromStartAt(startAt) {
  return Math.max(0, Math.floor((Date.now() - startAt) / 1000));
}

function computeRemainingFromEndAt(endAt) {
  return Math.max(0, Math.floor((endAt - Date.now()) / 1000));
}

function stopTimers() {
  if (tickTimer) {
    clearInterval(tickTimer);
    tickTimer = null;
  }
  if (syncTimer) {
    clearInterval(syncTimer);
    syncTimer = null;
  }
}

function sendTimerSync() {
  if (!connected.value) return;
  if (phase.value !== 'in_progress') return;
  try {
    const payload = JSON.stringify({
      type: 'timer_sync',
      ts: Date.now(),
      payload: { mode: clockMode.value, valueSec: clockValueSec.value },
    });
    wsClient?.api?.send?.(payload);
  } catch {}
}

function cleanupSocket() {
  try {
    wsClient?.api?.dispose?.();
  } catch {}
  wsClient = null;
  ws.value = null;
}

function handleIncomingText(text) {
  let msg;
  try { msg = JSON.parse(text); } catch { return; }

  if (debugMode.value) {
    const mt = String(msg?.type || '').trim();
    if (mt) logLine(`RECV type=${mt}`);
  }

  if (msg?.type === 'hello') {
    applySnapshot(msg.data);
    return;
  }

  if (msg?.type === 'command') {
    handleCommand(msg.command);
  }

  if (msg?.type === 'pong') {
    const clientTs = Number(msg?.payload?.clientTs);
    if (Number.isFinite(clientTs) && clientTs > 0) {
      latencyMs.value = Math.max(0, Math.floor(Date.now() - clientTs));
    }
  }
}

function startTimersIfNeeded() {
  stopTimers();

  if (phase.value !== 'in_progress') return;
  clockMode.value = getClockMode();

  if (clockMode.value === 'down') {
    const endAt = getEndAt();
    if (!endAt) return;
    clockValueSec.value = computeRemainingFromEndAt(endAt);
  } else {
    const startAt = getStartAt();
    if (!startAt) return;
    clockValueSec.value = computeElapsedFromStartAt(startAt);
  }

  tickTimer = setInterval(() => {
    clockMode.value = getClockMode();
    if (clockMode.value === 'down') {
      const e = getEndAt();
      if (!e) return;
      clockValueSec.value = computeRemainingFromEndAt(e);
      if (clockValueSec.value <= 0) {
        clockValueSec.value = 0;
        // 倒计时到 0 后停止同步/计时
        stopTimers();
        sendTimerSync();
      }
      return;
    }

    const s = getStartAt();
    if (!s) return;
    clockValueSec.value = computeElapsedFromStartAt(s);
  }, 1000);

  syncTimer = setInterval(() => {
    sendTimerSync();
  }, 1000);

  // 立刻同步一次
  sendTimerSync();
}

function applySnapshot(snapshot) {
  if (!snapshot) return;
  if (snapshot.phase) phase.value = snapshot.phase;
  requiredPhotoKind.value = snapshot.pendingPhotoKind || null;

  // 如果服务端已有时钟，而本地没有 startAt，则用它推算 startAt
  if (phase.value === 'in_progress') {
    const snapMode = snapshot.examClockMode === 'down' ? 'down' : 'up';
    setClockMode(snapMode);
    const sec = Number(snapshot.examClockSec);
    if (Number.isFinite(sec) && sec >= 0) {
      if (snapMode === 'down') {
        if (!getEndAt()) setEndAt(Date.now() + Math.floor(sec) * 1000);
      } else {
        if (!getStartAt()) setStartAt(Date.now() - Math.floor(sec) * 1000);
      }
    }
  }

  if (phase.value !== 'in_progress') {
    clockValueSec.value = 0;
    clearStartAt();
    clearEndAt();
    clearClockMode();
  }

  startTimersIfNeeded();
}

async function handleCommand(command) {
  lastUploadOk.value = false;
  clearDraft();

  switch (command?.type) {
    case 'open_exam':
      phase.value = 'opened';
      requiredPhotoKind.value = null;
      break;
    case 'paper_check':
      phase.value = 'paper_check';
      requiredPhotoKind.value = 'paper_check';
      ElMessageBox.alert('请拍照并上传试卷检查照片', '试卷检查', { confirmButtonText: '知道了' }).catch(() => {});
      break;
    case 'start_exam':
      phase.value = 'in_progress';
      requiredPhotoKind.value = null;
      // 开考不归零：优先使用服务端下发的当前时钟值/模式；否则沿用本地已设置的时钟
      if (command?.payload && (command.payload.mode || command.payload.sec != null)) {
        const mode = command?.payload?.mode === 'down' ? 'down' : 'up';
        const sec = Number(command?.payload?.sec);
        if (Number.isFinite(sec) && sec >= 0) {
          clockMode.value = mode;
          setClockMode(mode);
          clockValueSec.value = Math.floor(sec);
          if (mode === 'down') {
            clearStartAt();
            setEndAt(Date.now() + Math.floor(sec) * 1000);
          } else {
            clearEndAt();
            setStartAt(Date.now() - Math.floor(sec) * 1000);
          }
        }
      } else {
        // 没有 payload 时，不改动现有锚点；如果从未设置过，则默认从 00:00 正计时
        const mode = getClockMode();
        if (!getStartAt() && !getEndAt()) {
          clockMode.value = mode;
          setClockMode(mode);
          clockValueSec.value = 0;
          clearEndAt();
          setStartAt(Date.now());
        }
      }
      startTimersIfNeeded();
      ElMessage.success('已开考');
      break;
    case 'ui_controls': {
      const visible = command?.payload?.visible !== false;
      clockFocus.value = !visible;
      break;
    }
    case 'listening_open': {
      const url = String(command?.payload?.audioUrl || '').trim();
      if (!url) break;
      const abs = url.startsWith('http') ? url : `${serverUrl}${url}`;
      listeningAudioUrl.value = abs;
      if (!listeningAudio) {
        listeningAudio = new Audio();
        listeningAudio.preload = 'auto';
      }
      listeningAudio.src = abs;
      // 不自动播放，等待监考端下发 play
      ElMessage.success('已开启听力');
      break;
    }
    case 'listening_play': {
      if (!listeningAudio) {
        ElMessage.error('未开启听力');
        break;
      }
      try {
        const p = listeningAudio.play();
        if (p && typeof p.then === 'function') {
          await p;
        }
      } catch {
        // 可能被浏览器/安卓 WebView 的自动播放策略阻止
        try {
          await ElMessageBox.alert('请点击“确认”以允许播放听力', '听力播放', {
            confirmButtonText: '确认',
            closeOnClickModal: false,
            closeOnPressEscape: false,
            showClose: false,
          });
          await listeningAudio.play();
        } catch {
          ElMessage.error('播放被阻止');
        }
      }
      break;
    }
    case 'listening_pause': {
      if (!listeningAudio) break;
      try { listeningAudio.pause(); } catch {}
      break;
    }
    case 'listening_seek': {
      if (!listeningAudio) break;
      const percent = Number(command?.payload?.percent);
      if (!Number.isFinite(percent)) break;
      const dur = Number(listeningAudio.duration);
      if (!Number.isFinite(dur) || dur <= 0) {
        ElMessage.warning('音频尚未就绪，无法跳转进度');
        break;
      }
      const target = Math.max(0, Math.min(dur, (dur * percent) / 100));
      try { listeningAudio.currentTime = target; } catch {}
      break;
    }
    case 'listening_close': {
      if (listeningAudio) {
        try { listeningAudio.pause(); } catch {}
        try { listeningAudio.src = ''; } catch {}
      }
      listeningAudioUrl.value = '';
      ElMessage.success('已关闭听力');
      break;
    }
    case 'clock_set': {
      const mode = command?.payload?.mode === 'down' ? 'down' : 'up';
      const sec = Number(command?.payload?.sec);
      if (!Number.isFinite(sec) || sec < 0) break;

      const normalizedSec = Math.floor(sec);
      clockValueSec.value = normalizedSec;

      clockMode.value = mode;
      setClockMode(mode);

      if (mode === 'down') {
        clearStartAt();
        setEndAt(Date.now() + normalizedSec * 1000);
      } else {
        clearEndAt();
        setStartAt(Date.now() - normalizedSec * 1000);
      }

      // 若在考试中：立即刷新并开始计时/同步；否则仅更新显示
      if (phase.value === 'in_progress') {
        startTimersIfNeeded();
        sendTimerSync();
      } else {
        stopTimers();
      }
      break;
    }
    case 'notify': {
      const text = String(command?.payload?.text || '');
      lastNotification.value = text;
      if (text) {
        try {
          await ElMessageBox.alert(text, '考试通知', {
            confirmButtonText: '签收',
            closeOnClickModal: false,
            closeOnPressEscape: false,
            showClose: false,
          });
        } catch {
          // 理论上不会进入（已禁用 ESC/遮罩/关闭按钮），但兜底不阻塞后续流程
        }

        // 签收回报给监考端
        sendNotifyReceipt(text);
      }
      sendAck(command?.type);
      break;
    }
    case 'end_exam':
      phase.value = 'ended';
      stopTimers();
      clockValueSec.value = 0;
      clearStartAt();
      clearEndAt();
      clearClockMode();
      ElMessage.warning('已下考');
      break;
    case 'collect_paper':
      phase.value = 'collecting';
      requiredPhotoKind.value = 'collect_paper';
      ElMessageBox.alert('请拍照并上传收卷照片', '收卷', { confirmButtonText: '知道了' }).catch(() => {});
      break;
    case 'close_exam':
      phase.value = 'closed';
      requiredPhotoKind.value = null;
      stopTimers();
      clockValueSec.value = 0;
      clearStartAt();
      clearEndAt();
      clearClockMode();
      ElMessage.success('考试已关闭');
      break;
    default:
      break;
  }

  if (command?.type !== 'notify') {
    sendAck(command?.type);
  }
}

function connect() {
  connecting.value = true;
  connected.value = false;
  latencyMs.value = null;

  cleanupSocket();

  const url = wsUrl();
  wsClient = createWsClient(url);

  logLine(`CONNECT kind=${wsClient.kind} url=${url}`);

  wsClient.api.on('open', () => {
    connecting.value = false;
    connected.value = true;
    if (autoReconnectTimer) {
      clearInterval(autoReconnectTimer);
      autoReconnectTimer = null;
    }
    sendTimerSync();
    if (pingTimer) clearInterval(pingTimer);
    pingTimer = setInterval(() => sendPing(), 5000);
    sendPing();

    logLine('OPEN');
  });

  wsClient.api.on('close', () => {
    connecting.value = false;
    connected.value = false;
    latencyMs.value = null;
    if (pingTimer) {
      clearInterval(pingTimer);
      pingTimer = null;
    }
    ensureAutoReconnect();

    logLine('CLOSE');
  });

  wsClient.api.on('error', (ev) => {
    connecting.value = false;
    connected.value = false;
    latencyMs.value = null;
    const msg = String(ev?.message || ev?.name || '').trim();
    if (msg) {
      ElMessage.error(`WebSocket 连接失败：${msg}`);
    } else {
      ElMessage.error('WebSocket 连接失败');
    }
    const extra = [];
    if (ev?.name) extra.push(String(ev.name));
    if (ev?.httpCode != null) extra.push(`httpCode=${ev.httpCode}`);
    if (ev?.httpMessage) extra.push(`httpMessage=${String(ev.httpMessage)}`);
    logLine(`ERROR ${[msg, ...extra].filter(Boolean).join(' | ')}`.trim());
    if (debugMode.value && ev?.stack) {
      logLine(String(ev.stack));
    }
    ensureAutoReconnect();
  });

  wsClient.api.on('message', (ev) => {
    handleIncomingText(ev?.text);
  });

  wsClient.api.connect().catch((e) => {
    connecting.value = false;
    connected.value = false;
    const msg = String(e?.message || e || '').trim();
    logLine(`CONNECT_CALL_FAILED ${msg}`.trim());
    if (msg) ElMessage.error(`连接调用失败：${msg}`);
    ensureAutoReconnect();
  });
}

function reconnect() {
  connect();
}

function ensureAutoReconnect() {
  if (autoReconnectTimer) return;
  autoReconnectTimer = setInterval(() => {
    if (connected.value) return;
    if (connecting.value) return;
    connect();
  }, 5000);
}

function enterClockFocus() {
  // 最小化面板：关闭可能打开的弹窗，并清理草稿预览
  photoDialogVisible.value = false;
  lastUploadOk.value = false;
  clearDraft();
  clockFocus.value = true;
}

function openPhotoDialog() {
  if (!requiredPhotoKind.value) return;
  lastUploadOk.value = false;
  photoDialogVisible.value = true;
}

function clearDraft() {
  for (const p of draftPhotos.value) {
    try {
      URL.revokeObjectURL(p.previewUrl);
    } catch {}
  }
  draftPhotos.value = [];
}

async function triggerPick() {
  if (!requiredPhotoKind.value) return;
  if (addDisabled.value) return;

  if (requiredPhotoKind.value === 'paper_check') {
    clearDraft();
  }

  try {
    const p = await Camera.getPhoto({
      source: CameraSource.Camera,
      resultType: CameraResultType.Uri,
      quality: 90,
      saveToGallery: false,
    });

    const webPath = p?.webPath;
    if (!webPath) {
      ElMessage.error('拍照失败：未返回图片地址');
      return;
    }

    const resp = await fetch(webPath);
    const blob = await resp.blob();
    const ext = (blob.type && blob.type.includes('/')) ? blob.type.split('/')[1] : 'jpg';
    const filename = `photo_${Date.now()}.${ext}`;
    const file = new File([blob], filename, { type: blob.type || 'image/jpeg' });
    const previewUrl = URL.createObjectURL(file);

    draftPhotos.value.push({
      id: `${Date.now()}_${Math.random().toString(36).slice(2)}`,
      file,
      previewUrl,
    });
  } catch (e) {
    const msg = String(e?.message || e || '').toLowerCase();
    if (msg.includes('cancel') || msg.includes('canceled')) return;
    ElMessage.error('拍照失败');
  }
}

function removeDraft(id) {
  const idx = draftPhotos.value.findIndex((p) => p.id === id);
  if (idx < 0) return;
  const [removed] = draftPhotos.value.splice(idx, 1);
  try {
    URL.revokeObjectURL(removed.previewUrl);
  } catch {}
}

async function uploadSingle(file) {
  const fd = new FormData();
  fd.append('kind', requiredPhotoKind.value);
  fd.append('photo', file);

  const r = await fetch(`${serverUrl}/api/candidate/photo`, { method: 'POST', body: fd });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || j.ok !== true) {
    ElMessage.error(`上传失败：${j.error || r.status}`);
    return false;
  }
  return true;
}

async function uploadAll() {
  if (!requiredPhotoKind.value) {
    ElMessage.error('当前未要求拍照');
    return;
  }
  if (draftPhotos.value.length === 0) {
    ElMessage.error('请先拍照/选择照片');
    return;
  }
  if (requiredPhotoKind.value === 'paper_check' && draftPhotos.value.length !== 1) {
    ElMessage.error('试卷检查必须上传 1 张图片');
    return;
  }

  uploading.value = true;
  try {
    for (const p of draftPhotos.value) {
      const ok = await uploadSingle(p.file);
      if (!ok) return;
    }

    lastUploadOk.value = true;
    ElMessage.success('上传成功');

    // 成功后结束本次拍照要求
    requiredPhotoKind.value = null;
    clearDraft();
    photoDialogVisible.value = false;
  } finally {
    uploading.value = false;
  }
}

onMounted(() => {
  connect();
  ensureAutoReconnect();
});
onBeforeUnmount(() => {
  stopTimers();
  cleanupSocket();
  clearDraft();
  if (autoReconnectTimer) {
    clearInterval(autoReconnectTimer);
    autoReconnectTimer = null;
  }
  if (pingTimer) {
    clearInterval(pingTimer);
    pingTimer = null;
  }
});
</script>

<style scoped>
.clock { margin: 0 0 12px; text-align: center; font-size: 56px; font-weight: 700; transition: font-size 200ms ease, margin 200ms ease; }
.clock.focus { font-size: clamp(110px, 20vw, 180px); margin-bottom: 16px; }
.row { display:flex; gap: 8px; flex-wrap: wrap; }
.msgRow { display:flex; gap: 8px; flex-wrap: nowrap; align-items: center; }
.msgInput { flex: 1; min-width: 0; }
.muted { color: #666; }
.grid { display:flex; gap: 12px; flex-wrap: wrap; }
.thumb { width: 220px; }
.thumb img { width: 220px; max-width: 100%; border-radius: 8px; border: 1px solid #ddd; }

.fade-slide-enter-active,
.fade-slide-leave-active { transition: opacity 180ms ease, transform 180ms ease; }
.fade-slide-enter-from,
.fade-slide-leave-to { opacity: 0; transform: translateY(10px); }
</style>
