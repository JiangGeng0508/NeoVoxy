#version 460 core

layout(binding = 0) uniform sampler2D uAtlas;
layout(binding = 2) uniform sampler2D uLodDepth;
layout(location = 12) uniform vec2 uDepthRemap;
layout(location = 13) uniform int uReverseDepth;
layout(location = 0) in vec2 fUv;
layout(location = 6) in vec4 fLodClip;

void main() {
    if (texture(uAtlas, fUv).a < 0.1) {
        discard;
    }
    // 被 LOD 遮挡的列车不能写入光影深度，避免后处理产生轮廓。
    if (fLodClip.w <= 0.0) discard;
    vec3 ndc = fLodClip.xyz / fLodClip.w;
    vec2 uv = ndc.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) discard;
    ivec2 pixel = ivec2(uv * vec2(textureSize(uLodDepth, 0)));
    float terrainDepth = texelFetch(uLodDepth, pixel, 0).r;
    float trainDepth = ndc.z * uDepthRemap.x + uDepthRemap.y;
    float tolerance = 2.0 / 16777215.0;
    if (uReverseDepth != 0 ? trainDepth < terrainDepth - tolerance
            : trainDepth > terrainDepth + tolerance) discard;
}
