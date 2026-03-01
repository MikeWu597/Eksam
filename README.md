# Eksam Candidate App

考生端（Vue 3 + Element Plus）。通过 WebSocket 接收服务端指令；当收到 `试卷检查/收卷` 指令时拍照并上传。

## 开发启动

```bash
npm install
npm run dev
```

1. 复制 `.env.example` 为 `.env`，将 `VITE_SERVER_URL` 改为服务端地址。
2. 浏览器打开 Vite 输出的地址。

## 打包到安卓（Capacitor）

> 需要本机安装 Android Studio / JDK。

```bash
npm run build
npx cap add android
npx cap copy
npx cap open android
```

真机联网时，服务端应使用局域网 IP（见 `.env`）。
