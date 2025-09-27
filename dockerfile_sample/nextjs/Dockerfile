# 使用一个已包含 corepack 的 Node.js 官方镜像
FROM node:18-alpine AS base

# 阶段 1: 安装依赖
FROM base AS deps
RUN apk add --no-cache libc6-compat

WORKDIR /app

# 启用 corepack 并配置 pnpm
RUN corepack enable
# 在 package.json 中应指定 "packageManager": "pnpm@x.x.x"
COPY package.json pnpm-lock.yaml ./
RUN pnpm fetch --prod

# 阶段 2: 构建应用
FROM base AS builder
WORKDIR /app
RUN corepack enable

# 首先复制锁文件和包管理配置
COPY package.json pnpm-lock.yaml ./
# 从 deps 阶段复制已获取的依赖（存储在虚拟存储中）
COPY --from=deps /app/node_modules ./node_modules
# 然后复制源代码并构建
COPY . .
RUN pnpm install --frozen-lockfile
RUN pnpm run build

# 阶段 3: 准备运行环境
FROM base AS runner
WORKDIR /app
RUN corepack enable

# 以非 root 用户运行增强安全性
RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 nextjs

# 复制构建产物和运行依赖
COPY --from=builder /app/public ./public
# 根据 Next.js 输出模式调整（standalone 或 standard）
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs

EXPOSE 3003
ENV PORT 3003
ENV HOSTNAME "0.0.0.0"

CMD ["node", "server.js"]