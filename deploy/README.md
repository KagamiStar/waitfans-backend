# Waitfans Backend 服务器部署

此目录提供生产式单机部署基线：后端、MySQL、Redis 和 Elasticsearch 由 Docker Compose
统一编排，数据库与搜索服务不映射宿主机端口，只有 HTTP/IM 端口可从宿主机访问。

## 前置条件

- 64 位 Linux 服务器
- Docker Engine 与 Docker Compose v2
- 至少 4 GB 可用内存；建议 2 核、8 GB 内存
- Nginx/Caddy 等反向代理和可用域名（正式环境）

## 首次部署

```bash
cd waitfans-backend/deploy
cp .env.server.example .env.server
chmod 600 .env.server
```

编辑 `.env.server`，替换所有 `change-me`。数据库、Redis、OSS 密钥不要提交到 Git。

```bash
docker compose --env-file .env.server -f compose.yml config
docker compose --env-file .env.server -f compose.yml up -d --build
docker compose --env-file .env.server -f compose.yml ps
docker compose --env-file .env.server -f compose.yml logs -f backend
```

首次启动会自动导入 `database/waitfans.sql`，并创建 `video`、`user`、`search_word`
三个 Elasticsearch 索引。初始化只在全新数据卷上执行。

默认只监听 `127.0.0.1:7070` 和 `127.0.0.1:7071`，应由反向代理提供公网
HTTPS/WSS。只有明确不使用反向代理时，才将 `WAITFANS_BIND_ADDRESS` 改为
`0.0.0.0`，并同时配置防火墙。

## 更新

```bash
git pull
docker compose --env-file .env.server -f compose.yml up -d --build
```

命名卷会保留 MySQL、Redis、Elasticsearch 和上传目录的数据。不要在普通更新时运行
`docker compose down -v`，该命令会删除全部持久化数据。

## 备份

部署前后至少备份：

- MySQL：`mysqldump`
- Elasticsearch：快照仓库
- `backend-uploads` 命名卷
- 服务器上的 `.env.server`（加密保存）

生产环境建议固定并定期审核容器镜像版本或 digest，而不是长期使用浮动标签。
