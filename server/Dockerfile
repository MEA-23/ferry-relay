# Build from the repo root:  docker build -f server/Dockerfile -t ferry-server .
FROM node:24-alpine
RUN corepack enable
WORKDIR /app

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY server/package.json server/
COPY packages/crypto-ts/package.json packages/crypto-ts/
RUN pnpm install --frozen-lockfile --prod --filter @ferry/server

COPY server/src server/src
WORKDIR /app/server

ENV NODE_ENV=production PORT=3000
EXPOSE 3000
USER node
CMD ["node", "--disable-warning=ExperimentalWarning", "src/main.ts"]
