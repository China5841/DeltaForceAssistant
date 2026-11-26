#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <memory>
#include <android/log.h>
#include "memory.h"

#define LOG_TAG "UE4Parser"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// UE4 基础结构体定义 (基于 UE 4.27)
struct FUObjectItem {
    void* Object;           // 0x00
    int32_t Flags;          // 0x08
    int32_t ClusterIndex;   // 0x0C
    int32_t SerialNumber;   // 0x10
    uint32_t Reserved;      // 0x14
}; // Size: 0x18

struct TUObjectArray {
    FUObjectItem** Objects; // 0x00
    FUObjectItem* PreAllocated; // 0x08
    int32_t MaxElements;    // 0x10
    int32_t NumElements;    // 0x14
    int32_t MaxChunks;      // 0x18
    int32_t NumChunks;      // 0x1C
}; // Size: 0x20

struct FNameEntry {
    uint32_t Index;         // 0x00
    char AnsiName[1024];    // Variable
}; // Variable size

struct TNameEntryArray {
    FNameEntry** Entries;   // 0x00
    int32_t NumElements;    // 0x08
    int32_t NumChunks;      // 0x0C
}; // Size: 0x10

struct UObject {
    void* VTable;           // 0x00
    int32_t ObjectFlags;    // 0x08
    int32_t InternalIndex;  // 0x0C
    UObject* ClassPrivate;  // 0x10
    uint8_t Padding[0x10];  // 0x18
    UObject* OuterPrivate;  // 0x28
}; // Size: 0x30

struct UField : UObject {
    UField* Next;           // 0x30
}; // Size: 0x38

struct UStruct : UField {
    UStruct* SuperStruct;   // 0x38
    UField* Children;       // 0x40
    int32_t PropertySize;   // 0x48
    int32_t MinAlignment;   // 0x4C
    uint8_t Padding[0x10];  // 0x50
}; // Size: 0x60

struct UClass : UStruct {
    uint8_t Padding[0x1A0]; // 0x60
}; // Size: 0x200

// 全局变量偏移量 (需要根据版本动态获取)
struct UE4Globals {
    uint64_t GUObjectArray = 0;
    uint64_t GNames = 0;
    uint64_t GWorld = 0;
    uint64_t GEngine = 0;
};

static UE4Globals g_globals;
static int g_pid = -1;
static uint64_t g_ue4_base = 0;

// 读取内存辅助函数
static bool read_mem(uint64_t addr, void* buf, size_t size) {
    struct iovec local = { buf, size };
    struct iovec remote = { reinterpret_cast<void*>(addr), size };
    return process_vm_readv(g_pid, &local, 1, &remote, 1, 0) == static_cast<ssize_t>(size);
}

static bool write_mem(uint64_t addr, const void* buf, size_t size) {
    struct iovec local = { const_cast<void*>(buf), size };
    struct iovec remote = { reinterpret_cast<void*>(addr), size };
    return process_vm_writev(g_pid, &local, 1, &remote, 1, 0) == static_cast<ssize_t>(size);
}

