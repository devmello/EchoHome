#!/bin/bash
# First-time pairing with the Echo over Wi-Fi (Android 11 wireless debugging).
# Run once from the echo-root folder:  ./scripts/echo-pair.sh
cd "$(dirname "$0")/.." || exit 1
ADB=./platform-tools/adb
[ -x "$ADB" ] || { echo "adb not found at $ADB"; exit 1; }
xattr -d com.apple.quarantine platform-tools/adb platform-tools/fastboot 2>/dev/null
"$ADB" start-server >/dev/null 2>&1

echo
echo "On the Echo, open: Settings > System > Developer options > Wireless debugging"
echo "Tap 'Pair device with pairing code' and leave that screen open."
echo
printf "Looking for the Echo's pairing service on your network"
PAIR=""
for i in 1 2 3 4 5 6 7 8 9 10; do
  PAIR=$("$ADB" mdns services 2>/dev/null | awk '/_adb-tls-pairing._tcp/ {print $NF; exit}')
  [ -n "$PAIR" ] && break
  printf "."; sleep 1
done
echo
if [ -n "$PAIR" ]; then
  echo "Found it at $PAIR"
else
  printf "Not found automatically. Type the IP address & port shown under the pairing code (e.g. 192.168.1.42:37123): "
  read -r PAIR
fi
printf "Type the 6-digit pairing code shown on the Echo: "
read -r CODE
"$ADB" pair "$PAIR" "$CODE" || { echo "Pairing failed. Check the code and try again."; exit 1; }

IP=${PAIR%%:*}
echo "$IP" > .echo-ip
echo
echo "Paired. Connecting..."
sleep 2
exec ./scripts/echo-connect.sh
