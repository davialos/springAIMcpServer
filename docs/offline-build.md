# Offline build and the vendored dependency repository

`offline-repo/` is a Maven local repository committed to this repository: every jar, pom and Maven plugin the
build needs, resolved from Maven Central on 2026-09-30 (194 jars, ~120 MB). It lets the project build on a machine
with no network and pins the exact bytes of every dependency (supply-chain reproducibility).

## Use

```
scripts/build-offline.sh                 # mvn --offline verify: compile, unit tests, Testcontainers ITs
scripts/build-offline.sh -DskipITs       # unit tests only (no Docker needed)
scripts/build-offline.sh test -pl spring-ai-mcp-server-common-core
```

The script is `mvn -B --offline -Dmaven.repo.local=<repo>/offline-repo <args>`; the goal defaults to `verify`.
Requirements outside the repository: JDK 25 and Maven 3.9.x (`RequireJavaVersion` / `RequireMavenVersion` are
enforced), and for the ITs a Docker daemon that already has (or can pull) `postgres:17-alpine`.

Verified: a clean `git archive` export builds and passes all unit tests with the proxy variables unset.

## Refresh (after any dependency, plugin or version change)

```
rm -rf /tmp/fresh-repo
mvn -B -Dmaven.repo.local=/tmp/fresh-repo verify        # online, from an empty repository
find /tmp/fresh-repo \( -name '*.lastUpdated' -o -name resolver-status.properties \) -delete
rm -rf offline-repo && cp -r /tmp/fresh-repo offline-repo
scripts/build-offline.sh -DskipITs                       # prove it, then commit offline-repo together with the pom change
```

Starting from an empty repository keeps stale artifacts out. Do not hand-edit files under `offline-repo/`;
`.gitattributes` marks it `-text` so line endings are never rewritten.

## Notes

- The project's own modules are not in the repository (the reactor builds them); a host application that
  consumes the starter should use the `bom` module and its own repository.
- The `*.sha1` files and `_remote.repositories` markers are kept: Maven's enhanced local repository manager needs
  the markers to accept an artifact as coming from `central` in offline mode.
- ~120 MB of binaries grows the clone size; if that becomes a problem, move `offline-repo/` to Git LFS or an
  internal artifact repository (the script only needs a directory).
