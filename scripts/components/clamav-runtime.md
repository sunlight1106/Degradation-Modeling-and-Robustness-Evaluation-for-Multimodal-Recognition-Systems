# Portable ClamAV component

This component runs the real ClamAV daemon with the complete official main,
daily, and bytecode databases. It is not a mock scanner. It uses only loopback
TCP (`127.0.0.1:3310` by default), never a Unix socket or public bind address.

## Requirements and provenance

- Debian 13 (trixie), x86_64, a non-root user, Python 3, curl, dpkg-deb.
- ClamAV engine `1.4.3+dfsg-1`, the version offered by the authenticated Debian
  trixie package index at provisioning on 2026-10-02. It is not advertised as the
  latest upstream release. Keep engine packages updated using supported releases.
- `clamav-packages.tsv` locks the six packages downloaded from the official
  Debian repository, with SHA-256 hashes obtained from signed apt metadata.
- Archives are verified before local extraction. No package maintainer scripts,
  system services, privileged installation, or global configuration are used.
- Host shared libraries are retained; local libclamav and libmspack satisfy the
  missing dependencies. This is not a self-contained build for other platforms.
- FreshClam retrieves databases from `https://database.clamav.net` and validates
  them. The installer also uses `sigtool --info` to verify each CVD signature.

## Commands

Stop the component before provisioning or replacing its binaries. From the repository root:

```sh
scripts/components/clamav-provision.sh
scripts/components/clamav.sh run        # foreground; suitable for a supervisor
scripts/components/clamav.sh status     # PING and engine/database VERSION
scripts/components/clamav.sh self-test # clean text + harmless EICAR via INSTREAM
scripts/components/clamav.sh update    # one official database update
```

`CLAMAV_HOME` defaults to `/workspace/shared/tooling/clamav-runtime` and
`CLAMAV_PORT` defaults to `3310`. The home contains extracted binaries in `root`,
databases in `data`, configuration files at its root, logs in `logs`, and PID and
temporary files in `run`. Do not commit this runtime's binaries/databases.

The helper also provides `start`, `stop`, and `update-daemon`. On the managed
execution environment, individual command sessions have isolated loopback/PID
namespaces: launch the full stack and its tests within the same retained shell.
A daemon started in one command session is not reachable from another. `start`
and `stop` therefore belong in the same retained shell; prefer `run` as a
background child managed by the stack supervisor.

For a long-lived installation, keep FreshClam running with `update-daemon` in the
same supervisor, or run `update` regularly. Clamd reloads changed signatures on
its 600-second self-check. Installing initial definitions alone does not provide
ongoing updates. Preserve the database directory between starts.

## Backend integration

```sh
ANTIVIRUS_ENABLED=true
ANTIVIRUS_REQUIRED=true
CLAMAV_HOST=127.0.0.1
CLAMAV_PORT=3310
```

The backend uses clamd's `INSTREAM` protocol. The self-test sends the standard
non-malicious EICAR test string in memory, not an executable or real malware.
It requires `stream: OK` for clean content and an EICAR `FOUND` result. Its JSON
output includes daemon and database version evidence. Run end-to-end application
upload tests separately to verify the application's rejection path.

## Verified initial database snapshot

FreshClam download and database tests passed on 2026-10-02:

- `main.cvd`: version 63, 3,287,027 declared signatures
- `daily.cvd`: version 28141, 355,709 declared signatures, built 2026-10-02 06:26 UTC
- `bytecode.cvd`: version 339, 80 declared signatures

The daemon reports 3,628,114 loaded signatures. This differs from the sum of the
CVD header totals; the two counts are recorded separately. Subsequent updates
will change these versions and totals.

Timestamp note: this executor uses JST (UTC+09:00). Clamd VERSION therefore
printed `Fri Oct 2 15:26:12 2026`, while `sigtool --info daily.cvd` reports the
header build time as `02 Oct 2026 06:26 +0000`. Preserve timezone context when
comparing these values; version numbers and verified CVD signatures are the
authoritative evidence recorded here.
