#!/bin/bash
set -e

# Simple APK build using aapt2 and dx/d8 directly
ANDROID_SDK=/usr/lib/android-sdk
BUILD_TOOLS=$ANDROID_SDK/build-tools/34.0.0
PLATFORM=$ANDROID_SDK/platforms/android-34
WORK_DIR=/tmp/deltaforce_build
APP_DIR=/storage/emulated/0/Download/DSH-Go/DeltakForceSource/app

mkdir -p $WORK_DIR
cd $WORK_DIR

# Clean
rm -rf *

# Compile resources with aapt2
echo "Compiling resources..."
$BUILD_TOOLS/aapt2 compile \
    --dir $APP_DIR/src/main/res \
    -o resources.zip

# Link resources
echo "Linking resources..."
$BUILD_TOOLS/aapt2 link \
    --manifest $APP_DIR/src/main/AndroidManifest.xml \
    -I $PLATFORM/android.jar \
    -o base.apk \
    -R resources.zip \
    --auto-add-overlay \
    --java $WORK_DIR/java

# Compile Kotlin/Java sources
echo "Compiling sources..."
mkdir -p $WORK_DIR/classes

# Find all Kotlin files
find $APP_DIR/src/main/java -name "*.kt" > $WORK_DIR/kotlin_files.txt
find $APP_DIR/src/main/java -name "*.java" > $WORK_DIR/java_files.txt

# Use kotlinc if available, otherwise just compile Java
if command -v kotlinc &> /dev/null; then
    kotlinc @$WORK_DIR/kotlin_files.txt -d $WORK_DIR/classes -cp $PLATFORM/android.jar
fi

# Use dx/d8 to convert to dex
echo "Converting to dex..."
$BUILD_TOOLS/d8 --output $WORK_DIR/dex $WORK_DIR/classes 2>/dev/null || echo "d8 failed, trying dx"

# Add dex to APK
echo "Adding dex to APK..."
cd $WORK_DIR
$BUILD_TOOLS/aapt2 add base.apk classes.dex 2>/dev/null || $BUILD_TOOLS/aapt add base.apk classes.dex

# Sign APK
echo "Signing APK..."
$BUILD_TOOLS/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out deltaforce-debug.apk base.apk 2>/dev/null || {
    # Create debug keystore if not exists
    keytool -genkey -v -keystore debug.keystore -alias android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" -storepass android -keypass android
    $BUILD_TOOLS/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out deltaforce-debug.apk base.apk
}

# Align
echo "Aligning APK..."
$BUILD_TOOLS/zipalign -f 4 deltaforce-debug.apk deltaforce-aligned.apk

echo "APK built at: $WORK_DIR/deltaforce-aligned.apk"
cp $WORK_DIR/deltaforce-aligned.apk /storage/emulated/0/Download/DSH-Go/DeltaForceAssistant.apk
echo "Copied to /storage/emulated/0/Download/DSH-Go/DeltaForceAssistant.apk"
