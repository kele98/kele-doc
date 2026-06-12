#!/bin/sh
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
BUILD_DIR=$(cd $SCRIPT_DIR/..; pwd)
ROOT_DIR=$(cd $BUILD_DIR/..; pwd)

# 1. 重装前端依赖（确保原生二进制匹配当前平台）
echo "==> [1/5] 前端依赖安装 ..."
(cd $ROOT_DIR/kele-doc-web && npm run install:all)

# 2. 构建前端（并行构建 10 个子项目 + deploy 合并）
echo "==> [2/5] 前端构建 ..."
(cd $ROOT_DIR/kele-doc-web && npm run build)
(cd $ROOT_DIR/kele-doc-web && node scripts/deploy.js dist --clean > /dev/null)

# 3. 构建后端 JAR
echo "==> [3/5] mvn package ..."
(cd $ROOT_DIR && mvn -DskipTests -U clean package)

# 4. 拷贝构建产物到 dist/
echo "==> [4/5] 准备 dist/ 目录 ..."
mkdir -p $BUILD_DIR/dist
cp $ROOT_DIR/kele-core/target/kele-doc.jar $BUILD_DIR/dist/kele-doc.jar
rm -rf $BUILD_DIR/dist/frontend
cp -r $ROOT_DIR/kele-doc-web/dist $BUILD_DIR/dist/frontend

# 5. 构建 docker 镜像
echo "==> [5/5] docker build ..."
(cd $BUILD_DIR && docker build -f image/Dockerfile -t kele-doc:1.0 .)

echo ""
echo "==> 完成。启动整套服务："
echo "    cd $BUILD_DIR/compose && docker compose up -d"
