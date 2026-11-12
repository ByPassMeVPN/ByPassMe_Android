package main

import (
	"testing"
	"time"
)

func TestPeerFailover(t *testing.T) {
	addrs, err := parsePeers("1.1.1.1:56003, 2.2.2.2:56003")
	if err != nil || len(addrs) != 2 {
		t.Fatalf("parse: %v %v", addrs, err)
	}
	setPeers(addrs)
	defer setPeers(nil)
	a := currentPeer(nil)
	if a.String() != "1.1.1.1:56003" {
		t.Fatalf("want primary, got %v", a)
	}
	// Недавно был живой ответ — даже 3 неудачи не переключают.
	for i := 0; i < 3; i++ {
		notePeerSessionFailed(a)
	}
	if currentPeer(nil).String() != "1.1.1.1:56003" {
		t.Fatal("switched despite recent alive")
	}
	activePeers.lastAlive = time.Now().Add(-2 * time.Minute)
	for i := 0; i < 3; i++ {
		notePeerSessionFailed(a)
	}
	if currentPeer(nil).String() != "2.2.2.2:56003" {
		t.Fatal("must switch to alternate after silence")
	}
}

func TestSinglePeerNoFailover(t *testing.T) {
	addrs, _ := parsePeers("1.1.1.1:56003")
	setPeers(addrs)
	if activePeers != nil {
		t.Fatal("single peer must not enable failover")
	}
	if currentPeer(addrs[0]) != addrs[0] {
		t.Fatal("fallback expected")
	}
}
