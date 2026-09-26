import struct
data = bytearray(open("/mnt/c/Users/BN/AppData/Local/Temp/vtwork/ventoy.disk.img","rb").read())
bps=512; spc=1
resv = struct.unpack_from("<H", data, 14)[0]
fatsz = struct.unpack_from("<H", data, 22)[0]
root_off = (resv + 2*fatsz)*bps
data_start = root_off + 512*32*512//512
def fat_entry(n): return struct.unpack_from("<H", data, resv*bps + n*2)[0]
def chain(first):
    c = first; out=[]
    while 2 <= c < 0xFFF8: out.append(c); c = fat_entry(c)
    return out
def read_file(first, size):
    b = bytearray()
    for c in chain(first):
        off = data_start + (c-2)*spc*bps
        b += data[off:off+spc*bps]
    return bytes(b[:size])
def list_dir(first):
    b = b"".join(read_file(c,512) for c in chain(first))
    ents=[]
    for i in range(0, len(b), 32):
        e = b[i:i+32]
        if len(e)<32 or e[0] in (0x00,0xE5): continue
        if (e[11] & 0x3F) == 0x0F: continue
        name = e[0:8].decode("ascii","replace").rstrip(); ext=e[8:11].decode("ascii","replace").rstrip()
        clus = struct.unpack_from("<H", e, 26)[0]; sz = struct.unpack_from("<I", e, 28)[0]
        ents.append((name+("."+ext if ext else ""), clus, sz, e[11]))
    return ents
print("== /grub/themes ==")
for e in list_dir(11461): print(e)
