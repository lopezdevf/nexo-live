// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
//
// Sirga Studio PC: ventana principal. Busca los móviles con Sirga Studio en la red, y al conectar envía
// la pantalla y el sonido del PC a la fuente «PC» del móvil.

#include "BrandMark.h"
#include "Common.h"
#include "Streamer.h"

#include <commctrl.h>
#include <mmsystem.h>
#include <dwmapi.h>
#include <shellapi.h>
#include <shlobj.h>
#include <uxtheme.h>
#include <windowsx.h>

#include <objidl.h>
#include <gdiplus.h>

#include <mfapi.h>

#include <algorithm>
#include <memory>
#include <thread>
#include <vector>

using namespace sirga;

namespace {

constexpr UINT WM_APP_STATE = WM_APP + 1;
constexpr UINT WM_APP_PHONES = WM_APP + 2;
constexpr UINT_PTR TIMER_STATS = 1;
constexpr UINT_PTR TIMER_DISCOVERY = 2;

enum ControlId : int {
    IDC_PHONES = 100,
    IDC_REFRESH,
    IDC_ADDRESS,
    IDC_CODE,
    IDC_MONITOR,
    IDC_QUALITY,
    IDC_AUDIO,
    IDC_CURSOR,
    IDC_CONNECT,
};

// Colores de la app de Android (SirgaTheme)
constexpr COLORREF kInk = RGB(0x0D, 0x11, 0x17);         // Azul noche
constexpr COLORREF kPanel = RGB(0x14, 0x1A, 0x23);
constexpr COLORREF kRaised = RGB(0x1C, 0x24, 0x30);
constexpr COLORREF kLine = RGB(0x2A, 0x34, 0x41);
constexpr COLORREF kTextHigh = RGB(0xFF, 0xFF, 0xFF);
constexpr COLORREF kTextMid = RGB(0x9B, 0xA7, 0xB8);
constexpr COLORREF kTextLow = RGB(0x5F, 0x6B, 0x7C);
constexpr COLORREF kAccent = RGB(0x00, 0x66, 0xFF);      // Azul eléctrico
constexpr COLORREF kAccentGlow = RGB(0x00, 0xA3, 0xFF);  // Cian neón
constexpr COLORREF kLive = RGB(0xFF, 0x4D, 0x5E);
constexpr COLORREF kGood = RGB(0x4A, 0xDE, 0x80);

Gdiplus::Color G(COLORREF c) { return Gdiplus::Color(255, GetRValue(c), GetGValue(c), GetBValue(c)); }

struct App {
    HWND hwnd = nullptr;
    HWND phones = nullptr, refresh = nullptr, address = nullptr, code = nullptr, monitor = nullptr, quality = nullptr;
    HWND audio = nullptr, cursor = nullptr, connect = nullptr;
    UINT dpi = 96;
    HFONT fontBody = nullptr, fontSmall = nullptr, fontLabel = nullptr, fontTitle = nullptr, fontButton = nullptr;
    HBRUSH brushInk = nullptr, brushRaised = nullptr;

