package main

import (
	"errors"
	"testing"
	"time"
)

func TestTurnWatchFiresOnAllocRefreshFail(t *testing.T) {
	var got []string
	f := &turnWatchFactory{onDead: func(r string) { got = append(got, r) }}
	l := f.NewLogger("turnc")
	l.Warnf("Failed to refresh allocation: %s", "x")
	l.Warnf("Failed to refresh allocation: %s", "x") // второй раз не стреляет
	if len(got) != 1 {
		t.Fatalf("want 1 fire, got %v", got)
	}
}

func TestTurnWatchBindNeedsTwoFailsInRow(t *testing.T) {
	fired := 0
	f := &turnWatchFactory{onDead: func(string) { fired++ }}
	l := f.NewLogger("turnc")
	l.Warnf("Failed to bind channel %d: %s", 1, "x")
	l.Debugf("Channel binding successful: %s %d", "a", 1) // сброс
	l.Warnf("Failed to bind channel %d: %s", 1, "x")
	if fired != 0 {
		t.Fatalf("fired after non-consecutive fails")
	}
	l.Warnf("Failed to bind channel %d: %s", 1, "x")
	if fired != 1 {
		t.Fatalf("want fire after 2 consecutive fails, got %d", fired)
	}
}

func TestTurnWatchIgnoresOtherScopes(t *testing.T) {
	f := &turnWatchFactory{onDead: func(string) { t.Fatal("must not fire") }}
	f.NewLogger("turn").Warnf("Failed to refresh allocation: %s", "x")
}

func TestPickTurnURLSkipsBadRelays(t *testing.T) {
	urls := []string{"a:1", "b:1", "c:1"}
	markRelayBad("b:1", time.Minute)
	defer markRelayBad("b:1", -time.Second)
	if got := pickTurnURL("u", urls, 1); got != "c:1" {
		t.Fatalf("want c:1, got %s", got)
	}
	markRelayBad("a:1", time.Minute)
	markRelayBad("c:1", time.Minute)
	defer markRelayBad("a:1", -time.Second)
	defer markRelayBad("c:1", -time.Second)
	if got := pickTurnURL("u", urls, 1); got != "b:1" {
		t.Fatalf("all bad: want own b:1, got %s", got)
	}
}

func TestPickTurnURLSpreadsAndRespectsQuota(t *testing.T) {
	urls := []string{"x:1", "y:1"}
	r1 := acquireSeat("cred", "x:1")
	defer r1()
	// x занят одним местом, y свободен — новая сессия идёт на y даже со «своим» x.
	if got := pickTurnURL("cred", urls, 0); got != "y:1" {
		t.Fatalf("want least busy y:1, got %s", got)
	}
	r2 := acquireSeat("cred", "y:1")
	noteQuotaHit("cred", "y:1") // y держит 1 место — оно занято
	defer r2()
	if got := pickTurnURL("cred", urls, 1); got != "x:1" {
		t.Fatalf("y full by quota: want x:1, got %s", got)
	}
	// Другие креды квота y не касается.
	if got := pickTurnURL("other", urls, 1); got != "y:1" {
		t.Fatalf("other creds: want own y:1, got %s", got)
	}
	r2()
	r2() // повторный release безопасен
}

func TestSignalBusWakesAllWaiters(t *testing.T) {
	b := newSignalBus()
	w1, w2 := b.wait(), b.wait()
	b.fire("x")
	for _, w := range []<-chan struct{}{w1, w2} {
		select {
		case <-w:
		default:
			t.Fatal("waiter not released")
		}
	}
	select {
	case <-b.wait():
		t.Fatal("new waiter must block until next fire")
	default:
	}
}

func TestVkRateLimitLockout(t *testing.T) {
	vkLockoutUntil.Store(0)
	noteVkRateLimit(errors.New("VK returns error: Too many requests (error_code=6)"))
	if vkLockoutRemaining() != 0 {
		t.Fatal("non-rate-limit error must not lock out")
	}
	noteVkRateLimit(errors.New("api error: Flood control"))
	if vkLockoutRemaining() < 50*time.Second {
		t.Fatal("flood control must lock out ~60s")
	}
	vkLockoutUntil.Store(0)
}

func TestFastReconnectErrClassification(t *testing.T) {
	if !isFastReconnectErr(errReconnectRequested) || !isFastReconnectErr(errStreamSilent) {
		t.Fatal("transport errors must be fast")
	}
	if isFastReconnectErr(errServerRestart) || isFastReconnectErr(errors.New("TURN квота")) {
		t.Fatal("restart/quota are not fast")
	}
}
