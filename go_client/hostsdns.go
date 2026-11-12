package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// DNS для iOS Network Extension (идея из anton48/vk-turn-proxy-ios):
//   - чистый Go-резолвер в расширении не работает (нет /etc/resolv.conf);
//   - системный резолвер работает, но на части сетей в расширении может молчать;
//   - основное приложение заранее резолвит хосты VK своим DNS и передаёт IP —
//     для них отвечаем этими IP, остальное спрашиваем у системного резолвера.

var (
	vkHostIPsMu sync.RWMutex
	vkHostIPs   = map[string][]netip.Addr{}

	// Запасной DNS (Android: DNS сети + Яндекс), если системный резолвер не ответил.
	fallbackServers []string
)

// SetFallbackDNS: "ip,ip,..." (IPv4/IPv6) — запасные DNS, опрашиваются параллельно.
func SetFallbackDNS(csv string) {
	var servers []string
	for _, part := range strings.Split(csv, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if ip := net.ParseIP(part); ip != nil {
			part = net.JoinHostPort(ip.String(), "53")
		}
		servers = append(servers, part)
	}
	fallbackServers = servers
	if len(servers) > 0 {
		log.Printf("[КЛИЕНТ] Запасной DNS: %s", strings.Join(servers, ", "))
	}
}

// fallbackLookup шлёт запрос сразу на все запасные DNS (UDP) и берёт первый
// ответ: при глушении часть серверов молчит, ждать каждый по очереди нельзя.
func fallbackLookup(name string, want4 bool) ([]netip.Addr, bool, error) {
	servers := fallbackServers
	if len(servers) == 0 {
		return nil, false, fmt.Errorf("нет запасных DNS")
	}
	qtype := dnsmessage.TypeAAAA
	if want4 {
		qtype = dnsmessage.TypeA
	}
	qname, err := dnsmessage.NewName(strings.TrimSuffix(name, ".") + ".")
	if err != nil {
		return nil, false, err
	}
	type result struct {
		addrs    []netip.Addr
		notFound bool
		err      error
	}
	ch := make(chan result, len(servers))
	for _, srv := range servers {
		go func(srv string) {
			id := uint16(time.Now().UnixNano())
			q := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: id, RecursionDesired: true})
			_ = q.StartQuestions()
			_ = q.Question(dnsmessage.Question{Name: qname, Type: qtype, Class: dnsmessage.ClassINET})
			msg, err := q.Finish()
			if err != nil {
				ch <- result{err: err}
				return
			}
			// Сначала UDP, затем TCP по тому же серверу: при глушении UDP/53
			// часто режут, а TCP/53 проходит (так делает PWDTT).
			resp, err := dnsExchangeUDP(srv, msg)
			if err != nil {
				resp, err = dnsExchangeTCP(srv, msg)
			}
			if err != nil {
				ch <- result{err: err}
				return
			}
			addrs, notFound, perr := parseDNSAnswer(resp, id, srv)
			ch <- result{addrs: addrs, notFound: notFound, err: perr}
		}(srv)
	}
	var lastErr error
	for range servers {
		r := <-ch
		if r.err == nil {
			return r.addrs, r.notFound, nil
		}
		lastErr = r.err
	}
	return nil, false, lastErr
}

func dnsExchangeUDP(srv string, msg []byte) ([]byte, error) {
	conn, err := net.DialTimeout("udp", srv, 3*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write(msg); err != nil {
		return nil, err
	}
	buf := make([]byte, 1500)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}
	return buf[:n], nil
}

// dnsExchangeTCP — тот же запрос по TCP/53 (длина сообщения — 2 байта впереди).
func dnsExchangeTCP(srv string, msg []byte) ([]byte, error) {
	conn, err := net.DialTimeout("tcp", srv, 4*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
	framed := make([]byte, 2+len(msg))
	framed[0] = byte(len(msg) >> 8)
	framed[1] = byte(len(msg))
	copy(framed[2:], msg)
	if _, err := conn.Write(framed); err != nil {
		return nil, err
	}
	var head [2]byte
	if _, err := io.ReadFull(conn, head[:]); err != nil {
		return nil, err
	}
	size := int(head[0])<<8 | int(head[1])
	if size <= 0 || size > 65535 {
		return nil, fmt.Errorf("bad DNS length from %s", srv)
	}
	buf := make([]byte, size)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

func parseDNSAnswer(resp []byte, id uint16, srv string) ([]netip.Addr, bool, error) {
	var p dnsmessage.Parser
	h, err := p.Start(resp)
	if err != nil || h.ID != id {
		return nil, false, fmt.Errorf("bad DNS answer from %s", srv)
	}
	if h.RCode == dnsmessage.RCodeNameError {
		return nil, true, nil
	}
	_ = p.SkipAllQuestions()
	var out []netip.Addr
	for {
		rh, err := p.AnswerHeader()
		if err != nil {
			break
		}
		switch rh.Type {
		case dnsmessage.TypeA:
			r, _ := p.AResource()
			out = append(out, netip.AddrFrom4(r.A))
		case dnsmessage.TypeAAAA:
			r, _ := p.AAAAResource()
			out = append(out, netip.AddrFrom16(r.AAAA))
		default:
			_ = p.SkipAnswer()
		}
	}
	return out, false, nil
}

// SetVKHostIPs задаёт IP хостов VK, заранее полученные приложением.
func SetVKHostIPs(m map[string][]string) {
	next := make(map[string][]netip.Addr, len(m))
	for host, ips := range m {
		for _, s := range ips {
			if a, err := netip.ParseAddr(strings.TrimSpace(s)); err == nil {
				next[strings.ToLower(strings.TrimSuffix(host, "."))] = append(next[strings.ToLower(strings.TrimSuffix(host, "."))], a)
			}
		}
	}
	vkHostIPsMu.Lock()
	vkHostIPs = next
	vkHostIPsMu.Unlock()
	if len(next) > 0 {
		log.Printf("[КЛИЕНТ] IP хостов VK от приложения: %d", len(next))
	}
}

func presetIPs(host string) []netip.Addr {
	vkHostIPsMu.RLock()
	defer vkHostIPsMu.RUnlock()
	return vkHostIPs[strings.ToLower(strings.TrimSuffix(host, "."))]
}

// setupSystemResolverWithHosts: заранее известные IP хостов VK + системный DNS.
func setupSystemResolverWithHosts() {
	system := &net.Resolver{PreferGo: false}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			return &stubDNSConn{system: system}, nil
		},
	}
	log.Printf("[КЛИЕНТ] DNS для VK: IP от приложения + системный")
}

