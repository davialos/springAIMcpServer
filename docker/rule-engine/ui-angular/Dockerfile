# The Angular console: build with Node, serve with nginx. Build context is docker/rule-engine (NOT docker/rule-engine/ui-angular)
# because the app imports the protobuf contract from ../contract, where the Java code owns it, and the web server includes the
# one security-headers.conf that the React console owns as well.
#   docker build -f ui-angular/Dockerfile -t ruleengine/ui-angular:dev .        (from docker/rule-engine)

FROM node:24-alpine AS build
WORKDIR /app
COPY contract/src/main/protobuf ./contract/src/main/protobuf
WORKDIR /app/ui-angular
COPY ui-angular/package.json ui-angular/package-lock.json ui-angular/.npmrc ./
RUN npm ci --no-audit --no-fund
COPY ui-angular/ ./
# the local stack shows the seeded logins on the login page; a real deployment builds with --build-arg NG_CONFIGURATION=production
ARG NG_CONFIGURATION=local
RUN npm run proto && npx ng build --configuration ${NG_CONFIGURATION} && npm test

FROM nginx:1.27-alpine AS runtime
COPY --from=build /app/ui-angular/dist /usr/share/nginx/html
COPY ui/security-headers.conf /etc/nginx/security-headers.conf
COPY ui-angular/nginx.conf.template /etc/nginx/templates/default.conf.template
ENV AUTH_UPSTREAM=auth-service:8091 \
    RULES_UPSTREAM=rule-engine-service:8092
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
