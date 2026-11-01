#include "Cafe/HW/Latte/Renderer/RendererOuputShader.h"
#include "Cafe/HW/Latte/Renderer/OpenGL/OpenGLRenderer.h"
#include "config/ActiveSettings.h"

const std::string RendererOutputShader::s_copy_shader_source =
R"(
void outputShader()
{
	colorOut0 = vec4(texture(textureSrc, passUV).rgb,1.0);
}
)";

const std::string RendererOutputShader::s_copy_shader_source_mtl =
R"(#include <metal_stdlib>
using namespace metal;

struct VertexOut {
    float2 uv;
};

fragment float4 main0(VertexOut in [[stage_in]], texture2d<float> textureSrc [[texture(0)]], sampler samplr [[sampler(0)]]) {
	return float4(textureSrc.sample(samplr, in.uv).rgb, 1.0);
}
)";

const std::string RendererOutputShader::s_bicubic_shader_source =
R"(
vec4 cubic(float x)
{
	float x2 = x * x;
	float x3 = x2 * x;
	vec4 w;
	w.x = -x3 + 3 * x2 - 3 * x + 1;
	w.y = 3 * x3 - 6 * x2 + 4;
	w.z = -3 * x3 + 3 * x2 + 3 * x + 1;
	w.w = x3;
	return w / 6.0;
}

vec4 bcFilter(vec2 uv, vec4 texelSize)
{
	vec2 pixel = uv*texelSize.zw - 0.5;
	vec2 pixelFrac = fract(pixel);
	vec2 pixelInt = pixel - pixelFrac;

	vec4 xcubic = cubic(pixelFrac.x);
	vec4 ycubic = cubic(pixelFrac.y);

	vec4 c = vec4(pixelInt.x - 0.5, pixelInt.x + 1.5, pixelInt.y - 0.5, pixelInt.y + 1.5);
	vec4 s = vec4(xcubic.x + xcubic.y, xcubic.z + xcubic.w, ycubic.x + ycubic.y, ycubic.z + ycubic.w);
	vec4 offset = c + vec4(xcubic.y, xcubic.w, ycubic.y, ycubic.w) / s;

	vec4 sample0 = texture(textureSrc, vec2(offset.x, offset.z) * texelSize.xy);
	vec4 sample1 = texture(textureSrc, vec2(offset.y, offset.z) * texelSize.xy);
	vec4 sample2 = texture(textureSrc, vec2(offset.x, offset.w) * texelSize.xy);
	vec4 sample3 = texture(textureSrc, vec2(offset.y, offset.w) * texelSize.xy);

	float sx = s.x / (s.x + s.y);
	float sy = s.z / (s.z + s.w);

	return mix(
		mix(sample3, sample2, sx),
		mix(sample1, sample0, sx), sy);
}

void outputShader(){
	vec4 texelSize = vec4( 1.0 / textureSrcResolution.xy, textureSrcResolution.xy);
	colorOut0 = vec4(bcFilter(passUV, texelSize).rgb,1.0);
}
)";

const std::string RendererOutputShader::s_bicubic_shader_source_mtl =
R"(#include <metal_stdlib>
using namespace metal;

float4 cubic(float x) {
	float x2 = x * x;
	float x3 = x2 * x;
	float4 w;
	w.x = -x3 + 3 * x2 - 3 * x + 1;
	w.y = 3 * x3 - 6 * x2 + 4;
	w.z = -3 * x3 + 3 * x2 + 3 * x + 1;
	w.w = x3;
	return w / 6.0;
}

float4 bcFilter(texture2d<float> textureSrc, sampler samplr, float2 texcoord, float2 texscale) {
	float fx = fract(texcoord.x);
	float fy = fract(texcoord.y);
	texcoord.x -= fx;
	texcoord.y -= fy;

	float4 xcubic = cubic(fx);
	float4 ycubic = cubic(fy);

	float4 c = float4(texcoord.x - 0.5, texcoord.x + 1.5, texcoord.y - 0.5, texcoord.y + 1.5);
	float4 s = float4(xcubic.x + xcubic.y, xcubic.z + xcubic.w, ycubic.x + ycubic.y, ycubic.z + ycubic.w);
	float4 offset = c + float4(xcubic.y, xcubic.w, ycubic.y, ycubic.w) / s;

	float4 sample0 = textureSrc.sample(samplr, float2(offset.x, offset.z) * texscale);
	float4 sample1 = textureSrc.sample(samplr, float2(offset.y, offset.z) * texscale);
	float4 sample2 = textureSrc.sample(samplr, float2(offset.x, offset.w) * texscale);
	float4 sample3 = textureSrc.sample(samplr, float2(offset.y, offset.w) * texscale);

	float sx = s.x / (s.x + s.y);
	float sy = s.z / (s.z + s.w);

	return mix(
		mix(sample3, sample2, sx),
		mix(sample1, sample0, sx), sy);
}

struct VertexOut {
    float2 uv;
};

fragment float4 main0(VertexOut in [[stage_in]], texture2d<float> textureSrc [[texture(0)]], sampler samplr [[sampler(0)]]) {
    float2 textureSrcResolution = float2(textureSrc.get_width(), textureSrc.get_height());
	return float4(bcFilter(textureSrc, samplr, in.uv * textureSrcResolution, float2(1.0, 1.0) / textureSrcResolution).rgb, 1.0);
}
)";

const std::string RendererOutputShader::s_hermite_shader_source =
R"(
// https://www.shadertoy.com/view/MllSzX

