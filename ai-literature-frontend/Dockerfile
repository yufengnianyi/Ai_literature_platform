ARG WEB_BUILD_IMAGE=node:24-alpine
ARG WEB_RUNTIME_IMAGE=nginx:1.27-alpine

FROM ${WEB_BUILD_IMAGE} AS build

WORKDIR /app

COPY package.json package-lock.json ./

RUN npm ci

COPY . .

ARG VITE_APP_BASE_PATH=/
ENV VITE_APP_BASE_PATH=${VITE_APP_BASE_PATH}

ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN npm run build

FROM ${WEB_RUNTIME_IMAGE}

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
