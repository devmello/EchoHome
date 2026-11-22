#!/bin/bash
# Connect to the already-paired Echo over Wi-Fi and print its adb serial.
# Usage: ./scripts/echo-connect.sh     (then: adb -s "$(cat .echo-serial)" shell ...)
cd "$(dirname "$0")/.." || exit 1
ADB=./platform-tools/adb
"$ADB" start-server >/dev/null 2>&1
IP=$(cat .echo-ip 2>/dev/null)

# 1. Already connected?
SERIAL=$("$ADB" devices | awk -v ip="$IP" 'NR>1 && $2=="device" && (ip=="" || index($1, ip)==1 || $1 ~ /_adb-tls-connect/) {print $1; exit}')

# 2. Find the wireless-debugging port over mDNS (it changes after every reboot)
if [ -z "$SERIAL" ]; then
  for i in 1 2 3 4 5 6 7 8; do
    CONN=$("$ADB" mdns services 2>/dev/null | awk -v ip="$IP" '/_adb-tls-connect._tcp/ && (ip=="" || index($NF, ip)==1) {print $NF; exit}')
    [ -n "$CONN" ] && break
    sleep 1
  done
  # After a reboot this ROM may offer plain adb on 5555 (service.adb.tcp.port) instead of wireless debugging.
  if [ -z "$CONN" ] && [ -n "$IP" ] && "$ADB" connect "$IP:5555" 2>/dev/null | grep -q '^connected'; then
    sleep 1; "$ADB" -s "$IP:5555" get-state 2>/dev/null | grep -q device && CONN="$IP:5555"
  fi
  if [ -z "$CONN" ]; then
    printf "Couldn't find the Echo automatically. Type the IP address & port shown at the top of its Wireless debugging screen: "
    read -r CONN
  fi
  "$ADB" connect "$CONN" | grep -v '^$'
  SERIAL=$("$ADB" devices | awk -v c="$CONN" 'NR>1 && $1==c && $2=="device" {print $1; exit}')
fi

if [ -z "$SERIAL" ]; then
  echo "Not connected. Make sure Wireless debugging is on and the Echo is on the same Wi-Fi as this Mac."
  exit 1
fi
echo "$SERIAL" > .echo-serial
[ -z "$IP" ] && echo "${SERIAL%%:*}" > .echo-ip
echo "Connected: $SERIAL ($("$ADB" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r'))"
