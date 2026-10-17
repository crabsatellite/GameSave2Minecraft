#version 150
#moj_import <light.glsl>
#moj_import <fog.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in vec3 Normal;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 PoseMats[16];
uniform mat3 NormalMats[16];
uniform mat4 LightData[4];
uniform int FogShape;
uniform int Emissive;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
uniform vec4 Wind;
out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out float trimSelector;
void main(){
    vec3 local=Position;
    float weight=clamp((local.y-Wind.y)/Wind.z,0.0,1.0)*Wind.x;
    float phase=Wind.w*0.09+local.x*1.7+local.z*1.3;
    local.x+=sin(phase)*0.018*weight;
    local.z+=cos(phase*0.83)*0.009*weight;
    vec4 posed=PoseMats[gl_InstanceID]*vec4(local,1.0);
    gl_Position=ProjMat*ModelViewMat*posed;
    vertexDistance=Emissive==1?length((ModelViewMat*posed).xyz):fog_distance(posed.xyz,FogShape);
    vec3 normal=NormalMats[gl_InstanceID]*Normal;
    normal/=max(length(normal),0.0000001);
    vertexColor=minecraft_mix_light(Light0_Direction,Light1_Direction,normal,Color);
    vec4 lighting=LightData[gl_InstanceID/4][gl_InstanceID%4];
    lightMapColor=texelFetch(Sampler2,ivec2(lighting.xy)/16,0);
    overlayColor=texelFetch(Sampler1,ivec2(lighting.zw),0);
    texCoord0=UV0;
    trimSelector=float(UV1.x)/256.0;
}
