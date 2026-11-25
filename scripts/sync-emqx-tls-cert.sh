#!/usr/bin/env bash
set -Eeuo pipefail

DOMAIN="${EMQX_TLS_DOMAIN:-cabinet.easygovm.com}"
MODE="reload"
CONTAINER="${EMQX_CONTAINER:-ai-cabinet-emqx-1}"

usage() {
  echo "usage: $0 [--no-reload | --reload | --install-hook]" >&2
  exit 2
}

[ "$#" -le 1 ] || usage
case "${1:-}" in
  '') ;;
  --no-reload) MODE="sync" ;;
  --reload) MODE="reload" ;;
  --install-hook) MODE="install-hook" ;;
  *) usage ;;
esac

[ "$(id -u)" -eq 0 ] || { echo "must run as root" >&2; exit 1; }
[ -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ] || {
  echo "missing Let’s Encrypt fullchain for $DOMAIN" >&2
  exit 1
}
[ -f "/etc/letsencrypt/live/$DOMAIN/privkey.pem" ] || {
  echo "missing Let’s Encrypt private key for $DOMAIN" >&2
  exit 1
}

if [ "$MODE" = "install-hook" ]; then
  HOOK_DIR="/etc/letsencrypt/renewal-hooks/deploy"
  install -d -m 755 "$HOOK_DIR"
  install -m 755 "$0" "$HOOK_DIR/aicabinet-emqx-tls.sh"
  echo "installed certbot deploy hook: $HOOK_DIR/aicabinet-emqx-tls.sh"
  exit 0
fi

DEST="/etc/aicabinet/tls/$DOMAIN"
install -d -m 750 "$DEST"
install -m 644 "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" "$DEST/fullchain.pem"
install -m 600 "/etc/letsencrypt/live/$DOMAIN/privkey.pem" "$DEST/privkey.pem"
chown -R 1000:1000 "$DEST"
openssl x509 -in "$DEST/fullchain.pem" -noout -checkhost "$DOMAIN" >/dev/null

if [ "$MODE" = "reload" ] && [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || true)" = "true" ]; then
  docker restart "$CONTAINER" >/dev/null
  echo "restarted $CONTAINER to load the renewed certificate"
fi

echo "EMQX TLS material ready: $DEST"
