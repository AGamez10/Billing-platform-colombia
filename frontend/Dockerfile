# Interfaz web: se construye con pnpm (lockfile obligatorio) y se sirve con nginx sin root.
FROM node:25-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
ARG VITE_BRAND_NAME=""
ENV VITE_BRAND_NAME=${VITE_BRAND_NAME}
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
