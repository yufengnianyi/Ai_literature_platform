# Deployment Workflow

This repository is deployed from source. Development happens locally, and the server only pulls code from Git and rebuilds the Docker Compose stack.

## Rules

- Treat your local machine as the only place for code changes and debugging.
- Do not edit application code on the server.
- Keep the real `.env` file on the server only. Never commit it.
- Use the fixed Compose project name `ai_literature` to avoid duplicate stacks.

## One-Time Server Setup

1. Install `git`, Docker, and Docker Compose on the server.
2. Clone the repository into `/data01/www/chengjun/ai_literature/demo_01`.
3. Create the server `.env` file:

```bash
cd /data01/www/chengjun/ai_literature/demo_01
cp .env.example .env
```

4. Edit `.env` with the real server values.
5. Configure Apache as the only public entrypoint:

```apache
ProxyPreserveHost On
RedirectMatch 301 ^/viteApp$ /viteApp/
RequestHeader set X-Forwarded-Proto "https"
RequestHeader set X-Forwarded-Port "443"
ProxyPass        "/viteApp/" "http://127.0.0.1:8088/" retry=0 timeout=600 connectiontimeout=10 keepalive=On
ProxyPassReverse "/viteApp/" "http://127.0.0.1:8088/"
```

This keeps the Docker frontend private on `127.0.0.1:8088` and publishes the app at `https://biotec2.njau.edu.cn/viteApp/`.

6. Run the initial deployment:

```bash
cd /data01/www/chengjun/ai_literature/demo_01
bash scripts/bootstrap-server.sh
```

7. If this is the first deployment, rebuild the RAG dataset:

```bash
cd /data01/www/chengjun/ai_literature/demo_01
bash scripts/rebuild-rag.sh
```

## Daily Release Flow

### Local machine

1. Update the code locally.
2. Run local verification.
3. Commit and push to the deployment branch, default `main`.

### Server

```bash
cd /data01/www/chengjun/ai_literature/demo_01
bash scripts/deploy-prod.sh
```

What the script does:

- Verifies the server worktree is clean.
- Pulls `origin/main` with `git pull --ff-only`.
- Rebuilds the stack with `docker compose -p ai_literature -f docker-compose.prod.yml up -d --build`.
- Keeps the frontend bound to `127.0.0.1:8088` so Apache remains the only public endpoint.
- Checks container status, `/api/actuator/health`, `/api/rag/ingestions/status`, and the `embedding_store` row count.
- Warns if docs changed and RAG rebuild is required.

If you want docs changes to trigger RAG rebuild automatically during deployment:

```bash
cd /data01/www/chengjun/ai_literature/demo_01
AUTO_REBUILD_RAG=1 bash scripts/deploy-prod.sh
```

## RAG Operations

Check the current runtime status:

```bash
cd /data01/www/chengjun/ai_literature/demo_01
bash scripts/check-prod.sh
```

Rebuild embeddings after the docs dataset changes:

```bash
cd /data01/www/chengjun/ai_literature/demo_01
bash scripts/rebuild-rag.sh
```

The rebuild is required when files under `src/main/resources/docs` change.

## Public URL Layout

- Public app URL: `https://biotec2.njau.edu.cn/viteApp/`
- Public API base URL: `https://biotec2.njau.edu.cn/viteApp/api`
- Private container URL on the server: `http://127.0.0.1:8088/`

The production frontend bundle is built for the `/viteApp/` base path. If you change the public prefix later, update Apache `ProxyPass`, `VITE_APP_BASE_PATH`, and `VITE_API_BASE_URL` together.

## Cleaning Up Duplicate Containers

If an older stack was started under the default Compose project name, stop it before redeploying:

```bash
cd /data01/www/chengjun/ai_literature/demo_01
docker compose -p demo_01 -f docker-compose.prod.yml down --remove-orphans
```

After cleanup, the running containers should be limited to the `ai_literature-*` stack.
