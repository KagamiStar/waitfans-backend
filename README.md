# Waitfans Backend

Waitfans 的 Spring Boot 后端，由 `teriteri-backend` 迁移并作为后续二次开发基础。
上游项目说明保存在 [UPSTREAM_README.md](UPSTREAM_README.md)，原许可证保存在
[LICENSE](LICENSE)。

## 技术栈

- JDK 8、Maven 3.8.7、Spring Boot 2.7.15
- MySQL 8、Redis、Elasticsearch 7.17.16
- Elasticsearch IK/Pinyin 分词插件
- 阿里云 OSS（上传功能需要有效配置）
- Netty IM WebSocket

RabbitMQ 依赖仍保留，但原业务监听已停用，本地开发不需要启动 RabbitMQ。

## 本机环境

当前机器使用互相隔离的开发环境：

- MySQL：`127.0.0.1:3307`，项目数据位于 `.runtime/mysql`
- Redis：WSL `Ubuntu-D` 内运行，经 `127.0.0.1:6379` 访问
- Elasticsearch：`127.0.0.1:9200`，程序与数据位于 `.runtime/elasticsearch`
- HTTP：`127.0.0.1:7070`
- IM WebSocket：`127.0.0.1:7071/im`

本项目的 MySQL 不会修改本机已有的 `MySQL80/3306`。生成的数据库密码只保存在
被 Git 忽略的 `.env.local` 和 `.runtime/mysql/.root-password`。

首次初始化 MySQL：

```powershell
.\scripts\setup-local-mysql.ps1
```

以后启动全部依赖：

```powershell
.\scripts\start-local-services.ps1
```

检查环境：

```powershell
.\scripts\check-env.ps1
```

启动已构建的后端：

```powershell
.\scripts\start-local-backend.ps1
```

或以前台开发模式启动：

```powershell
.\scripts\start-dev.ps1 -Offline
```

停止本项目的本地服务：

```powershell
.\scripts\stop-local-services.ps1
```

## 构建

当前机器已配置的 JDK/Maven 路径保存在 `.env.local`。可运行：

```powershell
.\scripts\start-dev.ps1 -Offline
```

只生成 jar 时：

```powershell
$env:JAVA_HOME = "C:\Users\victory\.jdks\dragonwell-1.8.0_492"
$maven = "C:\Users\victory\.m2\wrapper\dists\apache-maven-3.8.7-bin\1ktonn2lleg549uah6ngl1r74r\apache-maven-3.8.7\bin\mvn.cmd"
& $maven -o -DskipTests package
```

原项目测试类包含 MySQL、Redis、Elasticsearch 和 OSS 集成操作，其中包括会修改外部
数据的测试。因此环境未完全隔离前，不要直接运行整个测试类；普通构建使用
`-DskipTests`。

## 配置

所有连接参数都通过环境变量注入，完整示例见 [.env.example](.env.example)。
`.env.local` 仅用于本机且不会提交。视频/图片上传前必须提供有效的 OSS 配置。

主要迁移项：

- Java 包名：`com.waitfans.backend`
- Maven 坐标：`com.waitfans:waitfans-backend`
- 启动类：`WaitfansBackendApplication`
- 数据库脚本：`database/waitfans.sql`
- HTTP/IM 端口：`WAITFANS_SERVER_PORT`、`WAITFANS_IM_PORT`

## 服务器部署

服务器使用 Docker Compose 一键部署，说明见 [deploy/README.md](deploy/README.md)。
该方案会自动构建后端镜像、安装 Elasticsearch 插件、初始化索引和数据库，并使用
命名卷保存数据。默认只向服务器本机开放 `7070/7071`，适合通过 Nginx 提供
HTTPS/WSS。
