<template>
  <div style="padding: 16px; max-width: 720px; margin: 0 auto;">
    <h2 style="margin: 0 0 12px;">考生端</h2>

    <el-card>
      <template #header>
        <div style="display:flex; justify-content: space-between; align-items: center; gap: 12px;">
          <div>
            <div><b>连接状态：</b>{{ connectionLabel }}</div>
            <div><b>考试状态：</b>{{ phaseLabel }}</div>
          </div>
          <el-button :disabled="connecting" @click="reconnect">重连</el-button>
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

      <div class="row">
        <el-button type="primary" :disabled="!requiredPhotoKind" @click="openPhotoDialog">拍照并上传</el-button>
        <el-button :disabled="!requiredPhotoKind" @click="clearSelected">清空选择</el-button>
      </div>

      <div style="margin-top: 12px;" v-if="selectedFileName">
        <div><b>已选择：</b>{{ selectedFileName }}</div>
      </div>

      <div style="margin-top: 12px;" v-if="uploadResultUrl">
        <el-alert title="已上传成功" type="success" show-icon />
        <div style="margin-top: 8px;">
          <a :href="uploadResultUrl" target="_blank">查看上传的图片</a>
        </div>
      </div>
    </el-card>

    <el-dialog v-model="photoDialogVisible" title="拍照" width="95%" :close-on-click-modal="false">
      <div>
        <input
          ref="fileInput"
          type="file"
          accept="image/*"
          capture="environment"
          @change="onPickFile"
        />
      </div>

      <template #footer>
        <el-button @click="photoDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedFile || uploading" :loading="uploading" @click="upload">上传</el-button>
      </template>
    </el-dialog>

    <div style="margin-top: 12px;" class="muted">
      服务器：{{ serverUrl }}
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';

const serverUrl = (import.meta.env.VITE_SERVER_URL || 'http://localhost:3000').replace(/\/$/, '');

const ws = ref(null);
const connecting = ref(false);
const connected = ref(false);

const phase = ref('idle');
const lastNotification = ref('');
const requiredPhotoKind = ref(null); // 'paper_check' | 'collect_paper' | null

const photoDialogVisible = ref(false);
const selectedFile = ref(null);
const selectedFileName = ref('');
const uploading = ref(false);
const uploadResultUrl = ref('');
const fileInput = ref(null);

const connectionLabel = computed(() => {
  if (connecting.value) return '连接中';
  return connected.value ? '已连接' : '未连接';
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
    ws.value?.send(JSON.stringify({ type: 'ack', ts: Date.now(), commandType }));
  } catch {}
}

function applySnapshot(snapshot) {
  if (!snapshot) return;
  if (snapshot.phase) phase.value = snapshot.phase;
  requiredPhotoKind.value = snapshot.pendingPhotoKind || null;
}

function handleCommand(command) {
  uploadResultUrl.value = '';

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
      ElMessage.success('已开考');
      break;
    case 'notify': {
      const text = String(command?.payload?.text || '');
      lastNotification.value = text;
      if (text) ElMessage.info(text);
      break;
    }
    case 'end_exam':
      phase.value = 'ended';
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
      ElMessage.success('考试已关闭');
      break;
    default:
      break;
  }

  sendAck(command?.type);
}

function connect() {
  connecting.value = true;
  connected.value = false;

  if (ws.value) {
    try { ws.value.close(); } catch {}
    ws.value = null;
  }

  const socket = new WebSocket(wsUrl());
  ws.value = socket;

  socket.onopen = () => {
    connecting.value = false;
    connected.value = true;
  };

  socket.onclose = () => {
    connecting.value = false;
    connected.value = false;
  };

  socket.onerror = () => {
    connecting.value = false;
    connected.value = false;
  };

  socket.onmessage = (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }

    if (msg?.type === 'hello') {
      applySnapshot(msg.data);
      return;
    }

    if (msg?.type === 'command') {
      handleCommand(msg.command);
    }
  };
}

function reconnect() {
  connect();
}

function openPhotoDialog() {
  if (!requiredPhotoKind.value) return;
  photoDialogVisible.value = true;
}

function clearSelected() {
  selectedFile.value = null;
  selectedFileName.value = '';
  if (fileInput.value) fileInput.value.value = '';
}

function onPickFile(e) {
  const file = e.target?.files?.[0] || null;
  selectedFile.value = file;
  selectedFileName.value = file?.name || '';
}

async function upload() {
  if (!requiredPhotoKind.value) {
    ElMessage.error('当前未要求拍照');
    return;
  }
  if (!selectedFile.value) {
    ElMessage.error('请先选择/拍摄照片');
    return;
  }

  uploading.value = true;
  try {
    const fd = new FormData();
    fd.append('kind', requiredPhotoKind.value);
    fd.append('photo', selectedFile.value);

    const r = await fetch(`${serverUrl}/api/candidate/photo`, { method: 'POST', body: fd });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || j.ok !== true) {
      ElMessage.error(`上传失败：${j.error || r.status}`);
      return;
    }

    uploadResultUrl.value = `${serverUrl}${j.url}`;
    ElMessage.success('上传成功');

    // 成功后等待服务端解除 pending（也允许客户端先清掉）
    requiredPhotoKind.value = null;
    clearSelected();
  } finally {
    uploading.value = false;
    photoDialogVisible.value = false;
  }
}

onMounted(() => connect());
onBeforeUnmount(() => {
  try { ws.value?.close(); } catch {}
});
</script>

<style scoped>
.row { display:flex; gap: 8px; flex-wrap: wrap; }
.muted { color: #666; }
</style>
