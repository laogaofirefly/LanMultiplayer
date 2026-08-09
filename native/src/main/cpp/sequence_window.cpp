#include <jni.h>
#include <cstdint>
extern "C" JNIEXPORT jboolean JNICALL Java_com_example_lanmultiplayer_NativeSequenceWindow_isNewer(JNIEnv*, jobject, jint candidate, jint previous) {
    if (previous < 0) return JNI_TRUE;
    const uint32_t delta = static_cast<uint32_t>(candidate) - static_cast<uint32_t>(previous);
    return delta != 0 && delta < 0x80000000u ? JNI_TRUE : JNI_FALSE;
}
