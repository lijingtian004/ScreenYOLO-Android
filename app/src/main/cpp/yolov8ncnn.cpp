#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <ncnn/net.h>
#include <ncnn/mat.h>
#include <vector>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "YoloV8Ncnn", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "YoloV8Ncnn", __VA_ARGS__)

static ncnn::Net g_yolov8;
static int g_inputSize = 640;

static const char* g_labels[] = {
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
    "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
    "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
    "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
    "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
    "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
};

struct Object {
    float x1, y1, x2, y2;
    float prob;
    int label;
};

static inline float intersection_area(const Object& a, const Object& b) {
    float x1 = std::max(a.x1, b.x1);
    float y1 = std::max(a.y1, b.y1);
    float x2 = std::min(a.x2, b.x2);
    float y2 = std::min(a.y2, b.y2);
    return std::max(0.f, x2 - x1) * std::max(0.f, y2 - y1);
}

static void nms(std::vector<Object>& objects, float nms_threshold) {
    std::sort(objects.begin(), objects.end(), [](const Object& a, const Object& b) {
        return a.prob > b.prob;
    });

    std::vector<int> picked;
    int n = (int)objects.size();
    std::vector<float> areas(n);
    for (int i = 0; i < n; i++) {
        areas[i] = (objects[i].x2 - objects[i].x1) * (objects[i].y2 - objects[i].y1);
    }

    for (int i = 0; i < n; i++) {
        const Object& a = objects[i];
        int keep = 1;
        for (int j = 0; j < (int)picked.size(); j++) {
            const Object& b = objects[picked[j]];
            float inter_area = intersection_area(a, b);
            float union_area = areas[i] + areas[picked[j]] - inter_area;
            if (inter_area / union_area > nms_threshold) {
                keep = 0;
                break;
            }
        }
        if (keep) picked.push_back(i);
    }

    std::vector<Object> result;
    result.reserve(picked.size());
    for (int idx : picked) {
        result.push_back(objects[idx]);
    }
    objects = std::move(result);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_screenyolo_engine_NcnnEngine_nativeLoadModel(JNIEnv* env, jobject thiz,
                                                              jstring paramPath, jstring binPath,
                                                              jint inputSize) {
    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin = env->GetStringUTFChars(binPath, nullptr);

    g_yolov8.clear();
    int ret = g_yolov8.load_param(param);
    if (ret != 0) {
        LOGE("load_param failed: %d", ret);
        env->ReleaseStringUTFChars(paramPath, param);
        env->ReleaseStringUTFChars(binPath, bin);
        return JNI_FALSE;
    }

    ret = g_yolov8.load_model(bin);
    if (ret != 0) {
        LOGE("load_model failed: %d", ret);
        env->ReleaseStringUTFChars(paramPath, param);
        env->ReleaseStringUTFChars(binPath, bin);
        return JNI_FALSE;
    }

    g_inputSize = inputSize;
    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath, bin);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_screenyolo_engine_NcnnEngine_nativeDetect(JNIEnv* env, jobject thiz,
                                                           jobject bitmap, jint inputSize) {
    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGB);
    if (in.empty()) {
        LOGE("from_android_bitmap failed");
        return env->NewObjectArray(0, env->FindClass("com/example/screenyolo/Detection"), nullptr);
    }

    // Resize to input size (stretch)
    ncnn::Mat in_resized;
    ncnn::resize_bilinear(in, in_resized, inputSize, inputSize);

    // Normalize 0-255 to 0-1
    const float norm_vals[3] = {1/255.f, 1/255.f, 1/255.f};
    in_resized.substract_mean_normalize(0, norm_vals);

    ncnn::Extractor ex = g_yolov8.create_extractor();
    ex.set_num_threads(4);

    // Try common input names
    int ret = ex.input("in0", in_resized);
    if (ret != 0) ret = ex.input("images", in_resized);
    if (ret != 0) ret = ex.input("input", in_resized);
    if (ret != 0) ret = ex.input("data", in_resized);
    if (ret != 0) {
        LOGE("input failed: %d", ret);
        return env->NewObjectArray(0, env->FindClass("com/example/screenyolo/Detection"), nullptr);
    }

    ncnn::Mat out;
    // Try common output names
    ret = ex.extract("out0", out);
    if (ret != 0) ret = ex.extract("output", out);
    if (ret != 0) ret = ex.extract("output0", out);
    if (ret != 0) {
        LOGE("extract failed: %d", ret);
        return env->NewObjectArray(0, env->FindClass("com/example/screenyolo/Detection"), nullptr);
    }

    // YOLOv8 output: [84, 8400] in NCNN Mat -> w=8400, h=84, c=1
    // or [8400, 84] -> w=84, h=8400, c=1
    bool is_transposed = false;
    int num_classes = 80;
    int num_anchors = 0;

    if (out.h == 84 && out.w > 84) {
        // Standard [84, N]
        num_anchors = out.w;
        is_transposed = true;
    } else if (out.h > 84 && out.w == 84) {
        // [N, 84]
        num_anchors = out.h;
        is_transposed = false;
    } else if (out.w == 84) {
        num_anchors = out.h;
        is_transposed = false;
    } else if (out.h == 84) {
        num_anchors = out.w;
        is_transposed = true;
    } else {
        // Try c dimension
        if (out.c == 84) {
            num_anchors = out.w * out.h;
            is_transposed = true;
        } else if (out.c > 1) {
            num_anchors = out.c;
            is_transposed = false;
            num_classes = out.c - 4;
        } else {
            LOGE("unexpected output shape: w=%d h=%d c=%d", out.w, out.h, out.c);
            return env->NewObjectArray(0, env->FindClass("com/example/screenyolo/Detection"), nullptr);
        }
    }

    const float conf_threshold = 0.25f;
    std::vector<Object> proposals;

    for (int i = 0; i < num_anchors; i++) {
        float cx, cy, w, h;
        const float* scores_ptr;

        if (is_transposed) {
            cx = out.row(0)[i];
            cy = out.row(1)[i];
            w  = out.row(2)[i];
            h  = out.row(3)[i];
            scores_ptr = out.row(4) + i;
        } else {
            const float* row = out.row(i);
            cx = row[0];
            cy = row[1];
            w  = row[2];
            h  = row[3];
            scores_ptr = row + 4;
        }

        float max_score = 0.f;
        int max_cls = 0;
        for (int c = 0; c < num_classes; c++) {
            float score = is_transposed ? scores_ptr[c * num_anchors] : scores_ptr[c];
            if (score > max_score) {
                max_score = score;
                max_cls = c;
            }
        }

        if (max_score > conf_threshold) {
            Object obj;
            obj.x1 = cx - w / 2.f;
            obj.y1 = cy - h / 2.f;
            obj.x2 = cx + w / 2.f;
            obj.y2 = cy + h / 2.f;
            obj.prob = max_score;
            obj.label = max_cls;
            proposals.push_back(obj);
        }
    }

    nms(proposals, 0.45f);

    jclass detClass = env->FindClass("com/example/screenyolo/Detection");
    if (detClass == nullptr) {
        LOGE("Detection class not found");
        return env->NewObjectArray(0, detClass, nullptr);
    }
    jmethodID ctor = env->GetMethodID(detClass, "<init>", "(FFFFFILjava/lang/String;)V");
    if (ctor == nullptr) {
        LOGE("Detection ctor not found");
        return env->NewObjectArray(0, detClass, nullptr);
    }

    jobjectArray result = env->NewObjectArray((jsize)proposals.size(), detClass, nullptr);
    for (size_t i = 0; i < proposals.size(); i++) {
        const Object& obj = proposals[i];
        jstring label = env->NewStringUTF(g_labels[obj.label]);
        jobject det = env->NewObject(detClass, ctor,
                                     obj.x1, obj.y1, obj.x2, obj.y2,
                                     obj.prob, obj.label, label);
        env->SetObjectArrayElement(result, (jsize)i, det);
        env->DeleteLocalRef(label);
        env->DeleteLocalRef(det);
    }

    return result;
}