    std::vector<MonitorInfo> monitors;
    std::vector<PhoneInfo> found;
    bool searching = false;
    bool audioOn = true;
    bool cursorOn = true;
    Streamer streamer;
    std::wstring status = L"Buscando móviles con Sirga Studio en la red…";
    COLORREF statusColor = kTextMid;
    std::wstring detail;
    bool autoConnect = false;
    bool forceSoftware = false;
    std::wstring settingsPath;
};

App app;

int S(int dip) { return MulDiv(dip, static_cast<int>(app.dpi), 96); }

HFONT MakeFont(int dip, int weight) {
    return CreateFontW(-S(dip), 0, 0, 0, weight, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                       DEFAULT_PITCH, L"Segoe UI");
}

void CreateFonts() {
    for (HFONT f : {app.fontBody, app.fontSmall, app.fontLabel, app.fontTitle, app.fontButton})
        if (f) DeleteObject(f);
    app.fontBody = MakeFont(14, FW_NORMAL);
    app.fontSmall = MakeFont(12, FW_NORMAL);
    app.fontLabel = MakeFont(11, FW_SEMIBOLD);
    app.fontTitle = MakeFont(22, FW_SEMIBOLD);
    app.fontButton = MakeFont(15, FW_SEMIBOLD);
    for (HWND h : {app.phones, app.refresh, app.address, app.code, app.monitor, app.quality, app.audio, app.cursor, app.connect})
        SendMessageW(h, WM_SETFONT, reinterpret_cast<WPARAM>(app.fontBody), TRUE);
    SendMessageW(app.code, WM_SETFONT, reinterpret_cast<WPARAM>(app.fontButton), TRUE);
    SendMessageW(app.phones, LB_SETITEMHEIGHT, 0, S(46));
}

// ---- Diseño --------------------------------------------------------------------------------

constexpr int kWidth = 440;
constexpr int kMargin = 24;

struct Layout {
    RECT phonesLabel, phones, refresh, addressLabel, address, codeLabel, code, monitorLabel, monitor, qualityLabel, quality, audio, cursor, connect, status;
};

RECT R(int x, int y, int w, int h) { return {S(x), S(y), S(x + w), S(y + h)}; }

Layout ComputeLayout() {
    const int inner = kWidth - kMargin * 2;
    Layout l{};
    l.phonesLabel = R(kMargin, 92, inner, 18);
    l.phones = R(kMargin, 114, inner, 140);
    l.refresh = R(kMargin, 262, 150, 32);
    l.addressLabel = R(kMargin, 310, 250, 18);
    l.address = R(kMargin, 332, 250, 34);
    l.codeLabel = R(kMargin + 266, 310, inner - 266, 18);
    l.code = R(kMargin + 266, 332, inner - 266, 34);
    l.monitorLabel = R(kMargin, 382, inner, 18);
    l.monitor = R(kMargin, 404, inner, 300);
    l.qualityLabel = R(kMargin, 450, inner, 18);
    l.quality = R(kMargin, 472, inner, 300);
    l.audio = R(kMargin, 520, inner, 30);
    l.cursor = R(kMargin, 554, inner, 30);
    l.connect = R(kMargin, 604, inner, 46);
    l.status = R(kMargin, 662, inner, 64);
    return l;
}

void Place(HWND h, const RECT& r) { SetWindowPos(h, nullptr, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOZORDER | SWP_NOACTIVATE); }

void ApplyLayout() {
    Layout l = ComputeLayout();
    Place(app.phones, l.phones);
    Place(app.refresh, l.refresh);
    Place(app.address, l.address);
    Place(app.code, l.code);
    Place(app.monitor, l.monitor);
    Place(app.quality, l.quality);
    Place(app.audio, l.audio);
    Place(app.cursor, l.cursor);
    Place(app.connect, l.connect);
    // Texto centrado verticalmente en los cuadros de edición
    for (HWND edit : {app.address, app.code}) {
        RECT rc;
        GetClientRect(edit, &rc);
        int pad = S(8);
        rc.left += S(10);
        rc.right -= S(10);
        rc.top += pad;
        rc.bottom -= S(4);
        SendMessageW(edit, EM_SETRECT, 0, reinterpret_cast<LPARAM>(&rc));
    }
    InvalidateRect(app.hwnd, nullptr, TRUE);
}

void ResizeWindow() {
    RECT rc = R(0, 0, kWidth, 740);
    AdjustWindowRectExForDpi(&rc, WS_OVERLAPPEDWINDOW & ~(WS_THICKFRAME | WS_MAXIMIZEBOX), FALSE, 0, app.dpi);
    int width = rc.right - rc.left;
    int height = rc.bottom - rc.top;
    // Que la ventana entera quepa sobre la barra de tareas
    RECT window;
    GetWindowRect(app.hwnd, &window);
    MONITORINFO monitor{sizeof(monitor)};
    GetMonitorInfoW(MonitorFromWindow(app.hwnd, MONITOR_DEFAULTTONEAREST), &monitor);
    const RECT& work = monitor.rcWork;
    int x = std::clamp(static_cast<int>(window.left), static_cast<int>(work.left), std::max(static_cast<int>(work.left), static_cast<int>(work.right) - width));
    int y = std::clamp(static_cast<int>(window.top), static_cast<int>(work.top), std::max(static_cast<int>(work.top), static_cast<int>(work.bottom) - height));
    SetWindowPos(app.hwnd, nullptr, x, y, width, height, SWP_NOZORDER | SWP_NOACTIVATE);
}

// ---- Ajustes -------------------------------------------------------------------------------

void LoadSettings() {
    wchar_t* folder = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_RoamingAppData, 0, nullptr, &folder))) {
        std::wstring dir = std::wstring(folder) + L"\\SirgaStudioPC";
        CreateDirectoryW(dir.c_str(), nullptr);
        app.settingsPath = dir + L"\\ajustes.ini";
    }
    CoTaskMemFree(folder);
    wchar_t buffer[256];
    GetPrivateProfileStringW(L"Sirga", L"direccion", L"", buffer, ARRAYSIZE(buffer), app.settingsPath.c_str());
    SetWindowTextW(app.address, buffer);
    GetPrivateProfileStringW(L"Sirga", L"codigo", L"", buffer, ARRAYSIZE(buffer), app.settingsPath.c_str());
    SetWindowTextW(app.code, buffer);
    int quality = GetPrivateProfileIntW(L"Sirga", L"calidad", 0, app.settingsPath.c_str());
    ComboBox_SetCurSel(app.quality, std::clamp(quality, 0, static_cast<int>(std::size(kQualities)) - 1));
    int monitor = GetPrivateProfileIntW(L"Sirga", L"pantalla", 0, app.settingsPath.c_str());
    ComboBox_SetCurSel(app.monitor, std::clamp(monitor, 0, std::max(0, static_cast<int>(app.monitors.size()) - 1)));
    app.audioOn = GetPrivateProfileIntW(L"Sirga", L"audio", 1, app.settingsPath.c_str()) != 0;
    app.cursorOn = GetPrivateProfileIntW(L"Sirga", L"cursor", 1, app.settingsPath.c_str()) != 0;
}

