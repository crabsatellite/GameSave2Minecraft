#version 430
#moj_import <fog.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;
uniform sampler2D Sampler7;
uniform sampler2D Sampler8;
uniform sampler2D Sampler9;
uniform int TrimCount;
uniform int Emissive;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in float trimSelector;
out vec4 fragColor;
void main(){
    vec4 color=texture(Sampler0,texCoord0);
    int layer=int(floor(trimSelector+0.5));
    if(TrimCount>1){
        if(layer==2)color=texture(Sampler3,texCoord0);
        else if(layer==3)color=texture(Sampler4,texCoord0);
        else if(layer==4)color=texture(Sampler5,texCoord0);
        else if(layer==5)color=texture(Sampler6,texCoord0);
        else if(layer==6)color=texture(Sampler7,texCoord0);
        else if(layer==7)color=texture(Sampler8,texCoord0);
        else if(layer==8)color=texture(Sampler9,texCoord0);
    }
    if(color.a<0.1)discard;
    color*=vertexColor*ColorModulator;
    color.rgb=mix(overlayColor.rgb,color.rgb,overlayColor.a);
    if(Emissive==1)fragColor=color*linear_fog_fade(vertexDistance,FogStart,FogEnd);
    else{
        color*=lightMapColor;
        fragColor=linear_fog(color,vertexDistance,FogStart,FogEnd,FogColor);
    }
}