// stubDNSConn — «DNS-сервер» внутри процесса: Go шлёт запрос как TCP DNS
// (с 2-байтовой длиной), ответ собираем сами.
type stubDNSConn struct {
	mu       sync.Mutex
	system   *net.Resolver
	response []byte
	readPos  int
	closed   bool
}

func (c *stubDNSConn) Write(b []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, net.ErrClosed
	}
	resp, err := c.answer(stripDNSWireFrame(b))
	if err != nil {
		return 0, err
	}
	c.response = frameDNSForTCP(resp)
	c.readPos = 0
	return len(b), nil
}

func (c *stubDNSConn) answer(query []byte) ([]byte, error) {
	var p dnsmessage.Parser
	hdr, err := p.Start(query)
	if err != nil {
		return nil, fmt.Errorf("stub dns: %w", err)
	}
	q, err := p.Question()
	if err != nil {
		return nil, fmt.Errorf("stub dns question: %w", err)
	}
	name := strings.TrimSuffix(q.Name.String(), ".")

	var addrs []netip.Addr
	rcode := dnsmessage.RCodeSuccess
	if q.Type == dnsmessage.TypeA || q.Type == dnsmessage.TypeAAAA {
		want4 := q.Type == dnsmessage.TypeA
		for _, a := range presetIPs(name) {
			if a.Is4() == want4 {
				addrs = append(addrs, a)
			}
		}
		if len(addrs) == 0 && !(want4 && len(presetIPs(name)) > 0) {
			netw := "ip6"
			if want4 {
				netw = "ip4"
			}
			ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
			ips, lerr := c.system.LookupNetIP(ctx, netw, name)
			cancel()
			if lerr != nil && len(fallbackServers) > 0 {
				if dnsErr, ok := lerr.(*net.DNSError); !ok || !dnsErr.IsNotFound {
					fb, notFound, ferr := fallbackLookup(name, want4)
					switch {
					case ferr == nil && notFound:
						lerr = &net.DNSError{Err: "no such host", Name: name, IsNotFound: true}
					case ferr == nil:
						ips, lerr = fb, nil
					}
				}
			}
			if lerr != nil {
				if dnsErr, ok := lerr.(*net.DNSError); ok && dnsErr.IsNotFound {
					rcode = dnsmessage.RCodeNameError
				} else if !want4 {
					// Нет IPv6 — не ошибка, просто пустой ответ.
				} else {
					rcode = dnsmessage.RCodeServerFailure
				}
			}
			for _, a := range ips {
				a = a.Unmap()
				if a.Is4() == want4 {
					addrs = append(addrs, a)
				}
			}
		}
	}

	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{
		ID:                 hdr.ID,
		Response:           true,
		RecursionDesired:   hdr.RecursionDesired,
		RecursionAvailable: true,
		RCode:              rcode,
	})
	b.EnableCompression()
	if err := b.StartQuestions(); err != nil {
		return nil, err
	}
	if err := b.Question(q); err != nil {
		return nil, err
	}
	if err := b.StartAnswers(); err != nil {
		return nil, err
	}
	rh := dnsmessage.ResourceHeader{Name: q.Name, Class: dnsmessage.ClassINET, TTL: 60}
	for _, a := range addrs {
		if a.Is4() {
			rh.Type = dnsmessage.TypeA
			if err := b.AResource(rh, dnsmessage.AResource{A: a.As4()}); err != nil {
				return nil, err
			}
		} else {
			rh.Type = dnsmessage.TypeAAAA
			if err := b.AAAAResource(rh, dnsmessage.AAAAResource{AAAA: a.As16()}); err != nil {
				return nil, err
			}
		}
	}
	return b.Finish()
}

func (c *stubDNSConn) Read(b []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, net.ErrClosed
	}
	if c.readPos >= len(c.response) {
		return 0, io.EOF
	}
	n := copy(b, c.response[c.readPos:])
	c.readPos += n
	return n, nil
}

func (c *stubDNSConn) Close() error {
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
	return nil
}

func (c *stubDNSConn) LocalAddr() net.Addr              { return &net.TCPAddr{IP: net.IPv4zero} }
func (c *stubDNSConn) RemoteAddr() net.Addr             { return &net.TCPAddr{IP: net.IPv4zero, Port: 53} }
func (c *stubDNSConn) SetDeadline(time.Time) error      { return nil }
func (c *stubDNSConn) SetReadDeadline(time.Time) error  { return nil }
func (c *stubDNSConn) SetWriteDeadline(time.Time) error { return nil }
