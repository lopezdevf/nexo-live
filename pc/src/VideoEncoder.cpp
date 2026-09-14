// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#include "VideoEncoder.h"

#include <codecapi.h>
#include <d3d11_1.h>
#include <dxgi1_2.h>
#include <mferror.h>
#include <strmif.h>

#include <algorithm>

namespace sirga {
namespace {

constexpr size_t kSurfaces = 8;

/** Avisa de que el codificador soltó una superficie: se puede volver a usar. */
struct SurfaceRelease : winrt::implements<SurfaceRelease, IMFAsyncCallback> {
    explicit SurfaceRelease(std::shared_ptr<std::atomic<bool>> busy) : busy(std::move(busy)) {}
    HRESULT STDMETHODCALLTYPE GetParameters(DWORD*, DWORD*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE Invoke(IMFAsyncResult*) override {
        busy->store(false);
        return S_OK;
    }
    std::shared_ptr<std::atomic<bool>> busy;
};

void SetUInt(ICodecAPI* codec, const GUID& key, UINT32 value) {
    VARIANT v;
    VariantInit(&v);
    v.vt = VT_UI4;
    v.ulVal = value;
    codec->SetValue(&key, &v);
}

void SetBool(ICodecAPI* codec, const GUID& key, bool value) {
    VARIANT v;
    VariantInit(&v);
    v.vt = VT_BOOL;
    v.boolVal = value ? VARIANT_TRUE : VARIANT_FALSE;
    codec->SetValue(&key, &v);
}

/** Tipos de NAL presentes en un bloque Annex-B. */
void ScanNals(const uint8_t* data, size_t size, bool& hasSps, bool& hasIdr) {
    for (size_t i = 0; i + 3 < size; i++) {
        if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
            uint8_t type = data[i + 3] & 0x1F;
            if (type == 7) hasSps = true;
            if (type == 5) hasIdr = true;
            i += 3;
        }
    }
}

}  // namespace

VideoEncoder::~VideoEncoder() { Stop(); }

bool VideoEncoder::Start(ID3D11Device* device, const Config& config, PacketCallback onPacket) {
    Stop();
    config_ = config;
    onPacket_ = std::move(onPacket);
    device_.copy_from(device);
    device_->GetImmediateContext(context_.put());
    videoDevice_ = device_.try_as<ID3D11VideoDevice>();
    videoContext_ = context_.try_as<ID3D11VideoContext>();
    if (!videoDevice_ || !videoContext_) {
        Log(L"La gráfica no ofrece conversión de vídeo por hardware");
        return false;
    }
    forceKeyframe_ = true;
    running_ = true;
    currentKbps_ = config.bitrateKbps;

    if (!config_.forceSoftware && SUCCEEDED(MFCreateDXGIDeviceManager(&resetToken_, manager_.put())) &&
        SUCCEEDED(manager_->ResetDevice(device_.get(), resetToken_)) && CreateHardwareEncoder()) {
        eventThread_ = std::thread([this] { EventLoop(); });
    } else if (!CreateSoftwareEncoder()) {
        running_ = false;
        Log(L"No hay ningún codificador H.264 disponible");
        return false;
    }
    Log(L"Codificador: %s (%s) %ux%u a %u fps, %u kbps", name_.c_str(), hardware_ ? L"hardware" : L"software", config_.width, config_.height,
        config_.fps, config_.bitrateKbps);
    return true;
}

void VideoEncoder::Stop() {
    if (!running_.exchange(false) && !transform_) return;
    // Despierta al hilo que espera eventos del codificador
    if (events_) events_->QueueEvent(MEUnknown, GUID_NULL, S_OK, nullptr);
    if (eventThread_.joinable()) eventThread_.join();
    std::lock_guard lock(mutex_);
    if (transform_) {
        transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
        if (auto shutdown = transform_.try_as<IMFShutdown>()) shutdown->Shutdown();
    }
    pending_ = nullptr;
    events_ = nullptr;
    transform_ = nullptr;
    manager_ = nullptr;
    surfaces_.clear();
    readback_ = nullptr;
    inputView_ = nullptr;
    staging_ = nullptr;
    processor_ = nullptr;
    processorEnum_ = nullptr;
    videoContext_ = nullptr;
    videoDevice_ = nullptr;
    context_ = nullptr;
    device_ = nullptr;
    inWidth_ = inHeight_ = 0;
    needInput_ = 0;
    hasFrame_ = false;
    sequenceHeader_.clear();
}

bool VideoEncoder::CreateConverter(uint32_t inWidth, uint32_t inHeight) {
    inputView_ = nullptr;
    staging_ = nullptr;
    processor_ = nullptr;
    processorEnum_ = nullptr;

    D3D11_VIDEO_PROCESSOR_CONTENT_DESC desc{};
    desc.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    desc.InputFrameRate = {config_.fps, 1};
    desc.InputWidth = inWidth;
    desc.InputHeight = inHeight;
    desc.OutputFrameRate = {config_.fps, 1};
    desc.OutputWidth = config_.width;
    desc.OutputHeight = config_.height;
    desc.Usage = D3D11_VIDEO_USAGE_OPTIMAL_SPEED;
    if (FAILED(videoDevice_->CreateVideoProcessorEnumerator(&desc, processorEnum_.put())) ||
        FAILED(videoDevice_->CreateVideoProcessor(processorEnum_.get(), 0, processor_.put()))) {
        Log(L"No se pudo crear el conversor de color %ux%u → %ux%u", inWidth, inHeight, config_.width, config_.height);
        return false;
    }

    D3D11_TEXTURE2D_DESC texture{};
    texture.Width = inWidth;
    texture.Height = inHeight;
    texture.MipLevels = 1;
    texture.ArraySize = 1;
    texture.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    texture.SampleDesc.Count = 1;
    texture.Usage = D3D11_USAGE_DEFAULT;
    texture.BindFlags = D3D11_BIND_RENDER_TARGET;
    if (FAILED(device_->CreateTexture2D(&texture, nullptr, staging_.put()))) return false;
    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC inputDesc{};
    inputDesc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    if (FAILED(videoDevice_->CreateVideoProcessorInputView(staging_.get(), processorEnum_.get(), &inputDesc, inputView_.put()))) return false;

    if (surfaces_.empty()) {
        D3D11_TEXTURE2D_DESC nv12 = texture;
        nv12.Width = config_.width;
        nv12.Height = config_.height;
        nv12.Format = DXGI_FORMAT_NV12;
        for (size_t i = 0; i < kSurfaces; i++) {
            auto surface = std::make_unique<Surface>();
            nv12.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_VIDEO_ENCODER;
            if (FAILED(device_->CreateTexture2D(&nv12, nullptr, surface->texture.put()))) {
                nv12.BindFlags = D3D11_BIND_RENDER_TARGET;
                if (FAILED(device_->CreateTexture2D(&nv12, nullptr, surface->texture.put()))) return false;
            }
            D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC outputDesc{};
            outputDesc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
            if (FAILED(videoDevice_->CreateVideoProcessorOutputView(surface->texture.get(), processorEnum_.get(), &outputDesc, surface->view.put()))) {
                return false;
            }
            surfaces_.push_back(std::move(surface));
        }
    } else {
        // Las vistas de salida dependen del enumerador: se recrean con el nuevo
        for (auto& surface : surfaces_) {
            surface->view = nullptr;
            D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC outputDesc{};
            outputDesc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
            if (FAILED(videoDevice_->CreateVideoProcessorOutputView(surface->texture.get(), processorEnum_.get(), &outputDesc, surface->view.put()))) {
                return false;
            }
        }
    }

    // RGB de la pantalla (rango completo) → YUV BT.709 de rango limitado, lo que espera cualquier decodificador
    if (auto context1 = videoContext_.try_as<ID3D11VideoContext1>()) {
        context1->VideoProcessorSetStreamColorSpace1(processor_.get(), 0, DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709);
        context1->VideoProcessorSetOutputColorSpace1(processor_.get(), DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P709);
    } else {
        D3D11_VIDEO_PROCESSOR_COLOR_SPACE input{};
        D3D11_VIDEO_PROCESSOR_COLOR_SPACE output{};
        output.YCbCr_Matrix = 1;
        output.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
        videoContext_->VideoProcessorSetStreamColorSpace(processor_.get(), 0, &input);
        videoContext_->VideoProcessorSetOutputColorSpace(processor_.get(), &output);
    }
    videoContext_->VideoProcessorSetStreamFrameFormat(processor_.get(), 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
    videoContext_->VideoProcessorSetStreamAutoProcessingMode(processor_.get(), 0, FALSE);
    RECT source{0, 0, static_cast<LONG>(inWidth), static_cast<LONG>(inHeight)};
    RECT target{0, 0, static_cast<LONG>(config_.width), static_cast<LONG>(config_.height)};
    videoContext_->VideoProcessorSetStreamSourceRect(processor_.get(), 0, TRUE, &source);
    videoContext_->VideoProcessorSetStreamDestRect(processor_.get(), 0, TRUE, &target);
    videoContext_->VideoProcessorSetOutputTargetRect(processor_.get(), TRUE, &target);

    inWidth_ = inWidth;
    inHeight_ = inHeight;
    return true;
}

bool VideoEncoder::CreateHardwareEncoder() {
    LUID luid{};
    if (auto dxgi = device_.try_as<IDXGIDevice>()) {
        winrt::com_ptr<IDXGIAdapter> adapter;
        DXGI_ADAPTER_DESC desc{};
        if (SUCCEEDED(dxgi->GetAdapter(adapter.put())) && SUCCEEDED(adapter->GetDesc(&desc))) luid = desc.AdapterLuid;
    }
    winrt::com_ptr<IMFAttributes> filter;
    MFCreateAttributes(filter.put(), 1);
    filter->SetBlob(MFT_ENUM_ADAPTER_LUID, reinterpret_cast<const UINT8*>(&luid), sizeof(luid));

    MFT_REGISTER_TYPE_INFO input{MFMediaType_Video, MFVideoFormat_NV12};
    MFT_REGISTER_TYPE_INFO output{MFMediaType_Video, MFVideoFormat_H264};
    IMFActivate** activates = nullptr;
    UINT32 count = 0;
    if (FAILED(MFTEnum2(MFT_CATEGORY_VIDEO_ENCODER, MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER, &input, &output, filter.get(), &activates,
                        &count))) {
        return false;
    }

    bool created = false;
    for (UINT32 i = 0; i < count; i++) {
        IMFActivate* activate = activates[i];
        if (created) {
            activate->Release();
            continue;
        }
        wchar_t* friendly = nullptr;
        UINT32 length = 0;
        std::wstring name = SUCCEEDED(activate->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &friendly, &length)) ? friendly : L"H.264 por hardware";
        CoTaskMemFree(friendly);

        winrt::com_ptr<IMFTransform> transform;
        if (SUCCEEDED(activate->ActivateObject(__uuidof(IMFTransform), transform.put_void()))) {
            winrt::com_ptr<IMFAttributes> attributes;
            transform->GetAttributes(attributes.put());
            if (attributes && MFGetAttributeUINT32(attributes.get(), MF_TRANSFORM_ASYNC, FALSE)) {
                attributes->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE);
                attributes->SetUINT32(MF_LOW_LATENCY, TRUE);
                DWORD inId = 0, outId = 0;
                if (transform->GetStreamIDs(1, &inId, 1, &outId) == S_OK) {
                    inputId_ = inId;
                    outputId_ = outId;
                } else {
                    inputId_ = outputId_ = 0;
                }
                if (SUCCEEDED(transform->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, reinterpret_cast<ULONG_PTR>(manager_.get())))) {
                    SetCodecValues(transform.get());
                    if (ConfigureTypes(transform.get())) {
                        SetCodecValues(transform.get());
                        transform->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
                        transform->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
                        events_ = transform.as<IMFMediaEventGenerator>();
                        transform_ = transform;
                        name_ = name;
                        hardware_ = true;
                        created = true;
                    }
                }
            }
            if (!created) {
                Log(L"Codificador %s descartado", name.c_str());
                activate->ShutdownObject();
            }
        }
        activate->Release();
    }
    CoTaskMemFree(activates);
    return created;
}

