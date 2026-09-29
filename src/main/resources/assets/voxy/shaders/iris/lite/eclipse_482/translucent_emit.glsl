// VOXY_BUILTIN_LITE Eclipse Shader 482 translucent
void voxy_emitFragment(VoxyFragmentParameters parameters) {
if (gl_FragCoord.x * texelSize.x < 1.0 && gl_FragCoord.y * texelSize.y < 1.0) {
    vec3 viewPos = DH_toScreenSpace(gl_FragCoord.xyz * vec3(texelSize, 1.0));
    int blockID = int(parameters.customId);
    bool isWater = blockID == 8;

    vec4 sampled = parameters.sampledColour * parameters.tinting;
    vec3 Albedo = toLinear(sampled.rgb);
    float unchangedAlpha = sampled.a;
    float outputAlpha = unchangedAlpha;

    #ifndef WhiteWorld
        #ifdef VANILLA_LIKE_WATER
            if (isWater) Albedo *= luma(Albedo);
        #else
            if (isWater) {
                Albedo = vec3(0.0);
                outputAlpha = 1.0 / 255.0;
            }
        #endif
    #endif

    vec3 normal = vec3(
        uint((parameters.face >> 1u) == 2u),
        uint((parameters.face >> 1u) == 0u),
        uint((parameters.face >> 1u) == 1u)
    ) * (float(int(parameters.face) & 1) * 2.0 - 1.0);
    if (normal.z <= -0.9) normal.xy = vec2(-1e-13);

    vec2 lightmap = clamp(parameters.lightMap, 0.0, 1.0);
    vec3 ambientLight = vec3(0.025);
    vec3 directLight = vec3(0.0);

    /* VOXY_LITE_DIMENSION_LIGHT */

    vec3 blockLight = doBlockLightLighting(
            vec3(1.0, 0.5, 0.25),
            lightmap.x * 0.8,
            vec3(0.0),
            vec3(0.0)
    );
    float outdoors = clamp((lightmap.y - 0.5) / 0.4, 0.0, 1.0);
    vec3 finalColor = (ambientLight + directLight * outdoors + blockLight) * Albedo;

    if (isWater) {
        vec3 viewNormal = normalize(worldToView(normal));
        float fresnel = pow(clamp(1.0 + dot(viewNormal, normalize(viewPos)), 0.0, 1.0), 5.0);
        fresnel = mix(0.02, 1.0, fresnel);
        /* VOXY_LITE_SKY_REFLECTION */
        finalColor = mix(finalColor, skyReflection, fresnel);
        outputAlpha += (1.0 - outputAlpha) * fresnel;
    }

    outputAlpha = clamp(outputAlpha, 0.0, 1.0);
    gbuffer_data_0 = vec4(
        clamp((finalColor / max(outputAlpha, 1.0 / 255.0)) * 0.1, 0.0, 65000.0),
        outputAlpha
    );
    gbuffer_data_1 = vec4(Albedo, isWater ? 1.0 : 0.7);

    vec4 tintData = vec4(Albedo, unchangedAlpha);
    #ifdef BIOME_TINT_WATER
        if (isWater) tintData.rgb = toLinear(parameters.tinting.rgb);
    #endif
    gbuffer_data_2 = vec4(0.0, encodeVec2(tintData.rg), encodeVec2(tintData.ba), 0.5);
    gbuffer_data_3 = vec4(1.0, 1.0, encodeVec2(lightmap), 1.0);
}
}
