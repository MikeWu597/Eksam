# Eksam Candidate App

考生端（Vue 3 + Element Plus）。通过 WebSocket（ws/wss）接收服务端指令；当收到 `试卷检查/收卷` 指令时拍照并上传。

## 考试计时

- 收到“开考”指令后开始自主计时，标题显示为 `00:00` 并递增。
- 每 10 秒向监考端同步一次当前计时（秒）。

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

注意：当前服务端默认是 `http://`（WebSocket 为 `ws://`）。在 Android 9+ 且 `targetSdkVersion >= 28`（本项目为 34）时，明文流量可能被系统策略拦截。

- 如果你用的是 `http://...:3000`（对应 `ws://`），需要在安卓侧允许明文（本项目已在 `AndroidManifest.xml` 设置 `android:usesCleartextTraffic="true"`）。
- 如果你希望更安全，建议把服务端放到 `https://`（对应 `wss://`）。
- 安卓真机/模拟器不要写 `localhost`：
	- 模拟器访问宿主机用 `http://10.0.2.2:3000`
	- 真机访问电脑用电脑的局域网 IP（例如 `http://192.168.1.10:3000`）

## 强制横屏（安卓）

安卓工程生成后（`android/` 目录存在），在 `AndroidManifest.xml` 的主 Activity 上配置 `android:screenOrientation="sensorLandscape"`。
