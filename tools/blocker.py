"""Pack the official Firefox release without modifying its contents."""
import hashlib
import io
import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path
from urllib.parse import urlsplit

ADDON_ID = "uBlock0@raymondhill.net"
VERSION = "1.71.0"

def download(url):
    if urlsplit(url).scheme != "https":
        raise ValueError("HTTPS obrigatório")
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()

def verify(data, digest):
    if "sha256:" + hashlib.sha256(data).hexdigest() != digest:
        raise ValueError("Pacote incompleto ou alterado")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        settings = manifest.get("browser_specific_settings") or manifest["applications"]
        if settings["gecko"]["id"] != ADDON_ID or manifest["version"] != VERSION:
            raise ValueError("Extensão ou versão inesperada")

def main(output):
    metadata = json.loads(download(
        f"https://addons.mozilla.org/api/v5/addons/addon/ublock-origin/versions/{VERSION}/"))
    package = metadata["file"]
    data = download(package["url"])
    verify(data, package["hash"])
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_suffix(".part")
    shutil.rmtree(temp, ignore_errors=True)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        archive.extractall(temp)
    shutil.rmtree(output, ignore_errors=True)
    temp.replace(output)

if __name__ == "__main__":
    main(Path(sys.argv[1]))