bool VideoEncoder::CreateSoftwareEncoder() {
    MFT_REGISTER_TYPE_INFO input{MFMediaType_Video, MFVideoFormat_NV12};
    MFT_REGISTER_TYPE_INFO output{MFMediaType_Video, MFVideoFormat_H264};
    IMFActivate** activates = nullptr;
    UINT32 count = 0;
    if (FAILED(MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_LOCALMFT | MFT_ENUM_FLAG_SORTANDFILTER, &input, &output,
                         &activates, &count)) ||
        count == 0) {
        return false;
    }
    winrt::com_ptr<IMFTransform> transform;
    HRESULT hr = activates[0]->ActivateObject(__uuidof(IMFTransform), transform.put_void());
    for (UINT32 i = 0; i < count; i++) activates[i]->Release();
    CoTaskMemFree(activates);
    if (FAILED(hr)) return false;

    inputId_ = outputId_ = 0;
    SetCodecValues(transform.get());
    if (!ConfigureTypes(transform.get())) return false;
    SetCodecValues(transform.get());
    transform->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    transform->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    transform_ = transform;
    name_ = L"H.264 por software (Windows)";
    hardware_ = false;
    return true;
}

bool VideoEncoder::ConfigureTypes(IMFTransform* transform) {
    winrt::com_ptr<IMFMediaType> output;
    MFCreateMediaType(output.put());
    output->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    output->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
    output->SetUINT32(MF_MT_AVG_BITRATE, config_.bitrateKbps * 1000);
    MFSetAttributeSize(output.get(), MF_MT_FRAME_SIZE, config_.width, config_.height);
    MFSetAttributeRatio(output.get(), MF_MT_FRAME_RATE, config_.fps, 1);
    MFSetAttributeRatio(output.get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    output->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    output->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Main);
    HRESULT hr = transform->SetOutputType(outputId_, output.get(), 0);
    if (FAILED(hr)) {
        Log(L"SetOutputType falló (0x%08X)", static_cast<uint32_t>(hr));
        return false;
    }

    for (DWORD i = 0;; i++) {
        winrt::com_ptr<IMFMediaType> input;
        hr = transform->GetInputAvailableType(inputId_, i, input.put());
        if (hr == MF_E_NO_MORE_TYPES || FAILED(hr)) break;
        GUID subtype{};
        if (FAILED(input->GetGUID(MF_MT_SUBTYPE, &subtype)) || subtype != MFVideoFormat_NV12) continue;
        MFSetAttributeSize(input.get(), MF_MT_FRAME_SIZE, config_.width, config_.height);
        MFSetAttributeRatio(input.get(), MF_MT_FRAME_RATE, config_.fps, 1);
        input->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        if (SUCCEEDED(transform->SetInputType(inputId_, input.get(), 0))) return true;
    }
    winrt::com_ptr<IMFMediaType> input;
    MFCreateMediaType(input.put());
    input->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    input->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
    MFSetAttributeSize(input.get(), MF_MT_FRAME_SIZE, config_.width, config_.height);
    MFSetAttributeRatio(input.get(), MF_MT_FRAME_RATE, config_.fps, 1);
    input->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    hr = transform->SetInputType(inputId_, input.get(), 0);
    if (FAILED(hr)) Log(L"SetInputType falló (0x%08X)", static_cast<uint32_t>(hr));
    return SUCCEEDED(hr);
}

