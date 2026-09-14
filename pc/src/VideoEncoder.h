// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors
#pragma once

#include "Common.h"

#include <d3d11.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mftransform.h>

#include <atomic>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace senda {

/**
 * Convierte la captura BGRA a NV12 en la GPU (escalando si hace falta) y la codifica en H.264 con
 * el codificador por hardware de la gráfica (Intel Quick Sync, NVIDIA NVENC o AMD AMF, a través de
 * Media Foundation). Si no hay ninguno, usa el codificador por software de Windows.
 *
 * Configurado para el menor retraso: modo de baja latencia, sin fotogramas B y cada fotograma se
 * entrega en cuanto sale. Si el codificador va ocupado, se codifica el fotograma más reciente.
 */
class VideoEncoder {
public:
    struct Config {
        uint32_t width = 1920;
        uint32_t height = 1080;
        uint32_t fps = 60;
        uint32_t bitrateKbps = 15000;
        /** Salta el codificador de la gráfica (controladores con fallos o pruebas). */
        bool forceSoftware = false;
    };
    using PacketCallback = std::function<void(const uint8_t* data, size_t size, bool keyframe, int64_t captureUs)>;

    ~VideoEncoder();

    bool Start(ID3D11Device* device, const Config& config, PacketCallback onPacket);
    void Stop();

    /** Llega desde el hilo de captura. [frame] solo es válido durante la llamada. */
    void SubmitFrame(ID3D11Texture2D* frame, int64_t captureUs);
    /** Codifica el siguiente fotograma como clave; si la pantalla está quieta, repite el último. */
    void RequestKeyframe();
    /** Cambia el bitrate sin reiniciar el codificador (bitrate adaptativo). */
    void SetBitrate(uint32_t kbps);
    uint32_t BitrateKbps() const { return currentKbps_; }

    const std::wstring& Name() const { return name_; }
    bool Hardware() const { return hardware_; }
    uint32_t TakeEncodedFrames() { return encodedFrames_.exchange(0); }

private:
    bool CreateConverter(uint32_t inWidth, uint32_t inHeight);
    bool CreateHardwareEncoder();
    bool CreateSoftwareEncoder();
    bool ConfigureTypes(IMFTransform* transform);
    void SetCodecValues(IMFTransform* transform);
    void EventLoop();
    void FeedPendingLocked();
    void DrainOutput();
    void SoftwareEncode(IMFSample* sample);
    void Deliver(IMFSample* sample);
    winrt::com_ptr<IMFSample> ConvertLocked(int64_t captureUs);

    Config config_;
    PacketCallback onPacket_;
    std::wstring name_;
    bool hardware_ = false;

    winrt::com_ptr<ID3D11Device> device_;
    winrt::com_ptr<ID3D11DeviceContext> context_;
    winrt::com_ptr<ID3D11VideoDevice> videoDevice_;
    winrt::com_ptr<ID3D11VideoContext> videoContext_;
    winrt::com_ptr<ID3D11VideoProcessorEnumerator> processorEnum_;
    winrt::com_ptr<ID3D11VideoProcessor> processor_;
    winrt::com_ptr<ID3D11Texture2D> staging_;  // última captura: se repite si hace falta un fotograma clave con la pantalla quieta
    winrt::com_ptr<ID3D11VideoProcessorInputView> inputView_;
    uint32_t inWidth_ = 0;
    uint32_t inHeight_ = 0;

    struct Surface {
        winrt::com_ptr<ID3D11Texture2D> texture;
        winrt::com_ptr<ID3D11VideoProcessorOutputView> view;
        /** Compartido con el aviso de liberación, que puede llegar después de destruir la superficie. */
        std::shared_ptr<std::atomic<bool>> busy = std::make_shared<std::atomic<bool>>(false);
    };
    std::vector<std::unique_ptr<Surface>> surfaces_;
    winrt::com_ptr<ID3D11Texture2D> readback_;  // solo codificador por software

    winrt::com_ptr<IMFDXGIDeviceManager> manager_;
    UINT resetToken_ = 0;
    winrt::com_ptr<IMFTransform> transform_;
    winrt::com_ptr<IMFMediaEventGenerator> events_;
    DWORD inputId_ = 0;
    DWORD outputId_ = 0;
    std::vector<uint8_t> sequenceHeader_;

    std::mutex mutex_;
    winrt::com_ptr<IMFSample> pending_;
    int needInput_ = 0;
    bool hasFrame_ = false;
    std::atomic<bool> forceKeyframe_{true};
    std::atomic<bool> running_{false};
    std::atomic<uint32_t> encodedFrames_{0};
    std::atomic<uint32_t> currentKbps_{0};
    std::thread eventThread_;
};

}  // namespace senda
