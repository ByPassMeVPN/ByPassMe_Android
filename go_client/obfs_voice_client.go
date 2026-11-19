// SPDX-License-Identifier: MIT
package main

import "sync/atomic"

// obfsVoiceDefault — «голосовой» заголовок для встраиваемого клиента (iOS:
// флагов командной строки нет, приложение вызывает SetObfsVoice до старта).
var obfsVoiceDefault atomic.Bool

// SetObfsVoice — включить голосовой RTP-заголовок (сервер с признаком voice).
func SetObfsVoice(on bool) { obfsVoiceDefault.Store(on) }