vec3 CubicHermite (vec3 A, vec3 B, vec3 C, vec3 D, float t)
{
	float t2 = t*t;
    float t3 = t*t*t;
    vec3 a = -A/2.0 + (3.0*B)/2.0 - (3.0*C)/2.0 + D/2.0;
    vec3 b = A - (5.0*B)/2.0 + 2.0*C - D / 2.0;
    vec3 c = -A/2.0 + C/2.0;
   	vec3 d = B;

    return a*t3 + b*t2 + c*t + d;
}


vec3 BicubicHermiteTexture(vec2 uv, vec4 texelSize)
{
	vec2 pixel = uv*texelSize.zw + 0.5;
	vec2 frac = fract(pixel);
    pixel = floor(pixel) / texelSize.zw - vec2(texelSize.xy/2.0);

	vec4 doubleSize = texelSize*2.0;

	vec3 C00 = texture(textureSrc, pixel + vec2(-texelSize.x ,-texelSize.y)).rgb;
    vec3 C10 = texture(textureSrc, pixel + vec2( 0.0        ,-texelSize.y)).rgb;
    vec3 C20 = texture(textureSrc, pixel + vec2( texelSize.x ,-texelSize.y)).rgb;
    vec3 C30 = texture(textureSrc, pixel + vec2( doubleSize.x,-texelSize.y)).rgb;

    vec3 C01 = texture(textureSrc, pixel + vec2(-texelSize.x , 0.0)).rgb;
    vec3 C11 = texture(textureSrc, pixel + vec2( 0.0        , 0.0)).rgb;
    vec3 C21 = texture(textureSrc, pixel + vec2( texelSize.x , 0.0)).rgb;
    vec3 C31 = texture(textureSrc, pixel + vec2( doubleSize.x, 0.0)).rgb;

    vec3 C02 = texture(textureSrc, pixel + vec2(-texelSize.x , texelSize.y)).rgb;
    vec3 C12 = texture(textureSrc, pixel + vec2( 0.0        , texelSize.y)).rgb;
    vec3 C22 = texture(textureSrc, pixel + vec2( texelSize.x , texelSize.y)).rgb;
    vec3 C32 = texture(textureSrc, pixel + vec2( doubleSize.x, texelSize.y)).rgb;

    vec3 C03 = texture(textureSrc, pixel + vec2(-texelSize.x , doubleSize.y)).rgb;
    vec3 C13 = texture(textureSrc, pixel + vec2( 0.0        , doubleSize.y)).rgb;
    vec3 C23 = texture(textureSrc, pixel + vec2( texelSize.x , doubleSize.y)).rgb;
    vec3 C33 = texture(textureSrc, pixel + vec2( doubleSize.x, doubleSize.y)).rgb;

    vec3 CP0X = CubicHermite(C00, C10, C20, C30, frac.x);
    vec3 CP1X = CubicHermite(C01, C11, C21, C31, frac.x);
    vec3 CP2X = CubicHermite(C02, C12, C22, C32, frac.x);
    vec3 CP3X = CubicHermite(C03, C13, C23, C33, frac.x);

    return CubicHermite(CP0X, CP1X, CP2X, CP3X, frac.y);
}

void outputShader(){
	vec4 texelSize = vec4( 1.0 / textureSrcResolution.xy, textureSrcResolution.xy);
	colorOut0 = vec4(BicubicHermiteTexture(passUV, texelSize), 1.0);
}
)";

const std::string RendererOutputShader::s_hermite_shader_source_mtl =
R"(#include <metal_stdlib>
using namespace metal;

// https://www.shadertoy.com/view/MllSzX

float3 CubicHermite(float3 A, float3 B, float3 C, float3 D, float t) {
	float t2 = t*t;
    float t3 = t*t*t;
    float3 a = -A/2.0 + (3.0*B)/2.0 - (3.0*C)/2.0 + D/2.0;
    float3 b = A - (5.0*B)/2.0 + 2.0*C - D / 2.0;
    float3 c = -A/2.0 + C/2.0;
   	float3 d = B;

    return a*t3 + b*t2 + c*t + d;
}


float3 BicubicHermiteTexture(texture2d<float> textureSrc, sampler samplr, float2 uv, float4 texelSize) {
	float2 pixel = uv*texelSize.zw + 0.5;
	float2 frac = fract(pixel);
    pixel = floor(pixel) / texelSize.zw - float2(texelSize.xy/2.0);

	float4 doubleSize = texelSize*texelSize;

	float3 C00 = textureSrc.sample(samplr, pixel + float2(-texelSize.x ,-texelSize.y)).rgb;
    float3 C10 = textureSrc.sample(samplr, pixel + float2( 0.0        ,-texelSize.y)).rgb;
    float3 C20 = textureSrc.sample(samplr, pixel + float2( texelSize.x ,-texelSize.y)).rgb;
    float3 C30 = textureSrc.sample(samplr, pixel + float2( doubleSize.x,-texelSize.y)).rgb;

    float3 C01 = textureSrc.sample(samplr, pixel + float2(-texelSize.x , 0.0)).rgb;
    float3 C11 = textureSrc.sample(samplr, pixel + float2( 0.0        , 0.0)).rgb;
    float3 C21 = textureSrc.sample(samplr, pixel + float2( texelSize.x , 0.0)).rgb;
    float3 C31 = textureSrc.sample(samplr, pixel + float2( doubleSize.x, 0.0)).rgb;

    float3 C02 = textureSrc.sample(samplr, pixel + float2(-texelSize.x , texelSize.y)).rgb;
    float3 C12 = textureSrc.sample(samplr, pixel + float2( 0.0        , texelSize.y)).rgb;
    float3 C22 = textureSrc.sample(samplr, pixel + float2( texelSize.x , texelSize.y)).rgb;
    float3 C32 = textureSrc.sample(samplr, pixel + float2( doubleSize.x, texelSize.y)).rgb;

    float3 C03 = textureSrc.sample(samplr, pixel + float2(-texelSize.x , doubleSize.y)).rgb;
    float3 C13 = textureSrc.sample(samplr, pixel + float2( 0.0        , doubleSize.y)).rgb;
    float3 C23 = textureSrc.sample(samplr, pixel + float2( texelSize.x , doubleSize.y)).rgb;
    float3 C33 = textureSrc.sample(samplr, pixel + float2( doubleSize.x, doubleSize.y)).rgb;

    float3 CP0X = CubicHermite(C00, C10, C20, C30, frac.x);
    float3 CP1X = CubicHermite(C01, C11, C21, C31, frac.x);
    float3 CP2X = CubicHermite(C02, C12, C22, C32, frac.x);
    float3 CP3X = CubicHermite(C03, C13, C23, C33, frac.x);

    return CubicHermite(CP0X, CP1X, CP2X, CP3X, frac.y);
}

