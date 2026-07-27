#version 450
layout(location=0) in vec2 iP;
layout(location=1) in vec2 iU;
layout(location=2) in vec4 iC;
layout(binding=0) uniform UBO{mat4 proj;}ubo;
layout(push_constant) uniform PC{int ti; int z;}pc;
layout(location=0) out vec2 oU;
layout(location=1) out vec4 oC;
void main(){
  gl_Position=ubo.proj*vec4(iP,pc.z*0.001,1);
  oU=iU;
  oC=iC;
}
