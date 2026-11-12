// SPDX-License-Identifier: MIT
// liveness.go — живучесть туннеля: общая шина переподключения, детектор сна,
// учёт здоровья TURN-серверов VK, сторож отказов TURN по логам pion и общая
// пауза запросов к VK. Идеи — free-turn-proxy (FreeTurn, HBL) и
// vk-turn-proxy-ios (anton48); код свой.
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"math/rand"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/logging"
)

// ─── Причины завершения сессии, после которых переподключаемся сразу ───

var (
	// errReconnectRequested — приложение сообщило о смене сети (RECONNECT).
	errReconnectRequested = errors.New("переподключение: сменилась сеть")
	// errStreamSilent — сервер перестал отвечать в этом потоке (relay молчит).
	errStreamSilent = errors.New("поток молчит — relay VK перестал передавать трафик")
	// errTurnChannelDead — VK отказал в продлении канала/аллокации TURN.
	errTurnChannelDead = errors.New("TURN-канал умер (VK отказал в продлении)")
	// errServerRestart — сервер заранее сообщил, что перезапускается.
	errServerRestart = errors.New("сервер перезапускается")
)

// isFastReconnectErr — сбой транспорта, а не VK/сервера: повтор без паузы и
// без роста счётчика неудач.
func isFastReconnectErr(err error) bool {
	return errors.Is(err, errReconnectRequested) || errors.Is(err, errStreamSilent) ||
		errors.Is(err, errTurnChannelDead)
}

// ─── Шина: один сигнал — все текущие сессии ───

type signalBus struct {
	mu     sync.Mutex
	ch     chan struct{}
	reason string
}

func newSignalBus() *signalBus { return &signalBus{ch: make(chan struct{})} }

// wait — канал, который закроется при следующем сигнале.
func (b *signalBus) wait() <-chan struct{} {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.ch
}

func (b *signalBus) fire(reason string) {
	b.mu.Lock()
	b.reason = reason
	close(b.ch)
	b.ch = make(chan struct{})
	b.mu.Unlock()
}

func (b *signalBus) lastReason() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.reason
}

var (
	reconnectBus = newSignalBus() // все сессии закрываются и сразу поднимаются заново
	wakeBus      = newSignalBus() // все сессии проверяют, жив ли поток
)

var lastReconnectAt atomic.Int64

// RequestReconnect — смена сети: старые сокеты к TURN мертвы, поднимаем все
// потоки заново, не перезапуская процесс и не трогая TUN.
func RequestReconnect(reason string) {
	now := time.Now().UnixMilli()
	if prev := lastReconnectAt.Load(); prev > 0 && now-prev < 3000 {
		log.Printf("[СЕТЬ] Переподключение уже идёт (%s) — пропуск", reason)
		return
	}
	lastReconnectAt.Store(now)
	log.Printf("[СЕТЬ] Переподключение всех потоков: %s", reason)
	reconnectBus.fire(reason)
}

// RequestWake — устройство проснулось: каждый поток шлёт keepalive и, если
// сервер не ответил, пересоздаётся. Живые потоки не трогаем (лишнее
// пересоздание = поход в VK, риск капчи).
func RequestWake(reason string) {
	log.Printf("[СОН] Проверка потоков: %s", reason)
	wakeBus.fire(reason)
}

// runSleepDetector — сон устройства по расхождению обычных и монотонных часов
// (монотонные во сне стоят). Ловит и сон, о котором приложение не сообщило.
func runSleepDetector(ctx context.Context) {
	const tick = 15 * time.Second
	const threshold = 45 * time.Second
	t := time.NewTicker(tick)
	defer t.Stop()
	last := time.Now()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			now := time.Now()
			gap := now.Round(0).Sub(last.Round(0)) - now.Sub(last)
			last = now
			if gap >= threshold {
				RequestWake(fmt.Sprintf("устройство спало %s", gap.Truncate(time.Second)))
			}
		}
	}
}

