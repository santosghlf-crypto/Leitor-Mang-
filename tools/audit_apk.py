"""Validate the real binary AndroidManifest, not just source XML."""
import struct, sys, zipfile
def inspect(path):
    with zipfile.ZipFile(path) as z:
        b=z.read("AndroidManifest.xml")
    def u16(o): return struct.unpack_from("<H",b,o)[0]
    def u32(o): return struct.unpack_from("<I",b,o)[0]
    def length8(o):
        n=b[o]
        return (((n&127)<<8)|b[o+1],o+2) if n&128 else (n,o+1)
    strings=[]
    def get(i): return strings[i] if i<len(strings) else ""
    pos=8; elements=[]
    while pos+8<=len(b):
        typ,header,size=struct.unpack_from("<HHI",b,pos)
        if size<8 or pos+size>len(b): raise ValueError("invalid AXML")
        if typ==1:
            count,_,flags,start,_=struct.unpack_from("<IIIII",b,pos+8)
            for i in range(count):
                o=pos+start+u32(pos+header+4*i)
                if flags&256:
                    _,o=length8(o); n,o=length8(o)
                    strings.append(b[o:o+n].decode("utf-8"))
                else:
                    n=u16(o);o+=2
                    if n&32768: n=((n&32767)<<16)|u16(o);o+=2
                    strings.append(b[o:o+n*2].decode("utf-16le"))
        elif typ==0x102:
            name=get(u32(pos+20))
            start,stride,count=struct.unpack_from("<HHH",b,pos+24)
            attrs={}
            for i in range(count):
                q=pos+16+start+i*stride
                key=get(u32(q+4)); raw=u32(q+8); typval=b[q+15]; val=u32(q+16)
                attrs[key]=get(raw) if raw!=0xffffffff else get(val) if typval==3 else bool(val) if typval==18 else val
            elements.append((name,attrs))
        pos+=size
    return elements
def verify(path):
    e=inspect(path)
    app=next(a for n,a in e if n=="application")
    assert app.get("debuggable",False) in (False,"false"), "APK debuggable!"
    assert app.get("allowBackup") in (False,"false"), "Backup Android inesperadamente permitido"
    assert app.get("usesCleartextTraffic") in (False,"false"), "Cleartext permitido"
    permissions={a.get("name") for n,a in e if n=="uses-permission"}
    allowed={"android.permission.INTERNET","android.permission.ACCESS_NETWORK_STATE","android.permission.WAKE_LOCK","android.permission.MODIFY_AUDIO_SETTINGS","br.app.koma.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
    assert permissions<=allowed, f"Permissões inesperadas: {permissions-allowed}"
    assert "android.permission.INTERNET" in permissions
    for n,a in e:
        if n in ("activity","service","provider","receiver") and a.get("exported") in (True,"true"):
            assert a.get("name")=="br.app.koma.Reader" or a.get("permission")=="android.permission.DUMP", f"Componente exportado: {a}"
    print("Manifesto real aprovado: sem depuração, backup ou cleartext; permissões e componentes conferidos.")
if __name__=="__main__": verify(sys.argv[1])
