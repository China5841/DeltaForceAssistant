#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <fcntl.h>
#include <unistd.h>
#include <linux/uinput.h>
#include <android/log.h>

#define LOG_TAG "InputInjector"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static int uinput_fd = -1;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deltaforce_assistant_input_InputInjector_init(JNIEnv* env, jobject thiz) {
    if (uinput_fd >= 0) return JNI_TRUE;
    
    uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (uinput_fd < 0) {
        LOGE("Failed to open /dev/uinput: %s", strerror(errno));
        return JNI_FALSE;
    }
    
    // 启用相对移动事件
    ioctl(uinput_fd, UI_SET_EVBIT, EV_REL);
    ioctl(uinput_fd, UI_SET_RELBIT, REL_X);
    ioctl(uinput_fd, UI_SET_RELBIT, REL_Y);
    
    // 启用按键事件
    ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TOUCH);
    
    // 启用绝对坐标事件 (触摸)
    ioctl(uinput_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_X);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_PRESSURE);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    
    struct uinput_setup setup = {};
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x1234;
    setup.id.product = 0x5678;
    strcpy(setup.name, "DeltaForce Virtual Input");
    
    if (ioctl(uinput_fd, UI_DEV_SETUP, &setup) < 0) {
        LOGE("UI_DEV_SETUP failed: %s", strerror(errno));
        close(uinput_fd);
        uinput_fd = -1;
        return JNI_FALSE;
    }
    
    if (ioctl(uinput_fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        close(uinput_fd);
        uinput_fd = -1;
        return JNI_FALSE;
    }
    
    LOGD("uinput device created successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_deltaforce_assistant_input_InputInjector_destroy(JNIEnv* env, jobject thiz) {
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_deltaforce_assistant_input_InputInjector_injectMove(JNIEnv* env, jobject thiz, jint deltaX, jint deltaY) {
    if (uinput_fd < 0) return;
    
    struct input_event ev[2] = {};
    ev[0].type = EV_REL;
    ev[0].code = REL_X;
    ev[0].value = deltaX;
    ev[1].type = EV_REL;
    ev[1].code = REL_Y;
    ev[1].value = deltaY;
    
    write(uinput_fd, ev, sizeof(ev));
    
    // 同步事件
    struct input_event sync = {};
    sync.type = EV_SYN;
    sync.code = SYN_REPORT;
    sync.value = 0;
    write(uinput_fd, &sync, sizeof(sync));
}

extern "C" JNIEXPORT void JNICALL
Java_com_deltaforce_assistant_input_InputInjector_injectClick(JNIEnv* env, jobject thiz, jint button) {
    if (uinput_fd < 0) return;
    
    int keyCode = (button == 0) ? BTN_LEFT : (button == 1) ? BTN_RIGHT : BTN_MIDDLE;
    
    // 按下
    struct input_event evDown = {};
    evDown.type = EV_KEY;
    evDown.code = keyCode;
    evDown.value = 1;
    write(uinput_fd, &evDown, sizeof(evDown));
    
    struct input_event sync = {};
    sync.type = EV_SYN;
    sync.code = SYN_REPORT;
    sync.value = 0;
    write(uinput_fd, &sync, sizeof(sync));
    
    // 释放
    struct input_event evUp = {};
    evUp.type = EV_KEY;
    evUp.code = keyCode;
    evUp.value = 0;
    write(uinput_fd, &evUp, sizeof(evUp));
    
    write(uinput_fd, &sync, sizeof(sync));
}

extern "C" JNIEXPORT void JNICALL
Java_com_deltaforce_assistant_input_InputInjector_injectTouch(JNIEnv* env, jobject thiz, jint x, jint y, jint pressure, jboolean down) {
    if (uinput_fd < 0) return;
    
    struct input_event ev[4] = {};
    
    // ABS_MT_TRACKING_ID
    ev[0].type = EV_ABS;
    ev[0].code = ABS_MT_TRACKING_ID;
    ev[0].value = down ? 0 : -1;
    
    // ABS_MT_POSITION_X
    ev[1].type = EV_ABS;
    ev[1].code = ABS_MT_POSITION_X;
    ev[1].value = x;
    
    // ABS_MT_POSITION_Y
    ev[2].type = EV_ABS;
    ev[2].code = ABS_MT_POSITION_Y;
    ev[2].value = y;
    
    // ABS_MT_PRESSURE
    ev[3].type = EV_ABS;
    ev[3].code = ABS_MT_PRESSURE;
    ev[3].value = pressure;
    
    write(uinput_fd, ev, sizeof(ev));
    
    struct input_event sync = {};
    sync.type = EV_SYN;
    sync.code = SYN_REPORT;
    sync.value = 0;
    write(uinput_fd, &sync, sizeof(sync));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deltaforce_assistant_input_InputInjector_isAvailable(JNIEnv* env, jobject thiz) {
    return uinput_fd >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    return JNI_VERSION_1_6;
}