// 扫描特征码
static uint64_t find_pattern(uint64_t start, uint64_t end, const std::vector<uint8_t>& pattern, const std::string& mask) {
    std::vector<uint8_t> buffer(4096);
    for (uint64_t addr = start; addr < end; addr += buffer.size()) {
        size_t read_size = std::min(buffer.size(), static_cast<size_t>(end - addr));
        if (!read_mem(addr, buffer.data(), read_size)) continue;
        
        for (size_t i = 0; i < read_size - pattern.size(); ++i) {
            bool match = true;
            for (size_t j = 0; j < pattern.size(); ++j) {
                if (mask[j] == 'x' && buffer[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return addr + i;
        }
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deltaforce_assistant_game_UE4Parser_initialize(JNIEnv* env, jobject thiz, jint pid, jlong ue4Base) {
    g_pid = pid;
    g_ue4_base = ue4Base;
    
    // 扫描 GUObjectArray 特征码
    // 48 8B 05 ?? ?? ?? ?? 48 85 C0 74 ?? 48 8B 00
    std::vector<uint8_t> guobj_pattern = {0x48, 0x8B, 0x05};
    std::string guobj_mask = "xxx";
    
    // 这里需要完整的特征码扫描实现
    // 简化版：直接读取已知偏移 (需要根据版本更新)
    
    LOGD("UE4Parser initialized: pid=%d, base=0x%lx", pid, ue4Base);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_deltaforce_assistant_game_UE4Parser_getGNames(JNIEnv* env, jobject thiz) {
    // 读取 GNames 指针
    uint64_t gnames_ptr = 0;
    if (!read_mem(g_globals.GNames, &gnames_ptr, sizeof(gnames_ptr))) {
        return nullptr;
    }
    
    // 读取 TNameEntryArray
    TNameEntryArray name_array;
    if (!read_mem(gnames_ptr, &name_array, sizeof(name_array))) {
        return nullptr;
    }
    
    // 创建 Java 返回对象
    jclass list_cls = env->FindClass("java/util/ArrayList");
    jmethodID list_ctor = env->GetMethodID(list_cls, "<init>", "()V");
    jmethodID list_add = env->GetMethodID(list_cls, "add", "(Ljava/lang/Object;)Z");
    jobject list = env->NewObject(list_cls, list_ctor);
    
    // 遍历名称条目 (简化版)
    for (int i = 0; i < name_array.NumElements && i < 1000; ++i) {
        uint64_t entry_ptr = 0;
        if (!read_mem(reinterpret_cast<uint64_t>(name_array.Entries) + i * 8, &entry_ptr, 8)) continue;
        if (entry_ptr == 0) continue;
        
        FNameEntry entry;
        if (!read_mem(entry_ptr, &entry, sizeof(entry.Index) + 64)) continue;
        
        jstring name = env->NewStringUTF(entry.AnsiName);
        env->CallBooleanMethod(list, list_add, name);
        env->DeleteLocalRef(name);
    }
    
    return list;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_deltaforce_assistant_game_UE4Parser_getGObjects(JNIEnv* env, jobject thiz) {
    // 读取 GUObjectArray
    TUObjectArray obj_array;
    if (!read_mem(g_globals.GUObjectArray, &obj_array, sizeof(obj_array))) {
        return nullptr;
    }
    
    jclass list_cls = env->FindClass("java/util/ArrayList");
    jmethodID list_ctor = env->GetMethodID(list_cls, "<init>", "()V");
    jmethodID list_add = env->GetMethodID(list_cls, "add", "(Ljava/lang/Object;)Z");
    jobject list = env->NewObject(list_cls, list_ctor);
    
    // 遍历对象
    for (int chunk = 0; chunk < obj_array.NumChunks; ++chunk) {
        FUObjectItem* chunk_ptr = obj_array.Objects[chunk];
        if (!chunk_ptr) continue;
        
        std::vector<FUObjectItem> items(65536);
        if (!read_mem(reinterpret_cast<uint64_t>(chunk_ptr), items.data(), items.size() * sizeof(FUObjectItem))) continue;
        
        for (int i = 0; i < 65536; ++i) {
            if (items[i].Object == nullptr) continue;
            
            // 创建 UObject 信息对象
            jclass obj_cls = env->FindClass("com/deltaforce/assistant/game/UObjectInfo");
            jmethodID obj_ctor = env->GetMethodID(obj_cls, "<init>", "(JJII)V");
            jobject obj = env->NewObject(obj_cls, obj_ctor,
                reinterpret_cast<jlong>(items[i].Object),
                items[i].SerialNumber,
                items[i].Flags,
                items[i].ClusterIndex);
            env->CallBooleanMethod(list, list_add, obj);
            env->DeleteLocalRef(obj);
        }
    }
    
    return list;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_deltaforce_assistant_game_UE4Parser_findObjectByName(JNIEnv* env, jobject thiz, jstring nameStr) {
    const char* name = env->GetStringUTFChars(nameStr, nullptr);
    
    // 获取 GNames 查找名称索引
    // 然后遍历 GUObjectArray 查找匹配的对象
    
    env->ReleaseStringUTFChars(nameStr, name);
    return nullptr; // 简化版
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_deltaforce_assistant_game_UE4Parser_getWorld(JNIEnv* env, jobject thiz) {
    uint64_t world_ptr = 0;
    if (read_mem(g_globals.GWorld, &world_ptr, sizeof(world_ptr))) {
        return static_cast<jlong>(world_ptr);
    }
    return 0;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_deltaforce_assistant_game_UE4Parser_getAllActors(JNIEnv* env, jobject thiz, jlong worldPtr) {
    // 读取 UWorld -> PersistentLevel -> Actors 数组
    // 简化版实现
    jclass list_cls = env->FindClass("java/util/ArrayList");
    jmethodID list_ctor = env->GetMethodID(list_cls, "<init>", "()V");
    jmethodID list_add = env->GetMethodID(list_cls, "add", "(Ljava/lang/Object;)Z");
    return env->NewObject(list_cls, list_ctor);
}