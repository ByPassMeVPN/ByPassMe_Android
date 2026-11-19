package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Кэш TURN-кредов VK на диске (идея из anton48/vk-turn-proxy-ios, creds-pool.json).
// Клиент перезапускается при каждой смене сети/переподключении (Android — новый
// процесс, iOS — новый клиент в расширении), а память при этом теряется. Без
// диска каждый перезапуск шёл в VK API: при глушении, капче или лимитах Обход
// не поднимался, хотя выданные креды ещё действовали.
//
// Файл лежит в рабочей папке клиента: Android — filesDir приложения, iOS —
// контейнер App Group (chdir в profileDir).

const (
	diskCredsFile   = "turn_creds_cache.json"
	diskCredsMaxAge = 8 * time.Hour
	diskCredsMargin = 5 * time.Minute
)

type diskCredsEntry struct {
	Username  string    `json:"u"`
	Password  string    `json:"p"`
	Addrs     []string  `json:"a"`
	ExpiresAt time.Time `json:"e"`
}

var diskCredsMu sync.Mutex

func diskCredsKey(cacheID int, link string) string {
	return fmt.Sprintf("%d|%s", cacheID, link)
}

// turnCredsExpiry: срок из имени TURN-пользователя (REST API TURN: «<unix-время
// окончания>:<id>»), минус запас; иначе — как у кэша в памяти.
func turnCredsExpiry(username string) time.Time {
	now := time.Now()
	if head, _, ok := strings.Cut(username, ":"); ok {
		if ts, err := strconv.ParseInt(head, 10, 64); err == nil && ts > now.Unix() {
			exp := time.Unix(ts, 0).Add(-diskCredsMargin)
			if limit := now.Add(diskCredsMaxAge); exp.After(limit) {
				exp = limit
			}
			return exp
		}
	}
	return now.Add(credentialLifetime - cacheSafetyMargin)
}

func loadDiskCredsAll() map[string]diskCredsEntry {
	out := map[string]diskCredsEntry{}
	data, err := os.ReadFile(diskCredsFile)
	if err != nil {
		return out
	}
	_ = json.Unmarshal(data, &out)
	return out
}

func saveDiskCredsAll(m map[string]diskCredsEntry) {
	now := time.Now()
	for k, e := range m {
		if !now.Before(e.ExpiresAt) {
			delete(m, k)
		}
	}
	data, err := json.Marshal(m)
	if err != nil {
		return
	}
	tmp := diskCredsFile + ".tmp"
	if os.WriteFile(tmp, data, 0o600) == nil {
		_ = os.Rename(tmp, diskCredsFile)
	}
}

func diskCredsGet(cacheID int, link string) (diskCredsEntry, bool) {
	diskCredsMu.Lock()
	defer diskCredsMu.Unlock()
	e, ok := loadDiskCredsAll()[diskCredsKey(cacheID, link)]
	if !ok || !time.Now().Before(e.ExpiresAt) || e.Username == "" || len(e.Addrs) == 0 {
		return diskCredsEntry{}, false
	}
	return e, true
}

func diskCredsPut(cacheID int, link, user, pass string, addrs []string) {
	diskCredsMu.Lock()
	defer diskCredsMu.Unlock()
	m := loadDiskCredsAll()
	m[diskCredsKey(cacheID, link)] = diskCredsEntry{
		Username:  user,
		Password:  pass,
		Addrs:     cloneStringSlice(addrs),
		ExpiresAt: turnCredsExpiry(user),
	}
	saveDiskCredsAll(m)
}

// diskCredsForget — TURN отверг креды: убрать все записи этой группы.
func diskCredsForget(cacheID int) {
	diskCredsMu.Lock()
	defer diskCredsMu.Unlock()
	m := loadDiskCredsAll()
	prefix := fmt.Sprintf("%d|", cacheID)
	changed := false
	for k := range m {
		if strings.HasPrefix(k, prefix) {
			delete(m, k)
			changed = true
		}
	}
	if changed {
		saveDiskCredsAll(m)
		log.Printf("[VK Auth] Креды группы %d удалены из кэша на диске", cacheID)
	}
}
