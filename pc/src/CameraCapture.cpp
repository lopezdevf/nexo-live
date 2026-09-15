// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#include "CameraCapture.h"

#include <mfapi.h>
#include <mfidl.h>

#include <cmath>

namespace sirga {

namespace {
constexpr DWORD kVideoStream = static_cast<DWORD>(MF_SOURCE_READER_FIRST_VIDEO_STREAM);
}

CameraCapture::~CameraCapture() { Stop(); }

bool CameraCapture::Open(ID3D11Device* device, const std::wstring& symbolicLink) {
    Stop();
    device_.copy_from(device);
    device_->GetImmediateContext(context_.put());

    winrt::com_ptr<IMFAttributes> sourceAttributes;
    if (FAILED(MFCreateAttributes(sourceAttributes.put(), 2))) return false;
    sourceAttributes->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
    sourceAttributes->SetString(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK, symbolicLink.c_str());
    if (FAILED(MFCreateDeviceSource(sourceAttributes.get(), source_.put()))) {
        Log(L"No se pudo abrir la cámara del PC");
        return false;
    }

    winrt::com_ptr<IMFAttributes> readerAttributes;
    MFCreateAttributes(readerAttributes.put(), 1);
    // Deja que Windows convierta MJPEG, YUY2 o NV12 al BGRA que espera el codificador
    readerAttributes->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, TRUE);
    if (FAILED(MFCreateSourceReaderFromMediaSource(source_.get(), readerAttributes.get(), reader_.put()))) {
        source_->Shutdown();
        source_ = nullptr;
        return false;
    }

    // El modo nativo de más resolución hasta 1080p; entre iguales, el más cercano a 30 fps sin pasarse de 60
    winrt::com_ptr<IMFMediaType> best;
    double bestScore = -1;
    for (DWORD i = 0;; i++) {
        winrt::com_ptr<IMFMediaType> type;
        if (FAILED(reader_->GetNativeMediaType(kVideoStream, i, type.put()))) break;
        UINT32 w = 0, h = 0, num = 0, den = 1;
        if (FAILED(MFGetAttributeSize(type.get(), MF_MT_FRAME_SIZE, &w, &h)) || h == 0 || h > 1080) continue;
        MFGetAttributeRatio(type.get(), MF_MT_FRAME_RATE, &num, &den);
        double fps = den ? static_cast<double>(num) / den : 0;
        if (fps < 14 || fps > 61) continue;
        double score = static_cast<double>(w) * h * 1000 - std::abs(fps - 30);
        if (fps < 23.5) score -= 1e12;  // 15 fps solo si no hay nada mejor
        if (score > bestScore) {
            bestScore = score;
            best = type;
        }
    }
    if (best) reader_->SetCurrentMediaType(kVideoStream, nullptr, best.get());

    winrt::com_ptr<IMFMediaType> bgra;
    MFCreateMediaType(bgra.put());
    bgra->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    bgra->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
    winrt::com_ptr<IMFMediaType> current;
    if (FAILED(reader_->SetCurrentMediaType(kVideoStream, nullptr, bgra.get())) ||
        FAILED(reader_->GetCurrentMediaType(kVideoStream, current.put())) ||
        FAILED(MFGetAttributeSize(current.get(), MF_MT_FRAME_SIZE, &width_, &height_))) {
        Log(L"La cámara del PC no ofrece un formato que se pueda convertir");
        reader_ = nullptr;
        source_->Shutdown();
        source_ = nullptr;
        return false;
    }
    UINT32 num = 30, den = 1;
    MFGetAttributeRatio(current.get(), MF_MT_FRAME_RATE, &num, &den);
    fps_ = den ? std::max<UINT32>(1, static_cast<UINT32>(std::lround(static_cast<double>(num) / den))) : 30;
    stride_ = static_cast<LONG>(MFGetAttributeUINT32(current.get(), MF_MT_DEFAULT_STRIDE, 0));
    if (stride_ == 0) MFGetStrideForBitmapInfoHeader(MFVideoFormat_RGB32.Data1, width_, &stride_);

    D3D11_TEXTURE2D_DESC desc{};
    desc.Width = width_;
    desc.Height = height_;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DYNAMIC;
    desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    if (FAILED(device_->CreateTexture2D(&desc, nullptr, texture_.put()))) {
        Stop();
        return false;
    }

    Log(L"Cámara del PC: %ux%u a %u fps", width_, height_, fps_);
    return true;
}

void CameraCapture::Begin(FrameCallback onFrame) {
    if (!reader_ || running_) return;
    onFrame_ = std::move(onFrame);
    running_ = true;
    thread_ = std::thread([this] { Loop(); });
}

void CameraCapture::Stop() {
    running_ = false;
    if (thread_.joinable()) thread_.join();
    reader_ = nullptr;
    if (source_) source_->Shutdown();
    source_ = nullptr;
    texture_ = nullptr;
    context_ = nullptr;
    device_ = nullptr;
}

void CameraCapture::Loop() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    while (running_) {
        DWORD stream = 0;
        DWORD flags = 0;
        LONGLONG timestamp = 0;
        winrt::com_ptr<IMFSample> sample;
        if (FAILED(reader_->ReadSample(kVideoStream, 0, &stream, &flags, &timestamp, sample.put()))) break;
        if (flags & (MF_SOURCE_READERF_ERROR | MF_SOURCE_READERF_ENDOFSTREAM)) {
            Log(L"La cámara del PC dejó de enviar imagen (¿desconectada?)");
            break;
        }
        if (!sample || !running_) continue;
        int64_t captureUs = NowUs();
        if (Upload(sample.get())) onFrame_(texture_.get(), captureUs);
    }
    CoUninitialize();
}

bool CameraCapture::Upload(IMFSample* sample) {
    winrt::com_ptr<IMFMediaBuffer> buffer;
    if (FAILED(sample->ConvertToContiguousBuffer(buffer.put()))) return false;
    BYTE* scanline0 = nullptr;
    LONG stride = stride_;
    BYTE* raw = nullptr;
    DWORD length = 0;
    auto buffer2d = buffer.try_as<IMF2DBuffer>();
    if (buffer2d) {
        if (FAILED(buffer2d->Lock2D(&scanline0, &stride))) return false;
    } else {
        if (FAILED(buffer->Lock(&raw, nullptr, &length))) return false;
        // Con paso negativo la imagen está de abajo arriba: la primera fila visible es la última del búfer
        scanline0 = stride >= 0 ? raw : raw + (height_ - 1) * static_cast<size_t>(-stride);
    }

    D3D11_MAPPED_SUBRESOURCE mapped{};
    bool ok = SUCCEEDED(context_->Map(texture_.get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped));
    if (ok) {
        const size_t rowBytes = static_cast<size_t>(width_) * 4;
        for (uint32_t y = 0; y < height_; y++) {
            const BYTE* src = scanline0 + static_cast<ptrdiff_t>(stride) * y;
            memcpy(static_cast<BYTE*>(mapped.pData) + static_cast<size_t>(mapped.RowPitch) * y, src, rowBytes);
        }
        context_->Unmap(texture_.get(), 0);
    }
    if (buffer2d) {
        buffer2d->Unlock2D();
    } else {
        buffer->Unlock();
    }
    return ok;
}

}  // namespace sirga
