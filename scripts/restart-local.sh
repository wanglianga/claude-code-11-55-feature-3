#!/usr/bin/env bash
# 本地开发辅助：停止后端 → 重建 jar → 重置数据库 → 后台启动
# 容器内使用；生产/Compose 场景请使用 docker compose up --build
set -euo pipefail
cd "$(dirname "$0")/.."
export PGPASSWORD=${DB_PASS:-ccdev}
PSQL=/opt/postgresql-18/bin/psql
pkill -f carbon-platform.jar 2>/dev/null || true
sleep 2
$PSQL -h ${DB_HOST:-127.0.0.1} -U ${DB_USER:-ccdev} -d ${DB_NAME:-ccdev} \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
cd backend
/opt/maven/bin/mvn -q package -DskipTests
nohup /opt/jdk-25/bin/java -jar target/carbon-platform.jar > /tmp/carbon.log 2>&1 &
for i in $(seq 1 60); do
  if curl -s -m 2 http://127.0.0.1:8080/api/health 2>/dev/null | grep -q UP; then
    echo "backend UP after ${i}s"
    exit 0
  fi
  sleep 1
done
echo "backend failed to start, see /tmp/carbon.log"
exit 1