struct VertexOut {
    float4 position [[position]];
    float2 uv;
};

fragment float4 main0(VertexOut in [[stage_in]], texture2d<float> textureSrc [[texture(0)]], sampler samplr [[sampler(0)]], constant float2& outputResolution [[buffer(0)]]) {
	float4 texelSize = float4(1.0 / outputResolution.xy, outputResolution.xy);
	return float4(BicubicHermiteTexture(textureSrc, samplr, in.uv, texelSize), 1.0);
}
)";

// AMD FidelityFX Super Resolution 1.0, edge adaptive spatial upsampling (EASU) pass in a single fragment shader.
// Ported from ffx_fsr1.h, Copyright (c) 2021 Advanced Micro Devices, Inc. MIT license
const std::string RendererOutputShader::s_fsr_shader_source =
R"(
float FsrRcp(float a) { return uintBitsToFloat(uint(0x7ef07ebb) - floatBitsToUint(a)); }
float FsrRsq(float a) { return uintBitsToFloat(uint(0x5f347d74) - (floatBitsToUint(a) >> uint(1))); }

vec3 FsrLoad(vec2 texel, vec2 texelSize)
{
	return texture(textureSrc, (texel + 0.5) * texelSize).rgb;
}

float FsrLuma(vec3 c)
{
	return c.b * 0.5 + (c.r * 0.5 + c.g);
}

// accumulates the edge direction and length of one of the four bilinear corners
void FsrEasuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE)
{
	float dc = lD - lC;
	float cb = lC - lB;
	float lenX = max(abs(dc), abs(cb));
	lenX = FsrRcp(lenX);
	float dirX = lD - lB;
	dir.x += dirX * w;
	lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
	lenX *= lenX;
	len += lenX * w;

	float ec = lE - lC;
	float ca = lC - lA;
	float lenY = max(abs(ec), abs(ca));
	lenY = FsrRcp(lenY);
	float dirY = lE - lA;
	dir.y += dirY * w;
	lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
	lenY *= lenY;
	len += lenY * w;
}

void FsrEasuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c)
{
	vec2 v;
	v.x = (off.x * dir.x) + (off.y * dir.y);
	v.y = (off.x * (-dir.y)) + (off.y * dir.x);
	v *= len;
	float d2 = v.x * v.x + v.y * v.y;
	d2 = min(d2, clp);
	// approximation of lanczos2 without sin() or rcp() or sqrt() to get x
	float wB = 2.0 / 5.0 * d2 - 1.0;
	float wA = lob * d2 - 1.0;
	wB *= wB;
	wA *= wA;
	wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
	float w = wB * wA;
	aC += c * w;
	aW += w;
}