void VideoEncoder::SetCodecValues(IMFTransform* transform) {
    winrt::com_ptr<ICodecAPI> codec;
    if (FAILED(transform->QueryInterface(__uuidof(ICodecAPI), codec.put_void()))) return;
    SetBool(codec.get(), CODECAPI_AVLowLatencyMode, true);
    SetUInt(codec.get(), CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR);
    SetUInt(codec.get(), CODECAPI_AVEncCommonMeanBitRate, config_.bitrateKbps * 1000);
    SetUInt(codec.get(), CODECAPI_AVEncMPVGOPSize, config_.fps * 2);
    SetUInt(codec.get(), CODECAPI_AVEncMPVDefaultBPictureCount, 0);
    SetUInt(codec.get(), CODECAPI_AVEncCommonQualityVsSpeed, 50);
}

void VideoEncoder::SubmitFrame(ID3D11Texture2D* frame, int64_t captureUs) {
    std::lock_guard lock(mutex_);
    if (!running_ || !transform_) return;
    D3D11_TEXTURE2D_DESC desc{};
    frame->GetDesc(&desc);
    if ((desc.Width != inWidth_ || desc.Height != inHeight_) && !CreateConverter(desc.Width, desc.Height)) return;
    context_->CopyResource(staging_.get(), frame);
    hasFrame_ = true;
    auto sample = ConvertLocked(captureUs);
    if (!sample) return;
    if (hardware_) {
        // Si el codificador aún no pidió más, este fotograma sustituye al que esperaba: siempre el más reciente
        pending_ = sample;
        FeedPendingLocked();
    } else {
        SoftwareEncode(sample.get());
    }
}