// ─── Здоровье TURN-серверов VK и места по учётным данным ───
//
// Relay, через который поток замолчал или умер канал, временно обходим для
// всех. Квота аллокаций (486) — у VK на пару «креды + relay»: считаем свои
// живые аллокации (места) на каждой паре, при 486 запоминаем, сколько пара
// выдерживает, и новый поток ставим на наименее занятый relay, где у его
// кредов есть место (идея — credpool из vk-turn-proxy-ios, anton48).

type relayState struct {
	badUntil time.Time // поток через этот relay замолчал / канал умер
}

type seatKey struct{ user, relay string }

type quotaInfo struct {
	limit int
	until time.Time
}

var (
	relayHealthMu sync.Mutex
	relayHealth   = map[string]*relayState{}
	seats         = map[seatKey]int{}
	quotaLearned  = map[seatKey]quotaInfo{}
)

const quotaMemory = 10 * time.Minute

func markRelayBad(addr string, d time.Duration) {
	relayHealthMu.Lock()
	st := relayHealth[addr]
	if st == nil {
		st = &relayState{}
		relayHealth[addr] = st
	}
	st.badUntil = time.Now().Add(d)
	relayHealthMu.Unlock()
}

func relayUsableLocked(addr string, now time.Time) bool {
	st := relayHealth[addr]
	return st == nil || now.After(st.badUntil)
}

func relayUsable(addr string) bool {
	relayHealthMu.Lock()
	defer relayHealthMu.Unlock()
	return relayUsableLocked(addr, time.Now())
}

func seatFreeLocked(k seatKey, now time.Time) bool {
	q, ok := quotaLearned[k]
	if !ok || now.After(q.until) {
		return true
	}
	return seats[k] < q.limit
}

// acquireSeat — аллокация на relay создана; release — когда она закрыта.
func acquireSeat(user, relay string) (release func()) {
	k := seatKey{user, relay}
	relayHealthMu.Lock()
	seats[k]++
	relayHealthMu.Unlock()
	var once sync.Once
	return func() {
		once.Do(func() {
			relayHealthMu.Lock()
			if seats[k] > 1 {
				seats[k]--
			} else {
				delete(seats, k)
			}
			relayHealthMu.Unlock()
		})
	}
}

// noteQuotaHit — VK ответил 486 на Allocate: эта пара держит не больше
// уже занятых мест (минимум одно — квота могла быть занята чужим клиентом).
func noteQuotaHit(user, relay string) {
	k := seatKey{user, relay}
	relayHealthMu.Lock()
	limit := seats[k]
	if limit < 1 {
		limit = 1
	}
	quotaLearned[k] = quotaInfo{limit: limit, until: time.Now().Add(quotaMemory)}
	relayHealthMu.Unlock()
	log.Printf("[TURN] Квота relay %s для этих кредов: %d мест (запомнено на %s)", relay, limit, quotaMemory)
}

// hasFreeRelay — есть ли для этих кредов живой relay с местом (после 486
// можно сразу пробовать другой, не ждать полминуты).
func hasFreeRelay(user string, urls []string) bool {
	now := time.Now()
	relayHealthMu.Lock()
	defer relayHealthMu.Unlock()
	for _, u := range urls {
		if relayUsableLocked(u, now) && seatFreeLocked(seatKey{user, u}, now) {
			return true
		}
	}
	return false
}

