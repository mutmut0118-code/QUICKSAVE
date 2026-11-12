"""Assemble a production Fabric 1.21.1 classpath (official client jar + Mojang libs + Fabric Loader profile).

Usage: python3 -I tools/prodtest/prepare.py run/prod   (run.sh calls it once)
"""
import json, os, sys, urllib.request

root = sys.argv[1]                       # instance dir
info = json.load(open(os.path.expanduser('~/.gradle/caches/fabric-loom/1.21.1/mojang_minecraft_info.json')))
libdir = os.path.join(root, 'libraries')
cp = []

def fetch(url, rel):
    dest = os.path.join(libdir, rel)
    if not os.path.exists(dest):
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        urllib.request.urlretrieve(url, dest)
    return dest

def allowed(lib):
    rules = lib.get('rules')
    if not rules:
        return True
    ok = False
    for r in rules:
        osname = r.get('os', {}).get('name')
        if osname is None or osname == 'linux':
            ok = r['action'] == 'allow'
    return ok

for lib in info['libraries']:
    if not allowed(lib):
        continue
    art = lib.get('downloads', {}).get('artifact')
    if art:
        cp.append(fetch(art['url'], art['path']))

profile = json.load(urllib.request.urlopen('https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.19.5/profile/json'))
for lib in profile['libraries']:
    g, a, v = lib['name'].split(':')[:3]
    rel = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.jar"
    base = lib.get('url', 'https://maven.fabricmc.net/')
    cp.append(fetch(base.rstrip('/') + '/' + rel, rel))

# Fabric's own libs must come first (newer ASM than Mojang's).
fabric = [p for p in cp if '/net/fabricmc/' in p or '/org/ow2/asm/' in p]
rest = [p for p in cp if p not in fabric]
cp = fabric + rest + [os.path.expanduser('~/.gradle/caches/fabric-loom/1.21.1/minecraft-client.jar')]
open(os.path.join(root, 'classpath.txt'), 'w').write(':'.join(cp))
print(profile['mainClass'], info['assetIndex']['id'], len(cp), 'entries')
