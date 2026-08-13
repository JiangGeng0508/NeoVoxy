#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable
#define VISIBILITY_ACCESS

#define SECTION_METADATA_BUFFER_BINDING 1
#define VISIBILITY_BUFFER_BINDING 2
#define INDIRECT_SECTION_LOOKUP_BINDING 3

#import <voxy:lod/section.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:util/depthutils.glsl>

flat out uint id;
flat out uint value;


#ifdef TAA
vec2 getTAA();
#endif

void main() {
    uint sid = indirectLookup[gl_InstanceID];

    SectionMeta section = sectionData[sid];

    uint detail = extractDetail(section);
    ivec3 ipos = extractPosition(section);
    ivec3 aabbOffset = extractAABBOffset(section);
    ivec3 size = extractAABBSize(section);

    //Transform ipos with respect to the vertex corner
    ivec3 pos = (((ipos<<detail)-baseSectionPos)<<5);

    //TODO maybe make the size expansion 0.5 (or maybe get rid of it all together?)
    const float EXPANSION = 1.0f;

    //World curvature drops the LOD mesh below this flat AABB, so expand the box downward by the
    // drop at its farthest corner. Without this the curved boundary sections are wrongly culled as
    // occluded by the flat vanilla chunks -> black holes in the void below them.
    float drop = 0.0f;
    if (uEarthRadius > 0.0f) {
        float s = float(1<<detail);
        vec2 minXZ = vec2(pos.xz) + (vec2(aabbOffset.xz) - EXPANSION) * s;
        vec2 maxXZ = vec2(pos.xz) + (vec2(aabbOffset.xz) + vec2(size.xz) + EXPANSION) * s;
        vec2 farXZ = mix(minXZ, maxXZ, greaterThan(abs(maxXZ), abs(minXZ)));
        float curveDist = length(farXZ) - uVanillaEnd;
        if (curveDist > 0.0f) {
            float radius = uEarthRadius + max(float(pos.y), 0.0f);
            float phi = curveDist / radius;
            drop = (cos(phi) - 1.0f) * radius;
        }
    }

    vec3 offset = aabbOffset-EXPANSION;
    offset += vec3(gl_VertexID&1, (gl_VertexID>>2)&1, (gl_VertexID>>1)&1)*(size+2*EXPANSION);
    if (((gl_VertexID>>2)&1) == 0) {
        offset.y += drop;
    }

    gl_Position = MVP * vec4(vec3(pos)+offset*(1<<detail),1);

    //Bring closer to camera
    gl_Position.z += (CLOSER_SIGN*0.000001f) * gl_Position.w;

    #ifdef TAA
    gl_Position.xy += getTAA()*gl_Position.w;//Apply TAA if we have it
    #endif

    //Write to the section id, to track temporal over time (litterally just need a single bit, 1 fking bit, but no)
    id = sid;

    //Me when data race condition between visibilityData in the vert shader and frag shader
    uint previous = visibilityData[sid]&0x7fffffffu;
    bool wasVisibleLastFrame = previous==(frameId-1);
    value = (frameId&0x7fffffffu)|(uint(wasVisibleLastFrame)<<31);//Encode if it was visible last frame
}


//Undefine depth stuff
#import <voxy:util/depthutils.glsl>
