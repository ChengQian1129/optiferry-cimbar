#include "cfc-cpp/MultiThreadedDecoder.h"

#include <jni.h>
#include <android/log.h>
#include <opencv2/imgproc.hpp>

#include <filesystem>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr char kTag[] = "OptiFerryCimbar";
constexpr char kDroppedFrame[] = "__OPTIFERRY_CIMBAR_DROPPED__";
std::mutex gMutex;
std::shared_ptr<MultiThreadedDecoder> gDecoder;
std::unordered_map<std::string, size_t> gReportedCounts;
std::string gPath;
int gMode = 68;

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring resultString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

void stopDecoderLocked() {
    if (gDecoder) gDecoder->stop();
    gDecoder.reset();
    gReportedCounts.clear();
}

int rotateCode(int degrees) {
    switch ((degrees % 360 + 360) % 360) {
        case 90: return cv::ROTATE_90_CLOCKWISE;
        case 180: return cv::ROTATE_180;
        case 270: return cv::ROTATE_90_COUNTERCLOCKWISE;
        default: return -1;
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_airferrylite_receiver_CimbarNative_processNv21(
        JNIEnv* env,
        jobject /* object */,
        jbyteArray nv21,
        jint width,
        jint height,
        jint rotationDegrees,
        jstring dataPath,
        jint mode) {
    if (nv21 == nullptr || width <= 0 || height <= 0) return resultString(env, {});

    try {
        const jsize length = env->GetArrayLength(nv21);
        const int chromaHeight = (height + 1) / 2;
        const int expected = width * height + width * chromaHeight;
        if (length < expected) return resultString(env, {});

        std::vector<jbyte> bytes(static_cast<size_t>(length));
        env->GetByteArrayRegion(nv21, 0, length, bytes.data());

        cv::Mat yuv(height + chromaHeight, width, CV_8UC1, bytes.data());
        cv::Mat rgb;
        cv::cvtColor(yuv, rgb, cv::COLOR_YUV2RGB_NV21);
        const int code = rotateCode(rotationDegrees);
        if (code >= 0) {
            cv::Mat rotated;
            cv::rotate(rgb, rotated, code);
            rgb = rotated;
        }
        const std::string path = toString(env, dataPath);
        if (path.empty()) return resultString(env, {});

        std::lock_guard<std::mutex> lock(gMutex);
        std::error_code fsError;
        std::filesystem::create_directories(path, fsError);
        if (fsError) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "Cannot create decoder directory: %s", path.c_str());
            return resultString(env, {});
        }

        if (!gDecoder || gPath != path || gMode != mode || !gDecoder->set_mode(mode)) {
            stopDecoderLocked();
            gPath = path;
            gMode = mode;
            gDecoder = std::make_shared<MultiThreadedDecoder>(path, mode);
        }

        const bool accepted = gDecoder->add(rgb);

        // Decoder workers finish asynchronously. Returning every newly stored file
        // on the next camera frame keeps the camera thread non-blocking while still
        // delivering all manifest/part completions exactly once.
        std::unordered_map<std::string, size_t> available;
        for (const std::string& filename : gDecoder->get_done()) {
            ++available[filename];
        }
        for (const auto& [filename, total] : available) {
            size_t& reported = gReportedCounts[filename];
            if (reported < total) {
                ++reported;
                return resultString(env, path + "/" + filename);
            }
        }
        return resultString(env, accepted ? "" : kDroppedFrame);
    } catch (const cv::Exception& error) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Cimbar frame failed: %s", error.what());
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Cimbar native exception: %s", error.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Cimbar native exception");
    }
    return resultString(env, {});
}

extern "C" JNIEXPORT void JNICALL
Java_com_airferrylite_receiver_CimbarNative_reset(
        JNIEnv* /* env */, jobject /* object */) {
    std::lock_guard<std::mutex> lock(gMutex);
    stopDecoderLocked();
    gPath.clear();
    gMode = 68;
}

extern "C" JNIEXPORT void JNICALL
Java_com_airferrylite_receiver_CimbarNative_shutdown(
        JNIEnv* /* env */, jobject /* object */) {
    std::lock_guard<std::mutex> lock(gMutex);
    stopDecoderLocked();
    gPath.clear();
}
