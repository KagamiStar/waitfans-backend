# Waitfans Backend

Waitfans 后端是独立的 Spring Boot 仓库，为 `waitfans-client` 和 `waitfans-admin` 提供 HTTP API，并通过 Netty 提供 IM WebSocket 服务。上游项目说明保存在 [UPSTREAM_README.md](UPSTREAM_README.md)，许可证见 [LICENSE](LICENSE)。

## 1. 技术栈与端口

- JDK 8
- Maven 3.8.7
- Spring Boot 2.7.15
- MySQL 8：`127.0.0.1:3307`
- Redis：`127.0.0.1:6379`
- Elasticsearch 7.17.16：`127.0.0.1:9200`
- HTTP API：`127.0.0.1:7070`
- IM WebSocket：`127.0.0.1:7071/im`
- 阿里云 OSS：上传功能需要有效的测试配置

RabbitMQ 依赖仍保留在项目中，但原业务监听已停用，本地开发无需启动 RabbitMQ。

用户端、管理端和后端分别是独立 Git 仓库。修改 API 路径、请求字段、响应结构、权限或业务码时，必须同步更新相应前端并完成联调。

## 2. 本地运行模型

仓库提供 Windows PowerShell 脚本，用于创建互相隔离的本地运行环境：

- MySQL 数据：`.runtime/mysql`
- Elasticsearch 程序与数据：`.runtime/elasticsearch`
- Redis：WSL 发行版 `Ubuntu-D`
- 后端日志：`.runtime/logs`
- 本机密钥和连接配置：`.env.local`

这些目录和文件均被 Git 忽略。脚本默认使用独立的 MySQL `3307` 端口，不会修改本机已有的 MySQL `3306` 服务。

## 3. 首次初始化

### 3.1 准备软件

确认已安装：

- JDK 8
- Maven 3.8.7，或允许 Maven Wrapper 首次下载 Maven
- MySQL Server 8.0，默认安装目录为 `C:\Program Files\MySQL\MySQL Server 8.0`
- WSL 与 `Ubuntu-D`，其中已安装 `redis-server`

Elasticsearch 由仓库脚本管理在 `.runtime/elasticsearch` 下。

### 3.2 创建隔离的 MySQL

在本仓库根目录执行：

```powershell
.\scripts\setup-local-mysql.ps1
```

脚本会：

1. 在 `.runtime/mysql` 创建独立数据目录。
2. 使用 `3307` 端口启动 MySQL。
3. 创建 `waitfans` 数据库和 `waitfans_app` 本地用户。
4. 导入 `database/waitfans.sql`。
5. 生成随机密码并写入被忽略的 `.env.local`。

MySQL 不在默认路径时：

```powershell
.\scripts\setup-local-mysql.ps1 -MySqlHome 'D:\Tools\MySQL Server 8.0' -Port 3307
```

如果已有自管的本地 MySQL，可先根据 `.env.example` 创建 `.env.local`，再执行：

```powershell
.\scripts\initialize-database.ps1
```

`initialize-database.ps1` 只允许操作 localhost 上名为 `waitfans` 的数据库。数据库已存在时会拒绝覆盖；`-Force` 会重新导入并可能替换表，仅在确认测试数据可被覆盖时使用。

## 4. 日常启动与停止

启动 MySQL、Redis 和 Elasticsearch，并执行环境检查：

```powershell
.\scripts\start-local-services.ps1
```

默认 WSL 发行版不是 `Ubuntu-D` 时：

```powershell
.\scripts\start-local-services.ps1 -RedisWslDistribution Ubuntu
```

单独检查 JDK、Maven和三个数据服务：

```powershell
.\scripts\check-env.ps1
```

构建完成后，以后台进程启动后端：

```powershell
.\scripts\start-local-backend.ps1
```

该脚本读取 `.env.local`、启动 `target` 中最新的 jar，并把 PID 和日志写入 `.runtime`。

前台开发模式：

```powershell
.\scripts\start-dev.ps1
```

首次运行或依赖未缓存时不要使用 `-Offline`；依赖已缓存后可执行：

```powershell
.\scripts\start-dev.ps1 -Offline
```

只停止后端：

```powershell
.\scripts\stop-local-backend.ps1
```

停止后端及所有本项目管理的本地服务：

```powershell
.\scripts\stop-local-services.ps1
```

停止脚本会校验 PID 或进程路径，避免误停不属于本项目的服务。

## 5. 配置

完整变量清单见 [.env.example](.env.example)。常用变量：