void VideoEncoder::SetBitrate(uint32_t kbps) {
    std::lock_guard lock(mutex_);
    if (!running_ || !transform_ || kbps == currentKbps_) return;
    winrt::com_ptr<ICodecAPI> codec;
    if (FAILED(transform_->QueryInterface(__uuidof(ICodecAPI), codec.put_void()))) return;
    SetUInt(codec.get(), CODECAPI_AVEncCommonMeanBitRate, kbps * 1000);
    currentKbps_ = kbps;
    Log(L"Bitrate: %u kbps", kbps);
}

void VideoEncoder::RequestKeyframe() {
    // Con la red atascada llegan peticiones seguidas (del móvil y del descarte de la cola). Cada fotograma clave
    // pesa varias veces más que uno normal: atenderlas todas vuelve a llenar la red. Como mucho una por segundo;
    // mientras tanto el móvil espera a esa o a la siguiente del GOP (cada 2 s).
    int64_t now = NowUs();
    if (now - lastKeyframeRequestUs_.load() < 1'000'000) return;
    lastKeyframeRequestUs_ = now;
    forceKeyframe_ = true;
    std::lock_guard lock(mutex_);
    if (!running_ || !hasFrame_ || pending_) return;
    // Con la pantalla quieta no llegan capturas: se repite la última para que salga el fotograma clave ya
    auto sample = ConvertLocked(NowUs());
    if (!sample) return;
    if (hardware_) {
        pending_ = sample;
        FeedPendingLocked();
    } else {
        SoftwareEncode(sample.get());
    }
}

