# The SPA, served by nginx which also proxies /api to the backend.
#
# Self-hosted fonts and assets only: an on-prem box may have no outbound
# internet, and a font that fails to load is a broken product.

FROM node:22-bookworm-slim AS build
WORKDIR /src
RUN corepack enable
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
COPY frontend/packages ./packages
COPY frontend/apps ./apps
COPY frontend/tsconfig*.json ./
COPY spec ../spec
RUN pnpm install --frozen-lockfile && pnpm build

FROM nginx:1.27-alpine
COPY ops/nginx/coreintra.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/apps/web/dist /usr/share/nginx/html
EXPOSE 80
