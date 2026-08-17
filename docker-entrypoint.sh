#!/bin/sh
set -e
# Bind-mounts often arrive as root:root. The process runs as uid 10001; fix
# ownership while we still have root, then drop privileges.
mkdir -p /data /data/memory
if [ "$(id -u)" = "0" ]; then
  chown -R mrr:mrr /data
  exec su -s /bin/sh mrr -c 'exec java -jar /app/mrr.jar'
fi
exec java -jar /app/mrr.jar