vec3 FsrEasu(vec2 uv)
{
	vec2 texelSize = 1.0 / textureSrcResolution;
	vec2 pp = uv * textureSrcResolution - 0.5;
	vec2 fp = floor(pp);
	pp -= fp;

	// 12 tap kernel
	//    b c
	//  e f g h
	//  i j k l
	//    n o
	vec3 b = FsrLoad(fp + vec2(0.0, -1.0), texelSize);
	vec3 c = FsrLoad(fp + vec2(1.0, -1.0), texelSize);
	vec3 e = FsrLoad(fp + vec2(-1.0, 0.0), texelSize);
	vec3 f = FsrLoad(fp + vec2(0.0, 0.0), texelSize);
	vec3 g = FsrLoad(fp + vec2(1.0, 0.0), texelSize);
	vec3 h = FsrLoad(fp + vec2(2.0, 0.0), texelSize);
	vec3 i = FsrLoad(fp + vec2(-1.0, 1.0), texelSize);
	vec3 j = FsrLoad(fp + vec2(0.0, 1.0), texelSize);
	vec3 k = FsrLoad(fp + vec2(1.0, 1.0), texelSize);
	vec3 l = FsrLoad(fp + vec2(2.0, 1.0), texelSize);
	vec3 n = FsrLoad(fp + vec2(0.0, 2.0), texelSize);
	vec3 o = FsrLoad(fp + vec2(1.0, 2.0), texelSize);

	float bL = FsrLuma(b);
	float cL = FsrLuma(c);
	float eL = FsrLuma(e);
	float fL = FsrLuma(f);
	float gL = FsrLuma(g);
	float hL = FsrLuma(h);
	float iL = FsrLuma(i);
	float jL = FsrLuma(j);
	float kL = FsrLuma(k);
	float lL = FsrLuma(l);
	float nL = FsrLuma(n);
	float oL = FsrLuma(o);

	vec2 dir = vec2(0.0);
	float len = 0.0;
	FsrEasuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
	FsrEasuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
	FsrEasuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
	FsrEasuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);

	// normalize the direction, defaulting to horizontal when there is no edge
	vec2 dir2 = dir * dir;
	float dirR = dir2.x + dir2.y;
	bool zro = dirR < (1.0 / 32768.0);
	dirR = FsrRsq(dirR);
	dirR = zro ? 1.0 : dirR;
	dir.x = zro ? 1.0 : dir.x;
	dir *= dirR;

	// shape the kernel: stretched along the edge and shrunk across it
	len = len * 0.5;
	len *= len;
	float stretch = (dir.x * dir.x + dir.y * dir.y) * FsrRcp(max(abs(dir.x), abs(dir.y)));
	vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
	float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
	float clp = FsrRcp(lob);

	vec3 aC = vec3(0.0);
	float aW = 0.0;
	FsrEasuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
	FsrEasuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
	FsrEasuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
	FsrEasuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
	FsrEasuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
	FsrEasuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
	FsrEasuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
	FsrEasuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
	FsrEasuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
	FsrEasuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
	FsrEasuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
	FsrEasuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);

	// deringing, clamp to the 2x2 neighborhood
	vec3 min4 = min(min(f, g), min(j, k));
	vec3 max4 = max(max(f, g), max(j, k));
	return min(max4, max(min4, aC / aW));
}

void outputShader()
{
	colorOut0 = vec4(FsrEasu(passUV), 1.0);
}
)";

// AMD FidelityFX Super Resolution 1.0, robust contrast adaptive sharpening (RCAS) pass.
// Runs on the EASU output at output resolution, like Eden does.
// Ported from ffx_fsr1.h, Copyright (c) 2021 Advanced Micro Devices, Inc. MIT license
const std::string RendererOutputShader::s_rcas_shader_source =
R"(
#define FSR_RCAS_LIMIT (0.25 - (1.0 / 16.0))

float FsrMedRcp(float a)
{
	float b = uintBitsToFloat(uint(0x7ef19fff) - floatBitsToUint(a));
	return b * (-b * a + 2.0);
}

float FsrMin3(float x, float y, float z) { return min(x, min(y, z)); }
float FsrMax3(float x, float y, float z) { return max(x, max(y, z)); }

void outputShader()
{
	// RCAS sharpness is in stops, 0 is the strongest. The slider maps 100% to 0 stops and goes towards Eden's 2 stops, 0% skips this pass
	float con = exp2(-(1.0 - upscaleSharpness) * 2.0);

	ivec2 sp = ivec2(passUV * textureSrcResolution);
	ivec2 maxP = ivec2(textureSrcResolution) - 1;
	//    b
	//  d e f
	//    h
	vec3 b = texelFetch(textureSrc, clamp(sp + ivec2(0, -1), ivec2(0), maxP), 0).rgb;
	vec3 d = texelFetch(textureSrc, clamp(sp + ivec2(-1, 0), ivec2(0), maxP), 0).rgb;
	vec3 e = texelFetch(textureSrc, clamp(sp, ivec2(0), maxP), 0).rgb;
	vec3 f = texelFetch(textureSrc, clamp(sp + ivec2(1, 0), ivec2(0), maxP), 0).rgb;
	vec3 h = texelFetch(textureSrc, clamp(sp + ivec2(0, 1), ivec2(0), maxP), 0).rgb;

	// min and max of ring
	vec3 mn4 = min(min(b, d), min(f, h));
	vec3 mx4 = max(max(b, d), max(f, h));
	// immediate constants for peak range
	vec2 peakC = vec2(1.0, -1.0 * 4.0);
	// limiters, these need to be high precision rcps
	vec3 hitMin = min(mn4, e) / (4.0 * mx4);
	vec3 hitMax = (peakC.x - max(mx4, e)) / (4.0 * mn4 + peakC.y);
	vec3 lobeRGB = max(-hitMin, hitMax);
	float lobe = max(-FSR_RCAS_LIMIT, min(FsrMax3(lobeRGB.r, lobeRGB.g, lobeRGB.b), 0.0)) * con;
	// resolve, which needs the medium precision rcp approximation to avoid visible tonality changes
	float rcpL = FsrMedRcp(4.0 * lobe + 1.0);
	colorOut0 = vec4((lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL, 1.0);
}
)";

// Snapdragon Game Super Resolution 1, mobile variant. Same shader Eden uses for its "Snapdragon GSR" filter.
// Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved. BSD-3-Clause license
const std::string RendererOutputShader::s_sgsr_shader_source =
R"(
const float SgsrEdgeThreshold = 8.0 / 255.0;

vec4 SgsrWeightY(vec4 dx, vec4 dy, vec4 std)
{
	vec4 x = ((dx * dx) + (dy * dy)) * 0.55 + std;
	return (x - 1.0) * (x - 4.0) * 3.8125; // approx. of (x - 1) * (x - 4)^3
}

