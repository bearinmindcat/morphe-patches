// What the two networks' glue (galleryocr.cpp, gallerysearch.cpp) share: OpenMP kept alive.
//
// ncnn runs its layers in OpenMP parallel loops, and the OpenMP runtime is linked into this library statically.
// A static runtime shuts itself down when the last thread that started a parallel loop ends -- here a job's or an
// executor's thread -- and starting it again later in the same process aborts in its CPU affinity setup
// (__kmp_create_affinity_none_places): Search by content's second run in a process crashed Gallery. One thread of our
// own starts the runtime when the library loads and then waits for good, so the runtime stays up.

#include <jni.h>
#include <omp.h>
#include <pthread.h>

namespace {

pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t never = PTHREAD_COND_INITIALIZER;

void* keep(void*)
{
    #pragma omp parallel num_threads(2)
    {
        omp_get_thread_num();
    }
    pthread_mutex_lock(&lock);
    for (;;) pthread_cond_wait(&never, &lock);
    return nullptr;
}

} // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*)
{
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&thread, &attr, keep, nullptr) == 0) pthread_setname_np(thread, "UA-omp");
    pthread_attr_destroy(&attr);
    return JNI_VERSION_1_6;
}
