# Context Router 部署入口

本目录是英语材料工作空间运行配置的源码真源。

- `workspace/start/deploy.sh`：完整启动工作空间。
- `fast/deploy.sh`、`full/deploy.sh`：根 Java Project 更新入口。
- `web-react/deploy/context-router/{fast|full}/deploy.sh`：React Project 更新入口。
- `deploy.sh`：所有入口共用的实现。
- `compose.yaml`：Java 与 Nginx 前端的统一 Compose。

首次运行前，以 `.env.local.example` 为模板在工作空间根创建被 Git 忽略的 `.env.local`。不要把真实凭据写入本目录。
