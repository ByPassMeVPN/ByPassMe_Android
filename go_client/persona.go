// SPDX-License-Identifier: MIT
// persona.go — «поколение» отпечатка для VK (UA + client hints + device +
// browser_fp в vk_profile.json). После капчи профиль меняется (см.
// rotateCaptchaProfile), и смена должна пережить перезапуск клиента: раньше
// приложение при каждом старте перезаписывало vk_profile.json профилем
// устройства, и VK снова видел отпечаток, на котором уже требовал капчу.
// Теперь приложение пишет свой профиль в vk_profile_device.json, а
// vk_profile.json только создаёт, если его нет. Через сутки без капчи
// возвращаемся к профилю устройства (он самый естественный).
// Идея — free-turn-proxy (FreeTurn), vkauth/personastate.go; код свой.
package main

import (
	"encoding/json"
	"log"
	"os"
	"sync"
	"time"
)

const (
	personaStateFile    = "vk_persona.json"
	deviceProfileFile   = "vk_profile_device.json"
	personaRestoreAfter = 24 * time.Hour
)

type personaState struct {
	Gen       int   `json:"gen"`
	RotatedAt int64 `json:"rotated_at"`
}

var personaMu sync.Mutex

func loadPersonaState() personaState {
	var st personaState
	if b, err := os.ReadFile(personaStateFile); err == nil {
		_ = json.Unmarshal(b, &st)
	}
	return st
}

func savePersonaState(st personaState) {
	if b, err := json.Marshal(st); err == nil {
		_ = os.WriteFile(personaStateFile, b, 0644)
	}
}

// notePersonaRotated — профиль сменён (капча): новое поколение, сохранено.
func notePersonaRotated() {
	personaMu.Lock()
	defer personaMu.Unlock()
	st := loadPersonaState()
	st.Gen++
	st.RotatedAt = time.Now().Unix()
	savePersonaState(st)
	log.Printf("[VK Auth] Отпечаток сменён (поколение %d) — сохранён, после перезапуска не вернётся", st.Gen)
}

// restoreDeviceProfileIfRested — сутки без капчи: возвращаем профиль
// устройства. Вызывать при старте клиента.
func restoreDeviceProfileIfRested() {
	personaMu.Lock()
	defer personaMu.Unlock()
	st := loadPersonaState()
	if st.Gen == 0 {
		return
	}
	if time.Since(time.Unix(st.RotatedAt, 0)) < personaRestoreAfter {
		log.Printf("[VK Auth] Отпечаток поколения %d (сменён %s назад)", st.Gen,
			time.Since(time.Unix(st.RotatedAt, 0)).Truncate(time.Minute))
		return
	}
	dev, err := os.ReadFile(deviceProfileFile)
	if err != nil || len(dev) == 0 {
		return
	}
	if err := os.WriteFile(profileFile, dev, 0644); err != nil {
		return
	}
	savePersonaState(personaState{})
	log.Printf("[VK Auth] Сутки без капчи — вернули профиль устройства")
}
