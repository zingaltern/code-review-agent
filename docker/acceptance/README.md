# M4b 本地验收（docker-compose + GitHub/LLM stub）

本目录是**验收演示**，不是生产组件：`stub.py` 模拟 GitHub REST 与 OpenAI 兼容端点，`docker-compose.acceptance.yml` 把它接到
server 上。生产部署见 [`../../docs/deployment-server.md`](../../docs/deployment-server.md)。

## 前置

```bash
mvn -B -ntp -pl cra-server -am package -DskipTests   # 生成 cra-server/target/cra-server-0.1.0-exec.jar
```

## 启动（PG + server + stub）

```bash
cd docker
CRA_SERVER_PORT=18000 CRA_POSTGRES_PORT=55433 \
CRA_POSTGRES_PASSWORD=cra \
CRA_SERVER_WEBHOOK_SECRET=accept-wh-secret \
CRA_SERVER_API_TOKEN=accept-api-token \
CRA_SERVER_SIGNING_KEY=accept-signing-key \
CRA_SERVER_SIGNING_KEY_ID=accept-key-v1 \
CRA_SERVER_QUEUE_CAPACITY=10 CRA_SERVER_WORKERS=1 \
docker compose -f docker-compose.yml -f docker-compose.acceptance.yml up --build -d
curl -fsS http://127.0.0.1:18000/readyz
```

## 模拟 webhook（合法签名）

```bash
cat > /tmp/webhook.json <<'JSON'
{"action":"opened","number":7,
 "pull_request":{"number":7,"draft":false,
   "base":{"sha":"base-abc123"},"head":{"sha":"head-abc123"}},
 "repository":{"name":"demo","owner":{"login":"acme"}}}
JSON
SIG=$(python3 - <<'PY'
import hmac, hashlib
body=open('/tmp/webhook.json','rb').read()
print('sha256='+hmac.new(b'accept-wh-secret', body, hashlib.sha256).hexdigest())
PY
)
curl -sS -w '\nHTTP %{http_code}\n' -X POST http://127.0.0.1:18000/webhooks/github \
  -H "Content-Type: application/json" -H "X-GitHub-Delivery: accept-delivery-1" \
  -H "X-GitHub-Event: pull_request" -H "X-Hub-Signature-256: $SIG" \
  --data-binary @/tmp/webhook.json
# → HTTP 202 {"status":"accepted"}
```

重复同一 delivery → `HTTP 200 {"status":"duplicate"}`，`review_run` 仍只有一条（幂等）。

## 账本与签发

```bash
cd docker
docker compose -f docker-compose.yml -f docker-compose.acceptance.yml \
  exec -T postgres psql -U cra -d cra \
  -c "select run_id, repo, commit_sha, status, findings from review_run"

# 任取一份 L2 形状的报告（含 commit/tier/k/depth/gate），把 commit 改成 webhook head
curl -sS -o /tmp/report.json.sig -X POST http://127.0.0.1:18000/api/v1/reports/attest \
  -H "Authorization: Bearer accept-api-token" -H "Content-Type: application/json" \
  --data-binary @/tmp/report.json
CRA_REPORT_SIGNING_KEY=accept-signing-key java -jar cra-eval/target/cra-eval-0.1.0.jar verify \
  --report /tmp/report.json --signature /tmp/report.json.sig \
  --tier l2 --expect-commit head-abc123
# → 结论: 通过（exit 0）；篡改报告后同命令 exit 5
```

## 停机

```bash
docker compose -f docker-compose.yml -f docker-compose.acceptance.yml stop -t 60 server
# 日志应包含 Graceful shutdown complete + review 队列停机完成: drained=true
docker compose -f docker-compose.yml -f docker-compose.acceptance.yml down -v
```