void outputShader()
{
	vec2 scale = 1.0 / textureSrcResolution;
	float edgeSharpness = 1.0 + upscaleSharpness;
	vec4 color = textureLod(textureSrc, passUV, 0.0);
	// image coord
	vec2 icoord = passUV * textureSrcResolution + vec2(-0.5, 0.5);
	vec2 icoordPixel = floor(icoord);
	vec2 coord = icoordPixel * scale;
	vec2 pl = icoord - icoordPixel;
	// left: 0, right: 1, upDown: 2
	mat3x4 dg = mat3x4(
		textureGather(textureSrc, coord, 1),
		textureGather(textureSrc, coord + vec2(2.0 * scale.x, 0.0), 1),
		vec4(
			textureGather(textureSrc, coord + vec2(scale.x, -scale.y), 1).wz,
			textureGather(textureSrc, coord + vec2(scale.x, +scale.y), 1).yx
		)
	);
	float edgeVote = abs(dg[0].z - dg[0].y) + abs(color.y - dg[0].y) + abs(color.y - dg[0].z);
	if (edgeVote > SgsrEdgeThreshold)
	{
		float mean = (dg[0].y + dg[0].z + dg[1].x + dg[1].w) * 0.25;
		dg = dg - mat3x4(vec4(mean), vec4(mean), vec4(mean));
		vec4 sum = abs(dg[0]) + abs(dg[1]) + abs(dg[2]);
		float std = 2.181818 / (sum.x + sum.y + sum.z + sum.w);
		mat2x4 w = mat2x4(
			SgsrWeightY(
				pl.xxxx + vec4(+1.0, +0.0, +0.0, +1.0),
				pl.yyyy + vec4(-1.0, -1.0, +0.0, +0.0),
				clamp(abs(dg[0]) * std, 0.0, 1.0)
			) + SgsrWeightY(
				pl.xxxx + vec4(-1.0, -2.0, -2.0, -1.0),
				pl.yyyy + vec4(-1.0, -1.0, +0.0, +0.0),
				clamp(abs(dg[1]) * std, 0.0, 1.0)
			) + SgsrWeightY(
				pl.xxxx + vec4(+0.0, -1.0, -1.0, +0.0),
				pl.yyyy + vec4(+1.0, +1.0, -2.0, -2.0),
				clamp(abs(dg[2]) * std, 0.0, 1.0)
			),
			dg[0] + dg[1] + dg[2]
		);
		// compute final y with bounds
		vec2 yb = vec2(
			min(min(dg[0].y, dg[0].z), min(dg[1].x, dg[1].w)),
			max(max(dg[0].y, dg[0].z), max(dg[1].x, dg[1].w))
		);
		vec2 fvy = vec2(
			w[0].x + w[0].y + w[0].z + w[0].w,
			w[1].x + w[1].y + w[1].z + w[1].w
		);
		float fy = clamp((fvy.y / fvy.x) * edgeSharpness, yb[0], yb[1]);
		// smooth high contrast input
		float dy = clamp(fy - color.y + mean, -23.0 / 255.0, 23.0 / 255.0);
		color = clamp(color + dy, 0.0, 1.0);
	}
	colorOut0 = vec4(color.rgb, 1.0);
}
)";

// Snapdragon Game Super Resolution 1, edge direction variant. Same shader Eden uses for "Snapdragon GSR EdgeDir".
// Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved. BSD-3-Clause license
const std::string RendererOutputShader::s_sgsr_edge_shader_source =
R"(
const float SgsrEdgeThreshold = 8.0 / 255.0;

float SgsrFastLanczos2(float x)
{
	float wA = x - 4.0;
	float wB = x * wA - wA;
	wA *= wA;
	return wB * wA;
}

vec2 SgsrWeightY(float dx, float dy, float c, vec3 data)
{
	float std = data.x;
	vec2 dir = data.yz;
	float edgeDis = (dx * dir.y) + (dy * dir.x);
	float x = ((dx * dx) + (dy * dy)) + ((edgeDis * edgeDis) * ((clamp((c * c) * std, 0.0, 1.0) * 0.7) - 1.0));
	float w = SgsrFastLanczos2(x);
	return vec2(w, w * c);
}

vec2 SgsrEdgeDirection(vec4 left, vec4 right)
{
	float RxLz = right.x - left.z;
	float RwLy = right.w - left.y;
	vec2 delta = vec2(RxLz + RwLy, RxLz - RwLy);
	float lengthInv = inversesqrt((delta.x * delta.x + 3.075740e-05) + (delta.y * delta.y));
	return delta * lengthInv;
}

