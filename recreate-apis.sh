#!/usr/bin/env bash
# Recria matrix-api e native-api com as imagens :local recém-construídas,
# HERDANDO as variáveis de ambiente (incl. DB_PASSWORD) dos containers atuais —
# a senha nunca aparece na linha de comando nem no terminal.
#
# Uso:  bash api-java/recreate-apis.sh
set -euo pipefail

for svc in matrix-api native-api; do
  echo ">> recriando $svc"
  # captura as flags --env do container em execução, sem imprimi-las
  mapfile -t ENVFLAGS < <(docker inspect "$svc" --format '{{range .Config.Env}}{{println "--env"}}{{println .}}{{end}}')
  port=$(docker inspect "$svc" --format '{{range $p,$_ := .NetworkSettings.Ports}}{{$p}}{{end}}' | grep -oE '^[0-9]+' | head -1)
  docker rm -f "$svc" >/dev/null
  docker run -d --name "$svc" -p "${port}:${port}" --restart unless-stopped \
    "${ENVFLAGS[@]}" "${svc}:local" >/dev/null
  echo "   $svc no ar em :$port"
done

echo ">> aguardando healthy..."
sleep 12
docker ps --filter name=matrix-api --filter name=native-api --format '{{.Names}}\t{{.Status}}'
