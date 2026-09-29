#version 460 core

layout(location = 0) in vec2 fUv;
layout(location = 1) in vec2 fLightUv;
layout(location = 2) in float fShade;
layout(location = 3) flat in uint fFace;
layout(location = 4) in vec4 fColor;
uniform vec4 uLaserColor;

#ifdef PATCHED_SHADER
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
    vec4 colour = vec4(uLaserColor.rgb, uLaserColor.a * fColor.a);
    if (colour.a <= 0.001) discard;
#ifdef PATCHED_SHADER
    voxy_emitFragment(VoxyFragmentParameters(
        colour, vec2(0.0), fUv, fFace, 0u, vec2(1.0), vec4(1.0), 0u));
#else
    outColour = colour;
#endif
}
