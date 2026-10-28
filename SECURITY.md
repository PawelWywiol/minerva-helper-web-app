# Security

Report vulnerabilities privately through GitHub: **Security → Report a vulnerability** on this repository. Do not open public issues for security problems.

The server has no login and is meant for a trusted LAN only. It rejects requests whose `Host` is a hostname outside `ALLOWED_HOSTS` (DNS rebinding) and state-changing requests without the `X-Minerva: 1` header (CSRF). It never serves the content of downloaded files.
