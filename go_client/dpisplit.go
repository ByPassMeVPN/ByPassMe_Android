// SPDX-License-Identifier: MIT
// dpisplit.go — разрезание первых байт соединения против DPI без TCP-сборки:
// DPI, который смотрит только в первый сегмент, не видит ни STUN-заголовка
// (TURN по TCP), ни имени сайта в TLS ClientHello (запросы к API VK).
// Разбор ClientHello — по free-turn-proxy (FreeTurn), internal/netconn/
// multisplit.go, Copyright (c) 2026 FreeTurn authors, Happy Bunny License
// (MIT-подобная).
package main

import (
	"context"
	"math/rand"
	"net"
	"sync/atomic"
	"time"
)

// splitFirstWriteConn — первый Write уходит двумя TCP-сегментами (у Go
// TCP_NODELAY включён по умолчанию, два Write = два сегмента).
type splitFirstWriteConn struct {
	net.Conn
	splitAt int
	delay   time.Duration
	done    atomic.Bool
}

func (s *splitFirstWriteConn) Write(b []byte) (int, error) {
	if s.done.CompareAndSwap(false, true) && len(b) > s.splitAt {
		n1, err := s.Conn.Write(b[:s.splitAt])
		if err != nil {
			return n1, err
		}
		if s.delay > 0 {
			time.Sleep(s.delay)
		}
		n2, err := s.Conn.Write(b[s.splitAt:])
		return n1 + n2, err
	}
	return s.Conn.Write(b)
}

// wrapSTUNSplit — разрез внутри STUN magic cookie (байты 4–7) первого запроса.
func wrapSTUNSplit(c net.Conn) net.Conn {
	return &splitFirstWriteConn{Conn: c, splitAt: 5 + rand.Intn(3), delay: 20 * time.Millisecond}
}

// sniSplitConn — первый Write (ClientHello) режется на 3 сегмента внутри
// имени сайта; если SNI не найден — один разрез на 6-м байте.
type sniSplitConn struct {
	net.Conn
	done atomic.Bool
}

func (s *sniSplitConn) Write(b []byte) (int, error) {
	if !s.done.CompareAndSwap(false, true) {
		return s.Conn.Write(b)
	}
	offsets := sniSplitOffsetsFor(b)
	if len(offsets) == 0 {
		return s.Conn.Write(b)
	}
	total, prev := 0, 0
	bounds := append(offsets, len(b))
	for i, bound := range bounds {
		if i > 0 {
			time.Sleep(20*time.Millisecond + time.Duration(rand.Intn(15))*time.Millisecond)
		}
		n, err := s.Conn.Write(b[prev:bound])
		total += n
		if err != nil {
			return total, err
		}
		prev = bound
	}
	return total, nil
}

func sniSplitOffsetsFor(b []byte) []int {
	if start, end, ok := clientHelloSNIRange(b); ok {
		l := end - start
		switch {
		case l < 2:
		case l < 3:
			return []int{start + 1}
		default:
			third := l / 3
			c1 := start + 1 + rand.Intn(third)
			c2 := end - third + rand.Intn(third)
			if c2 <= c1 {
				c2 = c1 + 1
			}
			if c2 < end {
				return []int{c1, c2}
			}
			return []int{c1}
		}
	}
	if len(b) > 6 {
		return []int{6}
	}
	return nil
}

// clientHelloSNIRange — [start,end) первого host_name в TLS ClientHello.
func clientHelloSNIRange(b []byte) (int, int, bool) {
	u16 := func(i int) int { return int(b[i])<<8 | int(b[i+1]) }
	if len(b) < 5 || b[0] != 0x16 { // record: handshake
		return 0, 0, false
	}
	p := 5
	if p+4 > len(b) || b[p] != 0x01 { // ClientHello
		return 0, 0, false
	}
	p += 4
	p += 2 + 32 // version + random
	if p+1 > len(b) {
		return 0, 0, false
	}
	p += 1 + int(b[p]) // session_id
	if p+2 > len(b) {
		return 0, 0, false
	}
	p += 2 + u16(p) // cipher_suites
	if p+1 > len(b) {
		return 0, 0, false
	}
	p += 1 + int(b[p]) // compression
	if p+2 > len(b) {
		return 0, 0, false
	}
	extEnd := p + 2 + u16(p)
	p += 2
	if extEnd > len(b) {
		extEnd = len(b)
	}
	for p+4 <= extEnd {
		etype, elen := u16(p), u16(p+2)
		p += 4
		if p+elen > extEnd {
			break
		}
		if etype == 0x0000 { // server_name
			if p+2 > p+elen {
				return 0, 0, false
			}
			listEnd := p + 2 + u16(p)
			q := p + 2
			if listEnd > p+elen {
				listEnd = p + elen
			}
			for q+3 <= listEnd {
				nameType, nameLen := b[q], u16(q+1)
				q += 3
				if q+nameLen > listEnd {
					break
				}
				if nameType == 0x00 {
					return q, q + nameLen, true
				}
				q += nameLen
			}
			return 0, 0, false
		}
		p += elen
	}
	return 0, 0, false
}

// vkDialContext — TCP до API VK с разрезанием ClientHello (для tls-client
// WithDialContext). Резолв — через net.DefaultResolver (наш DNS для VK).
func vkDialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	d := net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	c, err := d.DialContext(ctx, network, addr)
	if err != nil {
		return nil, err
	}
	return &sniSplitConn{Conn: c}, nil
}
