// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#include "Common.h"

#include <shlobj.h>

#include <cstdarg>
#include <share.h>
#include <cstdio>
#include <mutex>

namespace sirga {

std::string ToUtf8(const std::wstring& text) {
    if (text.empty()) return {};
    int size = WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    std::string result(size, '\0');
    WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), result.data(), size, nullptr, nullptr);
    return result;
}

std::wstring FromUtf8(const std::string& text) {
    if (text.empty()) return {};
    int size = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
    std::wstring result(size, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), result.data(), size);
    return result;
}

void Log(const wchar_t* format, ...) {
    wchar_t message[1024];
    va_list args;
    va_start(args, format);
    _vsnwprintf_s(message, _TRUNCATE, format, args);
    va_end(args);

    SYSTEMTIME now;
    GetLocalTime(&now);
    wchar_t line[1100];
    _snwprintf_s(line, _TRUNCATE, L"%02u:%02u:%02u.%03u %s\r\n", now.wHour, now.wMinute, now.wSecond, now.wMilliseconds, message);
    OutputDebugStringW(line);

    static std::mutex mutex;
    std::lock_guard lock(mutex);
    static FILE* file = [] {
        FILE* f = nullptr;
        wchar_t* folder = nullptr;
        if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &folder))) {
            std::wstring dir = std::wstring(folder) + L"\\SirgaStudioPC";
            CreateDirectoryW(dir.c_str(), nullptr);
            // Compartido: se puede leer mientras la app está abierta
            f = _wfsopen((dir + L"\\registro.txt").c_str(), L"w, ccs=UTF-8", _SH_DENYNO);
        }
        CoTaskMemFree(folder);
        return f;
    }();
    if (file) {
        fputws(line, file);
        fflush(file);
    }
}

}  // namespace sirga
