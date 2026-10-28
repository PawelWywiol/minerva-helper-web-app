# Minerva Helper web app

Browser-driven counterpart of [Minerva Helper](https://github.com/PawelWywiol/minervahelper) for Linux, shipped as a Docker image. It finds retro games in the public MiNERVA archive and downloads **only the files you pick**, verifies them and files them into an [ES-DE](https://es-de.org) style library.

> **Status:** early development. Only the server skeleton exists so far (health endpoint and request guard). Catalogue, queue, downloading and file placement follow in the next releases.

Part of the [retro](https://github.com/PawelWywiol/retro) stack, where it runs behind a VPN container.

## Run

```sh
docker run -d --name minerva-helper \
  -p 8090:8080 \
  -u 1000:1000 \
  -v /srv/retro/library:/library \
  -v /srv/retro/data/minerva:/data \
  ghcr.io/pawelwywiol/minerva-helper-web-app:latest
```

Open `http://<host-ip>:8090`.

| Variable        | Default | Purpose                                                       |
| --------------- | ------- | ------------------------------------------------------------- |
| `PORT`          | `8080`  | port inside the container                                     |
| `ALLOWED_HOSTS` | empty   | comma-separated hostnames allowed besides IPs and `localhost` |

| Volume     | Content                                   |
| ---------- | ----------------------------------------- |
| `/library` | game library root (`roms/<system>`)       |
| `/data`    | catalogue, queue, settings, staging files |

## Security model

No login; meant for a trusted LAN. Requests are rejected when `Host` is a hostname outside `ALLOWED_HOSTS` (DNS rebinding) and when a state-changing request lacks the `X-Minerva: 1` header (CSRF). See [SECURITY.md](SECURITY.md).

## Development

JDK is optional: `scripts/gradle.sh` uses a local JDK 25 when present, otherwise runs Gradle in Docker.

```sh
pnpm install          # tooling + git hooks (lefthook)
pnpm build            # Gradle build: compile, ktlint, tests
pnpm lint             # ktlint, shellcheck, hadolint, actionlint
pnpm scan:image       # build the image and scan it with Trivy
pnpm secrets          # gitleaks over the git history
pnpm audit            # audit-ci
pnpm release          # commit-and-tag-version
```

Commits follow [Conventional Commits](https://www.conventionalcommits.org) (commitlint, header ≤ 70 chars).

## License

[MIT](LICENSE)