void SaveSettings() {
    if (app.settingsPath.empty()) return;
    wchar_t buffer[256];
    GetWindowTextW(app.address, buffer, ARRAYSIZE(buffer));
    WritePrivateProfileStringW(L"Sirga", L"direccion", buffer, app.settingsPath.c_str());
    GetWindowTextW(app.code, buffer, ARRAYSIZE(buffer));
    WritePrivateProfileStringW(L"Sirga", L"codigo", buffer, app.settingsPath.c_str());
    WritePrivateProfileStringW(L"Sirga", L"calidad", std::to_wstring(ComboBox_GetCurSel(app.quality)).c_str(), app.settingsPath.c_str());
    WritePrivateProfileStringW(L"Sirga", L"pantalla", std::to_wstring(ComboBox_GetCurSel(app.monitor)).c_str(), app.settingsPath.c_str());
    WritePrivateProfileStringW(L"Sirga", L"audio", app.audioOn ? L"1" : L"0", app.settingsPath.c_str());
    WritePrivateProfileStringW(L"Sirga", L"cursor", app.cursorOn ? L"1" : L"0", app.settingsPath.c_str());
}

// ---- Estado --------------------------------------------------------------------------------

bool Busy() {
    auto state = app.streamer.State();
    return state == StreamState::Connecting || state == StreamState::Streaming || state == StreamState::Retrying;
}

void SetStatus(const std::wstring& text, COLORREF color, const std::wstring& detail = {}) {
    app.status = text;
    app.statusColor = color;
    app.detail = detail;
    RECT r = ComputeLayout().status;
    InvalidateRect(app.hwnd, &r, TRUE);
}

void UpdateControls() {
    bool busy = Busy();
    for (HWND h : {app.phones, app.refresh, app.address, app.code, app.monitor, app.quality, app.audio, app.cursor}) EnableWindow(h, !busy);
    InvalidateRect(app.connect, nullptr, TRUE);
}

void OnStateChanged() {
    switch (app.streamer.State()) {
        case StreamState::Idle:
            SetStatus(app.found.empty() ? L"Buscando móviles con Sirga Studio en la red…" : L"Elige el móvil y pulsa Conectar.", kTextMid);
            break;
        case StreamState::Connecting:
            SetStatus(L"Conectando…", kTextMid);
            break;
        case StreamState::Streaming:
            SetStatus(L"Enviando a «" + app.streamer.Device() + L"»", kGood, L"Preparando…");
            break;
        case StreamState::Retrying:
            SetStatus(app.streamer.LastError(), RGB(0xFF, 0x9F, 0x43), L"Se reintenta cada 2 segundos. Pulsa Cancelar para parar.");
            break;
        case StreamState::WrongCode:
        case StreamState::Failed:
            SetStatus(app.streamer.LastError(), kLive);
            break;
    }
    UpdateControls();
}

// ---- Búsqueda de móviles ---------------------------------------------------------------------

void StartDiscovery() {
    if (app.searching || Busy()) return;
    app.searching = true;
    HWND hwnd = app.hwnd;
    std::thread([hwnd] {
        auto phones = new std::vector<PhoneInfo>(DiscoverPhones(2500));
        if (!PostMessageW(hwnd, WM_APP_PHONES, 0, reinterpret_cast<LPARAM>(phones))) delete phones;
    }).detach();
}

std::wstring AddressOf(const PhoneInfo& phone) { return FromUtf8(phone.ip) + L":" + std::to_wstring(phone.port); }

void OnPhones(std::vector<PhoneInfo>* result) {
    app.searching = false;
    std::unique_ptr<std::vector<PhoneInfo>> phones(result);
    wchar_t current[128];
    GetWindowTextW(app.address, current, ARRAYSIZE(current));
    int selected = ListBox_GetCurSel(app.phones);
    std::wstring selectedAddress = selected >= 0 && selected < static_cast<int>(app.found.size()) ? AddressOf(app.found[selected]) : L"";

    app.found = std::move(*phones);
    ListBox_ResetContent(app.phones);
    for (size_t i = 0; i < app.found.size(); i++) {
        ListBox_AddString(app.phones, L"");
        std::wstring address = AddressOf(app.found[i]);
        if (address == selectedAddress || (selectedAddress.empty() && address == current)) ListBox_SetCurSel(app.phones, static_cast<int>(i));
    }
    // Un solo móvil encontrado y ninguna dirección escrita: se elige solo
    if (ListBox_GetCurSel(app.phones) < 0 && app.found.size() == 1 && current[0] == 0) {
        ListBox_SetCurSel(app.phones, 0);
        SetWindowTextW(app.address, AddressOf(app.found[0]).c_str());
    }
    ShowWindow(app.phones, app.found.empty() ? SW_HIDE : SW_SHOW);
    RECT r = ComputeLayout().phones;
    InvalidateRect(app.hwnd, &r, TRUE);
    if (!Busy() && app.streamer.State() == StreamState::Idle) OnStateChanged();
}