void outputShader()
{
	vec2 texelSize = 1.0 / textureSrcResolution;
	vec4 pix = vec4(texture(textureSrc, passUV).rgb, 0.0);

	// works on the green channel as an approximation of the luma
	vec2 imgCoord = passUV * textureSrcResolution + vec2(-0.5, 0.5);
	vec2 imgCoordPixel = floor(imgCoord);
	vec2 coord = imgCoordPixel * texelSize;
	vec2 pl = imgCoord - imgCoordPixel;
	vec4 left = textureGather(textureSrc, coord, 1);

	float edgeVote = abs(left.z - left.y) + abs(pix.g - left.y) + abs(pix.g - left.z);
	if (edgeVote > SgsrEdgeThreshold)
	{
		coord.x += texelSize.x;

		vec4 right = textureGather(textureSrc, coord + vec2(texelSize.x, 0.0), 1);
		vec4 upDown;
		upDown.xy = textureGather(textureSrc, coord + vec2(0.0, -texelSize.y), 1).wz;
		upDown.zw = textureGather(textureSrc, coord + vec2(0.0, texelSize.y), 1).yx;

		float mean = (left.y + left.z + right.x + right.w) * 0.25;
		left -= vec4(mean);
		right -= vec4(mean);
		upDown -= vec4(mean);
		pix.w = pix.g - mean;

		float sum = abs(left.x) + abs(left.y) + abs(left.z) + abs(left.w)
			+ abs(right.x) + abs(right.y) + abs(right.z) + abs(right.w)
			+ abs(upDown.x) + abs(upDown.y) + abs(upDown.z) + abs(upDown.w);
		float sumMean = 1.014185e+01 / sum;
		float std = sumMean * sumMean;

		vec3 data = vec3(std, SgsrEdgeDirection(left, right));

		vec2 aWY = SgsrWeightY(pl.x, pl.y + 1.0, upDown.x, data);
		aWY += SgsrWeightY(pl.x - 1.0, pl.y + 1.0, upDown.y, data);
		aWY += SgsrWeightY(pl.x - 1.0, pl.y - 2.0, upDown.z, data);
		aWY += SgsrWeightY(pl.x, pl.y - 2.0, upDown.w, data);
		aWY += SgsrWeightY(pl.x + 1.0, pl.y - 1.0, left.x, data);
		aWY += SgsrWeightY(pl.x, pl.y - 1.0, left.y, data);
		aWY += SgsrWeightY(pl.x, pl.y, left.z, data);
		aWY += SgsrWeightY(pl.x + 1.0, pl.y, left.w, data);
		aWY += SgsrWeightY(pl.x - 1.0, pl.y - 1.0, right.x, data);
		aWY += SgsrWeightY(pl.x - 2.0, pl.y - 1.0, right.y, data);
		aWY += SgsrWeightY(pl.x - 2.0, pl.y, right.z, data);
		aWY += SgsrWeightY(pl.x - 1.0, pl.y, right.w, data);

		float finalY = aWY.y / aWY.x;
		float maxY = max(max(left.y, left.z), max(right.x, right.w));
		float minY = min(min(left.y, left.z), min(right.x, right.w));
		finalY = clamp((1.0 + upscaleSharpness) * finalY, minY, maxY);

		// smooth high contrast input
		float deltaY = clamp(finalY - pix.w, -23.0 / 255.0, 23.0 / 255.0);
		pix.rgb = clamp(pix.rgb + deltaY, 0.0, 1.0);
	}
	colorOut0 = vec4(pix.rgb, 1.0);
}
)";

RendererOutputShader::RendererOutputShader(const std::string& vertex_source, const std::string& fragment_source)
{
    std::string finalFragmentSrc;
    if (g_renderer->GetType() == RendererAPI::Metal)
        finalFragmentSrc = fragment_source;
    else
        finalFragmentSrc = PrependFragmentPreamble(fragment_source);

	m_vertex_shader.reset(g_renderer->shader_create(RendererShader::ShaderType::kVertex, 0, 0, vertex_source, false, false));
	m_fragment_shader.reset(g_renderer->shader_create(RendererShader::ShaderType::kFragment, 0, 0, finalFragmentSrc, false, false));

	m_vertex_shader->PreponeCompilation(true);
	m_fragment_shader->PreponeCompilation(true);

	if (!m_vertex_shader->WaitForCompiled())
		throw std::exception();

	if(!m_fragment_shader->WaitForCompiled())
		throw std::exception();

}

RendererOutputShader::OutputUniformVariables RendererOutputShader::FillUniformBlockBuffer(const LatteTextureView& texture_view, const Vector2i& output_res, const bool padView) const
{
	OutputUniformVariables vars;

	sint32 effectiveWidth, effectiveHeight;
	texture_view.baseTexture->GetEffectiveSize(effectiveWidth, effectiveHeight, 0);
	vars.textureSrcResolution = {(float)effectiveWidth, (float)effectiveHeight};

	vars.nativeResolution = {(float)texture_view.baseTexture->width, (float)texture_view.baseTexture->height};
	vars.outputResolution = output_res;

	vars.applySRGBEncoding = padView ? LatteGPUState.drcBufferUsesSRGB : LatteGPUState.tvBufferUsesSRGB;
	vars.targetGamma = padView ? ActiveSettings::GetDRCGamma() : ActiveSettings::GetTVGamma();
	vars.displayGamma = GetConfig().userDisplayGamma;
	vars.sharpness = (float)GetConfig().upscale_sharpness / 100.0f;

	return vars;
}

RendererOutputShader* RendererOutputShader::s_copy_shader;
RendererOutputShader* RendererOutputShader::s_copy_shader_ud;

RendererOutputShader* RendererOutputShader::s_bicubic_shader;
RendererOutputShader* RendererOutputShader::s_bicubic_shader_ud;

RendererOutputShader* RendererOutputShader::s_hermit_shader;
RendererOutputShader* RendererOutputShader::s_hermit_shader_ud;

RendererOutputShader* RendererOutputShader::s_fsr_shader;
RendererOutputShader* RendererOutputShader::s_fsr_shader_ud;

RendererOutputShader* RendererOutputShader::s_sgsr_shader;
RendererOutputShader* RendererOutputShader::s_sgsr_shader_ud;

RendererOutputShader* RendererOutputShader::s_sgsr_edge_shader;
RendererOutputShader* RendererOutputShader::s_sgsr_edge_shader_ud;

RendererOutputShader* RendererOutputShader::s_rcas_shader;
RendererOutputShader* RendererOutputShader::s_rcas_shader_ud;

