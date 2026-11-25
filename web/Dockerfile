FROM node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS build
RUN mkdir -p /workspace/web && chown node:node /workspace/web
WORKDIR /workspace/web
USER node
COPY contracts/openapi.json /workspace/contracts/openapi.json
COPY --chown=node:node web/package.json web/package-lock.json ./
RUN npm ci
COPY --chown=node:node web/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine@sha256:65e3e85dbaed8ba248841d9d58a899b6197106c23cb0ff1a132b7bfe0547e4c0
COPY --from=build /workspace/web/dist/ /usr/share/nginx/html/
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
USER 101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