// ---- Conectar ------------------------------------------------------------------------------

void ToggleConnection() {
    if (Busy()) {
        app.streamer.RequestStop();
        SetStatus(L"Desconectando…", kTextMid);
        return;
    }
    wchar_t text[128];
    GetWindowTextW(app.address, text, ARRAYSIZE(text));
    std::wstring address = text;
    address.erase(std::remove_if(address.begin(), address.end(), iswspace), address.end());
    if (address.empty()) {
        SetStatus(L"Elige un móvil de la lista o escribe su dirección.", kLive, L"La dirección aparece en las propiedades de la fuente PC del móvil.");
        return;
    }
    uint16_t port = 9000;
    if (auto colon = address.rfind(L':'); colon != std::wstring::npos) {
        port = static_cast<uint16_t>(_wtoi(address.substr(colon + 1).c_str()));
        address = address.substr(0, colon);
    }
    GetWindowTextW(app.code, text, ARRAYSIZE(text));
    std::wstring code = text;
    if (code.size() != 4 || !std::all_of(code.begin(), code.end(), iswdigit)) {
        SetStatus(L"Escribe el código de 4 cifras.", kLive, L"Está en el móvil: propiedades de la fuente PC.");
        SetFocus(app.code);
        return;
    }
    int monitor = ComboBox_GetCurSel(app.monitor);
    if (monitor < 0 || monitor >= static_cast<int>(app.monitors.size())) return;

    StreamSettings settings;
    settings.ip = ToUtf8(address);
    settings.port = port ? port : 9000;
    settings.code = static_cast<uint16_t>(_wtoi(code.c_str()));
    settings.monitor = app.monitors[monitor].handle;
    settings.quality = static_cast<size_t>(std::max(0, ComboBox_GetCurSel(app.quality)));
    settings.audio = app.audioOn;
    settings.cursor = app.cursorOn;
    settings.forceSoftware = app.forceSoftware;
    SaveSettings();
    app.streamer.Start(settings);
    UpdateControls();
}

// ---- Dibujo --------------------------------------------------------------------------------

void RoundRect(Gdiplus::Graphics& g, const RECT& r, int radius, COLORREF fill, COLORREF border = CLR_INVALID) {
    Gdiplus::GraphicsPath path;
    int d = radius * 2;
    int x = r.left, y = r.top, w = r.right - r.left - 1, h = r.bottom - r.top - 1;
    path.AddArc(x, y, d, d, 180, 90);
    path.AddArc(x + w - d, y, d, d, 270, 90);
    path.AddArc(x + w - d, y + h - d, d, d, 0, 90);
    path.AddArc(x, y + h - d, d, d, 90, 90);
    path.CloseFigure();
    Gdiplus::SolidBrush brush(G(fill));
    g.FillPath(&brush, &path);
    if (border != CLR_INVALID) {
        Gdiplus::Pen pen(G(border), static_cast<Gdiplus::REAL>(S(1)));
        g.DrawPath(&pen, &path);
    }
}

void DrawText(HDC dc, const std::wstring& text, RECT r, HFONT font, COLORREF color, UINT format = DT_LEFT | DT_TOP | DT_WORDBREAK) {
    HGDIOBJ old = SelectObject(dc, font);
    SetTextColor(dc, color);
    SetBkMode(dc, TRANSPARENT);
    ::DrawTextW(dc, text.c_str(), -1, &r, format | DT_NOPREFIX);
    SelectObject(dc, old);
}