// pickTurnURL — relay для новой сессии: живой, с местом для этих кредов и
// наименее занятый; при равенстве — по кругу от номера сессии (как раньше).
// Если не подходит ни один — «свой» по номеру.
func pickTurnURL(user string, urls []string, sessionID int) string {
	start := sessionID % len(urls)
	now := time.Now()
	relayHealthMu.Lock()
	best, bestSeats := "", int(^uint(0)>>1)
	for i := 0; i < len(urls); i++ {
		u := urls[(start+i)%len(urls)]
		k := seatKey{user, u}
		if !relayUsableLocked(u, now) || !seatFreeLocked(k, now) {
			continue
		}
		if n := seats[k]; n < bestSeats {
			best, bestSeats = u, n
		}
	}
	relayHealthMu.Unlock()
	if best == "" {
		return urls[start]
	}
	if best != urls[start] {
		log.Printf("[СЕССИЯ #%d] TURN %s → %s (занятость/квота/молчание)", sessionID, urls[start], best)
	}
	return best
}

// ─── Сторож отказов TURN по логам pion ───
//
// pion/turn сообщает о провале продления только в лог. VK отклоняет
// CreatePermission refresh (400) — поэтому продление разрешений выключено
// (PermissionRefreshInterval), канал держится ChannelBind. Если VK отказал в
// продлении аллокации (сразу) или канала (2 раза подряд) — поток мёртв.

type turnWatchFactory struct {
	onDead func(reason string)
}

func (f *turnWatchFactory) NewLogger(scope string) logging.LeveledLogger {
	if scope != "turnc" {
		return &NullLogger{}
	}
	return &turnWatchLogger{f: f}
}

type turnWatchLogger struct {
	NullLogger
	f         *turnWatchFactory
	mu        sync.Mutex
	bindFails int
	fired     bool
}

func (l *turnWatchLogger) note(msg string) {
	var reason string
	l.mu.Lock()
	switch {
	case strings.Contains(msg, "Failed to refresh allocation"):
		if !l.fired {
			l.fired = true
			reason = "VK не продлил аллокацию"
		}
	case strings.Contains(msg, "Failed to bind channel"):
		l.bindFails++
		if !l.fired && l.bindFails >= 2 {
			l.fired = true
			reason = "VK дважды не продлил канал"
		}
	case strings.Contains(msg, "Channel binding successful"):
		l.bindFails = 0
	}
	l.mu.Unlock()
	if reason != "" && l.f.onDead != nil {
		l.f.onDead(reason)
	}
}

func (l *turnWatchLogger) Warn(msg string)                        { l.note(msg) }
func (l *turnWatchLogger) Warnf(format string, _ ...interface{})  { l.note(format) }
func (l *turnWatchLogger) Debug(msg string)                       { l.note(msg) }
func (l *turnWatchLogger) Debugf(format string, _ ...interface{}) { l.note(format) }

// ─── Общая пауза запросов к VK (Flood control / error 29) ───

var vkLockoutUntil atomic.Int64 // unix ms

// noteVkRateLimit — VK ограничил частоту: все потоки ждут вместе, а не
// долбят VK каждый сам по себе (это и продлевает блокировку, и зовёт капчу).
func noteVkRateLimit(err error) {
	if err == nil {
		return
	}
	s := strings.ToLower(err.Error())
	if !(strings.Contains(s, "flood control") || strings.Contains(s, "rate limit") ||
		strings.Contains(s, "error_code:29") || strings.Contains(s, "error_code: 29") ||
		strings.Contains(s, "error_code=29") || strings.Contains(s, "error 29")) {
		return
	}
	until := time.Now().Add(time.Duration(60+rand.Intn(30)) * time.Second).UnixMilli()
	if until > vkLockoutUntil.Load() {
		vkLockoutUntil.Store(until)
		log.Printf("[VK Auth] VK ограничил запросы — общая пауза %d с для всех потоков", (until-time.Now().UnixMilli())/1000)
	}
}

func vkLockoutRemaining() time.Duration {
	left := time.Until(time.UnixMilli(vkLockoutUntil.Load()))
	if left < 0 {
		return 0
	}
	return left
}

// sleepCtx — false, если ctx отменён раньше.
func sleepCtx(ctx context.Context, d time.Duration) bool {
	if d <= 0 {
		return ctx.Err() == nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}
