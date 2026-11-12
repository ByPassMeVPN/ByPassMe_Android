package main

import (
	"crypto/tls"
	"net"
	"testing"
)

// Настоящий ClientHello из crypto/tls: SNI находится и режется внутри имени.
func TestClientHelloSNIRange(t *testing.T) {
	c1, c2 := net.Pipe()
	go func() {
		_ = tls.Client(c1, &tls.Config{ServerName: "login.vk.ru", InsecureSkipVerify: true}).Handshake()
	}()
	buf := make([]byte, 4096)
	n, err := c2.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	_ = c1.Close()
	_ = c2.Close()
	hello := buf[:n]
	s, e, ok := clientHelloSNIRange(hello)
	if !ok || string(hello[s:e]) != "login.vk.ru" {
		t.Fatalf("SNI not found: ok=%v %q", ok, hello[s:e])
	}
	for i := 0; i < 50; i++ {
		offs := sniSplitOffsetsFor(hello)
		if len(offs) == 0 {
			t.Fatal("no offsets")
		}
		for _, o := range offs {
			if o <= s || o >= e {
				t.Fatalf("offset %d outside SNI [%d,%d)", o, s, e)
			}
		}
	}
}

func TestSNIRangeNonTLS(t *testing.T) {
	if _, _, ok := clientHelloSNIRange([]byte("GET / HTTP/1.1\r\n")); ok {
		t.Fatal("non-TLS must not match")
	}
}
