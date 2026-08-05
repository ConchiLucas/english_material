FROM node:20-alpine AS builder

WORKDIR /app

COPY web-react/package.json web-react/package-lock.json ./
RUN npm ci

COPY web-react/. .
ARG VITE_API_BASE=/api
ENV VITE_API_BASE=${VITE_API_BASE}
RUN npm run build

FROM nginx:1.27-alpine AS runner

COPY --from=builder /app/dist /usr/share/nginx/html
COPY deploy/frontend/web_react/local_full/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
