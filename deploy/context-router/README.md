# Context Router 部署入口

本目录是英语材料工作空间运行配置的源码真源。

- `workspace/start/deploy.sh`：依次执行后端 Full、前端 Full。
- `fast/`：Java 后端 Fast，复用最近一次 Full 的稳定依赖与 Boot Loader 基线。
- `full/`：Java 后端 Full，重新建立 Java 17、Codex CLI、稳定依赖和 Boot Loader 基线。
- `web-react/deploy/context-router/fast/`：Node/Vite 开发容器，挂载当前源码和锁文件哈希依赖卷。
- `web-react/deploy/context-router/full/`：Node 构建加只读 Nginx 生产容器。

Fast 和 Full 会替换同一个容器，并使用相同宿主机端口：

| Project | 容器 | Fast 镜像 | Full 镜像 | 地址 |
| --- | --- | --- | --- | --- |
| Java 后端 | `english-material-backend` | `english-material/backend:dev` | `english-material/backend:local` | `http://127.0.0.1:18744` |
| React 前端 | `english-material-frontend` | `english-material/frontend:dev` | `english-material/frontend:local` | `http://127.0.0.1:19638` |

两个容器都加入 `vibedeploy-shared`，前端通过唯一别名 `english-material-backend:18744` 访问后端。

首次运行前，以 `.env.local.example` 为模板在工作空间根创建被 Git 忽略的 `.env.local`。不要把真实凭据写入本目录。

后端必须先成功执行一次 Full，Fast 才允许复用依赖基线。修改 `pom.xml`、Java/Node/Codex CLI 基础镜像或部署基线文件后执行 Full；只修改 Java 源码时执行 Fast。前端修改依赖锁文件或生产构建配置时执行 Full，只修改页面源码时可执行 Fast。
