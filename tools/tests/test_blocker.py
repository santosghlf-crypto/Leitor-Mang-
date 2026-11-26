import hashlib
import io
import json
import stat
import unittest
import zipfile
from tools import blocker

def archive(entries):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as z:
        for name, content in entries:
            z.writestr(name, content)
    return data.getvalue()

def signed(entries=(), version=blocker.VERSION, addon_id=blocker.ADDON_ID):
    manifest = {"version": version, "browser_specific_settings":{"gecko":{"id":addon_id}}}
    data = archive([("manifest.json",json.dumps(manifest)),*entries])
    return data, "sha256:"+hashlib.sha256(data).hexdigest()

class SecurityTest(unittest.TestCase):
    def test_only_amo(self):
        for url in ("https://addons.mozilla.org/api/v5/", "https://addons.cdn.mozilla.net/a.xpi"):
            self.assertTrue(blocker.trusted_url(url))
        for url in ("http://addons.mozilla.org/x", "https://addons.mozilla.org.evil.com/x", "https://addons.mozilla.org@evil.com/x", "https://user@addons.mozilla.org/x", "https://addons.mozilla.org:8080/x", "file:///tmp/x"):
            self.assertFalse(blocker.trusted_url(url))
    def test_valid_archive(self):
        blocker.verify(*signed())
    def test_wrong_hash(self):
        data,_=signed()
        with self.assertRaises(ValueError): blocker.verify(data,"sha256:"+"0"*64)
    def test_wrong_id(self):
        with self.assertRaises(ValueError): blocker.verify(*signed(addon_id="other@extension"))
    def test_wrong_version(self):
        with self.assertRaises(ValueError): blocker.verify(*signed(version="0.1"))
    def test_path_traversal(self):
        for path in ("../escape","/escape","a/../../escape","a\\..\\escape","C:/escape"):
            with self.subTest(path=path),self.assertRaises(ValueError): blocker.verify(*signed([(path,b"x")]))
    def test_symlink(self):
        entry=zipfile.ZipInfo("link")
        entry.create_system=3
        entry.external_attr=(stat.S_IFLNK|0o777)<<16
        with self.assertRaises(ValueError): blocker.verify(*signed([(entry,b"../outside")]))
if __name__=="__main__":
    unittest.main()
