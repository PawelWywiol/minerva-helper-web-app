# Minerva Helper web app

Browser-driven counterpart of [Minerva Helper](https://github.com/PawelWywiol/minervahelper) for Linux, shipped as a Docker image. It finds retro games in the public MiNERVA archive and downloads **only the files you pick**, verifies them and files them into an [ES-DE](https://es-de.org) style library.

- search 2.4 million files by name, system or both (`nes mario`, `amiga cd32`), ranked by shared words in any order, or paste a whole list; filter by release region; versions of games you already have are flagged; browse by collection and platform, game details beside the list with the other versions of a game a tab away, favourites
- check from Settings that downloads leave through the VPN (needs gluetun's `GET /v1/vpn/status` and `GET /v1/publicip/ip` routes, see `VPN_CONTROL_URL`)
- download queue: order by torrent, pause whole torrents, retry failed ones
- only the picked file is fetched out of a torrent; nothing is ever uploaded (no incoming connections, no unchoking, torrents removed after each download, zero uploaded bytes checked)
- every file is verified against the archive's checksum; disc images are unpacked, cartridge zips are kept
- per-platform folders, ES-DE names by default; a game already in its folder is recognised instead of fetched again

Part of the [retro](https://github.com/PawelWywiol/retro) stack, where it runs behind a VPN container. Run it behind a VPN: downloading from a swarm exposes your IP to its peers.

On first start open Home and download the catalogue (about 1.7 GB, a few minutes, once).

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
| `VPN_CONTROL_URL` | `http://127.0.0.1:8000` | gluetun control server, asked by the VPN check in Settings |

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
