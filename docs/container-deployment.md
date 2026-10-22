# Container deployment

The repository-root and `backend/` Dockerfiles build the Node.js API and Socket.IO service. They do not package the Android app; SYSU Go's Android client remains an APK built and signed separately. The web dashboard is in the sibling `sysu-app` project.

## Build and run

Use a host with Docker Engine or another OCI-compatible container runtime. Copy `.env.container.example` to `.env`, choose `SYSU_ENV=staging` or `production` with the matching Firebase project, set the exact allowed origins, and provide a matching `FIREBASE_SERVICE_ACCOUNT_JSON` through the host's secret manager. The sample intentionally contains no credentials and will not start until valid deployment values are supplied.

```sh
docker compose up --build -d
docker compose ps
docker compose logs -f api
```

The default host/container port is `8080`; set `PORT` and `HOST_PORT` in `.env` as needed. The API and Socket.IO share one listener and the container health check probes `/api/health`. TLS and public DNS belong at the host's reverse proxy or ingress. Do not mount a service-account key into the image or commit `.env`.

The two supported build contexts are:

```sh
docker build -t sysu-go-api:local .
docker build -t sysu-go-api:local -f backend/Dockerfile backend
```

The root build uses the root `.dockerignore`; the backend build uses `backend/.dockerignore`. Both images run as the unprivileged Node user, install locked production dependencies, bind to `0.0.0.0`, and honor `PORT`. Configure the Android build variant's API and Socket.IO URLs to the deployed HTTPS origin when producing an APK.

The production API is hosted on Render at `https://sysu-go.onrender.com`; use the root Dockerfile and `/api/health` health check. The images support any OCI-compatible host. A plain LAMP installation without a container runtime cannot run them. Horizontal replicas need a shared Socket.IO adapter and suitable session routing before scaling; this image does not supply those services.


## Render production connections

- SYSU App API and Socket.IO: `https://sysu-app.onrender.com`.
- SYSU Go API and Socket.IO: `https://sysu-go.onrender.com`.
- Dashboard hosting remains `https://sysu-web-5a25d.web.app`; include this origin in `ALLOWED_ORIGINS` on both services.
- Build the web frontend with `VITE_API_URL` and `VITE_SOCKET_URL` pointing to SYSU App and `VITE_DRIVER_TRACKING_URL` pointing to SYSU Go. Rebuild and redeploy Firebase Hosting after endpoint changes.
- Rebuild and install the Android APK after changing its `API_BASE_URL` and `SOCKET_URL`; server environment variables cannot change an installed APK's destinations.
- SYSU Go requires `SYSU_ENV=production`, `FIREBASE_PROJECT_ID=sysu-web-5a25d`, and a valid replacement service-account credential supplied securely by the owner. Production must not have Firebase emulator variables set.
- Backend deployment does not publish the web frontend or distribute an updated Android APK. Health checks only prove process availability; verify authenticated cross-app behavior separately.
