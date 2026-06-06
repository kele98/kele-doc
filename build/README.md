# 构建与部署

## 目录结构（按作用分层）

```
build/
├── README.md                       # 本文件
├── .dockerignore                   # 构建上下文排除规则
├── dist/                           # 构建产物（jar/zip 落地点）
│   ├── kele-doc.jar                # mvn package 产物，build.sh 自动拷入
│   └── kele-doc.zip                # 前端 dist 打包，需手动放入
│
├── image/                          # 镜像层：打包进 docker image 的内容
│   ├── Dockerfile                  # 应用镜像构建（alpine + nginx + jdk11）
│   ├── run.sh                      # 容器内启动脚本（nginx + java）
│   ├── nginx.conf                  # 容器内 nginx 配置
│   └── application-prod.yml        # Spring 生产配置
│
├── compose/                        # 编排层：多容器协同
│   └── docker-compose.yml          # mysql / redis / minio / app 一键拉起
│
└── host/                           # 宿主机层：宿主机执行
    ├── build.sh                    # mvn package + docker build 一条龙
    └── doc.sql                     # MySQL 初始化（手动 import）
```

各层职责单一、互不耦合：
- **image/** 负责"应用镜像长什么样"
- **compose/** 负责"整个栈怎么编排"
- **host/** 负责"在宿主机上跑什么"

## 构建应用镜像

```sh
cd build
./host/build.sh
```

`build.sh` 内部：
1. 在仓库根目录跑 `mvn clean package` → 生成 `kele-core/target/kele-doc.jar`
2. 拷到 `build/dist/kele-doc.jar`
3. 在 `build/` 下 `docker build -f image/Dockerfile -t kele-doc:1.0 .`（构建上下文 = `build/`，让 `dist/` 可被 `COPY`）

## 启动整栈

```sh
cd build/compose
docker compose up -d
```

会拉起 4 个容器：`kele-doc-mysql` / `kele-doc-redis` / `kele-doc-minio` / `kele-doc`。
应用容器暴露 `2183` 端口（nginx 80），浏览器访问 `http://<host>:2183`。

## 初始化数据库

compose 里的 mysql 容器**不会自动**执行 `doc.sql`，需要手动导入：

```sh
docker exec -i kele-doc-mysql mysql -uroot -pTaeon@0727 kele-doc < host/doc.sql
```

## 配置变更后如何生效

| 改了什么                                | 怎么做                                                |
| ---------------------------------------- | ----------------------------------------------------- |
| `image/` 下的任何文件                   | `./host/build.sh` 重新打镜像，再 `docker compose up -d --build` |
| `compose/docker-compose.yml`            | `cd compose && docker compose up -d`                  |
| `host/doc.sql`                          | 手动重新 import 一次                                  |
| 仅 `image/application-prod.yml`（想热生效） | `docker restart kele-doc`                         |

## 镜像 tag

打出来的镜像 tag 是 `kele-doc:1.0`，compose 里 `kele-doc` 服务用的就是这个 tag。
改版本号需同步两处：`host/build.sh` 里的 `-t kele-doc:1.0` 和 `compose/docker-compose.yml` 里的 `image:`。
