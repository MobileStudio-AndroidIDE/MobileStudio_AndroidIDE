/*
 * mingw-example: 간단한 GUI 게임 (Windows)
 * "Dodger" - 화살표 키로 플레이어를 이동시켜 떨어지는 장애물 피하기
 * 
 * 빌드 (Linux 호스트, mingw-w64 교차 컴파일):
 *   x86_64-w64-mingw32-gcc -O2 -o dodger.exe dodger.c -lgdi32 -luser32 -lwinmm -mwindows
 * 
 * 실행: MobileStudio > 도구 > Exe Runner > dodger.exe 선택 > 실행
 */

#include <windows.h>
#include <stdlib.h>
#include <time.h>

#define WINDOW_WIDTH  640
#define WINDOW_HEIGHT 480
#define PLAYER_WIDTH  80
#define PLAYER_HEIGHT 15
#define OBJ_SIZE      25
#define MAX_OBJS      10
#define PLAYER_SPEED  12
#define OBJ_SPEED     6
#define FRAME_MS      16  /* ~60 FPS */

/* 전역 상태 */
static HWND   g_hwnd;
static int    g_player_x = (WINDOW_WIDTH - PLAYER_WIDTH) / 2;
static int    g_obj_x[MAX_OBJS];
static int    g_obj_y[MAX_OBJS];
static int    g_obj_active[MAX_OBJS];
static int    g_score = 0;
static int    g_game_over = 0;
static int    g_obj_count = 0;
static UINT   g_timer_id;
static HBRUSH g_brush_player;
static HBRUSH g_brush_obj;
static HBRUSH g_brush_bg;
static HPEN   g_pen_text;

static void reset_object(int i) {
    g_obj_x[i] = rand() % (WINDOW_WIDTH - OBJ_SIZE);
    g_obj_y[i] = -OBJ_SIZE - (rand() % 200);
    g_obj_active[i] = 1;
}

static void init_game(void) {
    g_player_x = (WINDOW_WIDTH - PLAYER_WIDTH) / 2;
    g_score = 0;
    g_game_over = 0;
    g_obj_count = 0;
    for (int i = 0; i < MAX_OBJS; i++) {
        g_obj_active[i] = 0;
    }
    /* 처음 3개 장애물 생성 */
    for (int i = 0; i < 3; i++) {
        reset_object(i);
        g_obj_count++;
    }
}

