# Capsule Code 后端 — Docker 一键部署

给要把 Capsule Code 跑起来的朋友用。整个后端打进一个镜像，`docker compose up -d` 就能起。

## 前置条件

- 装了 Docker（Desktop 或 Engine 都行）
- 有 **Anthropic Claude 订阅**（推荐）或 **API Key**
- 讯飞语音账号 4 个 key（Android app 语音输入要用；不用语音可先填空值，App 会提示初始化失败但不影响发消息）

## 三步跑起来

```bash
# 1. 进到 docker 目录
cd capsule-code/docker

# 2. 复制 .env.example 为 .env，填好你的 key
cp .env.example .env
# 然后编辑 .env

# 3. 起服务
docker compose up -d
```

首次启动要拉基础镜像 + 装 claude CLI + 装 tmux，大约 3-5 分钟。之后启动秒级。

## 验证

```bash
curl http://localhost:8082/app/version
# 应返回 {"size":0,"versionName":"","versionCode":0}（正常，APK 没打进镜像）

docker logs -f capsule-code-backend
# 看到 "Started CapsuleCodeApplication" 就是起来了
```

## Claude CLI 登录

两种方式二选一：

### 方式 A：Claude 订阅（推荐）

宿主机先登录一次，容器里会复用你的登录态：

```bash
# 宿主机执行
claude login
# 跟着浏览器流程登一次
```

然后把 `.env` 里的 `ANTHROPIC_API_KEY` 留空即可。`docker-compose.yml` 里已经把 `~/.claude` 挂到了容器的 `/root/.claude`。

### 方式 B：API Key

在 `.env` 填 `ANTHROPIC_API_KEY=sk-ant-...` 即可。按 token 计费。

## Android App 怎么连

1. 在手机上装 `capsule-code-*.apk`（到 `android/app/build/outputs/apk/release/` 拿，或用打包好的分发 APK）
2. 启动 App → 右上角齿轮 → 「服务器地址」填 **跑 Docker 这台机器的局域网 IP**（如 `192.168.1.100`）
3. 回到主页，第一次会弹出「开始新会话」— 点进去随便聊就 OK

> 如果 Docker 跑在云服务器 / NAS 上，填那台机器的公网 IP 或 Tailscale IP 即可。

## 目录挂载

`docker-compose.yml` 默认挂了 5 个卷，都在 `docker/` 下：

| 容器路径 | 宿主机路径 | 说明 |
|---|---|---|
| `/app/data` | `./data` | H2 嵌入式 DB（会话历史 / 上传记录） |
| `/app/uploads` | `./uploads` | Claude 消息附件（图片等） |
| `/app/logs` | `./logs` | 应用日志（排查问题看这里） |
| `/root/.claude` | `~/.claude` | Claude CLI 登录态 |
| `/workspace` | `./workspace` | 你想让 Claude 操作的代码目录 |

想换路径改 `.env` 里的 `WORKSPACE_DIR` 和 `CLAUDE_HOME` 即可。

## 常见问题

### 端口 8082 冲突

宿主机已经占了 8082，改 `docker-compose.yml` 里的 `ports: - "宿主端口:8082"`（容器内部端口不要动，App 默认连 8082，改了要同步改 App 设置）。

### HEIC 图片上传报 400

后端已内置 HEIC → JPEG 转换（基于 `libheif-examples`），iPhone 拍的 HEIC 会自动转好。如果还是报错，看 `./logs` 里的日志。

### 会话挂了、CLI 反应慢

```bash
# 进容器看看 tmux 会话
docker exec -it capsule-code-backend bash
tmux ls

# 重启容器
docker compose restart
```

### 想升级 Claude CLI 版本

```bash
docker exec -it capsule-code-backend bash -lc "npm update -g @anthropic-ai/claude-code"
docker compose restart
```

或者重建镜像：`docker compose up -d --build`。

## 更新后端

替换 `backend/target/capsule-code-backend-*.jar` 后：

```bash
docker compose build
docker compose up -d
```

## 停止 / 卸载

```bash
docker compose down              # 停服务，数据保留
docker compose down -v           # 停服务 + 删卷（数据会没！）
```
