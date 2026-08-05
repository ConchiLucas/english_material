# syntax=docker/dockerfile:1.7
FROM node:22-alpine AS build
WORKDIR /build
COPY web-react/package.json web-react/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY web-react ./
ARG VITE_API_BASE=/api
ENV VITE_API_BASE=${VITE_API_BASE}
RUN npm run build

FROM nginx:1.27-alpine AS runtime
COPY --from=build /build/dist /usr/share/nginx/html
COPY deploy/context-router/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
