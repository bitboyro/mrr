#!/bin/sh
set -e
# Bind-mounts often arrive as root:root. The process runs as uid 10001; fix
# ownership while we still have root, then drop privileges.
mkdir -p /data /data/memory
if [ "$(id -u)" = "0" ]; then
  chown -R mrr:mrr /data
  # su resets PATH to the login.defs default, which does not include
  # /opt/java/openjdk/bin — 'exec java' inside it fails with "java: not found".
  # The rest of the environment survives, so resolve the interpreter here, while
  # the image's own PATH is still in place, and hand su an absolute path.
  JAVA="$(command -v java || echo "${JAVA_HOME:-/opt/java/openjdk}/bin/java")"
  exec su -s /bin/sh mrr -c "exec '$JAVA' -jar /app/mrr.jar"
fi
exec java -jar /app/mrr.jar
