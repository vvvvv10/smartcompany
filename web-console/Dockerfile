FROM docker.m.daocloud.io/library/node:20-alpine AS builder
WORKDIR /build

# 走国内 npm 镜像，否则构建要等很久
COPY package.json ./
RUN npm install --registry=https://registry.npmmirror.com

COPY . .
RUN npm run build

FROM docker.m.daocloud.io/library/nginx:1.27-alpine
LABEL service="web-console"
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=20s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1
