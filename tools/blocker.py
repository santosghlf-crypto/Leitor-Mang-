"""Pack an authenticated official AMO release with bounded archive extraction."""
import hashlib
import hmac
import io
import json
import shutil
import stat
import sys
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit

ADDON_ID = "uBlock0@raymondhill.net"
VERSION = "1.77.0"
ALLOWED_HOSTS = {"addons.mozilla.org", "addons.cdn.mozilla.net"}
MAX_DOWNLOAD = 16 * 1024 * 1024
MAX_EXPANDED = 128 * 1024 * 1024

def trusted_url(url):
    try:
        u = urlsplit(url)
        return (u.scheme == "https" and u.hostname in ALLOWED_HOSTS and
                u.username is None and u.password is None and u.port in (None, 443))
    except ValueError:
        return False

class TrustedRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not trusted_url(newurl):
            raise ValueError("Redirecionamento fora da origem oficial")
        return super().redirect_request(req, fp, code, msg, headers, newurl)

def download(url):
    if not trusted_url(url):
        raise ValueError("Origem HTTPS da Mozilla obrigatória")
    opener = urllib.request.build_opener(TrustedRedirect())
    request = urllib.request.Request(url, headers={"User-Agent": "Koma-Android-build/3.1"})
    with opener.open(request, timeout=90) as response:
        if not trusted_url(response.geturl()):
            raise ValueError("Origem final não autorizada")
        data = response.read(MAX_DOWNLOAD + 1)
        if len(data) > MAX_DOWNLOAD:
            raise ValueError("Download excede limite")
        return data

def checked_members(archive):
    total = 0
    seen = set()
    for member in archive.infolist():
        name = member.filename
        path = PurePosixPath(name)
        mode = (member.external_attr >> 16) & 0o170000
        if (not name or name.startswith("/") or "\\" in name or ":" in name or
                ".." in path.parts or mode == stat.S_IFLNK or name in seen):
            raise ValueError("Entrada de arquivo não permitida")
        seen.add(name)
        total += member.file_size
        if total > MAX_EXPANDED or len(seen) > 20000:
            raise ValueError("Arquivo expandido excede limite")
        yield member

def verify(data, digest):
    actual = "sha256:" + hashlib.sha256(data).hexdigest()
    if not isinstance(digest, str) or not hmac.compare_digest(actual, digest):
        raise ValueError("Pacote incompleto ou alterado")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        list(checked_members(archive))
        manifest = json.loads(archive.read("manifest.json"))
        settings = manifest.get("browser_specific_settings") or manifest["applications"]
        if settings["gecko"]["id"] != ADDON_ID or manifest["version"] != VERSION:
            raise ValueError("Extensão ou versão inesperada")

def main(output):
    url = "https://addons.mozilla.org/api/v5/addons/addon/ublock-origin/versions/?page_size=50"
    metadata = None
    for _ in range(30):
        if not url or metadata is not None:
            break
        page = json.loads(download(url))
        metadata = next((v for v in page["results"] if v["version"] == VERSION), None)
        url = page.get("next")
    if metadata is None:
        raise ValueError("Versão oficial do uBlock não encontrada")
    package = metadata["file"]
    data = download(package["url"])
    verify(data, package["hash"])
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_suffix(".part")
    shutil.rmtree(temp, ignore_errors=True)
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            archive.extractall(temp, members=list(checked_members(archive)))
        shutil.rmtree(output, ignore_errors=True)
        temp.replace(output)
    finally:
        shutil.rmtree(temp, ignore_errors=True)

if __name__ == "__main__":
    main(Path(sys.argv[1]))
