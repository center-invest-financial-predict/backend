# Financial Predict test stand

The stand runs as three sibling checkouts on the homelab:

```text
/opt/financial-predict/frontend
/opt/financial-predict/backend
/opt/financial-predict/ml
```

The browser opens `http://192.168.31.206`. Nginx serves the frontend and forwards
`/api/*` to Spring Boot. PostgreSQL, Redis, integration services, and ML Core are
reachable only on Docker networks. ML Core remains in the `mlcore-user-demo`
Compose project so its existing local LLM and model mount are reused.

## Deploy or update

Fetch and check out the `dev` branch in each repository. Keep runtime `.env`
files on the homelab and out of Git. Copy this repository's `.env.example` to
`.env`, replace all placeholders with unique random values, and set
`INTEGRATION_API_KEY` to the same value in the 1C and Gos integration services.
Preserve the current `ml/.env` so its local model path and LLM settings remain
unchanged.

Create the shared network once, then bring up ML Core before the application:

```sh
docker network create app-net
cd /opt/financial-predict/ml
docker compose -p mlcore-user-demo --profile local up -d --build api llm
cd /opt/financial-predict/backend
docker compose -p financial-predict up -d --build
```

The network command may report that `app-net` already exists. Do not remove
Compose volumes during updates. The ML Core host port is loopback-only on
`127.0.0.1:18081`; backend-to-ML traffic uses `http://ml-core:8000` inside
`app-net`. Only the frontend publishes a LAN port.

Check `http://192.168.31.206/api/health` and
`http://192.168.31.206/api/ml/core/health` after rollout. Use the browser to
register a test user, select a seeded company, review an ML import before
confirming it, and verify that the forecast and operations list refresh.

The repository includes synthetic company metadata and the integration services
seed demo operations when source statement files are absent. Upload a real
statement to evaluate the ML forecast; short history can intentionally fall
back to the built-in backend forecast.
