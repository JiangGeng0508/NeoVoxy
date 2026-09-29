
float getFragDistance(int fogShape, vec3 position) {
    // Minecraft's FogShape enum uses 0 for SPHERE and 1 for CYLINDER.
    // Sphere fog uses full 3D distance; cylindrical fog uses horizontal
    // distance clamped against vertical distance.
    if (fogShape == 0) {
        return length(position);
    }
    return max(length(position.xz), abs(position.y));
}
