// SPDX-License-Identifier: MIT
// peers.go — несколько входных адресов сервера Обхода (-peer a:p,b:p).
// Все потоки идут на текущий адрес; если подряд несколько сессий не
// получили от сервера ни одного пакета, а живых ответов давно нет —
// переключаемся на следующий адрес (блок IP, смена IP ноды).
package main

import (
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"time"
)

type peerFailover struct {
	mu        sync.Mutex
	addrs     []*net.UDPAddr
	cur       int
	fails     int
	lastAlive time.Time
}

// activePeers — nil, если адрес один (поведение как раньше).
var activePeers *peerFailover

const (
	peerFailsToSwitch = 3
	peerSilenceWindow = 60 * time.Second
)

// parsePeers — «host:port[,host:port...]» → адреса (первый — основной).
func parsePeers(arg string) ([]*net.UDPAddr, error) {
	var out []*net.UDPAddr
	for _, p := range strings.Split(arg, ",") {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		a, err := net.ResolveUDPAddr("udp", p)
		if err != nil {
			return nil, fmt.Errorf("адрес %q: %w", p, err)
		}
		out = append(out, a)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("пустой -peer")
	}
	return out, nil
}

func setPeers(addrs []*net.UDPAddr) {
	if len(addrs) < 2 {
		activePeers = nil
		return
	}
	activePeers = &peerFailover{addrs: addrs, lastAlive: time.Now()}
	log.Printf("[ПИР] Адресов сервера: %d, основной %s", len(addrs), addrs[0])
}

// currentPeer — адрес для новой сессии.
func currentPeer(fallback *net.UDPAddr) *net.UDPAddr {
	pf := activePeers
	if pf == nil {
		return fallback
	}
	pf.mu.Lock()
	defer pf.mu.Unlock()
	return pf.addrs[pf.cur]
}

// notePeerAlive — от сервера пришёл пакет через этот адрес.
func notePeerAlive(peer *net.UDPAddr) {
	pf := activePeers
	if pf == nil || peer == nil {
		return
	}
	pf.mu.Lock()
	if pf.addrs[pf.cur].String() == peer.String() {
		pf.lastAlive = time.Now()
		pf.fails = 0
	}
	pf.mu.Unlock()
}

// notePeerSessionFailed — сессия на этот адрес закончилась ошибкой и не
// получила от сервера ни одного пакета.
func notePeerSessionFailed(peer *net.UDPAddr) {
	pf := activePeers
	if pf == nil || peer == nil {
		return
	}
	pf.mu.Lock()
	defer pf.mu.Unlock()
	if pf.addrs[pf.cur].String() != peer.String() {
		return // уже переключились
	}
	pf.fails++
	if pf.fails < peerFailsToSwitch || time.Since(pf.lastAlive) < peerSilenceWindow {
		return
	}
	prev := pf.addrs[pf.cur]
	pf.cur = (pf.cur + 1) % len(pf.addrs)
	pf.fails = 0
	pf.lastAlive = time.Now() // даём новому адресу окно
	log.Printf("[ПИР] Адрес %s не отвечает — переключаюсь на %s", prev, pf.addrs[pf.cur])
}
