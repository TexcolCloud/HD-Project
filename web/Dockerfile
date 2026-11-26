FROM node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS build
RUN mkdir -p /workspace/web && chown node:node /workspace/web
WORKDIR /workspace/web
USER node
COPY contracts/openapi.json /workspace/contracts/openapi.json
COPY --chown=node:node web/package.json web/package-lock.json ./
RUN npm ci
COPY --chown=node:node web/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31-alpine@sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e
COPY --from=build /workspace/web/dist/ /usr/share/nginx/html/
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
USER 101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
