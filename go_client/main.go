package main

import (
	"bufio"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Сигналы
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		select {
		case s := <-sig:
			log.Printf("[КЛИЕНТ] Сигнал %v, завершаю...", s)
			cancel()
		case <-ctx.Done():
			return
		}
		select {
		case s := <-sig:
			log.Printf("[КЛИЕНТ] Повторный %v, принудительный выход", s)
			os.Exit(1)
		case <-ctx.Done():
		}
	}()

	var pauseFlag int32

	// STDIN для PAUSE/RESUME/STOP и CAPTCHA_RESULT
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if !strings.Contains(line, "error:tunnel stopped") {
				log.Printf("[STDIN] %s", line)
			}
			switch {
			case line == "PAUSE":
				atomic.StoreInt32(&pauseFlag, 1)
			case line == "RESUME":
				atomic.StoreInt32(&pauseFlag, 0)
			case line == "STOP":
				cancel()
				return
			case line == "WAKE":
				// Экран включился после долгого сна — проверить потоки сразу,
				// не дожидаясь детектора сна (тик 15 с).
				RequestWake("приложение: экран включён после сна")
			case line == "RECONNECT" || strings.HasPrefix(line, "RECONNECT|"):
				// Сменилась сеть (Wi-Fi ↔ LTE): поднять все потоки заново, не
				// перезапуская процесс и не трогая TUN.
				reason := strings.TrimPrefix(strings.TrimPrefix(line, "RECONNECT"), "|")
				if reason == "" {
					reason = "приложение: сменилась сеть"
				}
				RequestReconnect(reason)
			case strings.HasPrefix(line, "CAPTCHA_RESULT|"):
				result := strings.TrimPrefix(line, "CAPTCHA_RESULT|")
				drainCaptchaResult()
				CaptchaResultChan <- result
				log.Printf("[КАПЧА] Результат от Kotlin записан в канал")
			case strings.HasPrefix(line, "TURN_CREDS|"):
				handleTurnCredsStdinLine(line)
			}
		}
	}()

	host := flag.String("turn", "", "переопределить IP TURN")
	port := flag.String("port", "", "переопределить порт TURN")
	listen := flag.String("listen", "127.0.0.1:9000", "локальный адрес")
	vkHash := flag.String("vk", "", "хеши VK-звонков (через запятую)")
	peerAddr := flag.String("peer", "", "адрес:порт VPS сервера")
	numW := flag.Int("n", 9, "количество воркеров")
	pingOnly := flag.Bool("ping-only", false, "запустить только замер задержки и выйти")

	deviceID := flag.String("device-id", "unknown", "уникальный ID устройства")
	connPassword := flag.String("password", "", "пароль подключения")
	captchaMode := flag.String("captcha-mode", "auto", "режим обхода капчи (auto/wv/rjs)")
	vkAuthMode := flag.String("vk-auth", "anonymous", "режим VK авторизации (account/anonymous)")
	vkAnonPath := flag.String("vk-anon-path", "vkcalls", "анонимный путь VK TURN (vkcalls/legacy)")
	vkCredsFile := flag.String("vk-creds-file", "", "файл с TURN кредами от аккаунта VK")
	vkHostIPsJSON := flag.String("vk-host-ips", "", "JSON {host:[ip]} — IP хостов VK, заранее полученные приложением")
	dnsFallback := flag.String("dns-fallback", "", "запасной DNS для -go-dns system: ip,ip,...")
	goDNS := flag.String("go-dns", "yandex", "DNS для VK (yandex/cloudflare/google, doh-yandex/doh-cloudflare/doh-google, custom:IP или doh:URL)")
	obfsMode := flag.String("obfs", "audio", "режим обфускации (audio/video)")
	obfsVoice := flag.Bool("obfs-voice", false, "«голосовой» RTP-заголовок как у звонка (нужен сервер с поддержкой — признак voice в списке)")
	checkHashes := flag.Bool("check-hashes", false, "проверить VK-хеши и выйти")
	connMode := flag.String("mode", "vpn", "режим клиента (vpn|socks|rawtun)")
	socksAddr := flag.String("socks", "127.0.0.1:1080", "локальный SOCKS5 (только -mode socks)")
	socksAuth := flag.Bool("socks-auth", false, "требовать логин и пароль SOCKS5")
	socksUser := flag.String("socks-user", "", "логин SOCKS5")
	socksPass := flag.String("socks-pass", "", "пароль SOCKS5")
	noDTLS := flag.Bool("notls", false, "прямой режим: RTP-obfs AEAD без DTLS поверх TURN (нужен сервер с -listen-direct)")
	turnTCP := flag.Bool("turn-tcp", false, "соединяться с TURN-relay по TCP вместо UDP (обход UDP-душения на некоторых сетях, напр. Ростелеком)")
	tunFdSock := flag.String("tun-fd-sock", "", "unix-сокет для получения TUN fd от Android (только -mode rawtun)")

	flag.Parse()
	activeConnMode := strings.ToLower(strings.TrimSpace(*connMode))
	if activeConnMode != "socks" && activeConnMode != "rawtun" {
		activeConnMode = "vpn"
	}
	if activeConnMode == "socks" && *socksAuth {
		if *socksUser == "" || *socksPass == "" {
			log.Fatal("[SOCKS] Для авторизации нужны логин и пароль")
		}
		if len([]byte(*socksUser)) > 255 || len([]byte(*socksPass)) > 255 {
			log.Fatal("[SOCKS] Логин и пароль должны быть не длиннее 255 байт")
		}
	}
	if *vkHostIPsJSON != "" {
		var hostIPs map[string][]string
		if err := json.Unmarshal([]byte(*vkHostIPsJSON), &hostIPs); err == nil {
			SetVKHostIPs(hostIPs)
		}
	}
	SetFallbackDNS(*dnsFallback)
	setupGlobalResolver(*goDNS)
	activeCaptchaMode := setCaptchaMode(*captchaMode)
	activeVkAuthMode := setVkAuthMode(*vkAuthMode)
	activeVkAnonPath := setVkAnonPath(*vkAnonPath)

	if err := loadVkCredsFile(*vkCredsFile); err != nil {
		log.Fatalf("[КЛИЕНТ] Ошибка чтения vk-creds-file: %v", err)
	}

	hashes := ParseHashes(*vkHash)
	if *checkHashes {
		if len(hashes) == 0 {
			log.Fatal("[CHECK] Нужен -vk со списком хешей")
		}
		log.Printf("[КЛИЕНТ] VK auth mode: %s (hash-check)", activeVkAuthMode)
		if activeVkAuthMode == "anonymous" {
			log.Printf("[КЛИЕНТ] VK anon path: %s", activeVkAnonPath)
		}
		log.Printf("[КЛИЕНТ] Captcha mode: %s", activeCaptchaMode)
		runHashChecks(ctx, hashes)
		return
	}

	log.Printf("[КЛИЕНТ] VK auth mode: %s", activeVkAuthMode)
	if activeVkAuthMode == "anonymous" {
		log.Printf("[КЛИЕНТ] VK anon path: %s", activeVkAnonPath)
	}

	if *peerAddr == "" || *vkHash == "" {
		log.Fatal("[КЛИЕНТ] Нужны -peer и -vk")
	}

	// -peer может содержать несколько адресов через запятую (несколько
	// входных IP страны) — переключение при недоступности, см. peers.go.
	peerList, err := parsePeers(*peerAddr)
	if err != nil {
		log.Fatalf("[КЛИЕНТ] Ошибка разбора пира: %v", err)
	}
	setPeers(peerList)
	peer := peerList[0]

	if len(hashes) == 0 {
		log.Fatal("[КЛИЕНТ] Нет хешей VK")
	}

	if *connPassword == "" {
		log.Fatal("[КЛИЕНТ] Нужен -password: WRAP ключ теперь выводится из пароля подключения")
	}

	// WRAP key
	wrapKey, err := deriveWrapKey(*connPassword)
	if err != nil {
		log.Fatalf("[КЛИЕНТ] WRAP key derive: %v", err)
	}

	// Лимит воркеров
	maxWorkers := 108
	if *numW > maxWorkers {
		*numW = maxWorkers
	}
	if getVkAuthMode() == "account" {
		const accountMaxWorkers = 4
		if *numW > accountMaxWorkers {
			log.Printf("[КЛИЕНТ] Аккаунт VK: TURN-квота ~%d relay на сессию, потоков %d -> %d", accountMaxWorkers, *numW, accountMaxWorkers)
			*numW = accountMaxWorkers
		}
		if *numW < 1 {
			*numW = 1
		}
	} else {
		if *numW < workersPerGroup {
			*numW = workersPerGroup
		}
		*numW = (*numW / workersPerGroup) * workersPerGroup
	}

	tp := &TurnParams{
		Host:         *host,
		Port:         *port,
		Hashes:       hashes,
		WrapKey:      wrapKey,
		ObfsMode:     normalizeObfsMode(*obfsMode),
		ObfsVoice:    *obfsVoice,
		NoDTLS:       *noDTLS,
		RawMode:      activeConnMode == "rawtun",
		TCPTransport: *turnTCP,
	}

	if *pingOnly {
		var lastErr error
		for i, hash := range hashes {
			user, pass, turnURLs, err := GetCreds(ctx, hash, 999)
			if err != nil {
				lastErr = fmt.Errorf("GetCreds hash %d: %v", i, err)
				continue
			}
			creds := &Credentials{User: user, Pass: pass, TurnURLs: turnURLs, CacheStreamID: 999}
			rtt, err := RunPing(ctx, tp, peer, creds)
			if err != nil {
				lastErr = fmt.Errorf("RunPing hash %d: %v", i, err)
				continue
			}
			fmt.Printf("PING_RESULT|%d\n", rtt)
			os.Exit(0)
		}
		// Если все хеши провалились
		fmt.Printf("PING_ERROR|All hashes failed. Last error: %v\n", lastErr)
		os.Exit(1)
	}

	// Слушаем локально (SO_REUSEADDR — быстрый перезапуск без «address already in use»)
	localConn, err := listenUDP(*listen)
	if err != nil {
		log.Fatalf("[КЛИЕНТ] Ошибка слушателя %s: %v", *listen, err)
	}
	if uc, ok := localConn.(*net.UDPConn); ok {
		_ = uc.SetReadBuffer(socketBufSize)
		_ = uc.SetWriteBuffer(socketBufSize)
	}
	stopLocalConn := context.AfterFunc(ctx, func() { _ = localConn.Close() })
	defer stopLocalConn()

	_, localPort, _ := net.SplitHostPort(*listen)
	if localPort == "" {
		localPort = "9000"
	}

	numGroups := (*numW + workersPerGroup - 1) / workersPerGroup

	wrapStatus := "OFF"
	if len(wrapKey) == wrapKeyLen {
		wrapStatus = "ON (password HKDF + RTP AEAD)"
	}

	captchaStatus := "AUTO: FT (без браузера) -> Go v2 x2 -> WBV Auto x2 -> Go v2 x1 -> Manual WBV"
	switch activeCaptchaMode {
	case "wv":
		captchaStatus = "WBV selected in Android"
	case "rjs":
		captchaStatus = "RJS: FT -> Go v2 -> WBV Auto"
	}

	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")
	log.Printf("[КЛИЕНТ] VK Creds: 2 stable app_id с циклическим fallback")
	log.Printf("[КЛИЕНТ] TLS: Chrome 146 fingerprint")
	log.Printf("[КЛИЕНТ] Воркеров: %d (групп: %d, по %d)", *numW, numGroups, workersPerGroup)
	log.Printf("[КЛИЕНТ] Хешей: %d", len(hashes))
	log.Printf("[КЛИЕНТ] Слушаю: %s | Пир: %s", *listen, *peerAddr)
	if *turnTCP {
		log.Printf("[КЛИЕНТ] TURN-транспорт: TCP")
	} else {
		log.Printf("[КЛИЕНТ] TURN-транспорт: UDP")
	}
	log.Printf("[КЛИЕНТ] Режим: %s", activeConnMode)
	if activeConnMode == "socks" {
		log.Printf("[КЛИЕНТ] SOCKS5: %s", *socksAddr)
		if *socksAuth {
			log.Printf("[КЛИЕНТ] SOCKS5: авторизация по логину и паролю включена")
		}
	}
	log.Printf("[КЛИЕНТ] WRAP: %s", wrapStatus)
	log.Printf("[WRAP] Ключ выведен из пароля, режим RTP AEAD активен")
	log.Printf("[КЛИЕНТ] Device ID: %s", *deviceID)
	log.Printf("[КЛИЕНТ] Captcha: %s", captchaStatus)
	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")

	go runSleepDetector(ctx)
	restoreDeviceProfileIfRested()

	stats := NewStats()
	shutdownCh := make(chan struct{})
	go func() {
		<-ctx.Done()
		close(shutdownCh)
	}()
	go stats.RunLoop(shutdownCh)

	var disp *Dispatcher
	if activeConnMode == "rawtun" {
		disp = NewDispatcherPendingTUN(ctx, stats)
	} else {
		disp = NewDispatcher(ctx, localConn, stats)
	}
	defer disp.Shutdown()

	configCh := make(chan string, 1)
	configDone := make(chan struct{})
	go func() {
		defer close(configDone)
		select {
		case rawConf, ok := <-configCh:
			if !ok || rawConf == "" {
				return
			}

			if strings.HasPrefix(rawConf, "RAWCONF:") {
				parts := strings.Split(strings.TrimPrefix(rawConf, "RAWCONF:"), "|")
				if len(parts) != 3 {
					log.Printf("[RAW] Некорректный RAWCONF: %q", rawConf)
					return
				}
				ip, dnsCSV, mtuStr := parts[0], parts[1], parts[2]
				fmt.Println()
				fmt.Println("╔══════════════ RAW Конфиг ══════════════╗")
				fmt.Printf("║ %-40s ║\n", fmt.Sprintf("IP = %s", ip))
				fmt.Printf("║ %-40s ║\n", fmt.Sprintf("DNS = %s", dnsCSV))
				fmt.Printf("║ %-40s ║\n", fmt.Sprintf("MTU = %s", mtuStr))
				fmt.Println("╚══════════════════════════════════════╝")

				if *tunFdSock == "" {
					log.Println("[RAW] Ошибка: -tun-fd-sock не указан")
					return
				}

				log.Println("[RAW] Ожидание TUN fd от Android...")
				var tunFile *os.File
				var fdErr error
				attempt := 0
				for {
					attempt++
					tunFile, fdErr = recvTunFD(*tunFdSock)
					if fdErr == nil {
						break
					}
					rawDiagf("recvTunFD попытка #%d неудачна: %v (повтор через 200мс)", attempt, fdErr)
					select {
					case <-ctx.Done():
						rawDiagf("recvTunFD: ctx отменён, прекращаю попытки")
						return
					case <-time.After(200 * time.Millisecond):
					}
				}
				rawDiagf("recvTunFD успешен на попытке #%d, fd=%v", attempt, tunFile.Fd())
				disp.AttachTUN(tunFile)
				log.Println("[RAW] TUN подключён, трафик пошёл")
				return
			}

			finalConf := rawConf
			// ByPassMe: VK-подсети и сам сервер — мимо WireGuard, иначе
			// TURN-трафик клиента зацикливается в туннель.
			if peerIP := peer.IP; peerIP != nil {
				finalConf = ModifyConfigForSplitTunnel(finalConf, peerIP)
			}
			if !strings.Contains(finalConf, "MTU =") {
				lines := strings.Split(finalConf, "\n")
				var newLines []string
				for _, line := range lines {
					newLines = append(newLines, line)
					if strings.TrimSpace(line) == "[Interface]" {
						newLines = append(newLines, "MTU = 1280")
					}
				}
				finalConf = strings.Join(newLines, "\n")
			}
			fmt.Println()
			fmt.Println("╔══════════════ WireGuard Конфиг ══════════════╗")
			for _, line := range strings.Split(finalConf, "\n") {
				fmt.Printf("║ %-44s ║\n", line)
			}
			fmt.Println("╚══════════════════════════════════════════════╝")
			if err := os.WriteFile("wg-turn.conf", []byte(finalConf+"\n"), 0600); err != nil {
				log.Printf("[КОНФИГ] Ошибка сохранения: %v", err)
			} else {
				log.Println("[КОНФИГ] Сохранён в wg-turn.conf")
			}

			if activeConnMode == "socks" {
				dev, tnet, err := startUserspaceWireGuard(finalConf)
				if err != nil {
					log.Printf("[SOCKS] Ошибка userspace WG: %v", err)
					return
				}
				defer dev.Close()
				if err := runSocks5Server(ctx, *socksAddr, tnet, *socksAuth, *socksUser, *socksPass); err != nil {
					log.Printf("[SOCKS] Сервер остановлен: %v", err)
				}
			}
		case <-ctx.Done():
		}
	}()

	var wg sync.WaitGroup
	workerIDCounter := 1

	var prevWaitReady <-chan struct{}

	for g := 0; g < numGroups; g++ {
		isFirst := (g == 0)

		var myWaitReady <-chan struct{}
		var mySignalReady chan<- struct{}

		if g > 0 {
			myWaitReady = prevWaitReady
		}
		if g < numGroups-1 {
			ch := make(chan struct{})
			mySignalReady = ch
			prevWaitReady = ch
		}

		startIdx := g * workersPerGroup
		endIdx := startIdx + workersPerGroup
		if endIdx > *numW {
			endIdx = *numW
		}
		groupSize := endIdx - startIdx
		if groupSize <= 0 {
			continue
		}

		ids := make([]int, groupSize)
		for i := range ids {
			ids[i] = workerIDCounter
			workerIDCounter++
		}

		gID := g + 1
		var cc chan<- string
		if isFirst {
			cc = configCh
		}

		wg.Add(1)
		go func(groupID int, isFirstGroup bool, configChan chan<- string, workerIds []int, startHashIndex int, waitR <-chan struct{}, sigR chan<- struct{}) {
			defer wg.Done()
			WorkerGroup(ctx, groupID, startHashIndex, tp, peer, disp, localPort,
				isFirstGroup, configChan, workerIds, &pauseFlag, *deviceID, *connPassword, stats, waitR, sigR)
		}(gID, isFirst, cc, ids, g, myWaitReady, mySignalReady)
	}

	wg.Wait()
	close(configCh)
	<-configDone
	log.Println("[КЛИЕНТ] Все воркеры завершены")
}