```dotenv
WAITFANS_SERVER_PORT=7070
WAITFANS_IM_PORT=7071
WAITFANS_DB_URL=jdbc:mysql://127.0.0.1:3307/waitfans
WAITFANS_DB_USERNAME=waitfans_app
WAITFANS_DB_PASSWORD=本机生成的密码
WAITFANS_REDIS_HOST=127.0.0.1
WAITFANS_REDIS_PORT=6379
WAITFANS_ES_HOST=127.0.0.1
WAITFANS_ES_PORT=9200
```

`.env.local` 只用于本机，禁止提交。视频和图片上传测试前，必须把示例 OSS 占位值替换为有效、权限受限的测试桶配置。

## 6. 构建

后端正在运行时，Windows 会锁定 jar。重新打包前先执行：

```powershell
.\scripts\stop-local-backend.ps1
```

若 JDK 和 Maven 已在 PATH：

```powershell
mvn -DskipTests package
```

使用 Maven Wrapper：

```powershell
.\mvnw.cmd -DskipTests package
```

依赖已缓存时可离线构建：

```powershell
.\mvnw.cmd -o -DskipTests package
```

构建产物：

```text
target/waitfans-backend-0.0.1-SNAPSHOT.jar
```

构建成功后重新执行 `.\scripts\start-local-backend.ps1`。

## 7. 安全测试与回归

本仓库新增的纯单元/契约测试不连接外部服务，可安全执行：

```powershell
.\mvnw.cmd -o '-Dtest=HotSearchJsonContractTest,CustomResponseJsonContractTest,UserAccountServiceImplTest' test
```

它们验证：

- 热搜对象的 JSON 字段契约。
- 通用响应的 JSON 字段契约。
- 管理员错误凭证返回 `code=403` 和“账号或密码不正确”，不会抛出服务器异常。

现有历史测试中包含 MySQL、Redis、Elasticsearch 和 OSS 集成操作，部分测试会修改外部数据。除非已经使用可丢弃的隔离环境并审查测试内容，否则不要直接运行整个测试集。

## 8. 服务探针

检查端口：

```powershell
Test-NetConnection 127.0.0.1 -Port 7070
Test-NetConnection 127.0.0.1 -Port 7071
Test-NetConnection 127.0.0.1 -Port 3307
Test-NetConnection 127.0.0.1 -Port 6379
Test-NetConnection 127.0.0.1 -Port 9200
```

检查公开 HTTP API：

```powershell
Invoke-RestMethod http://127.0.0.1:7070/category/getall
Invoke-RestMethod http://127.0.0.1:7070/search/hot/get
```

管理员失败登录契约：

```powershell
$body = @{
    username = 'missing-admin'
    password = 'wrong-password'
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri http://127.0.0.1:7070/admin/account/login `
    -ContentType 'application/json' `
    -Body $body
```

预期响应体包含：

```json
{
  "code": 403,
  "message": "账号或密码不正确",
  "data": null
}
```

## 9. 前后端完整联调顺序

1. 在本仓库执行 `start-local-services.ps1` 和 `check-env.ps1`。
2. 构建并执行安全测试。
3. 执行 `start-local-backend.ps1`，确认 `7070`、`7071` 均已监听。
4. 启动 `waitfans-client`（默认 `8787`）和 `waitfans-admin`（默认 `8788`）。
5. 通过两个 Vite 代理分别请求 `/api/category/getall`，响应 `code` 应为 `200`。
6. 验证用户端首页、搜索、登录和消息连接。
7. 验证管理端失败登录、管理员登录、会话恢复和审核流程。
8. 对注册、评论、审核、上传等写操作只使用专用测试数据。

有效用户和管理员账号、真实 OSS 上传、审核写入等场景无法通过无副作用冒烟测试覆盖，发布前必须在隔离测试环境补做。

## 10. 更新 CodeGraph

三个仓库由父目录统一索引。代码修改完成后，在 `waitfans` 父目录执行：

```powershell
codegraph sync .
codegraph status .
```

`status` 应显示索引为最新状态。CodeGraph 只帮助分析调用关系，不能替代编译、测试和浏览器联调。

## 11. 服务器部署

Docker Compose 部署说明见 [deploy/README.md](deploy/README.md)。部署流程会构建后端镜像、准备 Elasticsearch 插件、初始化索引和数据库，并使用命名卷保存数据。生产环境应通过 Nginx 提供 HTTPS/WSS，并只开放必要端口。
