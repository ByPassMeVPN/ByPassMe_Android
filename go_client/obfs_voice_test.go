package main

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
)

func voiceTestKey() []byte {
	k, _ := hex.DecodeString("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
	return k
}

func TestVoiceRoundTripAndLegacy(t *testing.T) {
	key := voiceTestKey()
	for _, voice := range []bool{false, true} {
		cfg, _ := NewObfsConfig("audio")
		cfg.Voice = voice
		st, _ := NewObfsState()
		seen := map[string]bool{}
		for i := 0; i < 2000; i++ {
			payload := bytes.Repeat([]byte{byte(i)}, 1+i%1300)
			w, err := obfsWrapPacket(key, payload, cfg, st)
			if err != nil {
				t.Fatal(err)
			}
			if voice && (w[0]&0x10 == 0 || len(w) < rtpHeaderLenVoice) {
				t.Fatal("voice packet must carry extension")
			}
			// Уникальность nonce (SSRC+seq+ts) — критично для AEAD.
			n := string(w[2:12])
			if seen[n] {
				t.Fatalf("nonce reuse at %d (voice=%v)", i, voice)
			}
			seen[n] = true
			dst := make([]byte, 2048)
			m, err := obfsUnwrapPacket(key, w, dst)
			if err != nil || !bytes.Equal(dst[:m], payload) {
				t.Fatalf("roundtrip voice=%v i=%d: %v", voice, i, err)
			}
		}
	}
}

// Обмен с сервером (ByPassMe-Server/obfs_voice_test.go) через файлы:
// OBFS_X_OUT — куда писать свои пакеты, OBFS_X_IN — что разобрать.
type vectors struct {
	Payloads []string `json:"payloads"`
	Packets  []string `json:"packets"`
}

func TestVoiceCrossExport(t *testing.T) {
	out := os.Getenv("OBFS_X_OUT")
	if out == "" {
		t.Skip("cross test only")
	}
	key := voiceTestKey()
	cfg, _ := NewObfsConfig("audio")
	cfg.Voice = true
	st, _ := NewObfsState()
	var v vectors
	for i := 0; i < 300; i++ {
		p := bytes.Repeat([]byte{0x45, byte(i)}, 1+i%600)
		w, _ := obfsWrapPacket(key, p, cfg, st)
		v.Payloads = append(v.Payloads, hex.EncodeToString(p))
		v.Packets = append(v.Packets, hex.EncodeToString(w))
	}
	b, _ := json.Marshal(v)
	if err := os.WriteFile(out, b, 0644); err != nil {
		t.Fatal(err)
	}
}

func TestVoiceCrossImport(t *testing.T) {
	in := os.Getenv("OBFS_X_IN")
	if in == "" {
		t.Skip("cross test only")
	}
	b, err := os.ReadFile(in)
	if err != nil {
		t.Fatal(err)
	}
	var v vectors
	_ = json.Unmarshal(b, &v)
	if len(v.Packets) == 0 {
		t.Fatal("no vectors")
	}
	key := voiceTestKey()
	for i := range v.Packets {
		w, _ := hex.DecodeString(v.Packets[i])
		want, _ := hex.DecodeString(v.Payloads[i])
		dst := make([]byte, 2048)
		m, err := obfsUnwrapPacket(key, w, dst)
		if err != nil || !bytes.Equal(dst[:m], want) {
			t.Fatalf("server packet %d: %v", i, err)
		}
	}
}