winrt::com_ptr<IMFSample> VideoEncoder::ConvertLocked(int64_t captureUs) {
    Surface* surface = nullptr;
    for (auto& candidate : surfaces_) {
        bool expected = false;
        if (candidate->busy->compare_exchange_strong(expected, true)) {
            surface = candidate.get();
            break;
        }
    }
    if (!surface) return nullptr;  // el codificador tiene todas: se descarta este fotograma

    D3D11_VIDEO_PROCESSOR_STREAM stream{};
    stream.Enable = TRUE;
    stream.pInputSurface = inputView_.get();
    if (FAILED(videoContext_->VideoProcessorBlt(processor_.get(), surface->view.get(), 0, 1, &stream))) {
        *surface->busy = false;
        return nullptr;
    }

    winrt::com_ptr<IMFSample> sample;
    if (hardware_) {
        winrt::com_ptr<IMFTrackedSample> tracked;
        winrt::com_ptr<IMFMediaBuffer> buffer;
        if (FAILED(MFCreateTrackedSample(tracked.put())) ||
            FAILED(MFCreateDXGISurfaceBuffer(__uuidof(ID3D11Texture2D), surface->texture.get(), 0, FALSE, buffer.put()))) {
            *surface->busy = false;
            return nullptr;
        }
        DWORD length = 0;
        if (auto buffer2d = buffer.try_as<IMF2DBuffer>(); buffer2d && SUCCEEDED(buffer2d->GetContiguousLength(&length))) buffer->SetCurrentLength(length);
        sample = tracked.as<IMFSample>();
        sample->AddBuffer(buffer.get());
        // Cuando el codificador suelte la muestra, la superficie queda libre
        tracked->SetAllocator(winrt::make<SurfaceRelease>(surface->busy).get(), nullptr);
    } else {
        // Por software: se copia la imagen NV12 a memoria
        if (!readback_) {
            D3D11_TEXTURE2D_DESC desc{};
            surface->texture->GetDesc(&desc);
            desc.Usage = D3D11_USAGE_STAGING;
            desc.BindFlags = 0;
            desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
            if (FAILED(device_->CreateTexture2D(&desc, nullptr, readback_.put()))) {
                *surface->busy = false;
                return nullptr;
            }
        }
        context_->CopyResource(readback_.get(), surface->texture.get());
        *surface->busy = false;
        D3D11_MAPPED_SUBRESOURCE mapped{};
        if (FAILED(context_->Map(readback_.get(), 0, D3D11_MAP_READ, 0, &mapped))) return nullptr;
        DWORD size = config_.width * config_.height * 3 / 2;
        winrt::com_ptr<IMFMediaBuffer> buffer;
        MFCreateMemoryBuffer(size, buffer.put());
        BYTE* dest = nullptr;
        buffer->Lock(&dest, nullptr, nullptr);
        auto src = static_cast<const BYTE*>(mapped.pData);
        for (uint32_t y = 0; y < config_.height; y++) memcpy(dest + y * config_.width, src + y * mapped.RowPitch, config_.width);
        const BYTE* uvSrc = src + mapped.RowPitch * config_.height;
        BYTE* uvDest = dest + config_.width * config_.height;
        for (uint32_t y = 0; y < config_.height / 2; y++) memcpy(uvDest + y * config_.width, uvSrc + y * mapped.RowPitch, config_.width);
        buffer->Unlock();
        buffer->SetCurrentLength(size);
        context_->Unmap(readback_.get(), 0);
        MFCreateSample(sample.put());
        sample->AddBuffer(buffer.get());
    }
    sample->SetSampleTime(captureUs * 10);
    sample->SetSampleDuration(10'000'000 / config_.fps);
    return sample;
}

