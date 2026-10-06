# The console: build with Node, serve with nginx. Build context is docker/rule-engine (NOT docker/rule-engine/ui) because the
# UI imports the protobuf contract from ../contract, where the Java code owns it.
#   docker build -f ui/Dockerfile -t ruleengine/ui:dev .        (from docker/rule-engine)

FROM node:24-alpine AS build
WORKDIR /app
# the contract first (its path relative to ui/ must match the source tree: ui/src/proto -> ../../../contract)
COPY contract/src/main/protobuf ./contract/src/main/protobuf
COPY contract/testdata ./contract/testdata
WORKDIR /app/ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ui/ ./
# the local stack shows the seeded logins on the login page; a real deployment builds with --build-arg DEV_LOGIN_HINT=false
ARG DEV_LOGIN_HINT=true
ENV VITE_DEV_LOGIN_HINT=${DEV_LOGIN_HINT}
RUN npm run build && npm test

FROM nginx:1.27-alpine AS runtime
# non-root nginx: the unprivileged port 8080 is used (see nginx.conf.template)
COPY --from=build /app/ui/dist /usr/share/nginx/html
COPY ui/security-headers.conf /etc/nginx/security-headers.conf
COPY ui/nginx.conf.template /etc/nginx/templates/default.conf.template
ENV AUTH_UPSTREAM=auth-service:8091 \
    RULES_UPSTREAM=rule-engine-service:8092
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
