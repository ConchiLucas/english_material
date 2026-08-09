# syntax=docker/dockerfile:1.7
FROM node:22-alpine AS builder

WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
ARG VITE_API_BASE=/api
ENV VITE_API_BASE=${VITE_API_BASE}
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/context-router/full/nginx.conf /etc/nginx/nginx.conf
COPY --from=builder --chown=nginx:nginx /app/dist/ /usr/share/nginx/html/
RUN test -s /usr/share/nginx/html/index.html

EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=8 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
