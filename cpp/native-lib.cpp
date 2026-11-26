#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>

#define LOG_TAG "DeltaForceNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deltaforce_assistant_memory_MemoryReader_attach(JNIEnv* env, jobject thiz, jint pid) {
    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) == -1) {
        LOGE("ptrace attach failed: pid=%d", pid);
        return JNI_FALSE;
    }
    int status;
    waitpid(pid, &status, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deltaforce_assistant_memory_MemoryReader_detach(JNIEnv* env, jobject thiz, jint pid) {
    if (ptrace(PTRACE_DETACH, pid, nullptr, nullptr) == -1) {
        LOGE("ptrace detach failed: pid=%d", pid);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_deltaforce_assistant_memory_MemoryReader_readMemory(JNIEnv* env, jobject thiz, jint pid, jlong address, jint size) {
    std::vector<uint8_t> buffer(size);
    struct iovec local = { buffer.data(), static_cast<size_t>(size) };
    struct iovec remote = { reinterpret_cast<void*>(address), static_cast<size_t>(size) };
    
    ssize_t bytes = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    if (bytes <= 0) {
        LOGE("process_vm_readv failed: pid=%d, addr=0x%lx, size=%d", pid, address, size);
        return env->NewByteArray(0);
    }
    
    jbyteArray result = env->NewByteArray(bytes);
    env->SetByteArrayRegion(result, 0, bytes, reinterpret_cast<jbyte*>(buffer.data()));
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deltaforce_assistant_memory_MemoryReader_writeMemory(JNIEnv* env, jobject thiz, jint pid, jlong address, jbyteArray data) {
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    jsize size = env->GetArrayLength(data);
    
    struct iovec local = { bytes, static_cast<size_t>(size) };
    struct iovec remote = { reinterpret_cast<void*>(address), static_cast<size_t>(size) };
    
    ssize_t written = process_vm_writev(pid, &local, 1, &remote, 1, 0);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    
    if (written != size) {
        LOGE("process_vm_writev failed: pid=%d, addr=0x%lx, size=%d", pid, address, size);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_deltaforce_assistant_memory_MemoryReader_getModuleBase(JNIEnv* env, jobject thiz, jint pid, jstring moduleName) {
    const char* name = env->GetStringUTFChars(moduleName, nullptr);
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    FILE* fp = fopen(mapsPath.c_str(), "r");
    if (!fp) {
        env->ReleaseStringUTFChars(moduleName, name);
        return 0;
    }
    
    char line[1024];
    uint64_t base = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, name) && strstr(line, "r-xp")) {
            sscanf(line, "%lx", &base);
            break;
        }
    }
    fclose(fp);
    env->ReleaseStringUTFChars(moduleName, name);
    return static_cast<jlong>(base);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_deltaforce_assistant_memory_MemoryReader_getModuleInfo(JNIEnv* env, jobject thiz, jint pid, jstring moduleName) {
    const char* name = env->GetStringUTFChars(moduleName, nullptr);
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    FILE* fp = fopen(mapsPath.c_str(), "r");
    if (!fp) {
        env->ReleaseStringUTFChars(moduleName, name);
        return nullptr;
    }
    
    char line[1024];
    uint64_t base = 0, end = 0;
    std::string perms, path;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, name) && strstr(line, "r-xp")) {
            sscanf(line, "%lx-%lx %s %*s %*s %*s %s", &base, &end, &perms[0], &path[0]);
            break;
        }
    }
    fclose(fp);
    env->ReleaseStringUTFChars(moduleName, name);
    
    if (base == 0) return nullptr;
    
    jclass cls = env->FindClass("com/deltaforce/assistant/memory/ModuleInfo");
    jmethodID constructor = env->GetMethodID(cls, "<init>", "(JJLjava/lang/String;Ljava/lang/String;)V");
    jstring jPerms = env->NewStringUTF(perms.c_str());
    jstring jPath = env->NewStringUTF(path.c_str());
    return env->NewObject(cls, constructor, base, end, jPerms, jPath);
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}