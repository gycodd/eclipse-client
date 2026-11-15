package xraybridge

import (
	"errors"
	"fmt"
	"sync"

	"github.com/xjasonlyu/tun2socks/v2/engine"
)

// На iOS пакеты из TUN читал Tun2SocksKit. На Android то же делает tun2socks (gVisor),
// только внутри этой же библиотеки: Kotlin отдаёт сюда файловый дескриптор VpnService.
var (
	tunMu      sync.Mutex
	tunRunning bool
)

// StartTun поднимает tun2socks поверх дескриптора fd (его выдал VpnService.Builder.establish()).
// Владение дескриптором переходит сюда: закрывать его на стороне Kotlin нельзя (detachFd).
func StartTun(fd int32, mtu int32, socksPort int32, user string, pass string) (err error) {
	tunMu.Lock()
	defer tunMu.Unlock()
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("tun2socks panic: %v", r)
		}
	}()
	if tunRunning {
		return errors.New("tun already running")
	}
	if fd <= 0 {
		return errors.New("bad tun fd")
	}
	engine.Insert(&engine.Key{
		MTU:      int(mtu),
		Proxy:    fmt.Sprintf("socks5://%s:%s@127.0.0.1:%d", user, pass, socksPort),
		Device:   fmt.Sprintf("fd://%d", fd),
		LogLevel: "warn",
	})
	engine.Start()
	tunRunning = true
	return nil
}

func StopTun() {
	tunMu.Lock()
	defer tunMu.Unlock()
	if !tunRunning {
		return
	}
	engine.Stop()
	tunRunning = false
}

func IsTunRunning() bool {
	tunMu.Lock()
	defer tunMu.Unlock()
	return tunRunning
}
