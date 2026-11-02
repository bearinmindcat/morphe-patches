// Runs the two PP-OCRv5 mobile networks (text detection and text recognition) with ncnn for Samsung Gallery's
// "Extract text" (org.ungoogled.samsunggallery.ocr.Ncnn). Everything around the networks -- resizing, finding the
// text lines in the detection map, cropping them, reading the characters -- is done in Java.
//
// Build: native/build.sh (links the ncnn release statically; the result goes into the patches as a resource).

#include <jni.h>
#include <android/log.h>

#include <mutex>

#include "cpu.h"
#include "net.h"

namespace {

const char* TAG = "UA";

struct Engine {
    ncnn::Net det;
    ncnn::Net rec;
    std::mutex lock;
};

bool load(ncnn::Net& net, JNIEnv* env, jstring param, jstring model, int threads)
{
    net.opt.num_threads = threads;
    const char* p = env->GetStringUTFChars(param, nullptr);
    const char* m = env->GetStringUTFChars(model, nullptr);
    bool ok = net.load_param(p) == 0 && net.load_model(m) == 0;
    env->ReleaseStringUTFChars(param, p);
    env->ReleaseStringUTFChars(model, m);
    return ok;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_org_ungoogled_samsunggallery_ocr_Ncnn_open(JNIEnv* env, jclass, jstring detParam,
        jstring detModel, jstring recParam, jstring recModel, jint threads)
{
    if (threads <= 0) threads = ncnn::get_physical_big_cpu_count();
    Engine* engine = new Engine();
    if (!load(engine->det, env, detParam, detModel, threads) || !load(engine->rec, env, recParam, recModel, threads)) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "ocr: models did not load");
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL Java_org_ungoogled_samsunggallery_ocr_Ncnn_close(JNIEnv*, jclass, jlong handle)
{
    delete reinterpret_cast<Engine*>(handle);
}

// Text probability per pixel (0..1) for an ARGB_8888 bitmap whose sides are multiples of 32, row by row.
JNIEXPORT jfloatArray JNICALL Java_org_ungoogled_samsunggallery_ocr_Ncnn_detect(JNIEnv* env, jclass, jlong handle,
        jobject bitmap)
{
    Engine* engine = reinterpret_cast<Engine*>(handle);
    if (!engine) return nullptr;
    // PP-OCR feeds BGR with ImageNet's mean and deviation, in that channel order.
    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_BGR);
    if (in.empty()) return nullptr;
    const float mean[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
    const float norm[3] = {1 / 0.229f / 255.f, 1 / 0.224f / 255.f, 1 / 0.225f / 255.f};
    in.substract_mean_normalize(mean, norm);

    ncnn::Mat out;
    {
        std::lock_guard<std::mutex> guard(engine->lock);
        ncnn::Extractor ex = engine->det.create_extractor();
        if (ex.input("in0", in) != 0 || ex.extract("out0", out) != 0) return nullptr;
    }
    if (out.w != in.w || out.h != in.h) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "ocr: detection map %dx%d for %dx%d", out.w, out.h, in.w, in.h);
        return nullptr;
    }
    const int size = out.w * out.h;
    jfloatArray result = env->NewFloatArray(size);
    if (!result) return nullptr;
    const ncnn::Mat map = out.channel(0);
    env->SetFloatArrayRegion(result, 0, size, static_cast<const float*>(map.data));
    return result;
}

// Reads one text line (an ARGB_8888 bitmap 48 pixels high): for every step of the recognizer's output (one per 8
// pixels of width) the most likely class and its probability, as {class, probability, class, probability, ...}.
// Class 0 is "no character", 1..N the dictionary's lines, N+1 a space.
JNIEXPORT jfloatArray JNICALL Java_org_ungoogled_samsunggallery_ocr_Ncnn_recognize(JNIEnv* env, jclass, jlong handle,
        jobject bitmap)
{
    Engine* engine = reinterpret_cast<Engine*>(handle);
    if (!engine) return nullptr;
    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_BGR);
    if (in.empty()) return nullptr;
    const float mean[3] = {127.5f, 127.5f, 127.5f};
    const float norm[3] = {1 / 127.5f, 1 / 127.5f, 1 / 127.5f};
    in.substract_mean_normalize(mean, norm);

    ncnn::Mat out;
    {
        std::lock_guard<std::mutex> guard(engine->lock);
        ncnn::Extractor ex = engine->rec.create_extractor();
        if (ex.input("in0", in) != 0 || ex.extract("out0", out) != 0) return nullptr;
    }
    // out: h = steps, w = classes
    jfloatArray result = env->NewFloatArray(out.h * 2);
    if (!result) return nullptr;
    float* steps = new float[out.h * 2];
    for (int i = 0; i < out.h; i++) {
        const float* p = out.row(i);
        int best = 0;
        float score = p[0];
        for (int j = 1; j < out.w; j++) {
            if (p[j] > score) {
                score = p[j];
                best = j;
            }
        }
        steps[i * 2] = static_cast<float>(best);
        steps[i * 2 + 1] = score;
    }
    env->SetFloatArrayRegion(result, 0, out.h * 2, steps);
    delete[] steps;
    return result;
}

} // extern "C"
