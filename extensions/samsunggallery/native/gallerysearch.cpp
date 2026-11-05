// Runs nomic-embed-vision-v1.5 (the image side of nomic-embed-text-v1.5) with ncnn for Samsung Gallery's "Search by
// content" (org.ungoogled.samsunggallery.search.Encoder): a 224x224 photo in, its 768 numbers out. Choosing the
// words that fit a photo is done in Java. The network is described in search/vision.param (made by
// native/search/convert.py); its weights are written on the phone from the file the user allowed to download.

#include <jni.h>
#include <android/log.h>

#include <mutex>

#include "cpu.h"
#include "net.h"

namespace {

const char* TAG = "UA";
const int SIDE = 224;
const int DIM = 768;

struct Encoder {
    ncnn::Net net;
    // The attention pooling's query (the model's "latent"), an input so that it is not folded into the weights.
    ncnn::Mat latent;
    std::mutex lock;
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_org_ungoogled_samsunggallery_search_Encoder_open(JNIEnv* env, jclass, jstring param,
        jstring model, jfloatArray latent, jint threads, jboolean halfPrecision)
{
    if (env->GetArrayLength(latent) != DIM) return 0;
    if (threads <= 0) threads = ncnn::get_physical_big_cpu_count();
    Encoder* encoder = new Encoder();
    encoder->net.opt.num_threads = threads;
    encoder->net.opt.lightmode = true;
    if (!halfPrecision) {
        // ARM code translated on an x86 device: half-precision math is emulated there, far slower than floats.
        encoder->net.opt.use_fp16_packed = false;
        encoder->net.opt.use_fp16_storage = false;
        encoder->net.opt.use_fp16_arithmetic = false;
        encoder->net.opt.use_bf16_storage = false;
    }
    const char* p = env->GetStringUTFChars(param, nullptr);
    const char* m = env->GetStringUTFChars(model, nullptr);
    bool ok = encoder->net.load_param(p) == 0 && encoder->net.load_model(m) == 0;
    env->ReleaseStringUTFChars(param, p);
    env->ReleaseStringUTFChars(model, m);
    if (!ok) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "search: the model did not load");
        delete encoder;
        return 0;
    }
    encoder->latent.create(DIM, 1);
    env->GetFloatArrayRegion(latent, 0, DIM, (float*) encoder->latent.data);
    return reinterpret_cast<jlong>(encoder);
}

JNIEXPORT void JNICALL Java_org_ungoogled_samsunggallery_search_Encoder_close(JNIEnv*, jclass, jlong handle)
{
    delete reinterpret_cast<Encoder*>(handle);
}

// The embedding (not normalized) of a 224x224 ARGB_8888 bitmap, or null.
JNIEXPORT jfloatArray JNICALL Java_org_ungoogled_samsunggallery_search_Encoder_embed(JNIEnv* env, jclass,
        jlong handle, jobject bitmap)
{
    Encoder* encoder = reinterpret_cast<Encoder*>(handle);
    if (!encoder) return nullptr;
    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGB);
    if (in.empty() || in.w != SIDE || in.h != SIDE) return nullptr;
    // CLIP's mean and deviation, RGB.
    const float mean[3] = {0.48145466f * 255.f, 0.4578275f * 255.f, 0.40821073f * 255.f};
    const float norm[3] = {1 / 0.26862954f / 255.f, 1 / 0.26130258f / 255.f, 1 / 0.27577711f / 255.f};
    in.substract_mean_normalize(mean, norm);

    ncnn::Mat out;
    {
        std::lock_guard<std::mutex> guard(encoder->lock);
        ncnn::Extractor ex = encoder->net.create_extractor();
        if (ex.input("in0", in) != 0 || ex.input("in1", encoder->latent) != 0 || ex.extract("out0", out) != 0) {
            return nullptr;
        }
    }
    if (out.total() != DIM) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "search: %d numbers out", (int) out.total());
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(DIM);
    env->SetFloatArrayRegion(result, 0, DIM, (const float*) out.data);
    return result;
}

} // extern "C"
