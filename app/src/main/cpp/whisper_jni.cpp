#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <memory>
#include <string>
#include <vector>

namespace {
struct AbortState { JNIEnv *env; jobject cancelled; jmethodID get; };
bool should_abort(void *user_data) {
    auto *state = static_cast<AbortState *>(user_data);
    return state->env->CallBooleanMethod(state->cancelled, state->get) == JNI_TRUE;
}
void throw_error(JNIEnv *env, const char *message) {
    const auto type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tomilov_stylishsat_speech_WhisperNative_transcribe(
    JNIEnv *env, jobject, jstring path, jfloatArray audio, jint threads, jobject cancelled) {
    const jsize sample_count = env->GetArrayLength(audio);
    if (sample_count <= 0 || sample_count > 16000 * 600) {
        throw_error(env, "Audio must contain at most ten minutes of 16 kHz mono samples"); return nullptr;
    }
    const char *path_chars = env->GetStringUTFChars(path, nullptr);
    if (!path_chars) return nullptr;
    const std::string model_path(path_chars);
    env->ReleaseStringUTFChars(path, path_chars);
    const auto atomic_type = env->GetObjectClass(cancelled);
    const auto get = env->GetMethodID(atomic_type, "get", "()Z");
    if (!get) return nullptr;
    AbortState abort_state{env, cancelled, get};
    if (should_abort(&abort_state)) { throw_error(env, "Transcription cancelled"); return nullptr; }
    auto context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
        whisper_init_from_file_with_params(model_path.c_str(), context_params), &whisper_free);
    if (!context) { throw_error(env, "Whisper could not initialize the downloaded model"); return nullptr; }
    std::vector<float> samples(sample_count);
    env->GetFloatArrayRegion(audio, 0, sample_count, samples.data());
    if (env->ExceptionCheck()) return nullptr;
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::clamp(static_cast<int>(threads), 1, 4);
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    // JNI environments are thread-local. Whisper's abort callback can run on a ggml worker,
    // so use the encoder callback below on the caller thread and no JNI ggml abort callback.
    params.encoder_begin_callback = [](whisper_context *, whisper_state *, void *data) {
        return !should_abort(data);
    };
    params.encoder_begin_callback_user_data = &abort_state;
    const int status = whisper_full(context.get(), params, samples.data(), sample_count);
    if (should_abort(&abort_state)) { throw_error(env, "Transcription cancelled"); return nullptr; }
    if (status != 0) { throw_error(env, "Whisper could not transcribe the recording"); return nullptr; }
    std::string transcript;
    const int segments = whisper_full_n_segments(context.get());
    for (int i = 0; i < segments; ++i) transcript += whisper_full_get_segment_text(context.get(), i);
    // Whisper emits UTF-8. Construct through Java's UTF-8 decoder (NewStringUTF uses modified UTF-8).
    const auto bytes = env->NewByteArray(static_cast<jsize>(transcript.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(transcript.size()), reinterpret_cast<const jbyte *>(transcript.data()));
    const auto string_type = env->FindClass("java/lang/String");
    const auto constructor = env->GetMethodID(string_type, "<init>", "([BLjava/lang/String;)V");
    const auto encoding = env->NewStringUTF("UTF-8");
    return static_cast<jstring>(env->NewObject(string_type, constructor, bytes, encoding));
}
