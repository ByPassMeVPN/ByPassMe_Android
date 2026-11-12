// SPDX-License-Identifier: MIT
// obfs_voice.go — «голосовой» RTP-заголовок: расширение RFC 8285 как у
// WebRTC-аудио (audio-level, abs-send-time, transport-wide-cc), смена
// речь/тишина с битом начала фразы и редкие «потери» (пропуск номеров).
// Звонковый сервис режет скорость трафику, не похожему на звук звонка;
// идея — профиль rtpopus3 из free-turn-proxy (FreeTurn), код свой.
//
// ФАЙЛ ОДИНАКОВЫЙ у клиента (go_client) и сервера (ByPassMe-Server).
package main

import (
	"encoding/binary"
	"errors"
	"math/rand"
	"time"
)

// rtpHeaderLenVoice — 12 байт RTP + 4 байта заголовка расширения (0xBEDE,
// длина 3 слова) + 12 байт элементов (1+1, 1+3, 1+2 и 3 байта выравнивания).
const rtpHeaderLenVoice = 28

// voiceSim — состояние имитации голоса для одного направления (под ObfsState.mu).
type voiceSim struct {
	started  bool
	inSpeech bool
	left     int    // пакетов до смены речь/тишина
	untilGap int    // пакетов до имитации потери
	seqExtra uint16 // пропущенные номера («потери»)
	tsExtra  uint32 // время «потерянных» пакетов
	tcc      uint16 // transport-wide sequence
}

func (v *voiceSim) next() (marker bool, level byte, seqAdd uint16, tsAdd uint32, tcc uint16) {
	if !v.started {
		v.started = true
		v.inSpeech = true
		v.left = 30 + rand.Intn(170)
		v.untilGap = 50 + rand.Intn(100)
		marker = true
	}
	if v.left <= 0 {
		v.inSpeech = !v.inSpeech
		if v.inSpeech {
			v.left = 30 + rand.Intn(170)
			marker = true // начало фразы
		} else {
			v.left = 5 + rand.Intn(25)
		}
	}
	v.left--
	v.untilGap--
	if v.untilGap <= 0 {
		n := uint16(1 + rand.Intn(3))
		v.seqExtra += n
		v.tsExtra += uint32(n) * 960
		v.untilGap = 50 + rand.Intn(100)
	}
	if v.inSpeech {
		level = 0x80 | byte(20+rand.Intn(40)) // V=1 (голос), -20…-59 dBov
	} else {
		level = byte(100 + rand.Intn(28)) // V=0, почти тишина
	}
	v.tcc++
	return marker, level, v.seqExtra, v.tsExtra, v.tcc
}

// writeVoiceHeader пишет 28-байтный заголовок в out[0:28].
func writeVoiceHeader(out []byte, pt uint8, seq uint16, ts, ssrc uint32, marker bool, level byte, tcc uint16) {
	out[0] = 0x80 | 0x20 | 0x10 // V=2, P=1, X=1
	out[1] = pt & 0x7F
	if marker {
		out[1] |= 0x80
	}
	binary.BigEndian.PutUint16(out[2:4], seq)
	binary.BigEndian.PutUint32(out[4:8], ts)
	binary.BigEndian.PutUint32(out[8:12], ssrc)
	out[12], out[13] = 0xBE, 0xDE // RFC 8285 one-byte header
	binary.BigEndian.PutUint16(out[14:16], 3)
	out[16] = 0x10 // audio-level, ID 1, 1 байт
	out[17] = level
	out[18] = 0x22 // abs-send-time, ID 2, 3 байта (6.18 с)
	ast := uint32((uint64(time.Now().UnixNano()) << 18) / 1e9)
	out[19], out[20], out[21] = byte(ast>>16), byte(ast>>8), byte(ast)
	out[22] = 0x31 // transport-wide-cc, ID 3, 2 байта
	binary.BigEndian.PutUint16(out[23:25], tcc)
	out[25], out[26], out[27] = 0, 0, 0
}

// rtpHeaderLenOf — длина заголовка входящего пакета: 12 без расширения,
// иначе 16 + 4×(длина расширения в словах). Понимает и старый 24-байтный
// вариант (длина 2), и голосовой 28-байтный (длина 3).
func rtpHeaderLenOf(wire []byte) (int, error) {
	if len(wire) < 12 {
		return 0, errors.New("obfs: packet too short")
	}
	if wire[0]&0x10 == 0 {
		return 12, nil
	}
	if len(wire) < 16 {
		return 0, errors.New("obfs: packet too short for extension header")
	}
	h := 16 + 4*int(binary.BigEndian.Uint16(wire[14:16]))
	if len(wire) < h+1 {
		return 0, errors.New("obfs: packet too short for declared extension")
	}
	return h, nil
}
