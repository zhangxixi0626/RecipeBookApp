FROM node:22-alpine

ENV NODE_ENV=production
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY server.js password-auth.js hash-password.js reset-device.js ./
COPY public ./public

# NAS解压工具可能把构建文件保存为仅所有者可读。构建时统一修正，
# 让非root的node用户可以读取应用和静态页面。
RUN mkdir -p /state \
  && chown -R node:node /app /state \
  && chmod 755 /app /app/public \
  && chmod 700 /state \
  && chmod 644 /app/*.js /app/package.json /app/package-lock.json /app/public/*.html

USER node
EXPOSE 8295
VOLUME ["/state"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8295/health || exit 1

CMD ["node", "server.js"]
