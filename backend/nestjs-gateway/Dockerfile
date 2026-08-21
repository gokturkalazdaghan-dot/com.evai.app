# backend/nestjs-gateway/Dockerfile
FROM node:20-alpine AS build

WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

FROM node:20-alpine AS runtime

WORKDIR /app
ENV NODE_ENV=production

COPY package.json package-lock.json* ./
RUN npm install --omit=dev

COPY --from=build /app/dist ./dist

EXPOSE 3000

# Basit bir process-level healthcheck — /health endpoint'i Gateway'de
# tanımlı değilse (şu anki kapsamda StationsController altında yok),
# docker-compose healthcheck'i doğrudan TCP portuna bakacak şekilde
# yapılandırıldı (aşağıdaki docker-compose.yml'e bakın).
CMD ["node", "dist/main.js"]
