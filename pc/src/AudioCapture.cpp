// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#include "AudioCapture.h"

#include <audioclient.h>
#include <avrt.h>
#include <mmdeviceapi.h>

#include <vector>

namespace sirga {

AudioCapture::~AudioCapture() { Stop(); }

void AudioCapture::Start(Callback onAudio, const std::wstring& microphoneId) {
    Stop();
    onAudio_ = std::move(onAudio);
    microphoneId_ = microphoneId;
    running_ = true;
    thread_ = std::thread([this] { Loop(); });
}

void AudioCapture::Stop() {
    running_ = false;
    if (thread_.joinable()) thread_.join();
}

void AudioCapture::Loop() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    DWORD taskIndex = 0;
    HANDLE task = AvSetMmThreadCharacteristicsW(L"Pro Audio", &taskIndex);
    std::vector<int16_t> silence;

    while (running_) {
        winrt::com_ptr<IMMDeviceEnumerator> enumerator;
        winrt::com_ptr<IMMDevice> device;
        winrt::com_ptr<IAudioClient> client;
        winrt::com_ptr<IAudioCaptureClient> capture;
        wchar_t* deviceId = nullptr;
        try {
            enumerator = winrt::create_instance<IMMDeviceEnumerator>(__uuidof(MMDeviceEnumerator));
            bool microphone = !microphoneId_.empty();
            if (microphone) {
                winrt::check_hresult(enumerator->GetDevice(microphoneId_.c_str(), device.put()));
            } else {
                winrt::check_hresult(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, device.put()));
            }
            device->GetId(&deviceId);
            winrt::check_hresult(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, client.put_void()));

            WAVEFORMATEX format{};
            format.wFormatTag = WAVE_FORMAT_PCM;
            format.nChannels = kChannels;
            format.nSamplesPerSec = kSampleRate;
            format.wBitsPerSample = 16;
            format.nBlockAlign = format.nChannels * format.wBitsPerSample / 8;
            format.nAvgBytesPerSec = format.nSamplesPerSec * format.nBlockAlign;
            // Windows convierte lo que suene (44,1 kHz, 5.1, flotante…) al formato que pide el móvil
            DWORD flags = AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
            if (!microphone) flags |= AUDCLNT_STREAMFLAGS_LOOPBACK;
            winrt::check_hresult(client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 1'000'000, 0, &format, nullptr));
            winrt::check_hresult(client->GetService(__uuidof(IAudioCaptureClient), capture.put_void()));
            winrt::check_hresult(client->Start());
            Log(microphone ? L"Micrófono del PC capturado" : L"Audio del PC capturado");
        } catch (const winrt::hresult_error& e) {
            Log(L"No se pudo capturar el audio: %s", e.message().c_str());
            CoTaskMemFree(deviceId);
            for (int i = 0; i < 20 && running_; i++) Sleep(100);
            continue;
        }

        int checks = 0;
        bool restart = false;
        while (running_ && !restart) {
            Sleep(5);
            UINT32 packet = 0;
            HRESULT hr = capture->GetNextPacketSize(&packet);
            while (SUCCEEDED(hr) && packet > 0) {
                BYTE* data = nullptr;
                UINT32 frames = 0;
                DWORD flags = 0;
                UINT64 qpcPosition = 0;
                hr = capture->GetBuffer(&data, &frames, &flags, nullptr, &qpcPosition);
                if (FAILED(hr)) break;
                int64_t captureUs = qpcPosition ? static_cast<int64_t>(qpcPosition / 10) : NowUs();
                if (flags & AUDCLNT_BUFFERFLAGS_SILENT) {
                    silence.assign(static_cast<size_t>(frames) * kChannels, 0);
                    onAudio_(silence.data(), frames, captureUs);
                } else {
                    onAudio_(reinterpret_cast<const int16_t*>(data), frames, captureUs);
                }
                capture->ReleaseBuffer(frames);
                hr = capture->GetNextPacketSize(&packet);
            }
            if (hr == AUDCLNT_E_DEVICE_INVALIDATED || FAILED(hr)) restart = true;

            // Cada segundo: si cambió la salida predeterminada, se sigue a la nueva
            if (microphoneId_.empty() && ++checks >= 200) {
                checks = 0;
                winrt::com_ptr<IMMDevice> current;
                wchar_t* currentId = nullptr;
                if (SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, current.put())) && SUCCEEDED(current->GetId(&currentId))) {
                    if (deviceId && wcscmp(deviceId, currentId) != 0) restart = true;
                    CoTaskMemFree(currentId);
                }
            }
        }
        client->Stop();
        CoTaskMemFree(deviceId);
    }

    if (task) AvRevertMmThreadCharacteristics(task);
    CoUninitialize();
}

}  // namespace sirga
