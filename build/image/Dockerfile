# 拉取基础镜像
FROM alpine:latest

# 维护人信息
LABEL org.opencontainers.image.authors="874565710@qq.com"

ENV SERVICE=kele-doc

USER root

EXPOSE 9222
EXPOSE 80

# 安装 openjdk11
ENV JAVA_HOME="/usr/lib/jvm/default-jvm/"
RUN apk add openjdk11
ENV PATH=$PATH:${JAVA_HOME}/bin

# 设置时区
RUN apk add tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata

# 安装 nginx
RUN apk add --no-cache nginx

# nginx 日志目录、web 静态目录等
RUN mkdir -p /var/log/nginx/ \
    && mkdir -p /usr/web/html/ \
    && mkdir -p /usr/nginx/config/ \
    && mkdir -p /usr/config/${SERVICE}/ \
    && mkdir -p /usr/app/${SERVICE}/ \
    && mkdir -p /usr/logs/${SERVICE}/ \
    && mkdir -p /usr/attachment/${SERVICE}/

# 复制并解压前端文件（构建上下文 = build/，dist/ 在其下）
COPY dist/kele-doc.zip /usr/web/html/
RUN unzip -q -d /usr/web/html/ /usr/web/html/kele-doc.zip \
    && rm -rf /usr/web/html/kele-doc.zip

# 拷贝 nginx 配置
COPY image/nginx.conf /usr/nginx/config/

# 拷贝后端运行相关文件
COPY image/run.sh /usr/app/${SERVICE}/
COPY image/application-prod.yml /usr/config/${SERVICE}/
COPY dist/kele-doc.jar /usr/app/${SERVICE}/

# 移除 Windows 下的 \r
RUN sed -i 's/\r$//' /usr/app/${SERVICE}/*.sh

# 设置工作目录
WORKDIR /usr/app/${SERVICE}/

# 启动脚本
ENTRYPOINT ["sh", "run.sh", "initStart"]