void PaintWindow(HDC dc) {
    RECT client;
    GetClientRect(app.hwnd, &client);
    FillRect(dc, &client, app.brushInk);
    Layout l = ComputeLayout();

    // Marca: el isotipo de Sirga Studio, del cian neón al azul eléctrico como el icono de la app
    {
        Gdiplus::Graphics g(dc);
        g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
        const Gdiplus::REAL height = static_cast<Gdiplus::REAL>(S(38));
        const Gdiplus::REAL scale = height / brand::kMarkHeight;
        const Gdiplus::REAL x0 = static_cast<Gdiplus::REAL>(S(kMargin)) + (static_cast<Gdiplus::REAL>(S(32)) - brand::kMarkWidth * scale) / 2;
        const Gdiplus::REAL y0 = static_cast<Gdiplus::REAL>(S(22));
        Gdiplus::GraphicsPath path(Gdiplus::FillModeAlternate);
        const brand::MarkPoint* point = brand::kMarkPoints;
        for (int size : brand::kMarkLoopSizes) {
            std::vector<Gdiplus::PointF> pts;
            pts.reserve(size);
            for (int i = 0; i < size; i++, point++) pts.push_back({x0 + point->x * scale, y0 + point->y * scale});
            path.StartFigure();
            path.AddBeziers(pts.data(), size);
            path.CloseFigure();
        }
        Gdiplus::LinearGradientBrush brush(Gdiplus::PointF(x0 + brand::kMarkWidth * scale, y0), Gdiplus::PointF(x0, y0 + height), G(kAccentGlow), G(kAccent));
        const Gdiplus::REAL positions[] = {0.0f, 0.55f, 1.0f};
        const Gdiplus::Color colors[] = {G(kAccentGlow), G(kAccent), G(kAccent)};
        brush.SetInterpolationColors(colors, positions, 3);
        g.FillPath(&brush, &path);
    }
    DrawText(dc, L"Sirga Studio PC", R(kMargin + 44, 18, 300, 32), app.fontTitle, kTextHigh, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    DrawText(dc, L"Envía la pantalla y el sonido de este PC a tu móvil.", R(kMargin + 44, 50, 340, 22), app.fontSmall, kTextMid,
             DT_LEFT | DT_SINGLELINE);

    DrawText(dc, L"MÓVIL", l.phonesLabel, app.fontLabel, kTextLow, DT_LEFT | DT_SINGLELINE);
    if (app.found.empty()) {
        Gdiplus::Graphics g(dc);
        g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
        RoundRect(g, l.phones, S(10), kPanel, kLine);
        RECT text = l.phones;
        InflateRect(&text, -S(16), -S(14));
        DrawText(dc,
                 app.searching ? L"Buscando…"
                               : L"No aparece ningún móvil.\nEn Sirga Studio añade la fuente «PC» y conecta el móvil a la misma WiFi que este PC "
                                 L"(o escribe su dirección abajo).",
                 text, app.fontSmall, kTextMid);
    }
    DrawText(dc, L"DIRECCIÓN (SI NO APARECE)", l.addressLabel, app.fontLabel, kTextLow, DT_LEFT | DT_SINGLELINE);
    DrawText(dc, L"CÓDIGO", l.codeLabel, app.fontLabel, kTextLow, DT_LEFT | DT_SINGLELINE);
    DrawText(dc, L"PANTALLA", l.monitorLabel, app.fontLabel, kTextLow, DT_LEFT | DT_SINGLELINE);
    DrawText(dc, L"CALIDAD", l.qualityLabel, app.fontLabel, kTextLow, DT_LEFT | DT_SINGLELINE);

    RECT status = l.status;
    DrawText(dc, app.status, status, app.fontBody, app.statusColor);
    RECT detail = status;
    RECT measure = status;
    HGDIOBJ old = SelectObject(dc, app.fontBody);
    ::DrawTextW(dc, app.status.c_str(), -1, &measure, DT_CALCRECT | DT_WORDBREAK);
    SelectObject(dc, old);
    detail.top = measure.bottom + S(2);
    DrawText(dc, app.detail, detail, app.fontSmall, kTextMid);
}

void DrawToggle(const DRAWITEMSTRUCT* item, bool on, const wchar_t* label) {
    HDC dc = item->hDC;
    RECT r = item->rcItem;
    FillRect(dc, &r, app.brushInk);
    bool enabled = !(item->itemState & ODS_DISABLED);
    Gdiplus::Graphics g(dc);
    g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
    int h = S(20), w = S(36);
    int top = r.top + (r.bottom - r.top - h) / 2;
    RECT track{r.left, top, r.left + w, top + h};
    RoundRect(g, track, h / 2, on ? (enabled ? kAccent : kTextLow) : kLine);
    int knob = h - S(6);
    int knobX = on ? track.right - S(3) - knob : track.left + S(3);
    Gdiplus::SolidBrush brush(G(on ? kTextHigh : kTextMid));
    g.FillEllipse(&brush, knobX, top + S(3), knob, knob);
    RECT text = r;
    text.left += w + S(12);
    DrawText(dc, label, text, app.fontBody, enabled ? kTextHigh : kTextLow, DT_LEFT | DT_VCENTER | DT_SINGLELINE);
}

void DrawButton(const DRAWITEMSTRUCT* item) {
    HDC dc = item->hDC;
    RECT r = item->rcItem;
    FillRect(dc, &r, app.brushInk);
    Gdiplus::Graphics g(dc);
    g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
    bool pressed = (item->itemState & ODS_SELECTED) != 0;
    bool enabled = !(item->itemState & ODS_DISABLED);
    if (item->CtlID == IDC_CONNECT) {
        auto state = app.streamer.State();
        bool busy = Busy();
        COLORREF fill = busy ? (state == StreamState::Streaming ? kLive : kRaised) : kAccent;
        if (pressed) fill = RGB(GetRValue(fill) * 85 / 100, GetGValue(fill) * 85 / 100, GetBValue(fill) * 85 / 100);
        RoundRect(g, r, S(10), fill, busy && state != StreamState::Streaming ? kLine : CLR_INVALID);
        const wchar_t* label = state == StreamState::Streaming ? L"Desconectar" : busy ? L"Cancelar" : L"Conectar";
        DrawText(dc, label, r, app.fontButton, kTextHigh, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    } else {
        RoundRect(g, r, S(8), pressed ? kLine : kRaised, kLine);
        DrawText(dc, L"Buscar de nuevo", r, app.fontBody, enabled ? kTextHigh : kTextLow, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    }
}

void DrawPhone(const DRAWITEMSTRUCT* item) {
    if (item->itemID == static_cast<UINT>(-1) || item->itemID >= app.found.size()) return;
    const PhoneInfo& phone = app.found[item->itemID];
    HDC dc = item->hDC;
    RECT r = item->rcItem;
    bool selected = (item->itemState & ODS_SELECTED) != 0;
    HBRUSH panel = CreateSolidBrush(selected ? kRaised : kPanel);
    FillRect(dc, &r, panel);
    DeleteObject(panel);
    if (selected) {
        RECT bar{r.left, r.top, r.left + S(4), r.bottom};
        HBRUSH accent = CreateSolidBrush(kAccent);
        FillRect(dc, &bar, accent);
        DeleteObject(accent);
    }
    RECT name{r.left + S(16), r.top + S(5), r.right - S(10), r.top + S(26)};
    DrawText(dc, phone.device, name, app.fontBody, kTextHigh, DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS);
    RECT sub{r.left + S(16), r.top + S(25), r.right - S(10), r.bottom - S(3)};
    DrawText(dc, L"Fuente «" + phone.source + L"» · " + AddressOf(phone), sub, app.fontSmall, kTextMid, DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS);
}

// ---- Ventana -------------------------------------------------------------------------------

HWND MakeControl(const wchar_t* cls, const wchar_t* text, DWORD style, int id, DWORD exStyle = 0) {
    return CreateWindowExW(exStyle, cls, text, WS_CHILD | WS_VISIBLE | style, 0, 0, 10, 10, app.hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),
                           GetModuleHandleW(nullptr), nullptr);
}

void CreateControls() {
    app.phones = MakeControl(WC_LISTBOXW, L"", LBS_OWNERDRAWFIXED | LBS_NOTIFY | LBS_NOINTEGRALHEIGHT | WS_VSCROLL | WS_TABSTOP, IDC_PHONES);
    app.refresh = MakeControl(WC_BUTTONW, L"Buscar de nuevo", BS_OWNERDRAW | WS_TABSTOP, IDC_REFRESH);
    app.address = MakeControl(WC_EDITW, L"", ES_AUTOHSCROLL | ES_MULTILINE | WS_TABSTOP, IDC_ADDRESS);
    app.code = MakeControl(WC_EDITW, L"", ES_NUMBER | ES_CENTER | ES_MULTILINE | WS_TABSTOP, IDC_CODE);
    SendMessageW(app.code, EM_SETLIMITTEXT, 4, 0);
    SendMessageW(app.address, EM_SETCUEBANNER, TRUE, reinterpret_cast<LPARAM>(L"192.168.1.10:9000"));
    app.monitor = MakeControl(WC_COMBOBOXW, L"", CBS_DROPDOWNLIST | WS_VSCROLL | WS_TABSTOP, IDC_MONITOR);
    app.quality = MakeControl(WC_COMBOBOXW, L"", CBS_DROPDOWNLIST | WS_VSCROLL | WS_TABSTOP, IDC_QUALITY);
    app.audio = MakeControl(WC_BUTTONW, L"", BS_OWNERDRAW | WS_TABSTOP, IDC_AUDIO);
    app.cursor = MakeControl(WC_BUTTONW, L"", BS_OWNERDRAW | WS_TABSTOP, IDC_CURSOR);
    app.connect = MakeControl(WC_BUTTONW, L"Conectar", BS_OWNERDRAW | WS_TABSTOP, IDC_CONNECT);

    for (HWND h : {app.monitor, app.quality, app.address, app.code}) SetWindowTheme(h, L"DarkMode_CFD", nullptr);
    SetWindowTheme(app.phones, L"DarkMode_Explorer", nullptr);

    app.monitors = EnumerateMonitors();
    for (size_t i = 0; i < app.monitors.size(); i++) {
        const auto& m = app.monitors[i];
        std::wstring label = L"Pantalla " + std::to_wstring(i + 1) + L" · " + std::to_wstring(m.rect.right - m.rect.left) + L"×" +
                             std::to_wstring(m.rect.bottom - m.rect.top) + (m.primary ? L" (principal)" : L"");
        ComboBox_AddString(app.monitor, label.c_str());
    }
    for (const auto& q : kQualities) ComboBox_AddString(app.quality, q.label);
    ShowWindow(app.phones, SW_HIDE);
}

void OnStatsTimer() {
    if (app.streamer.State() != StreamState::Streaming) return;
    uint32_t frames = 0;
    uint64_t bytes = 0;
    int latency = -1;
    uint32_t bitrate = 0;
    uint32_t keyframes = 0;
    uint64_t encoded = 0;
    app.streamer.TakeStats(frames, bytes, latency, bitrate, keyframes, encoded);
    wchar_t detail[300];
    std::wstring latencyText = latency >= 0 ? L" · retraso " + std::to_wstring(latency) + L" ms" : L"";
    if (bitrate > 0 && bitrate < kQualities[std::max(0, ComboBox_GetCurSel(app.quality))].bitrateKbps) latencyText += L" · red lenta: calidad ajustada";
    _snwprintf_s(detail, _TRUNCATE, L"%u fps · %.1f Mbps%s\n%s", frames, bytes * 8 / 1'000'000.0, latencyText.c_str(),
                 app.streamer.EncoderName().c_str());
    SetStatus(app.status, kGood, detail);
    static int ticks = 0;
    static uint32_t windowKeyframes = 0;
    static uint64_t windowBytes = 0;
    static uint64_t windowEncoded = 0;
    windowKeyframes += keyframes;
    windowBytes += bytes;
    windowEncoded += encoded;
    if (++ticks % 5 == 0) {
        Log(L"Estadísticas: %u fps, %.1f Mbps enviados (media 5 s %.1f), %.1f Mbps codificados (media 5 s), bitrate %u kbps, retraso %d ms, %u fotogramas clave en 5 s",
            frames, bytes * 8 / 1'000'000.0, windowBytes * 8 / 5'000'000.0, windowEncoded * 8 / 5'000'000.0, bitrate, latency, windowKeyframes);
        windowKeyframes = 0;
        windowBytes = 0;
        windowEncoded = 0;
    }
}

LRESULT CALLBACK WindowProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_CREATE: {
            app.hwnd = hwnd;
            app.dpi = GetDpiForWindow(hwnd);
            BOOL dark = TRUE;
            DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, &dark, sizeof(dark));
            COLORREF caption = kInk;
            DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR */, &caption, sizeof(caption));
            app.brushInk = CreateSolidBrush(kInk);
            app.brushRaised = CreateSolidBrush(kRaised);
            CreateControls();
            CreateFonts();
            ResizeWindow();
            ApplyLayout();
            LoadSettings();
            app.streamer.onStateChanged = [hwnd] { PostMessageW(hwnd, WM_APP_STATE, 0, 0); };
            SetTimer(hwnd, TIMER_STATS, 1000, nullptr);
            SetTimer(hwnd, TIMER_DISCOVERY, 4000, nullptr);
            StartDiscovery();
            if (app.autoConnect) PostMessageW(hwnd, WM_COMMAND, MAKEWPARAM(IDC_CONNECT, BN_CLICKED), 0);
            return 0;
        }
        case WM_DPICHANGED: {
            app.dpi = HIWORD(wParam);
            auto suggested = reinterpret_cast<RECT*>(lParam);
            SetWindowPos(hwnd, nullptr, suggested->left, suggested->top, suggested->right - suggested->left, suggested->bottom - suggested->top,
                         SWP_NOZORDER | SWP_NOACTIVATE);
            CreateFonts();
            ResizeWindow();
            ApplyLayout();
            return 0;
        }
        case WM_ERASEBKGND:
            return 1;
        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC dc = BeginPaint(hwnd, &ps);
            RECT client;
            GetClientRect(hwnd, &client);
            // Doble búfer: sin parpadeo al actualizar el estado cada segundo
            HDC mem = CreateCompatibleDC(dc);
            HBITMAP bitmap = CreateCompatibleBitmap(dc, client.right, client.bottom);
            HGDIOBJ oldBitmap = SelectObject(mem, bitmap);
            PaintWindow(mem);
            BitBlt(dc, 0, 0, client.right, client.bottom, mem, 0, 0, SRCCOPY);
            SelectObject(mem, oldBitmap);
            DeleteObject(bitmap);
            DeleteDC(mem);
            EndPaint(hwnd, &ps);
            return 0;
        }
        case WM_CTLCOLOREDIT:
        case WM_CTLCOLORLISTBOX:
        case WM_CTLCOLORSTATIC: {
            HDC dc = reinterpret_cast<HDC>(wParam);
            SetTextColor(dc, kTextHigh);
            SetBkColor(dc, kRaised);
            return reinterpret_cast<LRESULT>(app.brushRaised);
        }
        case WM_MEASUREITEM: {
            auto measure = reinterpret_cast<MEASUREITEMSTRUCT*>(lParam);
            if (measure->CtlID == IDC_PHONES) measure->itemHeight = S(46);
            return TRUE;
        }
        case WM_DRAWITEM: {
            auto item = reinterpret_cast<const DRAWITEMSTRUCT*>(lParam);
            if (item->CtlID == IDC_PHONES) DrawPhone(item);
            else if (item->CtlID == IDC_AUDIO) DrawToggle(item, app.audioOn, L"Enviar el sonido del PC");
            else if (item->CtlID == IDC_CURSOR) DrawToggle(item, app.cursorOn, L"Mostrar el puntero del ratón");
            else DrawButton(item);
            return TRUE;
        }
        case WM_COMMAND: {
            int id = LOWORD(wParam);
            int code = HIWORD(wParam);
            if (id == IDC_CONNECT && code == BN_CLICKED) ToggleConnection();
            if (id == IDC_REFRESH && code == BN_CLICKED) {
                StartDiscovery();
                RECT r = ComputeLayout().phones;
                InvalidateRect(hwnd, &r, TRUE);
            }
            if (id == IDC_AUDIO && code == BN_CLICKED) {
                app.audioOn = !app.audioOn;
                InvalidateRect(app.audio, nullptr, TRUE);
            }
            if (id == IDC_CURSOR && code == BN_CLICKED) {
                app.cursorOn = !app.cursorOn;
                InvalidateRect(app.cursor, nullptr, TRUE);
            }
            if (id == IDC_PHONES && code == LBN_SELCHANGE) {
                int index = ListBox_GetCurSel(app.phones);
                if (index >= 0 && index < static_cast<int>(app.found.size())) SetWindowTextW(app.address, AddressOf(app.found[index]).c_str());
                SetFocus(app.code);
            }
            if (id == IDC_PHONES && code == LBN_DBLCLK) ToggleConnection();
            return 0;
        }
        case WM_TIMER:
            if (wParam == TIMER_STATS) OnStatsTimer();
            if (wParam == TIMER_DISCOVERY && !Busy()) StartDiscovery();
            return 0;
        case WM_APP_STATE:
            OnStateChanged();
            return 0;
        case WM_APP_PHONES:
            OnPhones(reinterpret_cast<std::vector<PhoneInfo>*>(lParam));
            return 0;
        case WM_CLOSE:
            SaveSettings();
            ShowWindow(hwnd, SW_HIDE);
            app.streamer.onStateChanged = nullptr;
            app.streamer.Stop();
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

