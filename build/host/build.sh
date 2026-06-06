#!/bin/sh
set -e

# 定位路径：脚本可能在任何 cwd 下被调用
SCRIPT_DIR=$(cd $(dirname $0); pwd)
BUILD_DIR=$(cd $SCRIPT_DIR/..; pwd)
ROOT_DIR=$(cd $BUILD_DIR/..; pwd)

# 1. 构建后端 JAR（在仓库根目录跑 mvn）
echo "==> [1/3] mvn package ..."
(cd $ROOT_DIR && mvn -DskipTests -U clean package)

# 2. 拷贝构建产物到 dist/
echo "==> [2/3] 准备 dist/ 目录 ..."
mkdir -p $BUILD_DIR/dist
cp $ROOT_DIR/kele-core/target/kele-doc.jar $BUILD_DIR/dist/kele-doc.jar 2>/dev/null || true
if [ ! -f $BUILD_DIR/dist/kele-doc.zip ]; then
    echo "WARN: $BUILD_DIR/dist/kele-doc.zip 不存在，请把前端 dist zip 放到该路径"
fi

# 3. 构建 docker 镜像（构建上下文 = build/，Dockerfile 在 image/）
echo "==> [3/3] docker build ..."
(cd $BUILD_DIR && docker build -f image/Dockerfile -t kele-doc:1.0 .)

echo ""
echo "==> 完成。启动整套服务："
echo "    cd $BUILD_DIR/compose && docker compose up -d"
