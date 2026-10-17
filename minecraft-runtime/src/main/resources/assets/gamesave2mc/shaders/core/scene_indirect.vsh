#version 430
#moj_import <light.glsl>
#moj_import <fog.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
struct InstanceData { mat4 Pose; mat4 NormalMatrix; vec4 Light; vec4 Wind; };
layout(std430,binding=0) readonly buffer InstanceStorage { InstanceData Instances[]; };
uniform int FogShape;
uniform int Emissive;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out float trimSelector;
void main(){
    InstanceData instance=Instances[UV2.x];
    vec4 Wind=instance.Wind;
    vec3 local=Position;
    float weight=clamp((local.y-Wind.y)/Wind.z,0.0,1.0)*Wind.x;
    float phase=Wind.w*0.09+local.x*1.7+local.z*1.3;
    local.x+=sin(phase)*0.018*weight;
    local.z+=cos(phase*0.83)*0.009*weight;
    vec4 posed=instance.Pose*vec4(local,1.0);
    gl_Position=ProjMat*ModelViewMat*posed;
    vertexDistance=Emissive==1?length((ModelViewMat*posed).xyz):fog_distance(posed.xyz,FogShape);
    vec3 normal=mat3(instance.NormalMatrix)*Normal;
    normal/=max(length(normal),0.0000001);
    vertexColor=minecraft_mix_light(Light0_Direction,Light1_Direction,normal,Color);
    vec4 lighting=instance.Light;
    lightMapColor=texelFetch(Sampler2,ivec2(lighting.xy)/16,0);
    overlayColor=texelFetch(Sampler1,ivec2(lighting.zw),0);
    texCoord0=UV0;
    trimSelector=float(UV1.x)/256.0;
}
