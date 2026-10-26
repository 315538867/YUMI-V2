FROM node:22.14.0-alpine AS build

RUN npm install -g pnpm@11.23.0
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm run build

FROM nginx:1.28-alpine

RUN apk add --no-cache tzdata

ENV TZ=Asia/Shanghai
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
