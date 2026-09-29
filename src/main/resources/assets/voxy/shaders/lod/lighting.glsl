#ifndef _VOXY_LIGHTING_DECL
#define _VOXY_LIGHTING_DECL

vec2 getLightmapUv(uint index) {
    vec2 base = vec2((index >> 4) & 0xFu, index & 0xFu) / 15.0;
    return clamp(base * (15.0 / 16.0) + (0.5 / 16.0),
            vec2(8.0 / 256.0), vec2(248.0 / 256.0));
}

#ifdef LIGHTING_SAMPLER_BINDING

layout(binding = LIGHTING_SAMPLER_BINDING) uniform sampler2D lightSampler;

vec4 getLighting(uint index) {
    // Base level only - the lightmap's mip selection jitters at LOD range and flickers the blocks
    return textureLod(lightSampler, getLightmapUv(index), 0.0);
}
#endif

#endif
