// VOXY_BUILTIN_LITE Eclipse Shader 482 opaque
void voxy_emitFragment(VoxyFragmentParameters parameters) {
    int blockID = int(parameters.customId);
    vec4 Albedo = parameters.sampledColour * vec4(parameters.tinting.rgb, 1.0);
    Albedo.a = 1.0;

    if (blockID == 55 || blockID == 12 || blockID == 13 || blockID == 14) {
        Albedo.a = 0.60;
    } else if (blockID == 56) {
        Albedo.a = 0.55;
    }

    vec3 normal = vec3(
        uint((parameters.face >> 1u) == 2u),
        uint((parameters.face >> 1u) == 0u),
        uint((parameters.face >> 1u) == 1u)
    ) * (float(int(parameters.face) & 1) * 2.0 - 1.0);
    if (normal.z <= -0.9) normal.xy = vec2(-1e-13);

    vec2 lightmap = clamp(parameters.lightMap / (30.0 / 32.0) - (1.0 / 32.0), 0.0, 1.0);
    vec2 encodedNormal = encodeNormal(normal);

    float sssAmount = 0.0;
    if (blockID == 56 || blockID == 79 || blockID == 80 || blockID == 87) {
        sssAmount = 1.0;
    } else if (blockID == 11 || (blockID >= 12 && blockID <= 15)
            || blockID == 21 || blockID == 54 || blockID == 55
            || blockID == 81 || blockID == 82 || blockID == 84
            || blockID == 85 || blockID == 89
            || (blockID >= 101 && blockID <= 103) || blockID == 195
            || blockID == 282 || blockID == 401 || blockID == 409) {
        sssAmount = 0.5;
    }

    float emission = 0.0;
    if (blockID >= 100 && blockID < 282) emission = 0.5;
    if (blockID == 195) emission = 0.95;
    if (blockID == 185) emission = 0.85;
    if (blockID == 244) emission = 0.75;
    if (blockID == 502) emission = 0.75;

    Albedo = clamp(Albedo, 0.0, 1.0);
    gbuffer_data_0 = vec4(
        encodeVec2(Albedo.r, encodedNormal.x),
        encodeVec2(Albedo.g, encodedNormal.y),
        encodeVec2(Albedo.b, lightmap.x),
        encodeVec2(lightmap.y, Albedo.a)
    );

    vec3 flatNormal = normal * 0.5 + 0.5;
    gbuffer_data_1 = vec4(
        encodeVec2(0.0, flatNormal.x),
        encodeVec2(0.0, flatNormal.y),
        encodeVec2(sssAmount, flatNormal.z),
        encodeVec2(clamp(emission, 0.0, 0.99), 0.0)
    );
}