std::string RendererOutputShader::GetOpenGlVertexSource(bool render_upside_down)
{
	// vertex shader
	std::ostringstream vertex_source;
		vertex_source <<
			R"(#version 420
layout(location = 0) smooth out vec2 passUV;

out gl_PerVertex
{
   vec4 gl_Position;
};

void main(){
	vec2 vPos;
	vec2 vUV;
	int vID = gl_VertexID;
)";

		if (render_upside_down)
		{
			vertex_source <<
				R"(	if( vID == 0 ) { vPos = vec2(1.0,1.0); vUV = vec2(1.0,0.0); }
	else if( vID == 1 ) { vPos = vec2(-1.0,1.0); vUV = vec2(0.0,0.0); }
	else if( vID == 2 ) { vPos = vec2(-1.0,-1.0); vUV = vec2(0.0,1.0); }
	else if( vID == 3 ) { vPos = vec2(-1.0,-1.0); vUV = vec2(0.0,1.0); }
	else if( vID == 4 ) { vPos = vec2(1.0,-1.0); vUV = vec2(1.0,1.0); }
	else if( vID == 5 ) { vPos = vec2(1.0,1.0); vUV = vec2(1.0,0.0); }
	)";
		}
		else
		{
			vertex_source <<
				R"(	if( vID == 0 ) { vPos = vec2(1.0,1.0); vUV = vec2(1.0,1.0); }
	else if( vID == 1 ) { vPos = vec2(-1.0,1.0); vUV = vec2(0.0,1.0); }
	else if( vID == 2 ) { vPos = vec2(-1.0,-1.0); vUV = vec2(0.0,0.0); }
	else if( vID == 3 ) { vPos = vec2(-1.0,-1.0); vUV = vec2(0.0,0.0); }
	else if( vID == 4 ) { vPos = vec2(1.0,-1.0); vUV = vec2(1.0,0.0); }
	else if( vID == 5 ) { vPos = vec2(1.0,1.0); vUV = vec2(1.0,1.0); }
	)";
		}

		vertex_source <<
			R"(	passUV = vUV;
	gl_Position = vec4(vPos, 0.0, 1.0);
}
)";
		return vertex_source.str();
}

std::string RendererOutputShader::GetVulkanVertexSource(bool render_upside_down)
{
	// vertex shader
	std::ostringstream vertex_source;
		vertex_source <<
			R"(#version 450
layout(location = 0) out vec2 passUV;

out gl_PerVertex
{
   vec4 gl_Position;
};

void main(){
	vec2 vPos;
	vec2 vUV;
	int vID = gl_VertexIndex;
)";

		if (render_upside_down)
		{
			vertex_source <<
				R"(	if( vID == 0 ) { vPos = vec2(1.0,1.0); vUV = vec2(1.0,0.0); }
	else if( vID == 1 ) { vPos = vec2(-1.0,1.0); vUV = vec2(0.0,0.0); }
	else if( vID == 2 ) { vPos = vec2(-1.0,-1.0); vUV = vec2(0.0,1.0); }
	else if( vID == 3 ) { vPos = vec2(-1.0,-1.0); vUV = vec2(0.0,1.0); }
	else if( vID == 4 ) { vPos = vec2(1.0,-1.0); vUV = vec2(1.0,1.0); }
	else if( vID == 5 ) { vPos = vec2(1.0,1.0); vUV = vec2(1.0,0.0); }
	)";
		}
		else
		{
			vertex_source <<
				R"(	if( vID == 0 ) { vPos = vec2(1.0,1.0); vUV = vec2(1.0,1.0); }
	else if( vID == 1 ) { vPos = vec2(-1.0,1.0); vUV = vec2(0.0,1.0); }
	else if( vID == 2 ) { vPos = vec2(-1.0,-1.0); vUV = vec2(0.0,0.0); }
	else if( vID == 3 ) { vPos = vec2(-1.0,-1.0); vUV = vec2(0.0,0.0); }
	else if( vID == 4 ) { vPos = vec2(1.0,-1.0); vUV = vec2(1.0,0.0); }
	else if( vID == 5 ) { vPos = vec2(1.0,1.0); vUV = vec2(1.0,1.0); }
	)";
		}

		vertex_source <<
			R"(	passUV = vUV;
	gl_Position = vec4(vPos, 0.0, 1.0);
}
)";
		return vertex_source.str();
}

std::string RendererOutputShader::GetMetalVertexSource(bool render_upside_down)
{
	// vertex shader
	std::ostringstream vertex_source;
		vertex_source <<
			R"(#include <metal_stdlib>
using namespace metal;

struct VertexOut {
    float4 position [[position]];
    float2 uv;
};

vertex VertexOut main0(ushort vid [[vertex_id]]) {
	VertexOut out;
	float2 pos;
	if (vid == 0) pos = float2(-1.0, -3.0);
	else if (vid == 1) pos = float2(-1.0, 1.0);
	else if (vid == 2) pos = float2(3.0, 1.0);
	out.uv = pos * 0.5 + 0.5;
	out.uv.y = 1.0 - out.uv.y;
)";

		if (render_upside_down)
		{
			vertex_source <<
				R"(	pos.y = -pos.y;
	)";
		}

		vertex_source <<
			R"(	out.position = float4(pos, 0.0, 1.0);
	return out;
}
)";
		return vertex_source.str();
}