void VideoEncoder::FeedPendingLocked() {
    while (needInput_ > 0 && pending_) {
        if (forceKeyframe_.exchange(false)) {
            winrt::com_ptr<ICodecAPI> codec;
            if (SUCCEEDED(transform_->QueryInterface(__uuidof(ICodecAPI), codec.put_void()))) SetUInt(codec.get(), CODECAPI_AVEncVideoForceKeyFrame, 1);
        }
        HRESULT hr = transform_->ProcessInput(inputId_, pending_.get(), 0);
        pending_ = nullptr;
        needInput_--;
        if (FAILED(hr)) Log(L"ProcessInput falló (0x%08X)", static_cast<uint32_t>(hr));
    }
}

void VideoEncoder::EventLoop() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST);
    while (running_) {
        winrt::com_ptr<IMFMediaEvent> event;
        if (FAILED(events_->GetEvent(0, event.put()))) break;
        MediaEventType type = MEUnknown;
        event->GetType(&type);
        if (!running_) break;
        if (type == METransformNeedInput) {
            std::lock_guard lock(mutex_);
            needInput_++;
            FeedPendingLocked();
        } else if (type == METransformHaveOutput) {
            DrainOutput();
        }
    }
    CoUninitialize();
}

void VideoEncoder::DrainOutput() {
    MFT_OUTPUT_STREAM_INFO info{};
    if (FAILED(transform_->GetOutputStreamInfo(outputId_, &info))) return;
    bool providesSamples = (info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) != 0;

    winrt::com_ptr<IMFSample> ours;
    MFT_OUTPUT_DATA_BUFFER output{};
    output.dwStreamID = outputId_;
    if (!providesSamples) {
        winrt::com_ptr<IMFMediaBuffer> buffer;
        MFCreateSample(ours.put());
        MFCreateMemoryBuffer(std::max<DWORD>(info.cbSize, config_.width * config_.height), buffer.put());
        ours->AddBuffer(buffer.get());
        output.pSample = ours.get();
    }
    DWORD status = 0;
    HRESULT hr = transform_->ProcessOutput(0, 1, &output, &status);
    if (output.pEvents) output.pEvents->Release();
    if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
        winrt::com_ptr<IMFMediaType> type;
        if (SUCCEEDED(transform_->GetOutputAvailableType(outputId_, 0, type.put()))) transform_->SetOutputType(outputId_, type.get(), 0);
        return;
    }
    if (SUCCEEDED(hr) && output.pSample) Deliver(output.pSample);
    if (providesSamples && output.pSample) output.pSample->Release();
}