static void update_game(void) {
    if (g_game_over) return;

    /* 장애물 이동 */
    for (int i = 0; i < MAX_OBJS; i++) {
        if (!g_obj_active[i]) continue;
        g_obj_y[i] += OBJ_SPEED;

        /* 화면 아래로 떨어지면 제거하고 새 장애물 생성 */
        if (g_obj_y[i] > WINDOW_HEIGHT) {
            g_obj_active[i] = 0;
            g_obj_count--;
            g_score += 10;
            if (g_obj_count < MAX_OBJS && rand() % 3 == 0) {
                int idx = -1;
                for (int j = 0; j < MAX_OBJS; j++) {
                    if (!g_obj_active[j]) { idx = j; break; }
                }
                if (idx >= 0) { reset_object(idx); g_obj_count++; }
            }
        }

        /* 충돌 체크: 플레이어와 장애물 */
        int player_bottom = WINDOW_HEIGHT - 40;
        if (g_obj_y[i] + OBJ_SIZE > player_bottom &&
            g_obj_y[i] < player_bottom + PLAYER_HEIGHT &&
            g_obj_x[i] + OBJ_SIZE > g_player_x &&
            g_obj_x[i] < g_player_x + PLAYER_WIDTH) {
            g_game_over = 1;
        }
    }
}

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_CREATE:
        init_game();
        g_timer_id = SetTimer(hwnd, 1, FRAME_MS, NULL);
        g_brush_player = CreateSolidBrush(RGB(0, 150, 255));
        g_brush_obj    = CreateSolidBrush(RGB(255, 80, 80));
        g_brush_bg     = CreateSolidBrush(RGB(240, 240, 240));
        g_pen_text     = CreatePen(PS_SOLID, 1, RGB(0, 0, 0));
        srand((unsigned)time(NULL));
        break;

    case WM_KEYDOWN:
        if (!g_game_over) {
            switch (wParam) {
            case VK_LEFT:
                g_player_x -= PLAYER_SPEED;
                if (g_player_x < 0) g_player_x = 0;
                break;
            case VK_RIGHT:
                g_player_x += PLAYER_SPEED;
                if (g_player_x > WINDOW_WIDTH - PLAYER_WIDTH)
                    g_player_x = WINDOW_WIDTH - PLAYER_WIDTH;
                break;
            }
        } else if (wParam == VK_SPACE) {
            init_game();
        }
        InvalidateRect(hwnd, NULL, FALSE);
        break;

    case WM_TIMER:
        if (wParam == g_timer_id) {
            update_game();
            InvalidateRect(hwnd, NULL, FALSE);
        }
        break;

    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC hdc = BeginPaint(hwnd, &ps);

        /* 배경 */
        RECT rect;
        GetClientRect(hwnd, &rect);
        FillRect(hdc, &rect, g_brush_bg);

        /* 플레이어 */
        RECT player_rect = {
            g_player_x,
            WINDOW_HEIGHT - 40,
            g_player_x + PLAYER_WIDTH,
            WINDOW_HEIGHT - 40 + PLAYER_HEIGHT
        };
        FillRect(hdc, &player_rect, g_brush_player);

        /* 장애물 */
        for (int i = 0; i < MAX_OBJS; i++) {
            if (!g_obj_active[i]) continue;
            RECT obj_rect = {
                g_obj_x[i], g_obj_y[i],
                g_obj_x[i] + OBJ_SIZE, g_obj_y[i] + OBJ_SIZE
            };
            FillRect(hdc, &obj_rect, g_brush_obj);
        }

        /* 점수 / 메시지 */
        char buf[128];
        SetBkMode(hdc, TRANSPARENT);
        SelectObject(hdc, g_pen_text);

        if (g_game_over) {
            wsprintf(buf, "GAME OVER - Score: %d  (스페이스바로 재시작)", g_score);
            TextOut(hdc, WINDOW_WIDTH/2 - 150, WINDOW_HEIGHT/2 - 20, buf, lstrlen(buf));
        } else {
            wsprintf(buf, "Score: %d   (왼쪽/오른쪽 화살표로 이동)", g_score);
            TextOut(hdc, 20, 20, buf, lstrlen(buf));
            wsprintf(buf, "장애물을 피하세요!");
            TextOut(hdc, 20, 45, buf, lstrlen(buf));
        }

        EndPaint(hwnd, &ps);
        break;
    }

    case WM_DESTROY:
        if (g_timer_id) KillTimer(hwnd, g_timer_id);
        DeleteObject(g_brush_player);
        DeleteObject(g_brush_obj);
        DeleteObject(g_brush_bg);
        DeleteObject(g_pen_text);
        PostQuitMessage(0);
        break;

    default:
        return DefWindowProc(hwnd, msg, wParam, lParam);
    }
    return 0;
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance,
                   LPSTR lpCmdLine, int nCmdShow) {
    const char CLASS_NAME[] = "DodgerGameClass";

    WNDCLASS wc = {0};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInstance;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.lpszClassName = CLASS_NAME;
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);

    RegisterClass(&wc);

    HWND hwnd = CreateWindowEx(
        0, CLASS_NAME, "Dodger - 장애물 피하기 게임",
        WS_OVERLAPPEDWINDOW & ~WS_THICKFRAME & ~WS_MAXIMIZEBOX,
        CW_USEDEFAULT, CW_USEDEFAULT,
        WINDOW_WIDTH + 16, WINDOW_HEIGHT + 39,
        NULL, NULL, hInstance, NULL);

    if (!hwnd) return 1;

    g_hwnd = hwnd;
    ShowWindow(hwnd, nCmdShow);
    UpdateWindow(hwnd);

    MSG msg;
    while (GetMessage(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }
    return (int)msg.wParam;
}