std::string RendererOutputShader::PrependFragmentPreamble(const std::string& shaderSrc)
{
	return R"(#version 430
layout(location = 0) smooth in vec2 passUV;
layout(binding = 0) uniform sampler2D textureSrc;
layout(location = 0) out vec4 colorOut0;

#ifdef VULKAN
layout (binding = 1, std140)
#else
layout (binding = 0, std140)
#endif
uniform parameters {
uniform vec2 textureSrcResolution;
uniform vec2 nativeResolution;
uniform vec2 outputResolution;
uniform bool applySRGBEncoding;
uniform float targetGamma;
uniform float displayGamma;
uniform float upscaleSharpness;
};

float sRGBEncode(float linear)
{
	if(linear <= 0.0031308)
		return 12.92f * linear;
	else
		return 1.055f * pow(linear, 1.0f / 2.4f) - 0.055f;

}

vec3 sRGBEncode(vec3 linear)
{
	return vec3(sRGBEncode(linear.r), sRGBEncode(linear.g), sRGBEncode(linear.b));
}

// fwd. declaration
void outputShader();
void main()
{
	outputShader(); // sets colorOut0
	if(applySRGBEncoding)
		colorOut0 = vec4(sRGBEncode(colorOut0.rgb), 1.0f);

	if (displayGamma > 0.0f)
		colorOut0 = pow(colorOut0, vec4(targetGamma / displayGamma) );
	else
		colorOut0 = vec4( sRGBEncode( pow(colorOut0.rgb, vec3(targetGamma)) ), 1.0f);

}

)" + shaderSrc;
}
void RendererOutputShader::InitializeStatic()
{
    if (g_renderer->GetType() == RendererAPI::Metal)
    {
        std::string vertex_source = GetMetalVertexSource(false);
        std::string vertex_source_ud = GetMetalVertexSource(true);

       	s_copy_shader = new RendererOutputShader(vertex_source, s_copy_shader_source_mtl);
       	s_copy_shader_ud = new RendererOutputShader(vertex_source_ud, s_copy_shader_source_mtl);

       	s_bicubic_shader = new RendererOutputShader(vertex_source, s_bicubic_shader_source_mtl);
       	s_bicubic_shader_ud = new RendererOutputShader(vertex_source_ud, s_bicubic_shader_source_mtl);

       	s_hermit_shader = new RendererOutputShader(vertex_source, s_hermite_shader_source_mtl);
       	s_hermit_shader_ud = new RendererOutputShader(vertex_source_ud, s_hermite_shader_source_mtl);
    }
    else
    {
    	std::string vertex_source, vertex_source_ud;
    	// vertex shader
    	if (g_renderer->GetType() == RendererAPI::OpenGL)
    	{
    		vertex_source = GetOpenGlVertexSource(false);
    		vertex_source_ud = GetOpenGlVertexSource(true);
    	}
    	else if (g_renderer->GetType() == RendererAPI::Vulkan)
    	{
    		vertex_source = GetVulkanVertexSource(false);
    		vertex_source_ud = GetVulkanVertexSource(true);
    	}
    	s_copy_shader = new RendererOutputShader(vertex_source, s_copy_shader_source);
    	s_copy_shader_ud = new RendererOutputShader(vertex_source_ud, s_copy_shader_source);

    	s_bicubic_shader = new RendererOutputShader(vertex_source, s_bicubic_shader_source);
    	s_bicubic_shader_ud = new RendererOutputShader(vertex_source_ud, s_bicubic_shader_source);

    	s_hermit_shader = new RendererOutputShader(vertex_source, s_hermite_shader_source);
    	s_hermit_shader_ud = new RendererOutputShader(vertex_source_ud, s_hermite_shader_source);

    	// no Metal versions, the filter selection falls back to Hermite there
    	s_fsr_shader = new RendererOutputShader(vertex_source, s_fsr_shader_source);
    	s_fsr_shader_ud = new RendererOutputShader(vertex_source_ud, s_fsr_shader_source);

    	s_sgsr_shader = new RendererOutputShader(vertex_source, s_sgsr_shader_source);
    	s_sgsr_shader_ud = new RendererOutputShader(vertex_source_ud, s_sgsr_shader_source);

    	s_sgsr_edge_shader = new RendererOutputShader(vertex_source, s_sgsr_edge_shader_source);
    	s_sgsr_edge_shader_ud = new RendererOutputShader(vertex_source_ud, s_sgsr_edge_shader_source);

    	s_rcas_shader = new RendererOutputShader(vertex_source, s_rcas_shader_source);
    	s_rcas_shader_ud = new RendererOutputShader(vertex_source_ud, s_rcas_shader_source);
    }
}

void RendererOutputShader::ShutdownStatic()
{
	delete s_copy_shader;
	delete s_copy_shader_ud;

	delete s_bicubic_shader;
	delete s_bicubic_shader_ud;

	delete s_hermit_shader;
	delete s_hermit_shader_ud;

	delete s_fsr_shader;
	delete s_fsr_shader_ud;
	s_fsr_shader = s_fsr_shader_ud = nullptr;

	delete s_sgsr_shader;
	delete s_sgsr_shader_ud;
	s_sgsr_shader = s_sgsr_shader_ud = nullptr;

	delete s_sgsr_edge_shader;
	delete s_sgsr_edge_shader_ud;
	s_sgsr_edge_shader = s_sgsr_edge_shader_ud = nullptr;

	delete s_rcas_shader;
	delete s_rcas_shader_ud;
	s_rcas_shader = s_rcas_shader_ud = nullptr;
}