void VideoEncoder::SoftwareEncode(IMFSample* sample) {
    if (forceKeyframe_.exchange(false)) {
        winrt::com_ptr<ICodecAPI> codec;
        if (SUCCEEDED(transform_->QueryInterface(__uuidof(ICodecAPI), codec.put_void()))) SetUInt(codec.get(), CODECAPI_AVEncVideoForceKeyFrame, 1);
    }
    if (FAILED(transform_->ProcessInput(inputId_, sample, 0))) return;
    while (true) {
        MFT_OUTPUT_STREAM_INFO info{};
        transform_->GetOutputStreamInfo(outputId_, &info);
        winrt::com_ptr<IMFSample> out;
        winrt::com_ptr<IMFMediaBuffer> buffer;
        MFCreateSample(out.put());
        MFCreateMemoryBuffer(std::max<DWORD>(info.cbSize, config_.width * config_.height), buffer.put());
        out->AddBuffer(buffer.get());
        MFT_OUTPUT_DATA_BUFFER output{};
        output.dwStreamID = outputId_;
        output.pSample = out.get();
        DWORD status = 0;
        HRESULT hr = transform_->ProcessOutput(0, 1, &output, &status);
        if (output.pEvents) output.pEvents->Release();
        if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) break;
        if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
            winrt::com_ptr<IMFMediaType> type;
            if (SUCCEEDED(transform_->GetOutputAvailableType(outputId_, 0, type.put()))) transform_->SetOutputType(outputId_, type.get(), 0);
            continue;
        }
        if (FAILED(hr)) break;
        Deliver(out.get());
    }
}

void VideoEncoder::Deliver(IMFSample* sample) {
    winrt::com_ptr<IMFMediaBuffer> buffer;
    if (FAILED(sample->ConvertToContiguousBuffer(buffer.put()))) return;
    BYTE* data = nullptr;
    DWORD length = 0;
    if (FAILED(buffer->Lock(&data, nullptr, &length))) return;
    LONGLONG time = 0;
    sample->GetSampleTime(&time);
    bool hasSps = false;
    bool hasIdr = false;
    ScanNals(data, length, hasSps, hasIdr);
    bool keyframe = MFGetAttributeUINT32(sample, MFSampleExtension_CleanPoint, FALSE) != 0 || hasIdr;

    if (keyframe && !hasSps) {
        // Algunos codificadores dejan SPS/PPS en el tipo de salida: el móvil los necesita con cada fotograma clave
        if (sequenceHeader_.empty()) {
            winrt::com_ptr<IMFMediaType> type;
            UINT32 size = 0;
            if (SUCCEEDED(transform_->GetOutputCurrentType(outputId_, type.put())) && SUCCEEDED(type->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &size)) &&
                size > 0) {
                sequenceHeader_.resize(size);
                type->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER, sequenceHeader_.data(), size, nullptr);
            }
        }
        std::vector<uint8_t> withHeader(sequenceHeader_);
        withHeader.insert(withHeader.end(), data, data + length);
        onPacket_(withHeader.data(), withHeader.size(), true, time / 10);
    } else {
        onPacket_(data, length, keyframe, time / 10);
    }
    encodedFrames_++;
    if (keyframe) keyframes_++;
    buffer->Unlock();
}

}  // namespace sirga
