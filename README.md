# English Material

配置管理功能的 Java + PostgreSQL + React 实现，参考 `vibecoding-utils` 的配置管理页，保留：

- 项目配置
- 数据库配置
- AI 配置

已去掉左侧“服务器配置”。

## PostgreSQL

默认使用本地已有 PostgreSQL，不需要为本项目单独启动 Docker 容器。

请通过环境变量提供本地 PostgreSQL 连接信息，仓库不包含默认口令。示例：

```text
jdbc:postgresql://localhost:5432/english_material
user: postgres
password: （由 TASK_CENTER_DB_PASSWORD 提供）
```

## 一键启动

```bash
./scripts/start-dev.sh
```

脚本会启动 Java 后端和 React 前端。PostgreSQL 只检查本地已有服务，不会新启动 Docker 容器。

## 启动后端

```bash
mvn spring-boot:run
```

后端默认端口：

```text
http://localhost:18744
```

## 启动前端

前端使用 React + Ant Design：

```bash
cd web-react
npm install
npm run dev
```

打开：

```text
http://localhost:19638
```

Spring Boot 会通过 JPA 自动创建本地表：

- `tb_interface_project`
- `tb_connection`
- `tb_ai_config`
