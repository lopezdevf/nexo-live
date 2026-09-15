// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#include "Devices.h"

#include <mfapi.h>
#include <mfidl.h>
#include <mmdeviceapi.h>
#include <propkeydef.h>
#include <propsys.h>
// Necesita las definiciones de PROPERTYKEY de las cabeceras anteriores
#include <functiondiscoverykeys_devpkey.h>

namespace sirga {
namespace {

void AddCameras(std::vector<PcDevice>& out) {
    winrt::com_ptr<IMFAttributes> attributes;
    if (FAILED(MFCreateAttributes(attributes.put(), 1))) return;
    attributes->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
    IMFActivate** devices = nullptr;
    UINT32 count = 0;
    if (FAILED(MFEnumDeviceSources(attributes.get(), &devices, &count))) return;
    for (UINT32 i = 0; i < count; i++) {
        wchar_t* name = nullptr;
        wchar_t* link = nullptr;
        UINT32 length = 0;
        if (SUCCEEDED(devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &length)) &&
            SUCCEEDED(devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK, &link, &length))) {
            out.push_back({DeviceKind::Camera, link, name});
        }
        CoTaskMemFree(name);
        CoTaskMemFree(link);
        devices[i]->Release();
    }
    CoTaskMemFree(devices);
}

void AddMicrophones(std::vector<PcDevice>& out) {
    try {
        auto enumerator = winrt::create_instance<IMMDeviceEnumerator>(__uuidof(MMDeviceEnumerator));
        winrt::com_ptr<IMMDeviceCollection> collection;
        if (FAILED(enumerator->EnumAudioEndpoints(eCapture, DEVICE_STATE_ACTIVE, collection.put()))) return;
        UINT count = 0;
        collection->GetCount(&count);
        for (UINT i = 0; i < count; i++) {
            winrt::com_ptr<IMMDevice> device;
            wchar_t* id = nullptr;
            if (FAILED(collection->Item(i, device.put())) || FAILED(device->GetId(&id))) continue;
            std::wstring name = L"Micrófono";
            winrt::com_ptr<IPropertyStore> properties;
            if (SUCCEEDED(device->OpenPropertyStore(STGM_READ, properties.put()))) {
                PROPVARIANT value;
                PropVariantInit(&value);
                if (SUCCEEDED(properties->GetValue(PKEY_Device_FriendlyName, &value)) && value.vt == VT_LPWSTR) name = value.pwszVal;
                PropVariantClear(&value);
            }
            out.push_back({DeviceKind::Microphone, id, name});
            CoTaskMemFree(id);
        }
    } catch (const winrt::hresult_error&) {
    }
}

}  // namespace

std::vector<PcDevice> EnumerateDevices() {
    std::vector<PcDevice> devices;
    AddCameras(devices);
    AddMicrophones(devices);
    return devices;
}

}  // namespace sirga
