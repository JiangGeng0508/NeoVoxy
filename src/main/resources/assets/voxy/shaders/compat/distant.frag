#version 460 core

layout(binding = 0) uniform sampler2D uAtlas;
layout(binding = 1) uniform sampler2D uLightMap;

layout(location = 0) in vec2 fUv;
layout(location = 1) in vec2 fLightUv;
layout(location = 2) in float fShade;
layout(location = 3) flat in uint fFace;
layout(location = 4) in vec4 fColor;
layout(location = 5) flat in uint fCustomId;

#ifdef COPYCAT_OCCLUSION
layout(binding = 2) uniform sampler2D sourceTerrainDepth;
layout(location = 8) uniform mat4 sourceToLodClip;
layout(location = 12) uniform vec4 sourceDepthRemap;
layout(location = 13) uniform vec4 sourceViewport;
layout(location = 14) uniform int reverseDepth;

bool hiddenBySourceTerrain() {
    vec2 uv = gl_FragCoord.xy / sourceViewport.xy;
    float depth = texture(sourceTerrainDepth, uv * sourceViewport.zw).r;
    if (depth == (reverseDepth != 0 ? 0.0 : 1.0)) return false;
    vec4 clip = sourceToLodClip * vec4(uv * 2.0 - 1.0,
            depth * sourceDepthRemap.z + sourceDepthRemap.w, 1.0);
    if (clip.w <= 0.0) return false;
    float terrainDepth = (clip.z / clip.w) * sourceDepthRemap.x + sourceDepthRemap.y;
    float tolerance = 2.0 / 16777215.0;
    return reverseDepth != 0 ? gl_FragCoord.z < terrainDepth - tolerance
            : gl_FragCoord.z > terrainDepth + tolerance;
}
#endif

#ifdef PATCHED_SHADER
//Same contract as voxy's opaque LOD fragment shader: the shader-pack side (appended below by
//patchOpaqueShader) implements voxy_emitFragment and writes the full g-buffer.
struct VoxyFragmentParameters {
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;
};

void voxy_emitFragment(VoxyFragmentParameters parameters);
#else
layout(location = 0) out vec4 outColour;
#endif

void main() {
    #ifdef COPYCAT_OCCLUSION
    if (hiddenBySourceTerrain()) discard;
    #endif
    vec4 colour = texture(uAtlas, fUv);
    #ifdef TRANSLUCENT
    //LittleTiles' per-tile alpha is vertex material data, independent of the atlas texel alpha.
    //Fold it into sampledColour so both the plain path and shader-pack patches retain it.
    colour.a *= fColor.a;
    #endif
    if (colour.a < 0.1) {
        discard;
    }
    #ifdef PATCHED_SHADER
    #ifndef TRANSLUCENT
    colour.a = 1.0;
    #endif
    voxy_emitFragment(VoxyFragmentParameters(colour, vec2(0.0), fUv, fFace, 0u, fLightUv,
            vec4(fColor.rgb, 1.0), fCustomId));
    #else
    vec3 light = texture(uLightMap, fLightUv).rgb;
    #ifdef TRANSLUCENT
    outColour = vec4(colour.rgb * fColor.rgb * light * fShade, colour.a);
    #else
    outColour = vec4(colour.rgb * fColor.rgb * light * fShade, float(fFace & 7u) / 255.0);
    #endif
    #endif
}
