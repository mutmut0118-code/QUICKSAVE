#!/bin/bash
# Production-style (non-dev) Fabric launch of the Quicksave self-test next to other mods'
# release jars, headless. Dev runs use Yarn names, so mods that reflect on intermediary names
# (e.g. Faster Screenshots, bundled in Smooth Loading Screen) only behave correctly here.
#
#   ./gradlew build remapSelftestJar
#   tools/prodtest/run.sh [extra-mod.jar ...]        # result: run/prod/selftest-result.txt
#
# Needs a Loom cache (any ./gradlew build) and network access to Mojang's library CDN and
# maven.fabricmc.net for the first prepare.
set -e
REPO=$(cd "$(dirname "$0")/../.." && pwd)
P="$REPO/run/prod"
mkdir -p "$P"
[ -f "$P/classpath.txt" ] || python3 -I "$REPO/tools/prodtest/prepare.py" "$P"
CACHE=~/.gradle/caches/modules-2/files-2.1
VERSION=$(grep ^mod_version "$REPO/gradle.properties" | cut -d= -f2)

rm -rf "$P/mods" "$P/saves" "$P/quicksaves" "$P/selftest-result.txt" "$P/screenshots" "$P/config/quicksave.json"
mkdir -p "$P/mods"
cp "$REPO/build/libs/quicksave-$VERSION.jar" "$P/mods/"
cp "$REPO"/build/selftest-libs/quicksave-selftest-*.jar "$P/mods/"
cp "$(find $CACHE/net.fabricmc.fabric-api/fabric-api -name "fabric-api-$(grep ^fabric_version "$REPO/gradle.properties" | cut -d= -f2).jar" | head -1)" "$P/mods/"
cp "$(find $CACHE/com.terraformersmc/modmenu -name "modmenu-*.jar" ! -name "*sources*" | head -1)" "$P/mods/"
cp "$(find $CACHE/me.shedaniel.cloth/cloth-config-fabric -name "cloth-config-fabric-*.jar" ! -name "*sources*" | head -1)" "$P/mods/"
for extra in "$@"; do cp "$extra" "$P/mods/"; done
printf "onboardAccessibility:false\nrenderDistance:4\nsimulationDistance:5\ntutorialStep:none\nnarrator:0\n" > "$P/options.txt"

cd "$P"
LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1280x720x24" java -Xmx2G -cp "$(cat classpath.txt)" \
	net.fabricmc.loader.impl.launch.knot.KnotClient \
	--username Player --version 1.21.1 --gameDir "$P" \
	--assetsDir ~/.gradle/caches/fabric-loom/assets --assetIndex 17 --accessToken 0 --userType legacy
cat "$P/selftest-result.txt"
