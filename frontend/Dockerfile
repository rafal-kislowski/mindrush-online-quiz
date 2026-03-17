FROM node:20-bookworm-slim AS builder
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ \
  && rm -rf /var/lib/apt/lists/*

COPY package*.json ./
RUN npm install
RUN npm install --no-save @rollup/rollup-linux-x64-gnu@4.22.4

COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=builder /app/dist/frontend/browser /usr/share/nginx/html

EXPOSE 80
