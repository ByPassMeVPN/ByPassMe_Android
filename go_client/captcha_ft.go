// SPDX-License-Identifier: MIT
// captcha_ft.go — решение капчи VK без браузера новым решателем (ftvk/captcha,
// перенесён из free-turn-proxy 4.1.3). VK в ноябре 2026 сменил страницу
// капчи: PoW теперь уходит «конвертом» с телеметрией браузера, debug_info —
// UUID из window.vk, задание лежит в window.init. Старый решатель
// (captcha_v2.go) такую страницу не разбирает и сразу сдаётся, поэтому
// капчу решал только скрытый WebView — а при выключенном экране и на
// Windows/iOS это медленно или невозможно.
package main

import (
	"context"
	"errors"
	"log"
	"strings"
	"sync/atomic"
	"time"

	tlsclient "github.com/bogdanfinn/tls-client"

	"wg-turn-client/ftvk/browserprofile"
	ftcaptcha "wg-turn-client/ftvk/captcha"
)

// ftLogger — журнал решателя в наш лог. Отладочные строки нужны для разбора
// жалоб, но без тел запросов, заголовков и конверта PoW (там токены сессии).
type ftLogger struct{}

func ftSkip(format string) bool {
	return strings.HasPrefix(format, "[Captcha] we sent") ||
		strings.HasPrefix(format, "[Captcha] header (") ||
		strings.HasPrefix(format, "[Captcha] pow envelope")
}

func (ftLogger) Debugf(format string, v ...any) {
	if ftSkip(format) {
		return
	}
	log.Printf("[КАПЧА] "+format, v...)
}
func (ftLogger) Infof(format string, v ...any)  { log.Printf("[КАПЧА] "+format, v...) }
func (ftLogger) Warnf(format string, v ...any)  { log.Printf("[КАПЧА] ⚠ "+format, v...) }
func (ftLogger) Errorf(format string, v ...any) { log.Printf("[КАПЧА] ✗ "+format, v...) }
func (ftLogger) DebugEnabled() bool             { return true }

func init() { ftcaptcha.SetLogger(ftLogger{}) }

// ftRejectedUntil — VK отверг ответ нового решателя («бот» / неверно): до
// этого времени следующая капча сразу идёт в WebView, чтобы не жечь сессии
// одинаковыми отказами. Unix-секунды.
var ftRejectedUntil atomic.Int64

// ftRejectStreak — отказов подряд. Пауза растёт: 10 мин, 20, 40… до 12 ч —
// если с этого адреса VK считает решатель ботом, не жжём на нём капчи.
var ftRejectStreak atomic.Int32

const (
	ftRejectPause    = 10 * time.Minute
	ftRejectPauseMax = 12 * time.Hour
)

func ftSolverPaused() bool { return time.Now().Unix() < ftRejectedUntil.Load() }

func ftNoteRejected() {
	n := ftRejectStreak.Add(1)
	pause := ftRejectPause
	for i := int32(1); i < n && pause < ftRejectPauseMax; i++ {
		pause *= 2
	}
	pause = min(pause, ftRejectPauseMax)
	ftRejectedUntil.Store(time.Now().Add(pause).Unix())
	log.Printf("[КАПЧА] FT: отказ VK №%d подряд — следующие капчи через WebView %s", n, pause)
}

// ftProfile — браузер для капчи: те же UA и client hints, с которыми клиент
// только что ходил в API VK, экран и browser_fp — из профиля капчи.
func ftProfile(profile Profile, saved *SavedProfile) browserprofile.Profile {
	deviceJSON, fp := "", ""
	if saved != nil {
		fp = strings.TrimSpace(saved.BrowserFp)
		// Экран берём, только если он того же класса (телефон/ПК), что и UA.
		if (saved.SecChUaMobile == "?1") == (profile.SecChUaMobile == "?1") {
			deviceJSON = saved.DeviceJSON
		}
	}
	return browserprofile.FromBrowser(profile.UserAgent, profile.SecChUa, profile.SecChUaMobile,
		profile.SecChUaPlatform, deviceJSON, fp)
}

func ftError(e *VkCaptchaError) *ftcaptcha.Error {
	return &ftcaptcha.Error{
		ErrorCode:      e.ErrorCode,
		ErrorMsg:       e.ErrorMsg,
		CaptchaSid:     e.CaptchaSid,
		RedirectURI:    e.RedirectURI,
		SessionToken:   e.SessionToken,
		CaptchaTs:      e.CaptchaTs,
		CaptchaAttempt: e.CaptchaAttempt,
	}
}

// ftOutcome — чем кончилась попытка нового решателя.
type ftOutcome int

const (
	ftSolved   ftOutcome = iota
	ftFresh              // до check не дошли — эту же капчу можно решать дальше
	ftSpent              // ответ отправлен и отвергнут — нужна новая капча
	ftRateHit            // VK: лимит попыток для сессии
)

func solveVkCaptchaFT(
	ctx context.Context,
	streamID int,
	captchaErr *VkCaptchaError,
	client tlsclient.HttpClient,
	profile Profile,
	saved *SavedProfile,
) (string, ftOutcome, error) {
	if captchaErr == nil || captchaErr.SessionToken == "" || captchaErr.RedirectURI == "" {
		return "", ftFresh, errors.New("нет session_token или redirect_uri")
	}
	bp := ftProfile(profile, saved)
	log.Printf("[STREAM %d] [КАПЧА] FT: решаю без браузера (%s, %s)", streamID, bp.Platform, bp.PlatformName())
	started := time.Now()
	token, err := ftcaptcha.Solve(ctx, ftError(captchaErr), streamID, client, bp, ftLogger{})
	if err == nil {
		ftRejectedUntil.Store(0)
		ftRejectStreak.Store(0)
		log.Printf("[STREAM %d] [КАПЧА] FT: решена за %s", streamID, time.Since(started).Truncate(time.Millisecond))
		return token, ftSolved, nil
	}
	switch {
	case errors.Is(err, ftcaptcha.ErrRateLimit):
		ftNoteRejected()
		return "", ftRateHit, err
	case errors.Is(err, ftcaptcha.ErrSessionSpent), errors.Is(err, ftcaptcha.ErrBot):
		ftNoteRejected()
		return "", ftSpent, err
	}
	return "", ftFresh, err
}
