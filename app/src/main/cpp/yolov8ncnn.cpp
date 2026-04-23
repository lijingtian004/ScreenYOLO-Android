#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <ncnn/net.h>
#include <ncnn/mat.h>
#include <vector>
#include <algorithm>
#include <cmath>

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
            if (union_area > 0 && inter_area / union_area > nms_threshold) {
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

// Parse YOLOv5 style output: [N, 85] or transposed [85, N]
// columns: cx, cy, w, h, obj_conf, cls0...cls79
static void parse_v5_output(const ncnn::Mat& out, bool transposed, int num_anchors, int num_classes,
                            float conf_thresh, std::vector<Object>& proposals) {
    for (int i = 0; i < num_anchors; i++) {
        float cx, cy, w, h, obj_conf;
        const float* scores_ptr;

        if (transposed) {
            cx = out.row(0)[i];
            cy = out.row(1)[i];
            w  = out.row(2)[i];
            h  = out.row(3)[i];
            obj_conf = out.row(4)[i];
            scores_ptr = out.row(5) + i;
        } else {
            const float* row = out.row(i);
            cx = row[0];
            cy = row[1];
            w  = row[2];
            h  = row[3];
            obj_conf = row[4];
            scores_ptr = row + 5;
        }

        float max_score = 0.f;
        int max_cls = 0;
        for (int c = 0; c < num_classes; c++) {
            float score = transposed ? scores_ptr[c * num_anchors] : scores_ptr[c];
            if (score > max_score) {
                max_score = score;
                max_cls = c;
            }
        }

        float prob = obj_conf * max_score;
        if (prob > conf_thresh) {
            Object obj;
            obj.x1 = cx - w / 2.f;
            obj.y1 = cy - h / 2.f;
            obj.x2 = cx + w / 2.f;
            obj.y2 = cy + h / 2.f;
            obj.prob = prob;
            obj.label = max_cls;
            proposals.push_back(obj);
        }
    }
}

// Parse YOLOv8/v11 style output: [N, 84] or transposed [84, N]
// columns: cx, cy, w, h, cls0...cls79 (no obj_conf)
static void parse_v8_output(const ncnn::Mat& out, bool transposed, int num_anchors, int num_classes,
                            float conf_thresh, std::vector<Object>& proposals) {
    for (int i = 0; i < num_anchors; i++) {
        float cx, cy, w, h;
        const float* scores_ptr;

        if (transposed) {
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
            float score = transposed ? scores_ptr[c * num_anchors] : scores_ptr[c];
            if (score > max_score) {
                max_score = score;
                max_cls = c;
            }
        }

        if (max_score > conf_thresh) {
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

    ncnn::Mat in_resized;
    ncnn::resize_bilinear(in, in_resized, inputSize, inputSize);

    const float norm_vals[3] = {1/255.f, 1/255.f, 1/255.f};
    in_resized.substract_mean_normalize(0, norm_vals);

    ncnn::Extractor ex = g_yolov8.create_extractor();

    int ret = ex.input("in0", in_resized);
    if (ret != 0) ret = ex.input("images", in_resized);
    if (ret != 0) ret = ex.input("input", in_resized);
    if (ret != 0) ret = ex.input("data", in_resized);
    if (ret != 0) {
        LOGE("input failed: %d", ret);
        return env->NewObjectArray(0, env->FindClass("com/example/screenyolo/Detection"), nullptr);
    }

    ncnn::Mat out;
    ret = ex.extract("out0", out);
    if (ret != 0) ret = ex.extract("output", out);
    if (ret != 0) ret = ex.extract("output0", out);
    if (ret != 0) {
        LOGE("extract failed: %d", ret);
        return env->NewObjectArray(0, env->FindClass("com/example/screenyolo/Detection"), nullptr);
    }

    // Auto-detect output layout and version (v5=85 cols, v8/v11=84 cols)
    bool transposed = false;   // true: [cols, N], false: [N, cols]
    int num_anchors = 0;
    int cols = 0;

    // Try [N, cols] layout
    if (out.w >= 84) {
        cols = out.w;
        num_anchors = out.h;
        transposed = false;
    }
    // Try [cols, N] layout
    else if (out.h >= 84) {
        cols = out.h;
        num_anchors = out.w;
        transposed = true;
    }
    // Try channel as cols
    else if (out.c >= 84) {
        cols = out.c;
        num_anchors = out.w * out.h;
        transposed = true;
    }
    else {
        LOGE("unexpected output shape: w=%d h=%d c=%d", out.w, out.h, out.c);
        return env->NewObjectArray(0, env->FindClass("com/example/screenyolo/Detection"), nullptr);
    }

    int num_classes = cols - 4;  // default v8: 84-4=80
    bool is_v5 = false;

    if (cols == 85) {
        // Standard COCO v5: 80 classes + 5 (cx,cy,w,h,obj_conf)
        is_v5 = true;
        num_classes = 80;
    } else if (cols == 84) {
        // Standard COCO v8/v11: 80 classes + 4 (cx,cy,w,h)
        is_v5 = false;
        num_classes = 80;
    } else if (cols > 5) {
        // Custom model: auto detect by checking if (cols-4) or (cols-5) is divisible by common numbers
        int v5_classes = cols - 5;
        int v8_classes = cols - 4;
        // Heuristic: if v5_classes is positive and common, prefer v5 when cols-5 looks like a class count
        // But for safety, default to v8 style if cols-4 is more "round"
        if (v5_classes > 0 && v8_classes > 0) {
            // Most converted models drop obj_conf for v8, so default to v8
            is_v5 = false;
            num_classes = v8_classes;
            LOGI("custom cols=%d, assuming v8 style with %d classes", cols, num_classes);
        }
    }

    LOGI("detected output: cols=%d anchors=%d transposed=%d is_v5=%d", cols, num_anchors, transposed, is_v5);

    const float conf_threshold = 0.25f;
    std::vector<Object> proposals;

    if (is_v5) {
        parse_v5_output(out, transposed, num_anchors, num_classes, conf_threshold, proposals);
    } else {
        parse_v8_output(out, transposed, num_anchors, num_classes, conf_threshold, proposals);
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
        const char* label_str = (obj.label >= 0 && obj.label < 80) ? g_labels[obj.label] : "unknown";
        jstring label = env->NewStringUTF(label_str);
        jobject det = env->NewObject(detClass, ctor,
                                     obj.x1, obj.y1, obj.x2, obj.y2,
                                     obj.prob, obj.label, label);
        env->SetObjectArrayElement(result, (jsize)i, det);
        env->DeleteLocalRef(label);
        env->DeleteLocalRef(det);
    }

    return result;
}