/** `--connect IP:PUERTO --code 1234 [--quality N] [--monitor N] [--no-audio] [--software]`: conecta al abrir (útil en accesos directos). */
void ParseCommandLine() {
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (!argv) return;
    for (int i = 1; i < argc; i++) {
        std::wstring arg = argv[i];
        auto next = [&]() -> std::wstring { return i + 1 < argc ? argv[++i] : L""; };
        if (arg == L"--connect") {
            std::wstring address = next();
            WritePrivateProfileStringW(L"Sirga", L"direccion", address.c_str(), app.settingsPath.c_str());
            app.autoConnect = true;
        } else if (arg == L"--code") {
            WritePrivateProfileStringW(L"Sirga", L"codigo", next().c_str(), app.settingsPath.c_str());
        } else if (arg == L"--quality") {
            WritePrivateProfileStringW(L"Sirga", L"calidad", next().c_str(), app.settingsPath.c_str());
        } else if (arg == L"--monitor") {
            WritePrivateProfileStringW(L"Sirga", L"pantalla", next().c_str(), app.settingsPath.c_str());
        } else if (arg == L"--software") {
            app.forceSoftware = true;
        } else if (arg == L"--no-audio") {
            WritePrivateProfileStringW(L"Sirga", L"audio", L"0", app.settingsPath.c_str());
        }
    }
    LocalFree(argv);
}

}  // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    winrt::init_apartment(winrt::apartment_type::single_threaded);
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    MFStartup(MF_VERSION, MFSTARTUP_LITE);
    Gdiplus::GdiplusStartupInput gdiplusInput;
    ULONG_PTR gdiplusToken = 0;
    Gdiplus::GdiplusStartup(&gdiplusToken, &gdiplusInput, nullptr);
    // Temporizadores de 1 ms: la captura de audio y el envío no esperan de más
    timeBeginPeriod(1);

    INITCOMMONCONTROLSEX controls{sizeof(controls), ICC_STANDARD_CLASSES};
    InitCommonControlsEx(&controls);

    wchar_t* folder = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_RoamingAppData, 0, nullptr, &folder))) {
        std::wstring dir = std::wstring(folder) + L"\\SirgaStudioPC";
        CreateDirectoryW(dir.c_str(), nullptr);
        app.settingsPath = dir + L"\\ajustes.ini";
    }
    CoTaskMemFree(folder);
    ParseCommandLine();
    Log(L"Sirga Studio PC iniciado");

    WNDCLASSEXW wc{sizeof(wc)};
    wc.lpfnWndProc = WindowProc;
    wc.hInstance = instance;
    wc.hIcon = LoadIconW(instance, MAKEINTRESOURCEW(1));
    wc.hIconSm = LoadIconW(instance, MAKEINTRESOURCEW(1));
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.lpszClassName = L"SirgaStudioPC";
    RegisterClassExW(&wc);

    HWND hwnd = CreateWindowExW(0, wc.lpszClassName, L"Sirga Studio PC", WS_OVERLAPPEDWINDOW & ~(WS_THICKFRAME | WS_MAXIMIZEBOX), CW_USEDEFAULT, CW_USEDEFAULT,
                                480, 780, nullptr, nullptr, instance, nullptr);
    // Abierta desde otro programa sin ventana, Windows puede pedir SW_HIDE: se muestra igualmente
    ShowWindow(hwnd, show == SW_HIDE || show == SW_SHOWMINNOACTIVE ? SW_SHOWNORMAL : show);
    UpdateWindow(hwnd);

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0)) {
        if (!IsDialogMessageW(hwnd, &msg)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }

    timeEndPeriod(1);
    Gdiplus::GdiplusShutdown(gdiplusToken);
    MFShutdown();
    WSACleanup();
    return 0;
}
