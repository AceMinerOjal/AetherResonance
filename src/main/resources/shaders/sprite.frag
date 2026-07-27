#version 450
layout(location=0) in vec2 oU;
layout(location=1) in vec4 oC;
layout(binding=1) uniform sampler2D ts[64];
layout(push_constant) uniform PC{int ti; int z;}pc;
layout(location=0) out vec4 fC;
void main(){
  fC=pc.ti<0?oC:texture(ts[pc.ti],oU)*oC;
}
