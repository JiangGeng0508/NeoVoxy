#ifndef _POS_UTIL_DECL
#define _POS_UTIL_DECL

uint getLoDLevel(uvec2 packedPos) {
    return packedPos.x>>28;
}

ivec3 getLoDPosition(uvec2 packedPos) {
    if ((packedPos.y&1u) != 0u) {
        int y = (int(packedPos.x<<4)>>20);
        int x = (int(packedPos.y<<8)>>9);
        int z = int(((packedPos.x&0xFFFFu)<<8)|(packedPos.y>>24));
        z = (z<<8)>>8;
        return ivec3(x,y,z);
    }
    int y = ((int(packedPos.x)<<4)>>24);
    int x = (int(packedPos.y)<<4)>>8;
    int z = int((packedPos.x&((1u<<20)-1))<<4);
    z |= int(packedPos.y>>28);
    z <<= 8;
    z >>= 8;
    return ivec3(x,y,z);
}

#endif
